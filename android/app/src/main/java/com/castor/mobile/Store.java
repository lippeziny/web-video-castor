package com.castor.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Persistência simples (SharedPreferences) de configurações, histórico e fila. */
public final class Store {
    private static final String PREFS = "castor_store";
    private static final String K_HISTORY = "history";
    private static final String K_QUEUE = "queue";
    private static final String K_POPUP = "block_popup";
    private static final String K_REDIRECT = "block_redirect";
    private static final String K_ADS = "block_ads";
    private static final String K_SECONDS = "notice_seconds";
    private static final String K_DESKTOP = "desktop_mode";
    private static final String K_FILTER_TIME = "filter_time";
    private static final String K_TABS = "tabs";
    private static final String K_TABS_CURRENT = "tabs_current";
    private static final String K_POSITIONS = "positions";
    private static final String K_CAST_PROXY = "cast_proxy";
    private static final String K_REMOTE_URL = "remote_url";
    private static final String K_REMOTE_TOKEN = "remote_token";
    private static final int HISTORY_MAX = 300;
    private static final int POSITIONS_MAX = 400;
    private static final int TABS_MAX = 30;

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean popup() { return p.getBoolean(K_POPUP, true); }
    public boolean redirect() { return p.getBoolean(K_REDIRECT, true); }
    public boolean ads() { return p.getBoolean(K_ADS, true); }
    public boolean desktop() { return p.getBoolean(K_DESKTOP, false); }
    public int noticeSeconds() { return p.getInt(K_SECONDS, 3); }
    public long filterTime() { return p.getLong(K_FILTER_TIME, 0L); }

    public void setPopup(boolean v) { p.edit().putBoolean(K_POPUP, v).apply(); }
    public void setRedirect(boolean v) { p.edit().putBoolean(K_REDIRECT, v).apply(); }
    public void setAds(boolean v) { p.edit().putBoolean(K_ADS, v).apply(); }
    public void setDesktop(boolean v) { p.edit().putBoolean(K_DESKTOP, v).apply(); }
    public void setNoticeSeconds(int v) { p.edit().putInt(K_SECONDS, v).apply(); }
    public void setFilterTime(long v) { p.edit().putLong(K_FILTER_TIME, v).apply(); }

    // ---- transmissão (cast) ----
    public boolean castProxy() { return p.getBoolean(K_CAST_PROXY, true); }
    public String remoteUrl() { return p.getString(K_REMOTE_URL, ""); }
    public String remoteToken() { return p.getString(K_REMOTE_TOKEN, ""); }
    public void setCastProxy(boolean v) { p.edit().putBoolean(K_CAST_PROXY, v).apply(); }
    public void setRemote(String url, String token) {
        p.edit().putString(K_REMOTE_URL, url == null ? "" : url.trim())
                .putString(K_REMOTE_TOKEN, token == null ? "" : token.trim()).apply();
    }

    // ---- abas abertas (restauradas ao abrir o app) ----
    /** Salva as abas; cada item é {url, título}. */
    public void saveTabs(List<String[]> list, int current) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < list.size() && i < TABS_MAX; i++) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", list.get(i)[0]);
                o.put("title", list.get(i)[1]);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        p.edit().putString(K_TABS, a.toString()).putInt(K_TABS_CURRENT, current).apply();
    }

    public List<String[]> savedTabs() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_TABS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String u = o.optString("url");
                if (!u.isEmpty()) out.add(new String[]{u, o.optString("title")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public int savedTabsCurrent() { return p.getInt(K_TABS_CURRENT, 0); }

    // ---- posição salva do vídeo, por endereço da PÁGINA (o link do vídeo muda, o da página não) ----
    /** Normaliza o endereço da página: sem esquema, "www.", fragmento e parâmetros de rastreio. */
    public static String pageKey(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = u.getPath() == null ? "" : u.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            StringBuilder sb = new StringBuilder(host).append(path);
            TreeSet<String> names = new TreeSet<>(u.getQueryParameterNames());
            char sep = '?';
            for (String n : names) {
                String ln = n.toLowerCase(Locale.ROOT);
                if (ln.startsWith("utm_") || ln.equals("fbclid") || ln.equals("gclid") || ln.equals("ref")) continue;
                for (String v : u.getQueryParameters(n)) {
                    sb.append(sep).append(n).append('=').append(v);
                    sep = '&';
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** @return {posição ms, duração ms} ou null se não houver posição salva. */
    public long[] getPosition(String pageUrl) {
        String key = pageKey(pageUrl);
        if (key.isEmpty()) return null;
        try {
            JSONObject all = new JSONObject(p.getString(K_POSITIONS, "{}"));
            JSONObject o = all.optJSONObject(key);
            if (o == null) return null;
            long pos = o.optLong("p", 0);
            return pos > 0 ? new long[]{pos, o.optLong("d", 0)} : null;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setPosition(String pageUrl, long posMs, long durMs) {
        String key = pageKey(pageUrl);
        if (key.isEmpty()) return;
        try {
            JSONObject all = new JSONObject(p.getString(K_POSITIONS, "{}"));
            JSONObject o = new JSONObject();
            o.put("p", posMs);
            o.put("d", durMs);
            o.put("t", System.currentTimeMillis());
            all.put(key, o);
            if (all.length() > POSITIONS_MAX) {
                String oldest = null;
                long oldestT = Long.MAX_VALUE;
                Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    long t = all.getJSONObject(k).optLong("t", 0);
                    if (t < oldestT) {
                        oldestT = t;
                        oldest = k;
                    }
                }
                if (oldest != null) all.remove(oldest);
            }
            p.edit().putString(K_POSITIONS, all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public void clearPosition(String pageUrl) {
        String key = pageKey(pageUrl);
        if (key.isEmpty()) return;
        try {
            JSONObject all = new JSONObject(p.getString(K_POSITIONS, "{}"));
            if (all.has(key)) {
                all.remove(key);
                p.edit().putString(K_POSITIONS, all.toString()).apply();
            }
        } catch (JSONException ignored) {
        }
    }

    // ---- histórico de páginas ----
    public List<String[]> history() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_HISTORY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("url"), o.optString("title")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void addHistory(String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        List<String[]> list = history();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i)[0].equals(url)) list.remove(i);
        }
        list.add(0, new String[]{url, title == null ? "" : title});
        while (list.size() > HISTORY_MAX) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String[] e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", e[0]);
                o.put("title", e[1]);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        p.edit().putString(K_HISTORY, a.toString()).apply();
    }

    public void clearHistory() { p.edit().remove(K_HISTORY).apply(); }

    // ---- fila de reprodução ----
    public List<MediaItem> queue() {
        List<MediaItem> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_QUEUE, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(MediaItem.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void saveQueue(List<MediaItem> list) {
        JSONArray a = new JSONArray();
        for (MediaItem m : list) a.put(m.toJson());
        p.edit().putString(K_QUEUE, a.toString()).apply();
    }

    public boolean addToQueue(MediaItem item) {
        List<MediaItem> q = queue();
        for (MediaItem m : q) if (m.url.equals(item.url)) return false;
        q.add(item);
        saveQueue(q);
        return true;
    }

    public void removeFromQueue(int index) {
        List<MediaItem> q = queue();
        if (index >= 0 && index < q.size()) {
            q.remove(index);
            saveQueue(q);
        }
    }

    public void clearQueue() { p.edit().remove(K_QUEUE).apply(); }

    public static String queueToJson(List<MediaItem> list) {
        JSONArray a = new JSONArray();
        for (MediaItem m : list) a.put(m.toJson());
        return a.toString();
    }

    public static List<MediaItem> queueFromJson(String json) {
        List<MediaItem> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(MediaItem.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }
}
