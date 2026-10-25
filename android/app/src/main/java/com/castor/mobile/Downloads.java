package com.castor.mobile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Baixa um vídeo/áudio de link direto para a pasta Downloads pública do celular.
 * HLS/DASH não são suportados aqui: exigiriam FFmpeg nativo, que o app Android não embute.
 */
public final class Downloads {

    public interface Listener {
        void onDone(boolean success, String message, String uri);
    }

    private Downloads() {
    }

    /** Só baixamos arquivos de mídia progressiva (link direto). */
    public static boolean isDirect(String url, String type) {
        if (type != null && ("HLS".equals(type) || "DASH".equals(type))) return false;
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** Extensão do arquivo a partir da URL (sem query string). */
    public static String extensionOf(String url) {
        if (url == null) return "";
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int dot = p.lastIndexOf('.');
        int slash = p.lastIndexOf('/');
        if (dot > slash && p.length() - dot <= 6) return p.substring(dot + 1).toLowerCase(Locale.ROOT);
        return "";
    }

    public static String mimeOf(String url, String type) {
        if (type != null) {
            switch (type) {
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
                case "ÁUDIO":
                    String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
                    if (u.contains(".m4a")) return "audio/mp4";
                    if (u.contains(".ogg")) return "audio/ogg";
                    if (u.contains(".wav")) return "audio/wav";
                    return "audio/mpeg";
                default:
                    break;
            }
        }
        String ext = extensionOf(url);
        if (!ext.isEmpty()) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "video/mp4";
    }

    /** Nome de arquivo seguro a partir do título e da extensão da URL. */
    public static String fileName(String url, String title) {
        String base = (title == null || title.trim().isEmpty()) ? "video" : title.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "video";
        if (base.length() > 80) base = base.substring(0, 80);
        String ext = extensionOf(url);
        if (ext.isEmpty()) {
            String m = mimeOf(url, null);
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
            if (ext == null) ext = "mp4";
        }
        return base + "." + ext;
    }

    /** Inicia o download em segundo plano; o listener é chamado na thread principal. */
    public static void start(final Context context, final String url, final String title,
                             final String referer, final Listener listener) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setRequestProperty("User-Agent", MainActivity.USER_AGENT_MOBILE);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (referer != null && !referer.isEmpty()) conn.setRequestProperty("Referer", referer);
                int code = conn.getResponseCode();
                if (code >= 400) {
                    post(listener, false, "Não foi possível baixar (HTTP " + code + ")", null);
                    return;
                }
                String name = fileName(url, title);
                Uri uri = save(context, name, mimeOf(url, null), conn.getInputStream());
                post(listener, true, "Baixado para Downloads: " + name, uri == null ? null : uri.toString());
            } catch (Exception e) {
                post(listener, false, "Falha ao baixar: " + e.getMessage(), null);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "castor-download").start();
    }

    private static Uri save(Context context, String name, String mime, InputStream in) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = context.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("não foi possível criar o arquivo");
            try (OutputStream out = r.openOutputStream(uri)) {
                copy(in, out);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            r.update(uri, v, null, null);
            return uri;
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, name);
            try (OutputStream out = new FileOutputStream(f)) {
                copy(in, out);
            }
            return Uri.fromFile(f);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void post(final Listener l, final boolean ok, final String msg, final String uri) {
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(() -> l.onDone(ok, msg, uri));
    }
}
