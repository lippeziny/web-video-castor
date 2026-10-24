package com.castor.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
    private static final int HISTORY_MAX = 300;

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
