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
    private static final String K_THEME = "theme_mode";
    private static final String K_AUTO_PLAY = "auto_play";
    private static final String K_QUALITY = "quality_mode";
    private static final String K_CUSTOM_ICON = "custom_icon_uri";
    private static final String K_ACCENT = "accent_index";
    private static final String K_ROUNDED = "ui_rounded";
    private static final String K_NAV_LABELS = "nav_labels";
    private static final String K_SEARCH = "search_engine";
    private static final String K_TEXT_ZOOM = "text_zoom";
    private static final String K_BOOKMARKS = "bookmarks";
    private static final String K_DOWNLOADS = "downloads";
    private static final String K_IPTV_LISTS = "iptv_lists";
    private static final String K_IPTV_CHANNELS = "iptv_channels";
    private static final int BOOKMARKS_MAX = 200;
    private static final int DOWNLOADS_MAX = 200;

    public static final String[] SEARCH_IDS = {"google", "duckduckgo", "bing", "brave", "ecosia", "startpage"};
    public static final String[] SEARCH_NAMES = {"Google", "DuckDuckGo", "Bing", "Brave Search", "Ecosia", "Startpage"};
    private static final String[] SEARCH_URLS = {
            "https://www.google.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://www.bing.com/search?q=",
            "https://search.brave.com/search?q=",
            "https://www.ecosia.org/search?q=",
            "https://www.startpage.com/do/search?q="
    };
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
    public String theme() { return p.getString(K_THEME, "system"); }
    public boolean autoPlay() { return p.getBoolean(K_AUTO_PLAY, false); }
    /** high, medium ou low */
    public String quality() { return p.getString(K_QUALITY, "high"); }
    public String customIconUri() { return p.getString(K_CUSTOM_ICON, ""); }

    public void setPopup(boolean v) { p.edit().putBoolean(K_POPUP, v).apply(); }
    public void setRedirect(boolean v) { p.edit().putBoolean(K_REDIRECT, v).apply(); }
    public void setAds(boolean v) { p.edit().putBoolean(K_ADS, v).apply(); }
    public void setDesktop(boolean v) { p.edit().putBoolean(K_DESKTOP, v).apply(); }
    public void setNoticeSeconds(int v) { p.edit().putInt(K_SECONDS, v).apply(); }
    public void setFilterTime(long v) { p.edit().putLong(K_FILTER_TIME, v).apply(); }
    public void setTheme(String v) {
        if (!"light".equals(v) && !"amoled".equals(v) && !"system".equals(v)) v = "dark";
        p.edit().putString(K_THEME, v).apply();
    }
    public void setAutoPlay(boolean v) { p.edit().putBoolean(K_AUTO_PLAY, v).apply(); }
    public void setQuality(String v) {
        if (!"medium".equals(v) && !"low".equals(v)) v = "high";
        p.edit().putString(K_QUALITY, v).apply();
    }
    public void setCustomIconUri(String uri) {
        p.edit().putString(K_CUSTOM_ICON, uri == null ? "" : uri).apply();
    }

    // ---- aparência e navegação (3.0) ----
    /** Índice da cor de destaque (ver ThemePalette.ACCENT_NAMES). */
    public int accent() { return p.getInt(K_ACCENT, 0); }
    public void setAccent(int v) { p.edit().putInt(K_ACCENT, v).apply(); }
    public boolean rounded() { return p.getBoolean(K_ROUNDED, true); }
    public void setRounded(boolean v) { p.edit().putBoolean(K_ROUNDED, v).apply(); }
    public boolean navLabels() { return p.getBoolean(K_NAV_LABELS, true); }
    public void setNavLabels(boolean v) { p.edit().putBoolean(K_NAV_LABELS, v).apply(); }
    /** Zoom do texto das páginas, em porcentagem (80 a 150). */
    public int textZoom() { return p.getInt(K_TEXT_ZOOM, 100); }
    public void setTextZoom(int v) { p.edit().putInt(K_TEXT_ZOOM, Math.max(80, Math.min(150, v))).apply(); }

    public int searchIndex() {
        String id = p.getString(K_SEARCH, "google");
        for (int i = 0; i < SEARCH_IDS.length; i++) if (SEARCH_IDS[i].equals(id)) return i;
        return 0;
    }
    public void setSearchIndex(int i) {
        if (i < 0 || i >= SEARCH_IDS.length) i = 0;
        p.edit().putString(K_SEARCH, SEARCH_IDS[i]).apply();
    }
    /** Endereço de busca do mecanismo escolhido, já terminando em "q=". */
    public String searchUrl() { return SEARCH_URLS[searchIndex()]; }

    // ---- favoritos ----
    public List<String[]> bookmarks() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_BOOKMARKS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("url"), o.optString("title")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void saveBookmarks(List<String[]> list) {
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
        p.edit().putString(K_BOOKMARKS, a.toString()).apply();
    }

    public boolean isBookmarked(String url) {
        if (url == null || !url.startsWith("http")) return false;
        String key = pageKey(url);
        for (String[] e : bookmarks()) if (pageKey(e[0]).equals(key)) return true;
        return false;
    }

    /** Liga/desliga o favorito da página. @return true se ficou favoritada. */
    public boolean toggleBookmark(String url, String title) {
        if (url == null || !url.startsWith("http")) return false;
        List<String[]> list = bookmarks();
        String key = pageKey(url);
        for (int i = 0; i < list.size(); i++) {
            if (pageKey(list.get(i)[0]).equals(key)) {
                list.remove(i);
                saveBookmarks(list);
                return false;
            }
        }
        list.add(0, new String[]{url, title == null ? "" : title});
        while (list.size() > BOOKMARKS_MAX) list.remove(list.size() - 1);
        saveBookmarks(list);
        return true;
    }

    public void removeBookmark(int index) {
        List<String[]> list = bookmarks();
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        saveBookmarks(list);
    }

    public void clearBookmarks() { p.edit().remove(K_BOOKMARKS).apply(); }

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

    // ---- downloads (vídeo/áudio baixados do navegador) ----
    /** Cada item é {nome, uri, urlOrigem}. */
    public List<String[]> downloads() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_DOWNLOADS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("name"), o.optString("uri"), o.optString("url")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void addDownload(String name, String uri, String url) {
        List<String[]> list = downloads();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[1].equals(uri)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, new String[]{name == null ? "" : name, uri == null ? "" : uri, url == null ? "" : url});
        while (list.size() > DOWNLOADS_MAX) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String[] e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e[0]);
                o.put("uri", e[1]);
                o.put("url", e[2]);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        p.edit().putString(K_DOWNLOADS, a.toString()).apply();
    }

    public void removeDownload(int index) {
        List<String[]> list = downloads();
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        JSONArray a = new JSONArray();
        for (String[] e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e[0]);
                o.put("uri", e[1]);
                o.put("url", e[2]);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        p.edit().putString(K_DOWNLOADS, a.toString()).apply();
    }

    public void clearDownloads() { p.edit().remove(K_DOWNLOADS).apply(); }

    // ---- IPTV (listas M3U/M3U8) ----
    /** Cada lista é {nome, url}. */
    public List<String[]> iptvLists() {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(K_IPTV_LISTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String name = o.optString("name");
                String url = o.optString("url");
                if (!url.isEmpty()) out.add(new String[]{name.isEmpty() ? url : name, url});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void addIptvList(String name, String url) {
        if (url == null || url.trim().isEmpty()) return;
        List<String[]> list = iptvLists();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[1].equals(url.trim())) {
                list.remove(i);
                break;
            }
        }
        list.add(0, new String[]{name == null || name.trim().isEmpty() ? url.trim() : name.trim(), url.trim()});
        JSONArray a = new JSONArray();
        for (String[] e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e[0]);
                o.put("url", e[1]);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        p.edit().putString(K_IPTV_LISTS, a.toString()).apply();
    }

    public void removeIptvList(int index) {
        List<String[]> list = iptvLists();
        if (index < 0 || index >= list.size()) return;
        String url = list.get(index)[1];
        list.remove(index);
        JSONArray a = new JSONArray();
        for (String[] e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e[0]);
                o.put("url", e[1]);
                a.put(o);
            } catch (JSONException ignored) {
            }
        }
        p.edit().putString(K_IPTV_LISTS, a.toString()).apply();
        // remove também os canais em cache daquela lista
        try {
            JSONObject all = new JSONObject(p.getString(K_IPTV_CHANNELS, "{}"));
            if (all.has(url)) {
                all.remove(url);
                p.edit().putString(K_IPTV_CHANNELS, all.toString()).apply();
            }
        } catch (JSONException ignored) {
        }
    }

    /** Canais em cache de uma lista; vazio se nunca foi carregada. */
    public List<MediaItem> iptvChannels(String url) {
        List<MediaItem> out = new ArrayList<>();
        if (url == null || url.isEmpty()) return out;
        try {
            JSONObject all = new JSONObject(p.getString(K_IPTV_CHANNELS, "{}"));
            JSONArray a = all.optJSONArray(url);
            if (a != null) {
                for (int i = 0; i < a.length(); i++) out.add(MediaItem.fromJson(a.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void saveIptvChannels(String url, List<MediaItem> channels) {
        if (url == null || url.isEmpty()) return;
        try {
            JSONObject all = new JSONObject(p.getString(K_IPTV_CHANNELS, "{}"));
            JSONArray a = new JSONArray();
            for (MediaItem m : channels) a.put(m.toJson());
            all.put(url, a);
            p.edit().putString(K_IPTV_CHANNELS, all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }
}
