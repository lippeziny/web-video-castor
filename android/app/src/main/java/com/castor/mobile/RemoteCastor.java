package com.castor.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Cliente do api-server do Castor (Connect RPC em JSON) rodando num PC/NAS/Docker da sua rede.
 * É ele quem conversa com a TV, converte o vídeo com ffmpeg e queima legendas.
 */
public final class RemoteCastor {
    private RemoteCastor() {
    }

    public static List<CastDevice> listDevices(String base, String token) throws Exception {
        String r = post(base, token, "/castor.v1.DeviceService/ListDevices", "{}");
        JSONObject root = new JSONObject(r);
        JSONArray arr = root.optJSONArray("devices");
        if (arr == null) {
            Iterator<String> keys = root.keys();
            while (keys.hasNext() && arr == null) arr = root.optJSONArray(keys.next());
        }
        List<CastDevice> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = first(o, "id", "deviceId", "device_id", "name");
            if (id.isEmpty()) continue;
            String name = first(o, "name", "friendlyName", "friendly_name", "id");
            String type = first(o, "type", "protocol");
            out.add(new CastDevice(CastDevice.REMOTE, "remote:" + id,
                    type.isEmpty() ? name : name + " (" + type.toLowerCase() + ")", "", 0, id, ""));
        }
        return out;
    }

    /** Manda o servidor transmitir; {@code isPage} usa a extração de vídeo do próprio servidor. */
    public static void cast(String base, String token, String deviceId, String url, boolean isPage) throws Exception {
        JSONObject body = new JSONObject();
        body.put("target", new JSONObject().put("deviceId", deviceId));
        JSONObject source = new JSONObject();
        if (isPage) {
            source.put("pages", new JSONObject().put("urls", new JSONArray().put(url)));
        } else {
            source.put("stream", new JSONObject().put("url", url));
        }
        body.put("source", source);
        post(base, token, "/castor.v1.CastService/Cast", body.toString());
    }

    public static void stop(String base, String token, String deviceId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("target", new JSONObject().put("deviceId", deviceId));
        post(base, token, "/castor.v1.CastService/Stop", body.toString());
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String post(String base, String token, String path, String json) throws Exception {
        String b = base.trim();
        if (!b.startsWith("http")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        HttpURLConnection c = (HttpURLConnection) new URL(b + path).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(30000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Connect-Protocol-Version", "1");
        if (token != null && !token.trim().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token.trim());
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        }
        c.disconnect();
        String resp = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) {
            String msg = resp;
            try {
                msg = new JSONObject(resp).optString("message", resp);
            } catch (Exception ignored) {
            }
            if (code == 401 || code == 403) throw new IOException("Servidor recusou o token (" + code + ")");
            throw new IOException("Servidor Castor: " + code + " " + msg);
        }
        return resp.isEmpty() ? "{}" : resp;
    }
}
