package com.castor.mobile;

import org.json.JSONException;
import org.json.JSONObject;

/** Um vídeo/áudio detectado numa página ou adicionado à fila. */
public final class MediaItem {
    public final String url;
    public final String title;
    public final String referer;
    public final String pageUrl;
    public final String type;
    public final String quality;
    public final String language;

    public MediaItem(String url, String title, String referer, String pageUrl, String type) {
        this(url, title, referer, pageUrl, type, "", "");
    }

    public MediaItem(String url, String title, String referer, String pageUrl, String type,
                     String quality, String language) {
        this.url = url;
        this.title = title == null ? "" : title;
        this.referer = referer == null ? "" : referer;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.type = type == null ? "" : type;
        this.quality = quality == null ? "" : quality.trim();
        this.language = language == null ? "" : language.trim();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("url", url);
            o.put("title", title);
            o.put("referer", referer);
            o.put("pageUrl", pageUrl);
            o.put("type", type);
            o.put("quality", quality);
            o.put("language", language);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static MediaItem fromJson(JSONObject o) {
        return new MediaItem(
                o.optString("url"),
                o.optString("title"),
                o.optString("referer"),
                o.optString("pageUrl"),
                o.optString("type"),
                o.optString("quality", ""),
                o.optString("language", ""));
    }
}
