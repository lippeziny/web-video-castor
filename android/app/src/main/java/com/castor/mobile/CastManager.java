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
import java.util.concurrent.atomic.AtomicInteger;

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
    // A single command queue prevents rapid seek/volume/play commands from racing each other.
    private final ExecutorService commandExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "castor-cast-commands");
        t.setDaemon(true);
        return t;
    });
    // Keep polling from racing the TV while one or more seek commands are still in flight.
    private final AtomicInteger pendingSeekCommands = new AtomicInteger();
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
    private volatile long lastPosAt;
    private volatile long ignoreDlnaPositionUntil;
    private volatile float lastRequestedVolume = 0.5f;
    private volatile long castGeneration;
    private volatile long connectionGeneration;
    private volatile String activeStreamUrl = "";
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
        commandExec.execute(() -> {
            long generation = -1L;
            String streamUrl = m.url;
            try {
                // Casting is an exclusive playback target: pause the phone player before sending
                // the stream to the TV. The TV decodes the stream itself; the phone becomes a remote.
                PlaybackService.pauseForCast(app);
                stopInternal(false);
                synchronized (this) {
                    generation = ++castGeneration;
                }
                long start = startMs;
                long resumeDuration = 0L;
                // A zero passed by a freshly reopened player is not an intentional restart if this
                // page already has a saved checkpoint. Prefer the persisted position in that case.
                if (start <= 5000L) {
                    long[] saved = store.getPosition(m.pageUrl.isEmpty() ? m.url : m.pageUrl);
                    // Older sessions may have stored the checkpoint against the direct media URL.
                    if (saved == null && !m.pageUrl.isEmpty()) saved = store.getPosition(m.url);
                    if (saved != null && saved[0] > 5000L) {
                        start = Math.max(0L, saved[0] - 2000L);
                        resumeDuration = Math.max(0L, saved[1]);
                    } else if (start < 0L) {
                        start = 0L;
                    }
                }
                activeItem = m;
                active = d;
                lastPos = start;
                lastDur = resumeDuration;
                lastPosAt = System.currentTimeMillis();
                ignoreDlnaPositionUntil = 0L;
                if (d.kind == CastDevice.REMOTE) {
                    boolean page = m.type.equals("PÁGINA");
                    RemoteCastor.cast(store.remoteUrl(), store.remoteToken(), d.control, m.url, page);
                    lastState = "PLAYING";
                    CastService.start(app, m.title);
                    message("Enviado ao servidor Castor. A TV deve começar em instantes.");
                    notifyState();
                    return;
                }
                if (m.type.equals("PÁGINA")) {
                    message("Players embutidos só funcionam via servidor Castor. Abra o player e toque no vídeo.");
                    activeItem = null;
                    active = null;
                    return;
                }

                // O serviço e os locks são necessários mesmo quando o proxy local foi desativado.
                // Caso contrário, alguns Androids suspendem a CPU/Wi-Fi e derrubam a sessão com a tela apagada.
                CastService.start(app, m.title);
                streamUrl = m.url;
                // For DLNA always keep the byte-range proxy in the path: besides forwarding
                // Referer/Cookies, it advertises Interactive transfer and preserves HTTP Range,
                // which is required by many Samsung renderers to expose their Seek action.
                if (store.castProxy() || d.kind == CastDevice.DLNA) {
                    String ip = LocalProxy.lanIp();
                    if (ip != null) {
                        proxy.start();
                        proxy.setHost(ip);
                        streamUrl = proxy.register(m.url, m.referer);
                    } else {
                        message("Sem IP de Wi-Fi: enviando o link direto à TV.");
                    }
                }
                activeStreamUrl = streamUrl;
                boolean hls = m.type.equals("HLS");
                if (lastPos >= 5000L) saveProgress();
                if (d.kind == CastDevice.CHROMECAST) {
                    startChromecast(d, m, streamUrl, hls, start, generation, 0);
                } else {
                    startDlna(d, m, streamUrl, hls, start);
                    lastState = "PLAYING";
                }
                startPolling();
                notifyState();
            } catch (Exception e) {
                if (d.kind == CastDevice.CHROMECAST && generation >= 0 && generation == castGeneration
                        && activeItem == m) {
                    message("A conexão inicial falhou; tentando reconectar ao Chromecast…");
                    scheduleChromecastReconnect(d, m, streamUrl, m.type.equals("HLS"),
                            Math.max(0L, lastPos), generation, 1);
                    startPolling();
                } else {
                    active = null;
                    activeItem = null;
                    activeStreamUrl = "";
                    proxy.stop();
                    CastService.stop(app);
                    message("Falha ao transmitir: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
        });
    }

    private void startChromecast(CastDevice d, MediaItem m, String url, boolean hls,
                                 long startMs, long generation, int reconnectAttempt) throws Exception {
        final long connectionId;
        synchronized (this) {
            if (generation != castGeneration) return;
            connectionId = ++connectionGeneration;
        }
        final CastV2Client[] clientHolder = new CastV2Client[1];
        CastV2Client c = new CastV2Client(new CastV2Client.Listener() {
            private boolean isCurrent() {
                return generation == castGeneration && connectionId == connectionGeneration;
            }

            @Override
            public void onStatus(String state, long posMs, long durMs) {
                if (!isCurrent()) return;
                lastState = state;
                lastPos = posMs;
                lastDur = durMs;
                notifyState();
            }

            @Override
            public void onError(String msg) {
                if (isCurrent()) message(msg);
            }

            @Override
            public void onClosed() {
                if (!isCurrent()) return;
                // Não deixe o loop de acompanhamento usar o socket que acabou de cair.
                if (cc == clientHolder[0]) cc = null;
                scheduleChromecastReconnect(d, m, url, hls, Math.max(lastPos, startMs),
                        generation, reconnectAttempt + 1);
            }
        });
        clientHolder[0] = c;
        cc = c;
        try {
            c.connect(d.host, d.port > 0 ? d.port : 8009);
            c.load(url, mimeOf(m, true), m.title, false, Math.max(0L, startMs) / 1000.0);
            // GET_STATUS da mídia fornece mediaSessionId; sem ele, SEEK/PAUSE/PLAY falham.
            c.requestStatus();
            try { c.requestReceiverStatus(); } catch (Exception ignored) { }
            lastState = "BUFFERING";
            notifyState();
        } catch (Exception e) {
            synchronized (this) {
                if (connectionGeneration == connectionId) connectionGeneration++;
            }
            if (cc == c) cc = null;
            c.close();
            throw e;
        }
    }

    private void scheduleChromecastReconnect(CastDevice d, MediaItem m, String oldUrl, boolean hls,
                                            long startMs, long generation, int attempt) {
        if (generation != castGeneration || activeItem != m || active != d) return;
        if (attempt > 6) {
            saveProgress();
            synchronized (this) {
                if (generation != castGeneration) return;
                castGeneration++;
                connectionGeneration++;
                pollGen++;
            }
            CastV2Client previous = cc;
            cc = null;
            if (previous != null) previous.close();
            active = null;
            activeItem = null;
            activeStreamUrl = "";
            lastState = "";
            proxy.stop();
            CastService.stop(app);
            message("Não consegui reconectar ao Chromecast. Confira o Wi-Fi e toque em Cast novamente.");
            notifyState();
            return;
        }

        lastState = "CONNECTING";
        notifyState();
        message("Conexão com a TV interrompida; reconectando (" + attempt + "/6)…");
        exec.execute(() -> {
            try {
                Thread.sleep(Math.min(1000L + attempt * 1500L, 8000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (generation != castGeneration || activeItem != m || active != d) return;
            String retryUrl = oldUrl;
            try {
                if (store.castProxy()) {
                    String ip = LocalProxy.lanIp();
                    if (ip != null) {
                        proxy.start();
                        proxy.setHost(ip);
                        retryUrl = proxy.register(m.url, m.referer);
                    }
                }
                // O serviço de primeiro plano já está ativo desde o início do cast.
                activeStreamUrl = retryUrl;
                startChromecast(d, m, retryUrl, hls, Math.max(0L, lastPos > 0 ? lastPos : startMs),
                        generation, attempt);
                notifyState();
            } catch (Exception e) {
                if (generation == castGeneration && activeItem == m && active == d) {
                    scheduleChromecastReconnect(d, m, retryUrl, hls,
                            Math.max(0L, lastPos > 0 ? lastPos : startMs), generation, attempt + 1);
                }
            }
        });
    }

    private void startDlna(CastDevice d, MediaItem m, String url, boolean hls, long startMs) throws Exception {
        try { DlnaClient.stop(d); } catch (Exception ignored) { }
        DlnaClient.setUri(d, url, m.title, mimeOf(m, false));
        DlnaClient.play(d);
        lastState = "PLAYING";
        lastPosAt = System.currentTimeMillis();
        if (startMs > 3000L) {
            // Wait for the TV's decoder to enter PLAYING before asking it to resume.
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(600L);
                    String st = DlnaClient.transportState(d);
                    if ("PLAYING".equalsIgnoreCase(st)) break;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) { }
            }
            try {
                DlnaClient.seek(d, startMs);
                lastPos = startMs;
                lastPosAt = System.currentTimeMillis();
                ignoreDlnaPositionUntil = lastPosAt + 2200L;
                String key = m.pageUrl.isEmpty() ? m.url : m.pageUrl;
                store.setPosition(key, startMs, Math.max(0L, lastDur));
            } catch (Exception e) {
                // Never keep the requested resume timestamp as if the TV had jumped successfully.
                // Otherwise every later checkpoint would overwrite the real position with a stale one.
                long[] actual = DlnaClient.position(d);
                if (actual != null && actual[0] > 0L) {
                    lastPos = actual[0];
                    if (actual[1] > 0L) lastDur = actual[1];
                } else {
                    lastPos = 0L;
                }
                lastPosAt = System.currentTimeMillis();
                ignoreDlnaPositionUntil = 0L;
                message("A TV recusou retomar de " + DlnaClient.fmt(startMs)
                        + "; mantive o horário real informado pela TV para não salvar um ponto falso. Detalhe: "
                        + e.getMessage());
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
            case "MOV":
                return "video/quicktime";
            case "FLV":
                return "video/x-flv";
            case "M4V":
                return "video/x-m4v";
            case "TS":
                return "video/mp2t";
            case "MP3":
                return "audio/mpeg";
            case "IMAGEM":
                String u = m.url == null ? "" : m.url.toLowerCase(Locale.ROOT);
                if (u.endsWith(".png")) return "image/png";
                if (u.endsWith(".gif")) return "image/gif";
                if (u.endsWith(".webp")) return "image/webp";
                return "image/jpeg";
            case "ÁUDIO":
                String a = m.url == null ? "" : m.url.toLowerCase(Locale.ROOT);
                if (a.contains(".m4a")) return "audio/mp4";
                if (a.contains(".ogg")) return "audio/ogg";
                if (a.contains(".wav")) return "audio/wav";
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
                if (pendingSeekCommands.get() > 0) continue;
                if (d.kind == CastDevice.DLNA) {
                    String st = DlnaClient.transportState(d);
                    if (!st.isEmpty()) lastState = st.startsWith("PAUSED") ? "PAUSED" : st;
                    long[] p = DlnaClient.position(d);
                    long now = System.currentTimeMillis();
                    boolean playing = lastState.toUpperCase(Locale.ROOT).startsWith("PLAY");
                    if (p != null) {
                        if (p[1] > 0) lastDur = p[1];
                        if (now >= ignoreDlnaPositionUntil) {
                            // Some Samsung TVs keep returning RelTime=00:00:00 even after playback
                            // or a seek. Do not overwrite a valid non-zero checkpoint with this
                            // stale zero; keep an estimated clock while playing and preserve it paused.
                            if (p[0] > 0) {
                                lastPos = p[0];
                                lastPosAt = now;
                            } else if (p[0] == 0 && lastPos <= 5000L) {
                                lastPos = 0L;
                                lastPosAt = now;
                            } else if (playing) {
                                if (lastPosAt > 0) lastPos += Math.max(0L, now - lastPosAt);
                                lastPosAt = now;
                            } else {
                                // Reset the estimate anchor while paused, so a later resume does
                                // not add the entire paused interval to the approximate position.
                                lastPosAt = now;
                            }
                        }
                    } else if (playing) {
                        if (lastPosAt > 0) lastPos += Math.max(0L, now - lastPosAt);
                        lastPosAt = now;
                    }
                    if (lastDur > 0) lastPos = Math.min(lastPos, lastDur);
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

    /** Checkpoint usado pelo serviço persistente quando o app sai da tela ou o serviço é encerrado. */
    public void checkpoint() {
        commandExec.execute(this::saveProgress);
    }

    private void saveProgress() {
        MediaItem m = activeItem;
        if (m == null || m.type.equalsIgnoreCase("LIVE") || lastPos < 5000L) return;
        String key = m.pageUrl.isEmpty() ? m.url : m.pageUrl;
        // Some DLNA renderers don't report duration reliably. Persist position anyway; only clear
        // the record when a known duration proves the item reached its end.
        if (lastDur >= 60000L && lastPos >= lastDur - 15000L) {
            store.clearPosition(key);
            if (!m.url.equals(key)) store.clearPosition(m.url);
        } else {
            store.setPosition(key, lastPos, Math.max(0L, lastDur));
            // Keep a direct-URL checkpoint too, so resume still works if the page URL changes.
            if (!m.url.equals(key)) store.setPosition(m.url, lastPos, Math.max(0L, lastDur));
        }
    }

    private void notifyState() {
        final StateListener l = stateListener;
        final String s = lastState;
        final long p = lastPos;
        final long d = lastDur;
        MediaItem item = activeItem;
        CastDevice device = active;
        CastService.updateCastState(app, item == null ? "" : item.title,
                device == null ? "" : device.name, s, p, d);
        if (l != null) ui.post(() -> l.onState(s, p, d));
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
        commandExec.execute(() -> {
            final CastDevice d = active;
            if (d == null) return;
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
                lastPosAt = System.currentTimeMillis();
                notifyState();
            } catch (Exception e) {
                message("Erro: " + e.getMessage());
            }
        });
    }

    public void seekTo(final long ms) {
        CastDevice d = active;
        if (d == null) return;
        if (d.kind != CastDevice.DLNA && (d.kind != CastDevice.CHROMECAST || cc == null)) {
            enqueueSeek(ms);
            return;
        }
        long target = clampSeekTarget(ms, d);
        // Update the remote UI immediately. The command itself can take several seconds on DLNA.
        lastPos = target;
        lastPosAt = System.currentTimeMillis();
        if (d.kind == CastDevice.DLNA) ignoreDlnaPositionUntil = lastPosAt + 2200L;
        enqueueSeek(target);
        notifyState();
    }

    private long clampSeekTarget(long ms, CastDevice d) {
        long duration = lastDur;
        CastV2Client client = cc;
        if (d.kind == CastDevice.CHROMECAST && client != null && client.duration() > 0) {
            duration = client.duration();
        }
        return Math.max(0L, duration > 0 ? Math.min(ms, Math.max(0L, duration - 1000L)) : ms);
    }

    private void enqueueSeek(long target) {
        pendingSeekCommands.incrementAndGet();
        commandExec.execute(() -> {
            try {
                performSeekTo(target);
            } finally {
                pendingSeekCommands.decrementAndGet();
            }
        });
    }

    private void performSeekTo(long ms) {
        final CastDevice d = active;
        if (d == null) return;
        try {
            long duration = lastDur;
            CastV2Client client = cc;
            if (d.kind == CastDevice.CHROMECAST && client != null && client.duration() > 0) {
                duration = client.duration();
            }
            long target = Math.max(0L, duration > 0 ? Math.min(ms, Math.max(0L, duration - 1000L)) : ms);
            if (d.kind == CastDevice.DLNA) DlnaClient.seek(d, target);
            else if (d.kind == CastDevice.CHROMECAST && client != null) client.seek(target);
            else {
                message("Este tipo de transmissão não permite buscar dentro do vídeo.");
                return;
            }
            lastPos = target;
            lastPosAt = System.currentTimeMillis();
            if (d.kind == CastDevice.DLNA) ignoreDlnaPositionUntil = lastPosAt + 2200L;
            MediaItem item = activeItem;
            String key = item == null ? "" : (item.pageUrl.isEmpty() ? item.url : item.pageUrl);
            if (!key.isEmpty()) {
                if (target >= 5000L) {
                    store.setPosition(key, target, Math.max(0L, duration));
                    if (item != null && !item.url.equals(key)) store.setPosition(item.url, target, Math.max(0L, duration));
                } else {
                    // Seeking to the beginning invalidates the old resume point; otherwise a
                    // previous 45-minute checkpoint would unexpectedly reappear after restart.
                    store.clearPosition(key);
                    if (item != null && !item.url.equals(key)) store.clearPosition(item.url);
                }
            }
            notifyState();
        } catch (Exception e) {
            message("Erro ao avançar/retroceder: " + e.getMessage());
        } finally {
            if (d.kind == CastDevice.DLNA) {
                ignoreDlnaPositionUntil = System.currentTimeMillis() + 2200L;
            }
        }
    }

    public void seekBy(long deltaMs) {
        CastDevice d = active;
        if (d == null) return;
        if (d.kind != CastDevice.DLNA && (d.kind != CastDevice.CHROMECAST || cc == null)) {
            enqueueSeek(Math.max(0L, lastPos + deltaMs));
            return;
        }
        long now = System.currentTimeMillis();
        long base = lastPos;
        if (lastState.toUpperCase(Locale.ROOT).startsWith("PLAY") && lastPosAt > 0) {
            base += Math.max(0L, now - lastPosAt);
        }
        long target = clampSeekTarget(Math.max(0L, base + deltaMs), d);
        // Optimistic position makes repeated taps accumulate and prevents the slider snapping back.
        lastPos = target;
        lastPosAt = now;
        if (d.kind == CastDevice.DLNA) ignoreDlnaPositionUntil = now + 2200L;
        enqueueSeek(target);
        notifyState();
    }

    public boolean supportsVolume() {
        CastDevice d = active;
        return d != null && (d.kind == CastDevice.CHROMECAST
                || (d.kind == CastDevice.DLNA && !d.renderingControl.isEmpty()));
    }

    public float volumeLevel() {
        CastDevice d = active;
        if (d != null && d.kind == CastDevice.CHROMECAST && cc != null) {
            float reported = cc.volumeLevel();
            if (reported >= 0f) return reported;
        }
        return lastRequestedVolume;
    }

    /** Ajuste remoto; retorna false quando o dispositivo não oferece esse recurso. */
    public boolean adjustVolume(final int direction) {
        final CastDevice d = active;
        if (d == null || d.kind == CastDevice.REMOTE
                || (d.kind == CastDevice.DLNA && d.renderingControl.isEmpty())) return false;
        commandExec.execute(() -> {
            try {
                if (d.kind == CastDevice.DLNA) {
                    int current = DlnaClient.getVolume(d);
                    int next = Math.max(0, Math.min(100, current + (direction < 0 ? -5 : 5)));
                    DlnaClient.setVolume(d, next);
                    lastRequestedVolume = next / 100f;
                    CastService.updateRemoteVolume(lastRequestedVolume);
                } else if (cc != null) {
                    float current = cc.volumeLevel();
                    if (current < 0f) current = lastRequestedVolume;
                    float next = Math.max(0f, Math.min(1f, current + (direction < 0 ? -0.05f : 0.05f)));
                    cc.setVolume(next);
                    lastRequestedVolume = next;
                    CastService.updateRemoteVolume(lastRequestedVolume);
                }
            } catch (Exception e) {
                message("Não foi possível ajustar o volume da TV: " + e.getMessage());
            }
        });
        return true;
    }

    public boolean setVolume(final float level) {
        final CastDevice d = active;
        final float value = Math.max(0f, Math.min(1f, level));
        if (d == null || d.kind == CastDevice.REMOTE
                || (d.kind == CastDevice.DLNA && d.renderingControl.isEmpty())) return false;
        commandExec.execute(() -> {
            try {
                if (d.kind == CastDevice.DLNA) DlnaClient.setVolume(d, Math.round(value * 100f));
                else if (cc != null) cc.setVolume(value);
                lastRequestedVolume = value;
                CastService.updateRemoteVolume(value);
            } catch (Exception e) {
                message("Não foi possível ajustar o volume da TV: " + e.getMessage());
            }
        });
        return true;
    }

    public void stop() {
        commandExec.execute(() -> stopInternal(true));
    }

    private void stopInternal(boolean notify) {
        CastDevice d = active;
        synchronized (this) {
            pollGen++;
            castGeneration++;
            connectionGeneration++;
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
        activeItem = null;
        activeStreamUrl = "";
        lastState = "";
        CastService.updateCastState(app, "", "", "", 0L, 0L);
        proxy.stop();
        CastService.stop(app);
        if (notify) notifyState();
    }

    public String stateLabel() {
        String s = lastState == null ? "" : lastState.toUpperCase(Locale.ROOT);
        if (s.startsWith("PLAY")) return "Reproduzindo";
        if (s.startsWith("PAUS")) return "Pausado";
        if (s.startsWith("CONNECT")) return "Reconectando…";
        if (s.equals("BUFFERING") || s.startsWith("TRANSITION")) return "Carregando…";
        if (s.equals("IDLE") || s.startsWith("STOP")) return "Parado";
        return s.isEmpty() ? "Conectando…" : s;
    }
}
