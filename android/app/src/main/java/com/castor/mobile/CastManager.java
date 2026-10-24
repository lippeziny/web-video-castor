package com.castor.mobile;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Orquestra descoberta de TVs, envio do vídeo (DLNA, Chromecast ou servidor Castor) e controle remoto. */
public final class CastManager {
    public interface DevicesListener {
        void onDevices(List<CastDevice> devices, boolean searching);
    }

    public interface StateListener {
        void onState(String state, long posMs, long durMs);

        void onMessage(String message);
    }

    private static CastManager instance;

    public static synchronized CastManager get(Context c) {
        if (instance == null) instance = new CastManager(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Store store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "castor-cast");
        t.setDaemon(true);
        return t;
    });
    private final LocalProxy proxy = new LocalProxy();
    private final CopyOnWriteArrayList<CastDevice> devices = new CopyOnWriteArrayList<>();

    private volatile DevicesListener devicesListener;
    private volatile StateListener stateListener;
    private volatile boolean searching;
    private int searchGen;

    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener nsdListener;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    // sessão ativa
    private volatile CastDevice active;
    private volatile MediaItem activeItem;
    private volatile CastV2Client cc;
    private volatile String lastState = "";
    private volatile long lastPos;
    private volatile long lastDur;
    private int pollGen;

    private CastManager(Context c) {
        app = c;
        store = new Store(c);
        proxy.setCookieProvider(url -> {
            try {
                return CookieManager.getInstance().getCookie(url);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public void setDevicesListener(DevicesListener l) {
        devicesListener = l;
    }

    public void setStateListener(StateListener l) {
        stateListener = l;
    }

    public List<CastDevice> devices() {
        return new ArrayList<>(devices);
    }

    public boolean isActive() {
        return active != null;
    }

    public CastDevice activeDevice() {
        return active;
    }

    public MediaItem activeItem() {
        return activeItem;
    }

    public String lastState() {
        return lastState;
    }

    public long lastPos() {
        return lastPos;
    }

    public long lastDur() {
        return lastDur;
    }

    // ---------------------------------------------------------------- descoberta

    public void discover() {
        final int gen;
        synchronized (this) {
            gen = ++searchGen;
        }
        searching = true;
        devices.clear();
        notifyDevices();
        acquireMulticast();

        exec.execute(() -> {
            DlnaClient.search(5000, d -> addDevice(d));
            DlnaClient.search(3500, d -> addDevice(d));
            finishSearch(gen);
        });
        startNsd();
        final String remote = store.remoteUrl();
        if (!remote.isEmpty()) {
            exec.execute(() -> {
                try {
                    for (CastDevice d : RemoteCastor.listDevices(remote, store.remoteToken())) addDevice(d);
                } catch (Exception e) {
                    message("Servidor Castor: " + e.getMessage());
                }
            });
        }
        ui.postDelayed(() -> finishSearch(gen), 9000);
    }

    private void finishSearch(int gen) {
        synchronized (this) {
            if (gen != searchGen) return;
        }
        searching = false;
        stopNsd();
        releaseMulticast();
        notifyDevices();
    }

    public void addDevice(CastDevice d) {
        for (CastDevice e : devices) {
            if (e.id.equals(d.id)) return;
        }
        devices.add(d);
        notifyDevices();
    }

    private void notifyDevices() {
        final DevicesListener l = devicesListener;
        if (l == null) return;
        final List<CastDevice> snap = new ArrayList<>(devices);
        final boolean s = searching;
        ui.post(() -> l.onDevices(snap, s));
    }

    /** Adiciona manualmente: "http://ip:porta/desc.xml" (DLNA) ou apenas "192.168.0.5" (Chromecast). */
    public void addManual(final String input) {
        final String text = input.trim();
        if (text.isEmpty()) return;
        exec.execute(() -> {
            try {
                if (text.startsWith("http")) {
                    CastDevice d = DlnaClient.describe(text);
                    if (d == null) {
                        message("Esse endereço não é um renderizador DLNA");
                        return;
                    }
                    addDevice(d);
                } else {
                    String host = text.contains(":") ? text.substring(0, text.indexOf(':')) : text;
                    addDevice(new CastDevice(CastDevice.CHROMECAST, "cc:" + host, "Chromecast " + host, host, 8009, "", ""));
                }
            } catch (Exception e) {
                message("Não foi possível adicionar: " + e.getMessage());
            }
        });
    }

    private void acquireMulticast() {
        try {
            if (multicastLock == null) {
                WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    multicastLock = wm.createMulticastLock("castor-multicast");
                    multicastLock.setReferenceCounted(false);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) multicastLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticast() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {
        }
    }

    private synchronized void startNsd() {
        try {
            stopNsd();
            nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return;
            nsdListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    synchronized (CastManager.this) {
                        resolveQueue.add(info);
                    }
                    resolveNext();
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nsd.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) {
        }
    }

    private synchronized void stopNsd() {
        try {
            if (nsd != null && nsdListener != null) nsd.stopServiceDiscovery(nsdListener);
        } catch (Exception ignored) {
        }
        nsdListener = null;
        resolveQueue.clear();
        resolving = false;
    }

    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (this) {
            if (resolving || resolveQueue.isEmpty() || nsd == null) return;
            next = resolveQueue.poll();
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    try {
                        String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
                        String name = info.getServiceName();
                        Map<String, byte[]> attrs = info.getAttributes();
                        if (attrs != null && attrs.get("fn") != null) {
                            name = new String(attrs.get("fn"), StandardCharsets.UTF_8);
                        }
                        if (!host.isEmpty()) {
                            addDevice(new CastDevice(CastDevice.CHROMECAST, "cc:" + host, name, host,
                                    info.getPort() > 0 ? info.getPort() : 8009, "", ""));
                        }
                    } finally {
                        done();
                    }
                }

                private void done() {
                    synchronized (CastManager.this) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            synchronized (this) {
                resolving = false;
            }
        }
    }

    // ---------------------------------------------------------------- transmitir

    /**
     * Envia o vídeo para o dispositivo. {@code startMs < 0} usa a posição salva da página (se houver).
     */
    public void cast(final CastDevice d, final MediaItem m, final long startMs) {
        message("Conectando a " + d.name + "…");
        exec.execute(() -> {
            try {
                stopInternal(false);
                long start = startMs;
                if (start < 0) {
                    long[] saved = store.getPosition(m.pageUrl.isEmpty() ? m.url : m.pageUrl);
                    start = saved == null ? 0 : Math.max(0, saved[0] - 2000);
                }
                activeItem = m;
                lastPos = start;
                lastDur = 0;
                if (d.kind == CastDevice.REMOTE) {
                    boolean page = m.type.equals("PÁGINA");
                    RemoteCastor.cast(store.remoteUrl(), store.remoteToken(), d.control, m.url, page);
                    active = d;
                    lastState = "PLAYING";
                    message("Enviado ao servidor Castor. A TV deve começar em instantes.");
                    notifyState();
                    return;
                }
                if (m.type.equals("PÁGINA")) {
                    message("Players embutidos só funcionam via servidor Castor. Abra o player e toque no vídeo.");
                    activeItem = null;
                    return;
                }
                String url = m.url;
                if (store.castProxy()) {
                    String ip = LocalProxy.lanIp();
                    if (ip != null) {
                        proxy.start();
                        proxy.setHost(ip);
                        url = proxy.register(m.url, m.referer);
                        CastService.start(app, m.title);
                    } else {
                        message("Sem IP de Wi-Fi: enviando o link direto à TV.");
                    }
                }
                boolean hls = m.type.equals("HLS");
                if (d.kind == CastDevice.CHROMECAST) {
                    startChromecast(d, m, url, hls, start);
                } else {
                    startDlna(d, m, url, hls, start);
                }
                active = d;
                startPolling();
                notifyState();
            } catch (Exception e) {
                active = null;
                CastService.stop(app);
                message("Falha ao transmitir: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    private void startChromecast(CastDevice d, MediaItem m, String url, boolean hls, long startMs) throws Exception {
        CastV2Client c = new CastV2Client(new CastV2Client.Listener() {
            @Override
            public void onStatus(String state, long posMs, long durMs) {
                lastState = state;
                lastPos = posMs;
                lastDur = durMs;
                notifyState();
            }

            @Override
            public void onError(String msg) {
                message(msg);
            }

            @Override
            public void onClosed() {
                if (active != null && active.kind == CastDevice.CHROMECAST) {
                    active = null;
                    lastState = "";
                    CastService.stop(app);
                    message("A conexão com o Chromecast foi encerrada.");
                    notifyState();
                }
            }
        });
        c.connect(d.host, d.port > 0 ? d.port : 8009);
        cc = c;
        c.load(url, mimeOf(m, true), m.title, false, startMs / 1000.0);
    }

    private void startDlna(CastDevice d, MediaItem m, String url, boolean hls, long startMs) throws Exception {
        try {
            DlnaClient.stop(d);
        } catch (Exception ignored) {
        }
        DlnaClient.setUri(d, url, m.title, mimeOf(m, false));
        DlnaClient.play(d);
        lastState = "PLAYING";
        if (startMs > 3000) {
            // muitas TVs só aceitam o seek depois de começar a tocar
            for (int i = 0; i < 8; i++) {
                try {
                    Thread.sleep(1200);
                    String st = DlnaClient.transportState(d);
                    if (st.equals("PLAYING") || i >= 5) {
                        DlnaClient.seek(d, startMs);
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    static String mimeOf(MediaItem m, boolean chromecast) {
        switch (m.type) {
            case "HLS":
                return chromecast ? "application/x-mpegURL" : "application/vnd.apple.mpegurl";
            case "DASH":
                return "application/dash+xml";
            case "WEBM":
                return "video/webm";
            case "MKV":
                return "video/x-matroska";
            case "ÁUDIO":
                return "audio/mpeg";
            default:
                return "video/mp4";
        }
    }

    // ---------------------------------------------------------------- acompanhamento

    private void startPolling() {
        final int gen;
        synchronized (this) {
            gen = ++pollGen;
        }
        exec.execute(() -> {
            int tick = 0;
            while (gen == pollGen && active != null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                CastDevice d = active;
                if (d == null || gen != pollGen) return;
                if (d.kind == CastDevice.DLNA) {
                    long[] p = DlnaClient.position(d);
                    if (p != null) {
                        lastPos = p[0];
                        if (p[1] > 0) lastDur = p[1];
                    }
                    String st = DlnaClient.transportState(d);
                    if (!st.isEmpty()) lastState = st.startsWith("PAUSED") ? "PAUSED" : st;
                } else if (d.kind == CastDevice.CHROMECAST) {
                    CastV2Client c = cc;
                    if (c != null) {
                        if (tick % 3 == 0) c.requestStatus();
                        lastPos = c.position();
                        if (c.duration() > 0) lastDur = c.duration();
                        if (!c.state().isEmpty()) lastState = c.state();
                    }
                }
                notifyState();
                if (++tick % 5 == 0) saveProgress();
            }
        });
    }

    private void saveProgress() {
        MediaItem m = activeItem;
        if (m == null || lastDur < 60000) return;
        String key = m.pageUrl.isEmpty() ? m.url : m.pageUrl;
        if (lastPos >= lastDur - 15000) store.clearPosition(key);
        else if (lastPos >= 5000) store.setPosition(key, lastPos, lastDur);
    }

    private void notifyState() {
        final StateListener l = stateListener;
        if (l == null) return;
        final String s = lastState;
        final long p = lastPos;
        final long d = lastDur;
        ui.post(() -> l.onState(s, p, d));
    }

    private void message(final String text) {
        final StateListener l = stateListener;
        ui.post(() -> {
            if (l != null) l.onMessage(text);
            else android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show();
        });
    }

    // ---------------------------------------------------------------- controles

    public void togglePlayPause() {
        final CastDevice d = active;
        if (d == null) return;
        exec.execute(() -> {
            try {
                boolean playing = !lastState.toUpperCase(Locale.ROOT).startsWith("PAUS");
                if (d.kind == CastDevice.DLNA) {
                    if (playing) DlnaClient.pause(d);
                    else DlnaClient.play(d);
                } else if (d.kind == CastDevice.CHROMECAST && cc != null) {
                    if (playing) cc.pause();
                    else cc.play();
                } else {
                    message("O servidor Castor não expõe pausa por aqui.");
                    return;
                }
                lastState = playing ? "PAUSED" : "PLAYING";
                notifyState();
            } catch (Exception e) {
                message("Erro: " + e.getMessage());
            }
        });
    }

    public void seekTo(final long ms) {
        final CastDevice d = active;
        if (d == null) return;
        exec.execute(() -> {
            try {
                long target = Math.max(0, lastDur > 0 ? Math.min(ms, lastDur - 1000) : ms);
                if (d.kind == CastDevice.DLNA) DlnaClient.seek(d, target);
                else if (d.kind == CastDevice.CHROMECAST && cc != null) cc.seek(target);
                else return;
                lastPos = target;
                notifyState();
            } catch (Exception e) {
                message("Erro ao avançar: " + e.getMessage());
            }
        });
    }

    public void seekBy(long deltaMs) {
        seekTo(lastPos + deltaMs);
    }

    public void stop() {
        exec.execute(() -> stopInternal(true));
    }

    private void stopInternal(boolean notify) {
        CastDevice d = active;
        synchronized (this) {
            pollGen++;
        }
        if (d != null) {
            saveProgress();
            try {
                if (d.kind == CastDevice.DLNA) DlnaClient.stop(d);
                else if (d.kind == CastDevice.CHROMECAST && cc != null) cc.stopApp();
                else if (d.kind == CastDevice.REMOTE) RemoteCastor.stop(store.remoteUrl(), store.remoteToken(), d.control);
            } catch (Exception ignored) {
            }
        }
        if (cc != null) {
            cc.close();
            cc = null;
        }
        active = null;
        lastState = "";
        proxy.stop();
        CastService.stop(app);
        if (notify) notifyState();
    }

    public String stateLabel() {
        String s = lastState == null ? "" : lastState.toUpperCase(Locale.ROOT);
        if (s.startsWith("PLAY")) return "Reproduzindo";
        if (s.startsWith("PAUS")) return "Pausado";
        if (s.equals("BUFFERING") || s.startsWith("TRANSITION")) return "Carregando…";
        if (s.equals("IDLE") || s.startsWith("STOP")) return "Parado";
        return s.isEmpty() ? "Conectando…" : s;
    }
}
