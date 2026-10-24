package com.castor.mobile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor HTTP local no celular. A TV não sabe enviar Referer/Cookie/User-Agent,
 * então ela busca o vídeo aqui e o app busca no site original com os cabeçalhos certos.
 * Playlists HLS (.m3u8) são reescritas para que os segmentos também passem pelo proxy.
 */
public final class LocalProxy {
    public interface CookieProvider {
        String cookiesFor(String url);
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    private static final class Entry {
        final String url;
        final String referer;

        Entry(String url, String referer) {
            this.url = url;
            this.referer = referer == null ? "" : referer;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> byKey = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "castor-proxy-conn");
        t.setDaemon(true);
        return t;
    });
    private volatile ServerSocket server;
    private volatile String host = "127.0.0.1";
    private volatile CookieProvider cookies;
    private long counter;

    public void setCookieProvider(CookieProvider p) {
        cookies = p;
    }

    public synchronized void start() throws IOException {
        if (server != null && !server.isClosed()) return;
        ServerSocket ss = new ServerSocket(0, 64);
        server = ss;
        Thread t = new Thread(() -> acceptLoop(ss), "castor-proxy");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        entries.clear();
        byKey.clear();
    }

    public boolean isRunning() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    public void setHost(String h) {
        if (h != null && !h.isEmpty()) host = h;
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? 0 : s.getLocalPort();
    }

    /** Registra uma URL de origem e devolve a URL local que a TV deve abrir. */
    public synchronized String register(String url, String referer) {
        String key = url + "\n" + (referer == null ? "" : referer);
        String id = byKey.get(key);
        if (id == null) {
            id = Long.toString(System.nanoTime(), 36) + Long.toString(++counter, 36);
            byKey.put(key, id);
            entries.put(id, new Entry(url, referer));
        }
        return "http://" + host + ":" + port() + "/s/" + id + extensionOf(url);
    }

    static String extensionOf(String url) {
        try {
            String p = new URL(url).getPath().toLowerCase(Locale.ROOT);
            int dot = p.lastIndexOf('.');
            if (dot >= 0 && p.length() - dot <= 6 && p.substring(dot + 1).matches("[a-z0-9]+")) {
                return p.substring(dot);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** IPv4 da rede local (Wi-Fi ou ponto de acesso) que a TV consegue alcançar. */
    public static String lanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return null;
            for (NetworkInterface ni : Collections.list(en)) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName().toLowerCase(Locale.ROOT);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
                            || name.startsWith("swlan")) {
                        return a.getHostAddress();
                    }
                    if (fallback == null && !name.startsWith("rmnet") && !name.startsWith("ccmni")) {
                        fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                final Socket s = ss.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1];
            if (method.equals("OPTIONS")) {
                writeHead(out, 204, "No Content", new String[][]{{"Content-Length", "0"}});
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                writeHead(out, 405, "Method Not Allowed", new String[][]{{"Content-Length", "0"}});
                return;
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            Entry e = null;
            if (path.startsWith("/s/")) {
                String id = path.substring(3);
                int dot = id.indexOf('.');
                if (dot >= 0) id = id.substring(0, dot);
                e = entries.get(id);
            }
            if (e == null) {
                writeHead(out, 404, "Not Found", new String[][]{{"Content-Length", "0"}});
                return;
            }
            serve(out, method, e, headers.get("range"));
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve(OutputStream out, String method, Entry e, String range) throws IOException {
        HttpURLConnection conn = null;
        try {
            String target = e.url;
            for (int i = 0; i < 6; i++) {
                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setInstanceFollowRedirects(false);
                // Respect HEAD requests. Some TV renderers use HEAD to inspect seek/range support
                // before deciding whether the DLNA Seek action is available.
                conn.setRequestMethod("HEAD".equalsIgnoreCase(method) ? "HEAD" : "GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (!e.referer.isEmpty()) {
                    conn.setRequestProperty("Referer", e.referer);
                    try {
                        URL r = new URL(e.referer);
                        conn.setRequestProperty("Origin", r.getProtocol() + "://" + r.getHost());
                    } catch (Exception ignored) {
                    }
                }
                CookieProvider cp = cookies;
                if (cp != null) {
                    String ck = cp.cookiesFor(target);
                    if (ck != null && !ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
                }
                if (range != null && !range.isEmpty()) conn.setRequestProperty("Range", range);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) break;
                    target = new URL(new URL(target), loc).toString();
                    continue;
                }
                break;
            }
            if (conn == null) {
                writeHead(out, 502, "Bad Gateway", new String[][]{{"Content-Length", "0"}});
                return;
            }
            int code = conn.getResponseCode();
            URL finalUrl = conn.getURL();
            String ct = conn.getContentType();
            String lowerPath = finalUrl.getPath().toLowerCase(Locale.ROOT);
            boolean playlist = lowerPath.endsWith(".m3u8")
                    || (ct != null && ct.toLowerCase(Locale.ROOT).contains("mpegurl"));
            if (code >= 400) {
                writeHead(out, code, "Error", new String[][]{{"Content-Length", "0"}});
                return;
            }
            if (playlist) {
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0 && bos.size() < 8 * 1024 * 1024) bos.write(buf, 0, n);
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                byte[] body = rewritePlaylist(text, finalUrl, e.referer).getBytes(StandardCharsets.UTF_8);
                writeHead(out, 200, "OK", new String[][]{
                        {"Content-Type", "application/vnd.apple.mpegurl"},
                        {"Content-Length", String.valueOf(body.length)}});
                if (!method.equals("HEAD")) out.write(body);
                out.flush();
                return;
            }
            String len = conn.getHeaderField("Content-Length");
            String cr = conn.getHeaderField("Content-Range");
            if (ct == null || ct.isEmpty()) ct = guessType(lowerPath);
            java.util.List<String[]> h = new java.util.ArrayList<>();
            h.add(new String[]{"Content-Type", ct});
            h.add(new String[]{"Accept-Ranges", "bytes"});
            // On-demand video should be advertised as Interactive. Streaming is for one-way/live
            // content and causes some Samsung renderers to omit Seek from AVTransport actions.
            String ctypeLower = ct.toLowerCase(Locale.ROOT);
            boolean progressiveMedia = ctypeLower.startsWith("video/")
                    && !ctypeLower.contains("mpegurl") && !ctypeLower.contains("dash+xml");
            h.add(new String[]{"transferMode.dlna.org", progressiveMedia ? "Interactive" : "Streaming"});
            // DLNA renderers often hide/disable Seek unless byte/time seeking is advertised.
            // The proxy forwards Range requests to the origin and relays 206 + Content-Range.
            if (progressiveMedia) {
                h.add(new String[]{"contentFeatures.dlna.org",
                        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=21F00000000000000000000000000000"});
            }
            if (len != null) h.add(new String[]{"Content-Length", len});
            if (cr != null) h.add(new String[]{"Content-Range", cr});
            writeHead(out, code, code == 206 ? "Partial Content" : "OK", h.toArray(new String[0][]));
            if (method.equals("HEAD")) {
                out.flush();
                return;
            }
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (IOException ex) {
            // TV fechou a conexão (comum ao dar seek) ou o site caiu: nada a fazer.
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String guessType(String lowerPath) {
        if (lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v")) return "video/mp4";
        if (lowerPath.endsWith(".webm")) return "video/webm";
        if (lowerPath.endsWith(".ts")) return "video/mp2t";
        if (lowerPath.endsWith(".m4s")) return "video/iso.segment";
        if (lowerPath.endsWith(".aac")) return "audio/aac";
        if (lowerPath.endsWith(".mp3")) return "audio/mpeg";
        if (lowerPath.endsWith(".vtt")) return "text/vtt";
        return "application/octet-stream";
    }

    String rewritePlaylist(String text, URL base, String referer) {
        StringBuilder out = new StringBuilder(text.length() + 256);
        for (String line : text.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                out.append(line).append('\n');
            } else if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String u = m.group(1);
                    String rep = u.startsWith("data:") ? u : proxify(u, base, referer);
                    m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + rep + "\""));
                }
                m.appendTail(sb);
                out.append(sb).append('\n');
            } else {
                out.append(proxify(t, base, referer)).append('\n');
            }
        }
        return out.toString();
    }

    private String proxify(String ref, URL base, String referer) {
        try {
            return register(new URL(base, ref).toString(), referer);
        } catch (Exception e) {
            return ref;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("linha longa demais");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void writeHead(OutputStream out, int code, String msg, String[][] headers) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        for (String[] h : headers) sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: *\r\n");
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
