package com.castor.mobile;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lê listas IPTV no formato M3U/M3U8 (#EXTINF + URL) e devolve uma lista de MediaItem. */
public final class Iptv {

    private Iptv() {
    }

    /** Baixa e interpreta uma lista remota. {@code url} é usada como base para links relativos. */
    public static List<MediaItem> fetch(Context ctx, String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent", MainActivity.USER_AGENT_MOBILE);
        try {
            int code = conn.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return parse(sb.toString(), url);
        } finally {
            conn.disconnect();
        }
    }

    /** Interpreta o texto de uma lista M3U/M3U8. */
    public static List<MediaItem> parse(String text, String baseUrl) {
        List<MediaItem> out = new ArrayList<>();
        if (text == null) return out;
        String pendingTitle = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.indexOf(',');
                String title = comma >= 0 ? line.substring(comma + 1).trim() : "";
                if (title.isEmpty()) title = attr(line, "tvg-name");
                if (title.isEmpty()) title = attr(line, "group-title");
                pendingTitle = title.isEmpty() ? "Canal" : title;
                continue;
            }
            if (line.startsWith("#")) continue;
            String url = resolve(baseUrl, line);
            out.add(new MediaItem(url, pendingTitle.isEmpty() ? "Canal" : pendingTitle,
                    "", baseUrl == null ? "" : baseUrl, typeOf(url)));
            pendingTitle = "";
        }
        return out;
    }

    private static String attr(String line, String name) {
        int i = line.indexOf(name + "=\"");
        if (i < 0) return "";
        int s = i + name.length() + 2;
        int e = line.indexOf('"', s);
        return e > s ? line.substring(s, e) : "";
    }

    private static String resolve(String base, String ref) {
        if (ref == null) return "";
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        try {
            if (base != null && !base.isEmpty()) return new URL(new URL(base), ref).toString();
        } catch (Exception ignored) {
        }
        return ref;
    }

    static String typeOf(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (u.contains(".m3u8") || u.contains(".m3u")) return "HLS";
        if (u.contains(".mpd")) return "DASH";
        if (u.contains(".ts")) return "TS";
        if (u.contains(".webm")) return "WEBM";
        if (u.contains(".mp4")) return "MP4";
        if (u.contains(".mkv")) return "MKV";
        if (u.contains(".flv")) return "FLV";
        return "HLS";
    }
}
