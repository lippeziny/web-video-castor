package com.castor.mobile;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bloqueador de anúncios/rastreadores por domínio e por trechos de caminho.
 * Usa uma lista embutida + regras simples "||dominio^" baixadas de EasyList/EasyPrivacy.
 */
public final class FilterEngine {
    private static final String[] LISTS = {
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://easylist.to/easylist/fanboy-annoyance.txt"
    };

    private static final String[] BUILTIN_DOMAINS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagmanager.com", "googletagservices.com", "adservice.google.com", "adnxs.com",
            "adsrvr.org", "adform.net", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
            "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "indexww.com",
            "moatads.com", "scorecardresearch.com", "quantserve.com", "amazon-adsystem.com",
            "media.net", "revcontent.com", "mgid.com", "popads.net", "popcash.net", "propellerads.com",
            "propellerclick.com", "adsterra.com", "exoclick.com", "exosrv.com", "juicyads.com",
            "trafficjunky.net", "trafficfactory.biz", "clickadu.com", "hilltopads.net", "adcash.com",
            "onclickads.net", "onclkds.com", "ad-maven.com", "admaven.com", "richpush.co",
            "pushwoosh.com", "push-sdk.com", "monetag.com", "a-ads.com", "ero-advertising.com",
            "yandex.ru/ads", "mc.yandex.ru", "adskeeper.com", "smartadserver.com", "teads.tv",
            "adition.com", "advertising.com", "bidswitch.net", "contextweb.com", "lijit.com",
            "sharethrough.com", "33across.com", "tapad.com", "bluekai.com", "demdex.net",
            "everesttech.net", "krxd.net", "hotjar.com", "mouseflow.com", "facebook.net/tr",
            "connect.facebook.net", "ads-twitter.com", "ads.linkedin.com", "snap.licdn.com",
            "bat.bing.com", "clarity.ms", "yieldmo.com", "undertone.com", "zedo.com", "betrad.com",
            "serving-sys.com", "flashtalking.com", "mathtag.com", "turn.com", "rlcdn.com",
            "adsco.re", "ad.gt", "bidvertiser.com", "clickaine.com", "tsyndicate.com",
            "syndication.exoclick.com", "magsrv.com", "realsrv.com", "voluumtrk.com", "ads.yahoo.com"
    };

    private static final String[] PATH_HINTS = {
            "/ads/", "/adserver", "/advert", "/banner/ads", "/pagead/", "/popunder", "/pop-under",
            "/adframe", "/doubleclick/", "/prebid", "/adsbygoogle", "/ad-loader", "/ads.js",
            "/ads-", "/_ads/", "/adview", "/adx/", "/vast.xml", "/vpaid"
    };

    private volatile Set<String> domains = Collections.emptySet();

    public FilterEngine() {
        Set<String> s = new HashSet<>();
        for (String d : BUILTIN_DOMAINS) s.add(d);
        domains = s;
    }

    public int size() {
        return domains.size();
    }

    /** Decide se a requisição deve ser bloqueada. */
    public boolean shouldBlock(String url, String pageHost) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        String host = hostOf(lower);
        if (host.isEmpty()) return false;

        boolean thirdParty = pageHost == null || pageHost.isEmpty() || !sameSite(host, pageHost);

        // domínio (ou qualquer pai dele) na lista
        String h = host;
        Set<String> d = domains;
        while (true) {
            if (d.contains(h)) {
                return thirdParty || !isAllowedFirstParty(h);
            }
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
            if (h.indexOf('.') < 0) break;
        }
        // trechos de caminho típicos de anúncio, só para terceiros
        if (thirdParty) {
            for (String hint : PATH_HINTS) {
                if (lower.contains(hint)) return true;
            }
        }
        return false;
    }

    private static boolean isAllowedFirstParty(String host) {
        // se o próprio site é o domínio da lista (ex.: o usuário abriu o site), não bloqueia
        return true;
    }

    private static String hostOf(String lowerUrl) {
        int s = lowerUrl.indexOf("://");
        if (s < 0) return "";
        s += 3;
        int e = s;
        while (e < lowerUrl.length()) {
            char c = lowerUrl.charAt(e);
            if (c == '/' || c == ':' || c == '?' || c == '#') break;
            e++;
        }
        return lowerUrl.substring(s, e);
    }

    public static String hostOfUrl(String url) {
        return url == null ? "" : hostOf(url.toLowerCase(Locale.ROOT));
    }

    public static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        return registrable(a).equals(registrable(b));
    }

    private static String registrable(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String last = parts[parts.length - 1];
        String prev = parts[parts.length - 2];
        boolean twoLevel = last.length() == 2 && (prev.equals("com") || prev.equals("co") || prev.equals("org")
                || prev.equals("net") || prev.equals("gov") || prev.equals("edu") || prev.equals("ac"));
        int n = twoLevel ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - n; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** CSS para esconder anúncios comuns na página. */
    public static final String COSMETIC_CSS =
            "ins.adsbygoogle,[id^='google_ads'],[id^='div-gpt-ad'],[class*='adsbygoogle'],"
                    + "[class*='ad-banner'],[class*='advert'],[id*='advert'],[class*='sponsored'],"
                    + "[aria-label='Advertisement'],[data-ad],[data-ad-slot],iframe[src*='doubleclick'],"
                    + "iframe[src*='googlesyndication'],.ad-container,.ad-slot,.ads-container,.ad_unit,"
                    + "#ad-container,#ads,#adBanner,.popup-ad,.pop-up-ad{display:none!important;visibility:hidden!important}";

    // ---- cache e download de listas ----

    public void loadCache(Context ctx) {
        File f = new File(ctx.getFilesDir(), "filters.txt");
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            merge(in);
        } catch (Exception ignored) {
        }
    }

    /** Baixa as listas (bloqueante; chamar em thread). Retorna true se algo foi baixado. */
    public boolean download(Context ctx) {
        StringBuilder all = new StringBuilder();
        boolean any = false;
        for (String u : LISTS) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "CastorAndroid/2.0");
                if (c.getResponseCode() != 200) continue;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0 && total < 3_000_000) {
                        bo.write(buf, 0, n);
                        total += n;
                    }
                    all.append(new String(bo.toByteArray(), StandardCharsets.UTF_8)).append('\n');
                    any = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (!any) return false;
        try {
            File f = new File(ctx.getFilesDir(), "filters.txt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(all.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = new FileInputStream(f)) {
                merge(in);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /** Lê regras simples do tipo ||dominio.com^ (sem exceções nem opções complexas). */
    private void merge(InputStream in) throws java.io.IOException {
        Set<String> s = new HashSet<>();
        for (String d : BUILTIN_DOMAINS) s.add(d);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            if (!line.startsWith("||")) continue;
            if (line.contains("##") || line.contains("#@#") || line.contains("@@")) continue;
            String rule = line.substring(2);
            int dollar = rule.indexOf('$');
            if (dollar >= 0) {
                String opts = rule.substring(dollar + 1);
                // só aceita regras puras de terceiros ou de tipo simples
                if (opts.contains("domain=") || opts.contains("redirect") || opts.contains("csp")
                        || opts.contains("removeparam") || opts.contains("badfilter") || opts.contains("important")) {
                    continue;
                }
                rule = rule.substring(0, dollar);
            }
            if (!rule.endsWith("^")) continue; // só domínio puro: ||exemplo.com^
            rule = rule.substring(0, rule.length() - 1);
            if (rule.isEmpty() || rule.contains("/") || rule.contains("*") || rule.contains("?")
                    || rule.contains(":") || rule.indexOf('.') < 0) continue;
            s.add(rule.toLowerCase(Locale.ROOT));
            if (s.size() > 120_000) break;
        }
        domains = s;
    }
}
