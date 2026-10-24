package com.castor.mobile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Descoberta (SSDP) e controle (UPnP AVTransport/SOAP) de TVs e players DLNA. */
public final class DlnaClient {
    private DlnaClient() {
    }

    // ---------------------------------------------------------------- descoberta

    /** Procura renderizadores por {@code timeoutMs}; chama {@code cb} para cada novo dispositivo. Bloqueante. */
    public static void search(int timeoutMs, Consumer<CastDevice> cb) {
        Set<String> seen = new HashSet<>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(700);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] targets = {
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1"
            };
            long end = System.currentTimeMillis() + timeoutMs;
            long nextSend = 0;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (String st : targets) {
                        String req = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                                + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
                        byte[] b = req.getBytes(StandardCharsets.US_ASCII);
                        try {
                            sock.send(new DatagramPacket(b, b.length, group, 1900));
                        } catch (IOException ignored) {
                        }
                    }
                    nextSend = System.currentTimeMillis() + 1800;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException t) {
                    continue;
                }
                String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                String loc = header(resp, "location");
                if (loc == null || !seen.add(loc)) continue;
                try {
                    CastDevice d = describe(loc);
                    if (d != null) cb.accept(d);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
    }

    private static String header(String resp, String name) {
        for (String line : resp.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    /** Lê o XML de descrição; devolve null se o aparelho não tiver AVTransport. */
    public static CastDevice describe(String location) throws Exception {
        byte[] xml = httpGet(location, 4000);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
        }
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(xml));
        String name = text(doc.getElementsByTagName("friendlyName"));
        String udn = text(doc.getElementsByTagName("UDN"));
        NodeList services = doc.getElementsByTagName("service");
        URL base = new URL(location);
        String avtControl = "", avtService = "", renderControl = "", renderService = "";
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            String type = text(s.getElementsByTagName("serviceType"));
            String ctl = text(s.getElementsByTagName("controlURL"));
            if (type == null || ctl == null) continue;
            String abs = new URL(base, ctl).toString();
            if (type.contains(":service:AVTransport:")) {
                avtControl = abs;
                avtService = type;
            } else if (type.contains(":service:RenderingControl:")) {
                renderControl = abs;
                renderService = type;
            }
        }
        if (avtControl.isEmpty()) return null;
        return new CastDevice(CastDevice.DLNA, "dlna:" + (udn != null ? udn : location),
                name, base.getHost(), base.getPort(), avtControl, avtService, renderControl, renderService);
    }

    private static String text(NodeList l) {
        if (l == null || l.getLength() == 0) return null;
        String t = l.item(0).getTextContent();
        return t == null ? null : t.trim();
    }

    static byte[] httpGet(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- controle

    public static void setUri(CastDevice d, String url, String title, String mime) throws IOException {
        boolean audio = mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/");
        String objectClass = audio ? "object.item.audioItem.musicTrack" : "object.item.videoItem";
        // Advertise seek support for progressive files. HLS is a playlist stream and is not
        // consistently seekable over generic DLNA renderers.
        boolean progressive = mime != null && !mime.toLowerCase(Locale.ROOT).contains("mpegurl")
                && !mime.toLowerCase(Locale.ROOT).contains("dash+xml");
        // The proxy implements HTTP byte ranges, not TimeSeekRange.dlna.org, so advertise
        // byte seek (OP=01) only. FLAGS includes byte-seek (0x20000000) and Interactive mode.
        String dlnaOps = progressive
                ? "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=21F00000000000000000000000000000"
                : "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>" + objectClass + "</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + esc(mime) + ":" + dlnaOps + "\">"
                + esc(url) + "</res></item></DIDL-Lite>";
        soap(d, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>" + esc(url)
                + "</CurrentURI><CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
    }

    public static void play(CastDevice d) throws IOException {
        soap(d, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    public static void pause(CastDevice d) throws IOException {
        soap(d, "Pause", "<InstanceID>0</InstanceID>");
    }

    public static void stop(CastDevice d) throws IOException {
        soap(d, "Stop", "<InstanceID>0</InstanceID>");
    }

    /**
     * Seek is implemented defensively for Samsung DLNA renderers. Some firmwares only publish
     * Seek after the current transition completes or while paused, and reject a fractional time.
     */
    public static void seek(CastDevice d, long ms) throws IOException {
        // A number of Samsung AVTransport implementations answer 701 when Seek is sent while
        // PLAYING, even though they support it once the renderer is fully PAUSED_PLAYBACK.
        // Start by settling the transport instead of knowingly issuing a request likely to fail.
        String target = fmt(ms);
        String initialState = transportState(d);
        if (initialState.isEmpty()) initialState = "PLAYING"; // best-effort for older renderers
        boolean wasPlaying = "PLAYING".equalsIgnoreCase(initialState)
                || "TRANSITIONING".equalsIgnoreCase(initialState);
        boolean pausedByUs = false;
        IOException failures = new IOException("A TV recusou a busca para " + target);
        String state = initialState;
        try {
            long transitionDeadline = System.currentTimeMillis() + 4500L;
            while ("TRANSITIONING".equalsIgnoreCase(state)
                    && System.currentTimeMillis() < transitionDeadline) {
                sleepQuietly(180L);
                state = transportState(d);
            }

            if ("PLAYING".equalsIgnoreCase(state)) {
                try {
                    pause(d);
                    pausedByUs = true;
                } catch (IOException pauseError) {
                    failures.addSuppressed(pauseError);
                }
            }

            // Samsung TVs can take more than one second to move to PAUSED_PLAYBACK. Re-issuing
            // Pause is intentionally avoided unless the state remains PLAYING after the first wait.
            long pausedDeadline = System.currentTimeMillis() + 4200L;
            boolean pauseRetried = false;
            while (System.currentTimeMillis() < pausedDeadline) {
                state = transportState(d);
                if ("PAUSED_PLAYBACK".equalsIgnoreCase(state) || "PAUSED".equalsIgnoreCase(state)) break;
                if ("PLAYING".equalsIgnoreCase(state) && !pauseRetried) {
                    try { pause(d); pausedByUs = true; } catch (IOException e) { failures.addSuppressed(e); }
                    pauseRetried = true;
                }
                if (!"PLAYING".equalsIgnoreCase(state) && !"TRANSITIONING".equalsIgnoreCase(state)
                        && !state.isEmpty() && !"PAUSED_PLAYBACK".equalsIgnoreCase(state)) break;
                sleepQuietly(180L);
            }

            String actions = currentTransportActions(d);
            IOException lastError = null;
            String[] modes = {"REL_TIME", "ABS_TIME"};
            for (String mode : modes) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        soap(d, "Seek", "<InstanceID>0</InstanceID><Unit>" + mode
                                + "</Unit><Target>" + target + "</Target>");
                        // A successful command can still take a short time to update GetPositionInfo.
                        // CastManager suppresses stale poll values while it persists this target.
                        return;
                    } catch (IOException e) {
                        lastError = e;
                        failures.addSuppressed(e);
                        if (!isTransitionUnavailable(e) && !isSeekModeUnsupported(e)) break;
                        String nowState = transportState(d);
                        if ("TRANSITIONING".equalsIgnoreCase(nowState)) {
                            long deadline = System.currentTimeMillis() + 1700L;
                            while ("TRANSITIONING".equalsIgnoreCase(nowState)
                                    && System.currentTimeMillis() < deadline) {
                                sleepQuietly(150L);
                                nowState = transportState(d);
                            }
                        }
                        // If a renderer resumed itself while processing the failed request, pause
                        // it again before retrying. Some firmware only exposes Seek in paused state.
                        if ("PLAYING".equalsIgnoreCase(nowState)) {
                            try { pause(d); pausedByUs = true; } catch (IOException pauseError) { failures.addSuppressed(pauseError); }
                            long deadline = System.currentTimeMillis() + 1400L;
                            while (System.currentTimeMillis() < deadline) {
                                nowState = transportState(d);
                                if ("PAUSED_PLAYBACK".equalsIgnoreCase(nowState)
                                        || "PAUSED".equalsIgnoreCase(nowState)) break;
                                sleepQuietly(150L);
                            }
                        }
                        if (attempt < 2) sleepQuietly(250L + attempt * 250L);
                        // Do not repeat an invalid seek mode three times; give ABS_TIME a chance.
                        if (isSeekModeUnsupported(e)) break;
                    }
                }
            }
            String finalState = transportState(d);
            String detail = lastError == null ? "" : " Último erro: " + lastError.getMessage() + ".";
            throw new IOException("A TV recusou a busca para " + target + ". Estado final: "
                    + finalState + "; ações anunciadas: " + (actions.isEmpty() ? "não informadas" : actions)
                    + "." + detail + " Se Seek não aparecer nas ações anunciadas mesmo pausada, "
                    + "o firmware/recurso atual da TV não está oferecendo busca por DLNA.",
                    lastError == null ? failures : lastError);
        } finally {
            if (pausedByUs && wasPlaying) {
                try { play(d); } catch (IOException resumeError) { failures.addSuppressed(resumeError); }
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static boolean isTransitionUnavailable(IOException e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("701") || (m.contains("500") && m.contains("transition not available"))
                || m.contains("transition not available") || m.contains("transição não disponível");
    }

    private static boolean isSeekModeUnsupported(IOException e) {
        String m = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("710") || m.contains("seek mode not supported") || m.contains("seek mode unsupported");
    }

    public static String currentTransportActions(CastDevice d) {
        try {
            String r = soap(d, "GetCurrentTransportActions", "<InstanceID>0</InstanceID>");
            String actions = tag(r, "Actions");
            return actions == null ? "" : actions.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** @return {posição ms, duração ms} ou null. */
    public static long[] position(CastDevice d) {
        try {
            String r = soap(d, "GetPositionInfo", "<InstanceID>0</InstanceID>");
            long pos = parseTime(tag(r, "RelTime"));
            long dur = parseTime(tag(r, "TrackDuration"));
            return new long[]{pos, dur};
        } catch (Exception e) {
            return null;
        }
    }

    /** @return PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING... ou "" */
    public static String transportState(CastDevice d) {
        try {
            String r = soap(d, "GetTransportInfo", "<InstanceID>0</InstanceID>");
            String s = tag(r, "CurrentTransportState");
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    public static int getVolume(CastDevice d) throws IOException {
        if (d.renderingControl.isEmpty()) throw new IOException("A TV não anuncia controle de volume DLNA");
        String svc = d.renderingService.isEmpty()
                ? "urn:schemas-upnp-org:service:RenderingControl:1" : d.renderingService;
        String response = soapAt(d.renderingControl, svc, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>");
        String value = tag(response, "CurrentVolume");
        if (value == null) throw new IOException("A TV não retornou o volume atual");
        try { return Math.max(0, Math.min(100, Integer.parseInt(value.trim()))); }
        catch (NumberFormatException e) { throw new IOException("Volume DLNA inválido", e); }
    }

    public static void setVolume(CastDevice d, int volume) throws IOException {
        if (d.renderingControl.isEmpty()) throw new IOException("A TV não anuncia controle de volume DLNA");
        String svc = d.renderingService.isEmpty()
                ? "urn:schemas-upnp-org:service:RenderingControl:1" : d.renderingService;
        soapAt(d.renderingControl, svc, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"
                + "<DesiredVolume>" + Math.max(0, Math.min(100, volume)) + "</DesiredVolume>");
    }

    private static String soap(CastDevice d, String action, String args) throws IOException {
        String svc = d.service.isEmpty() ? "urn:schemas-upnp-org:service:AVTransport:1" : d.service;
        return soapAt(d.control, svc, action, args);
    }

    private static String soapAt(String controlUrl, String svc, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + svc + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(controlUrl).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + svc + "#" + action + "\"");
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
        c.disconnect();
        if (code >= 400) {
            String upnpCode = tag(resp, "errorCode");
            String desc = tag(resp, "errorDescription");
            String fault = upnpCode == null ? "" : " [UPnP " + upnpCode + "]";
            throw new IOException("DLNA " + action + " falhou (HTTP " + code + ")" + fault
                    + (desc != null ? ": " + desc : ""));
        }
        return resp;
    }

    // ---------------------------------------------------------------- utilidades

    private static String tag(String xml, String name) {
        if (xml == null) return null;
        Pattern p = Pattern.compile("(?is)<(?:[A-Za-z0-9_-]+:)?" + Pattern.quote(name)
                + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_-]+:)?" + Pattern.quote(name) + "\\s*>");
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    public static String fmt(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        return String.format(Locale.ROOT, "%d:%02d:%02d",
                totalSeconds / 3600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L);
    }

    /** Returns -1 when the TV reports NOT_IMPLEMENTED or an invalid time, not a fake 00:00:00. */
    static long parseTime(String t) {
        if (t == null || t.trim().isEmpty() || t.trim().equalsIgnoreCase("NOT_IMPLEMENTED")) return -1L;
        try {
            String[] p = t.trim().split(":");
            if (p.length < 3) return -1L;
            double sec = Double.parseDouble(p[2]);
            return (Long.parseLong(p[0]) * 3600L + Long.parseLong(p[1]) * 60L) * 1000L + (long) (sec * 1000);
        } catch (Exception e) {
            return -1L;
        }
    }
}
