package com.castor.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Cliente mínimo do protocolo Google Cast (CASTV2) sem o SDK do Google: TLS na porta 8009,
 * mensagens protobuf escritas à mão. Usa o "Default Media Receiver" (CC1AD845) para tocar a URL.
 */
public final class CastV2Client implements Closeable {
    public interface Listener {
        void onStatus(String state, long posMs, long durMs);

        void onError(String message);

        void onClosed();
    }

    private static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECV = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String APP_ID = "CC1AD845";
    private static final String SENDER = "sender-0";

    private final Listener listener;
    private final AtomicInteger reqId = new AtomicInteger(1);
    private final Object lock = new Object();
    private SSLSocket sock;
    private DataInputStream in;
    private OutputStream out;
    private volatile boolean closed;
    private volatile String transportId;
    private volatile String sessionId;
    private volatile int mediaSessionId = -1;
    private volatile String lastState = "";
    private volatile long lastPos;
    private volatile long lastDur;
    private volatile long lastPosAt;
    // A GET_STATUS can race a SEEK and contain the old position; keep the requested target briefly.
    private volatile long seekRequestedAt;
    private volatile long seekRequestedPosition;
    private volatile float receiverVolume = -1f;

    public CastV2Client(Listener l) {
        this.listener = l;
    }

    public void connect(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        sock = (SSLSocket) ctx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress(host, port), 6000);
        sock.setSoTimeout(0);
        sock.startHandshake();
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();

        Thread reader = new Thread(this::readLoop, "castv2-reader");
        reader.setDaemon(true);
        reader.start();
        Thread beat = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(5000);
                    send(NS_HEART, "receiver-0", "{\"type\":\"PING\"}");
                } catch (Exception e) {
                    return;
                }
            }
        }, "castv2-heartbeat");
        beat.setDaemon(true);
        beat.start();
        send(NS_CONN, "receiver-0", "{\"type\":\"CONNECT\"}");
    }

    /** Abre o receptor padrão e manda tocar. Bloqueia até o LOAD ser enviado. */
    public void load(String url, String contentType, String title, boolean live, double startSec) throws Exception {
        if (transportId == null) {
            send(NS_RECV, "receiver-0", "{\"type\":\"LAUNCH\",\"appId\":\"" + APP_ID + "\",\"requestId\":"
                    + reqId.getAndIncrement() + "}");
            long end = System.currentTimeMillis() + 20000;
            synchronized (lock) {
                while (transportId == null && !closed) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("O Chromecast não abriu o receptor de mídia");
                    lock.wait(left);
                }
            }
            if (closed) throw new IOException("Conexão com o Chromecast encerrada");
            send(NS_CONN, transportId, "{\"type\":\"CONNECT\"}");
        }
        JSONObject media = new JSONObject();
        media.put("contentId", url);
        media.put("contentType", contentType);
        media.put("streamType", live ? "LIVE" : "BUFFERED");
        JSONObject meta = new JSONObject();
        meta.put("metadataType", 0);
        meta.put("title", title == null ? "" : title);
        media.put("metadata", meta);
        JSONObject msg = new JSONObject();
        msg.put("type", "LOAD");
        msg.put("requestId", reqId.getAndIncrement());
        msg.put("sessionId", sessionId);
        msg.put("media", media);
        msg.put("autoplay", true);
        msg.put("currentTime", startSec);
        mediaSessionId = -1;
        send(NS_MEDIA, transportId, msg.toString());
    }

    public void requestStatus() {
        try {
            if (transportId != null) {
                send(NS_MEDIA, transportId, "{\"type\":\"GET_STATUS\",\"requestId\":" + reqId.getAndIncrement() + "}");
            }
        } catch (Exception ignored) {
        }
    }

    public void play() throws Exception {
        mediaCmd("PLAY", null);
    }

    public void pause() throws Exception {
        mediaCmd("PAUSE", null);
    }

    public void seek(long ms) throws Exception {
        long target = Math.max(0L, ms);
        JSONObject extra = new JSONObject();
        extra.put("currentTime", target / 1000.0);
        // Não force a retomada se o usuário estiver assistindo com a reprodução pausada.
        extra.put("resumeState", "PLAYBACK_UNCHANGED");
        // Set the expected target before sending. A status response may race the command and still
        // report the old position; onMediaStatus() ignores that stale value for a short window.
        long requestedAt = System.currentTimeMillis();
        seekRequestedPosition = target;
        seekRequestedAt = requestedAt;
        lastPos = target;
        lastPosAt = requestedAt;
        try {
            mediaCmd("SEEK", extra);
        } catch (Exception e) {
            seekRequestedAt = 0L;
            throw e;
        }
        requestStatus();
    }

    public void stopApp() {
        try {
            if (sessionId != null) {
                send(NS_RECV, "receiver-0", "{\"type\":\"STOP\",\"sessionId\":\"" + sessionId
                        + "\",\"requestId\":" + reqId.getAndIncrement() + "}");
            }
        } catch (Exception ignored) {
        }
    }

    public String state() {
        return lastState;
    }

    /** Posição estimada (o receptor só informa de tempos em tempos). */
    public long position() {
        long p = lastPos;
        if ("PLAYING".equals(lastState)) p += System.currentTimeMillis() - lastPosAt;
        return lastDur > 0 ? Math.min(p, lastDur) : p;
    }

    public long duration() {
        return lastDur;
    }

    public float volumeLevel() { return receiverVolume; }

    public void requestReceiverStatus() throws IOException {
        send(NS_RECV, "receiver-0", "{\"type\":\"GET_STATUS\",\"requestId\":" + reqId.getAndIncrement() + "}");
    }

    public void setVolume(float level) throws Exception {
        float clamped = Math.max(0f, Math.min(1f, level));
        JSONObject volume = new JSONObject();
        volume.put("level", clamped);
        JSONObject msg = new JSONObject();
        msg.put("type", "SET_VOLUME");
        msg.put("volume", volume);
        msg.put("requestId", reqId.getAndIncrement());
        send(NS_RECV, "receiver-0", msg.toString());
        receiverVolume = clamped;
    }

    private void mediaCmd(String type, JSONObject extra) throws Exception {
        if (transportId == null) throw new IOException("O receptor do Chromecast ainda não está pronto");
        if (mediaSessionId < 0) {
            requestStatus();
            long deadline = System.currentTimeMillis() + 4000L;
            synchronized (lock) {
                while (mediaSessionId < 0 && !closed) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    lock.wait(Math.min(remaining, 500L));
                }
            }
        }
        if (closed) throw new IOException("A conexão com o Chromecast foi encerrada");
        if (mediaSessionId < 0) throw new IOException("O Chromecast ainda não confirmou a sessão de mídia; tente novamente em instantes");
        JSONObject m = extra == null ? new JSONObject() : extra;
        m.put("type", type);
        m.put("mediaSessionId", mediaSessionId);
        m.put("requestId", reqId.getAndIncrement());
        send(NS_MEDIA, transportId, m.toString());
    }

    // ---------------------------------------------------------------- leitura

    private void readLoop() {
        try {
            while (!closed) {
                int len = in.readInt();
                if (len <= 0 || len > 4 * 1024 * 1024) throw new IOException("quadro inválido");
                byte[] buf = new byte[len];
                in.readFully(buf);
                handle(buf);
            }
        } catch (Exception e) {
            // conexão caiu
        } finally {
            boolean wasOpen = !closed;
            closed = true;
            synchronized (lock) {
                lock.notifyAll();
            }
            if (wasOpen) listener.onClosed();
        }
    }

    private void handle(byte[] frame) throws JSONException, IOException {
        String src = "", dst = "", ns = "", payload = "";
        int i = 0;
        while (i < frame.length) {
            long[] tag = varint(frame, i);
            i = (int) tag[1];
            int field = (int) (tag[0] >> 3);
            int wire = (int) (tag[0] & 7);
            if (wire == 0) {
                i = (int) varint(frame, i)[1];
            } else if (wire == 2) {
                long[] l = varint(frame, i);
                i = (int) l[1];
                int len = (int) l[0];
                if (i + len > frame.length) throw new IOException("quadro truncado");
                String s = new String(frame, i, len, StandardCharsets.UTF_8);
                if (field == 2) src = s;
                else if (field == 3) dst = s;
                else if (field == 4) ns = s;
                else if (field == 6) payload = s;
                i += len;
            } else {
                throw new IOException("tipo de campo desconhecido");
            }
        }
        if (payload.isEmpty()) return;
        JSONObject j = new JSONObject(payload);
        String type = j.optString("type");
        if (ns.equals(NS_HEART)) {
            if (type.equals("PING")) send(NS_HEART, src, "{\"type\":\"PONG\"}");
        } else if (ns.equals(NS_CONN)) {
            if (type.equals("CLOSE")) {
                closed = true;
                listener.onClosed();
            }
        } else if (ns.equals(NS_RECV)) {
            if (type.equals("RECEIVER_STATUS")) onReceiverStatus(j.optJSONObject("status"));
            else if (type.equals("LAUNCH_ERROR")) listener.onError("Falha ao abrir o receptor: " + j.optString("reason"));
        } else if (ns.equals(NS_MEDIA)) {
            if (type.equals("MEDIA_STATUS")) onMediaStatus(j.optJSONArray("status"));
            else if (type.equals("LOAD_FAILED") || type.equals("LOAD_CANCELLED") || type.equals("INVALID_REQUEST")) {
                listener.onError("O Chromecast recusou o vídeo (" + type + "). Tente ligar o proxy do app.");
            }
        }
    }

    private void onReceiverStatus(JSONObject status) {
        if (status == null) return;
        JSONObject volume = status.optJSONObject("volume");
        if (volume != null && volume.has("level")) {
            double level = volume.optDouble("level", -1);
            if (level >= 0 && level <= 1) receiverVolume = (float) level;
        }
        JSONArray apps = status.optJSONArray("applications");
        if (apps == null) return;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a != null && APP_ID.equals(a.optString("appId")) && !a.optString("transportId").isEmpty()) {
                sessionId = a.optString("sessionId");
                transportId = a.optString("transportId");
                synchronized (lock) {
                    lock.notifyAll();
                }
                return;
            }
        }
    }

    private void onMediaStatus(JSONArray arr) {
        if (arr == null || arr.length() == 0) return;
        JSONObject s = arr.optJSONObject(0);
        if (s == null) return;
        mediaSessionId = s.optInt("mediaSessionId", mediaSessionId);
        synchronized (lock) {
            lock.notifyAll();
        }
        lastState = s.optString("playerState", lastState);
        long now = System.currentTimeMillis();
        boolean stalePositionAfterSeek = false;
        if (s.has("currentTime")) {
            long reportedPos = (long) (s.optDouble("currentTime", 0) * 1000);
            long seekAt = seekRequestedAt;
            boolean awaitingSeekStatus = seekAt > 0L && now - seekAt < 5000L;
            boolean terminalState = "IDLE".equalsIgnoreCase(lastState)
                    || "FINISHED".equalsIgnoreCase(lastState);
            if (awaitingSeekStatus && !terminalState
                    && Math.abs(reportedPos - seekRequestedPosition) > 2500L) {
                // Keep the target and its clock anchor; the receiver has not reported the seek yet.
                stalePositionAfterSeek = true;
            } else {
                // Status messages can briefly report 0 during transitions. Preserve a valid position
                // unless the client explicitly sought to the beginning or playback has finished.
                if (reportedPos > 0L || lastPos <= 5000L || terminalState) lastPos = reportedPos;
                if (awaitingSeekStatus) seekRequestedAt = 0L;
            }
        }
        if (!stalePositionAfterSeek) lastPosAt = now;
        JSONObject media = s.optJSONObject("media");
        if (media != null && media.has("duration")) lastDur = (long) (media.optDouble("duration", 0) * 1000);
        if ("IDLE".equals(lastState) && "ERROR".equals(s.optString("idleReason"))) {
            listener.onError("O Chromecast não conseguiu reproduzir este vídeo.");
        }
        listener.onStatus(lastState, position(), lastDur);
    }

    // ---------------------------------------------------------------- escrita

    private void send(String ns, String dest, String payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x08);
        b.write(0x00); // protocol_version = CASTV2_1_0
        putString(b, 2, SENDER);
        putString(b, 3, dest);
        putString(b, 4, ns);
        b.write(0x28);
        b.write(0x00); // payload_type = STRING
        putString(b, 6, payload);
        byte[] msg = b.toByteArray();
        synchronized (this) {
            if (out == null) throw new IOException("sem conexão");
            out.write(new byte[]{(byte) (msg.length >>> 24), (byte) (msg.length >>> 16),
                    (byte) (msg.length >>> 8), (byte) msg.length});
            out.write(msg);
            out.flush();
        }
    }

    private static void putString(ByteArrayOutputStream b, int field, String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        b.write((field << 3) | 2);
        long v = d.length;
        while (v > 0x7F) {
            b.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        b.write((int) v);
        b.write(d, 0, d.length);
    }

    /** @return {valor, próxima posição} */
    private static long[] varint(byte[] a, int i) throws IOException {
        long v = 0;
        int shift = 0;
        while (true) {
            if (i >= a.length || shift > 63) throw new IOException("varint inválido");
            int c = a[i++] & 0xFF;
            v |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{v, i};
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
    }
}
