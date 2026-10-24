package com.castor.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native browser wrapper for Castor concepts with automatic local browser setup. */
public final class MainActivity extends Activity {
    private static final String PREFS = "castor_mobile_preferences";
    private static final String KEY_FILTER_REFRESH = "filter_refresh_at";
    private static final String KEY_ADBLOCK = "ad_block_enabled";
    private static final String KEY_REDIRECT_BLOCK = "redirect_block_enabled";
    private static final String KEY_POPUP_BLOCK = "popup_block_enabled";
    private static final String KEY_PROMPT_SECONDS = "blocked_prompt_seconds";
    private static final String KEY_FIRST_BOOT = "first_boot_finished";
    private static final long FILTER_REFRESH_MS = 24L * 60L * 60L * 1000L;
    private static final int FILTER_MAX_BYTES = 1_500_000;

    private static final int BG = Color.rgb(23, 25, 21);
    private static final int PANEL = Color.rgb(36, 39, 32);
    private static final int PANEL_LIGHT = Color.rgb(47, 51, 42);
    private static final int TEXT = Color.rgb(255, 250, 242);
    private static final int MUTED = Color.rgb(179, 180, 166);
    private static final int PEACH = Color.rgb(242, 174, 134);
    private static final int GREEN = Color.rgb(170, 205, 145);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bootstrapExecutor = Executors.newSingleThreadExecutor();
    private final FilterEngine filterEngine = new FilterEngine();
    private final Map<String, VideoItem> foundVideos = new LinkedHashMap<>();

    private SharedPreferences preferences;
    private LinearLayout bootRoot;
    private TextView bootTitle;
    private TextView bootSubtitle;
    private ProgressBar bootProgress;

    private LinearLayout rootLayout;
    private EditText addressInput;
    private TextView videoButtonLabel;
    private ProgressBar pageProgress;
    private WebView webView;
    private FrameLayout webFrame;
    private View blockedPrompt;
    private Runnable dismissPromptRunnable;
    private String pendingBlockedUrl;
    private String pendingBlockedReason;
    private volatile String currentPageUrl = "https://www.google.com/";
    private long lastAdNoticeAt;
    private String lastAdNoticeUrl = "";
    private long lastUserNavigationAt;
    private String expectedNavigationHost = "";
    private String sessionPassword;
    private boolean triedSessionPassword;
    private boolean blobVideoDetected;

    private boolean isAdBlockEnabled() { return preferences.getBoolean(KEY_ADBLOCK, true); }
    private boolean isRedirectBlockEnabled() { return preferences.getBoolean(KEY_REDIRECT_BLOCK, true); }
    private boolean isPopupBlockEnabled() { return preferences.getBoolean(KEY_POPUP_BLOCK, true); }
    private int getPromptSeconds() { return preferences.getInt(KEY_PROMPT_SECONDS, 3); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(18, 19, 15));
        window.setNavigationBarColor(Color.rgb(18, 19, 15));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }

        showBootScreen();
        bootstrapEnvironment();
    }

    // First-run boot is intentionally simple: only these two status stages are shown.
    private void showBootScreen() {
        bootRoot = new LinearLayout(this);
        bootRoot.setOrientation(LinearLayout.VERTICAL);
        bootRoot.setGravity(Gravity.CENTER);
        bootRoot.setBackgroundColor(BG);
        bootRoot.setPadding(dp(32), dp(32), dp(32), dp(32));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.castor.mobile.R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        logoParams.bottomMargin = dp(20);
        bootRoot.addView(logo, logoParams);

        TextView brand = text("castor", 31, TEXT, true);
        brand.setGravity(Gravity.CENTER);
        bootRoot.addView(brand);

        bootTitle = text("Baixando ambiente", 18, PEACH, true);
        bootTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(36);
        bootRoot.addView(bootTitle, titleParams);

        bootSubtitle = text("Preparando a navegação e os filtros de proteção", 13, MUTED, false);
        bootSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        bootRoot.addView(bootSubtitle, subtitleParams);

        bootProgress = new ProgressBar(this);
        bootProgress.setIndeterminate(true);
        bootProgress.setIndeterminateTintList(ColorStateList.valueOf(PEACH));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(180), dp(4));
        progressParams.topMargin = dp(25);
        bootRoot.addView(bootProgress, progressParams);
        setContentView(bootRoot);
    }

    private void bootstrapEnvironment() {
        bootstrapExecutor.execute(() -> {
            String allRules = readCachedRules();
            boolean cacheFresh = System.currentTimeMillis() - preferences.getLong(KEY_FILTER_REFRESH, 0L) < FILTER_REFRESH_MS;
            if (!cacheFresh && hasNetwork()) {
                String downloaded = downloadFilterSets();
                if (!TextUtils.isEmpty(downloaded)) {
                    allRules = downloaded;
                    writeCachedRules(downloaded);
                    preferences.edit().putLong(KEY_FILTER_REFRESH, System.currentTimeMillis()).apply();
                }
            }
            filterEngine.clearDownloadedRules();
            if (!TextUtils.isEmpty(allRules)) {
                filterEngine.loadText(allRules);
            } else {
                try (InputStream in = getAssets().open("filters/base-blocklist.txt")) {
                    filterEngine.load(in);
                } catch (Exception ignored) { }
            }
            mainHandler.post(() -> {
                bootTitle.setText("Iniciando");
                bootSubtitle.setText("Preparando o navegador");
                mainHandler.postDelayed(() -> {
                    preferences.edit().putBoolean(KEY_FIRST_BOOT, true).apply();
                    showBrowser();
                }, 650);
            });
        });
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readCachedRules() {
        File cache = new File(getFilesDir(), "web-filter-rules.txt");
        if (!cache.isFile()) return "";
        try (InputStream in = new FileInputStream(cache)) {
            return new String(readLimited(in, FILTER_MAX_BYTES * 2), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void writeCachedRules(String content) {
        if (TextUtils.isEmpty(content)) return;
        File cache = new File(getFilesDir(), "web-filter-rules.txt");
        try (OutputStream out = new FileOutputStream(cache, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private String downloadFilterSets() {
        String[] urls = {
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist.to/easylist/easyprivacy.txt"
        };
        StringBuilder combined = new StringBuilder();
        for (String address : urls) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(4500);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "CastorAndroid/1.1 (filter updater)");
                connection.setRequestProperty("Accept", "text/plain,*/*;q=0.8");
                connection.connect();
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (InputStream in = connection.getInputStream()) {
                        byte[] bytes = readLimited(in, FILTER_MAX_BYTES);
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        if (text.contains("!") || text.contains("||")) {
                            combined.append("\n! source: ").append(address).append("\n").append(text).append('\n');
                        }
                    }
                }
            } catch (Exception ignored) {
                // Bundled fallback rules remain available if a filter host is offline.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return combined.length() > 0 ? combined.toString() : "";
    }

    private byte[] readLimited(InputStream in, int limit) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            int remaining = limit - output.size();
            if (remaining <= 0) break;
            output.write(buffer, 0, Math.min(read, remaining));
        }
        return output.toByteArray();
    }

    private void showBrowser() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(BG);
        rootLayout.setPadding(dp(10), dp(7), dp(10), dp(5));
        setContentView(rootLayout);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.rightMargin = dp(8);
        header.addView(icon, iconParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("castor", 22, TEXT, true);
        TextView subtitle = text("NAVEGADOR", 9, PEACH, true);
        subtitle.setLetterSpacing(0.12f);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button settings = button("⚙", PANEL_LIGHT, TEXT);
        settings.setContentDescription("Configurações");
        header.addView(settings, new LinearLayout.LayoutParams(dp(46), dp(42)));
        settings.setOnClickListener(v -> showSettings());
        rootLayout.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addressRowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        addressRowParams.topMargin = dp(8);

        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setTextSize(14);
        addressInput.setTextColor(TEXT);
        addressInput.setHintTextColor(MUTED);
        addressInput.setHint("Pesquisar ou digitar endereço");
        addressInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setSelectAllOnFocus(false);
        addressInput.setPadding(dp(14), 0, dp(10), 0);
        addressInput.setBackground(roundRect(PANEL, dp(13), null));
        addressRow.addView(addressInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button go = button("Ir", PEACH, Color.rgb(29, 27, 22));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT);
        goParams.leftMargin = dp(6);
        addressRow.addView(go, goParams);
        go.setOnClickListener(v -> navigateFromAddress());
        addressInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigateFromAddress();
                return true;
            }
            return false;
        });
        rootLayout.addView(addressRow, addressRowParams);

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgressTintList(ColorStateList.valueOf(PEACH));
        pageProgress.setProgressBackgroundTintList(ColorStateList.valueOf(PANEL));
        pageProgress.setVisibility(View.GONE);
        rootLayout.addView(pageProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webFrame = new FrameLayout(this);
        webFrame.setBackgroundColor(BG);
        LinearLayout.LayoutParams webFrameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webFrameParams.topMargin = dp(5);
        rootLayout.addView(webFrame, webFrameParams);

        webView = new WebView(this);
        WebSettings settingsWeb = webView.getSettings();
        settingsWeb.setJavaScriptEnabled(true);
        settingsWeb.setDomStorageEnabled(true);
        settingsWeb.setLoadsImagesAutomatically(true);
        settingsWeb.setMediaPlaybackRequiresUserGesture(false);
        settingsWeb.setAllowFileAccess(false);
        settingsWeb.setAllowContentAccess(false);
        settingsWeb.setJavaScriptCanOpenWindowsAutomatically(true);
        settingsWeb.setSupportMultipleWindows(true);
        settingsWeb.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settingsWeb.setSafeBrowsingEnabled(true);
        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new CastorBridge(), "CastorBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                if (newProgress >= 100) updateVideoButton();
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Capture the URL in a throwaway WebView; never leave a second tab running.
                final WebView popupWebView = new WebView(MainActivity.this);
                popupWebView.getSettings().setJavaScriptEnabled(true);
                popupWebView.setWebViewClient(new WebViewClient() {
                    private boolean handled;
                    private void captureAndClose(String url) {
                        if (handled) return;
                        handled = true;
                        mainHandler.post(() -> handlePopupRequest(url));
                        try { popupWebView.stopLoading(); popupWebView.destroy(); } catch (Exception ignored) { }
                    }
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        captureAndClose(request.getUrl().toString());
                        return true;
                    }
                    @Override
                    @SuppressWarnings("deprecation")
                    public boolean shouldOverrideUrlLoading(WebView child, String url) {
                        captureAndClose(url);
                        return true;
                    }
                });
                if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                    popupWebView.destroy();
                    return false;
                }
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleLegacyNavigation(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String requestUrl = request.getUrl().toString();
                boolean isMainFrame = request.isForMainFrame();
                if (isAdBlockEnabled() && filterEngine.shouldBlock(requestUrl, currentPageUrl, isMainFrame)) {
                    maybeShowAdBlocked(requestUrl);
                    return emptyBlockedResponse();
                }
                if (isSupportedMediaUrl(requestUrl)) captureMedia(requestUrl, "rede");
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentPageUrl = url;
                runOnUiThread(() -> {
                    if (addressInput != null) addressInput.setText(url);
                    synchronized (foundVideos) { foundVideos.clear(); }
                    blobVideoDetected = false;
                    if (isSupportedMediaUrl(url)) captureMedia(url, "página");
                    updateVideoButton();
                });
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentPageUrl = url;
                injectBrowserHelpers(view);
                if (addressInput != null) addressInput.setText(url);
                super.onPageFinished(view, url);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                promptForPassword(handler);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "Não foi possível abrir esta página.", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(MainActivity.this, "Certificado HTTPS inválido; conexão bloqueada.", Toast.LENGTH_LONG).show();
            }
        });
        webFrame.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        bottomParams.topMargin = dp(5);

        Button back = button("‹", PANEL_LIGHT, TEXT);
        Button forward = button("›", PANEL_LIGHT, TEXT);
        Button home = button("⌂", PANEL_LIGHT, TEXT);
        Button reload = button("↻", PANEL_LIGHT, TEXT);
        Button videos = button("Extrair · 0", PEACH, Color.rgb(29, 27, 22));
        videoButtonLabel = videos;
        addBarButton(bottomBar, back, 0.7f);
        addBarButton(bottomBar, forward, 0.7f);
        addBarButton(bottomBar, home, 0.7f);
        addBarButton(bottomBar, reload, 0.7f);
        addBarButton(bottomBar, videos, 1.5f);
        rootLayout.addView(bottomBar, bottomParams);

        back.setOnClickListener(v -> { if (webView.canGoBack()) webView.goBack(); });
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        home.setOnClickListener(v -> loadUserUrl("https://www.google.com/"));
        reload.setOnClickListener(v -> webView.reload());
        videos.setOnClickListener(v -> showVideos());

        loadUserUrl("https://www.google.com/");
    }

    private void addBarButton(LinearLayout row, Button button, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        row.addView(button, params);
    }

    private boolean handleNavigation(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String url = uri.toString();
        String scheme = uri.getScheme();
        if (scheme == null) return true;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            openExternalScheme(uri);
            return true;
        }
        if (request.isForMainFrame() && isRedirectBlockEnabled() && request.isRedirect()) {
            boolean explicitlyInitiated = System.currentTimeMillis() - lastUserNavigationAt < 2500L
                    && sameHost(expectedNavigationHost, uri.getHost());
            String currentHost = Uri.parse(currentPageUrl).getHost();
            boolean sameSiteRedirect = sameHost(currentHost, uri.getHost()) || sameHost(uri.getHost(), currentHost);
            if (!explicitlyInitiated && !sameSiteRedirect) {
                showBlockedNotice(url, "redirecionamento automático", true);
                return true;
            }
        }
        return false;
    }

    private boolean handleLegacyNavigation(String rawUrl) {
        Uri uri = Uri.parse(rawUrl);
        String scheme = uri.getScheme();
        if (scheme == null) return true;
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
        openExternalScheme(uri);
        return true;
    }

    private void openExternalScheme(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null) return;
        if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme) || "intent".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                Toast.makeText(this, "Não há aplicativo compatível para este link.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void navigateFromAddress() {
        String raw = addressInput.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) return;
        String target;
        if (raw.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            target = raw;
        } else if (raw.contains(" ") || !raw.contains(".") || raw.contains("\\")) {
            target = "https://www.google.com/search?q=" + Uri.encode(raw);
        } else {
            target = "https://" + raw;
        }
        Uri uri = Uri.parse(target);
        if (uri.getHost() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            Toast.makeText(this, "Endereço inválido.", Toast.LENGTH_SHORT).show();
            return;
        }
        loadUserUrl(target);
    }

    private void loadUserUrl(String url) {
        if (webView == null) return;
        Uri uri = Uri.parse(url);
        lastUserNavigationAt = System.currentTimeMillis();
        expectedNavigationHost = uri.getHost() == null ? "" : uri.getHost();
        if (addressInput != null) addressInput.setText(url);
        webView.loadUrl(url);
    }

    private boolean sameHost(String a, String b) {
        return a != null && b != null && (a.equalsIgnoreCase(b) || b.toLowerCase(Locale.ROOT).endsWith("." + a.toLowerCase(Locale.ROOT)));
    }


    private WebResourceResponse emptyBlockedResponse() {
        return new WebResourceResponse("text/plain", "utf-8", 204, "Blocked by Castor", new java.util.HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private void injectBrowserHelpers(WebView view) {
        String script = "(function(){try{" +
                "if(window.__castorHelpersInstalled)return;window.__castorHelpersInstalled=true;" +
                "function send(){try{var out=[],seen={};function add(u){if(!u||typeof u!=='string'||u.indexOf('blob:')===0||u.indexOf('data:')===0)return; if(seen[u])return;seen[u]=1;out.push(u)}" +
                "document.querySelectorAll('video').forEach(function(v){if((v.currentSrc||v.src||'').indexOf('blob:')===0){try{CastorBridge.onBlobMedia()}catch(x){}}add(v.currentSrc);add(v.src);v.querySelectorAll('source').forEach(function(s){add(s.src)})});" +
                "document.querySelectorAll('source').forEach(function(s){add(s.src)});" +
                "document.querySelectorAll('a[href]').forEach(function(a){if(/\\.(m3u8|mp4|mpd|webm|m4v|mov|mkv|ts|3gp|flv|mp3|m4a|aac|ogg|wav)(?:$|[?#])/i.test(a.href))add(a.href)});" +
                "performance.getEntriesByType('resource').forEach(function(r){if(/\\.(m3u8|mp4|mpd|webm|m4v|mov|mkv|ts|3gp|flv|mp3|m4a|aac|ogg|wav)(?:$|[?#])/i.test(r.name))add(r.name)});" +
                "CastorBridge.onMediaList(JSON.stringify(out));" +
                "}catch(e){}}" +
                "window.open=function(u){try{CastorBridge.onPopup(String(u||location.href))}catch(e){}return null};" +
                "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[target=_blank],a[target=\\\"_blank\\\"]'):null;if(a){e.preventDefault();e.stopImmediatePropagation();try{CastorBridge.onPopup(a.href)}catch(x){}}},true);" +
                "send();new MutationObserver(send).observe(document.documentElement||document,{childList:true,subtree:true,attributes:true});setInterval(send,3000);" +
                "}catch(e){}})();";
        view.evaluateJavascript(script, null);
    }

    private boolean isSupportedMediaUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) return false;
        String url = rawUrl.toLowerCase(Locale.ROOT);
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        int query = url.indexOf('?');
        int hash = url.indexOf('#');
        int end = url.length();
        if (query >= 0) end = Math.min(end, query);
        if (hash >= 0) end = Math.min(end, hash);
        String path = url.substring(0, end);
        String[] extensions = {".m3u8", ".mp4", ".mpd", ".webm", ".m4v", ".mov", ".mkv", ".ts", ".3gp", ".flv", ".mp3", ".m4a", ".aac", ".ogg", ".wav"};
        for (String extension : extensions) if (path.endsWith(extension)) return true;
        return false;
    }

    private void captureMedia(String url, String source) {
        if (!isSupportedMediaUrl(url)) return;
        synchronized (foundVideos) {
            if (!foundVideos.containsKey(url)) {
                if (foundVideos.size() >= 120) {
                    String firstKey = foundVideos.keySet().iterator().next();
                    foundVideos.remove(firstKey);
                }
                foundVideos.put(url, new VideoItem(url, guessTitle(url), source));
            }
        }
        runOnUiThread(this::updateVideoButton);
    }

    private String guessTitle(String url) {
        Uri uri = Uri.parse(url);
        String path = uri.getLastPathSegment();
        if (TextUtils.isEmpty(path)) return "Vídeo encontrado";
        path = Uri.decode(path);
        if (path.length() > 58) path = path.substring(0, 55) + "…";
        return path;
    }

    private void updateVideoButton() {
        if (videoButtonLabel == null) return;
        int count;
        synchronized (foundVideos) { count = foundVideos.size(); }
        videoButtonLabel.setText("Extrair · " + count);
    }

    private void showVideos() {
        List<VideoItem> items;
        synchronized (foundVideos) { items = new ArrayList<>(foundVideos.values()); }
        if (items.isEmpty()) {
            String message = blobVideoDetected
                    ? "A página usa um vídeo blob: ou um fluxo que não expõe uma URL direta. Esse formato pode exigir um extrator específico e nem sempre pode ser reproduzido fora da página."
                    : "Nenhuma URL direta de mídia foi detectada ainda. Inicie o vídeo na página e abra esta lista novamente. Formatos diretos comuns incluem MP4, M3U8/HLS, MPD/DASH e WebM.";
            new AlertDialog.Builder(this).setTitle("Vídeos da página").setMessage(message).setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            VideoItem item = items.get(i);
            labels[i] = item.title + "\n" + mediaType(item.url) + " · " + Uri.parse(item.url).getHost();
        }
        new AlertDialog.Builder(this)
                .setTitle("Vídeos encontrados (" + items.size() + ")")
                .setItems(labels, (dialog, which) -> showVideoActions(items.get(which)))
                .setNegativeButton("Fechar", null)
                .show();
    }

    private String mediaType(String url) {
        String clean = url.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        if (clean.endsWith(".m3u8")) return "M3U8 / HLS";
        if (clean.endsWith(".mpd")) return "MPD / DASH";
        if (clean.endsWith(".mp4")) return "MP4";
        if (clean.endsWith(".webm")) return "WebM";
        if (clean.endsWith(".mkv")) return "MKV";
        if (clean.endsWith(".ts")) return "MPEG-TS";
        if (clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".aac") || clean.endsWith(".ogg") || clean.endsWith(".wav")) return "Áudio";
        return "Mídia";
    }

    private void showVideoActions(VideoItem item) {
        TextView urlText = text(mediaType(item.url) + "\nEncontrado em: " + item.source + "\n\n" + item.url, 12, MUTED, false);
        urlText.setTextIsSelectable(true);
        urlText.setPadding(dp(4), dp(4), dp(4), dp(4));
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setView(urlText)
                .setPositiveButton("Reproduzir", (d, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(item.url), "video/*");
                        startActivity(intent);
                    } catch (Exception e) {
                        new AlertDialog.Builder(this).setMessage("Não há reprodutor compatível instalado. Você pode copiar o link e abri-lo em um reprodutor que suporte " + mediaType(item.url) + ".")
                                .setPositiveButton("OK", null).show();
                    }
                })
                .setNeutralButton("Copiar link", (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("URL do vídeo", item.url));
                    Toast.makeText(this, "Link copiado.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Compartilhar", (d, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, item.url);
                    startActivity(Intent.createChooser(share, "Compartilhar link do vídeo"));
                }).show();
    }

    private void maybeShowAdBlocked(String url) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastAdNoticeAt < 4500L) return;
            if (url.equals(lastAdNoticeUrl) && now - lastAdNoticeAt < 12000L) return;
            lastAdNoticeAt = now;
            lastAdNoticeUrl = url;
        }
        showBlockedNotice(url, "anúncio ou rastreador", true);
    }

    private void showBlockedNotice(String url, String reason, boolean actionable) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> showBlockedNotice(url, reason, actionable));
            return;
        }
        if (webFrame == null || isFinishing()) return;
        pendingBlockedUrl = url;
        pendingBlockedReason = reason;
        if (blockedPrompt != null) {
            webFrame.removeView(blockedPrompt);
            blockedPrompt = null;
        }
        if (dismissPromptRunnable != null) mainHandler.removeCallbacks(dismissPromptRunnable);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(9));
        card.setBackground(roundRect(PANEL, dp(15), PEACH));
        card.setElevation(dp(8));

        TextView header = text("Bloqueado: " + reason, 13, PEACH, true);
        card.addView(header);
        String displayUrl = url == null ? "Link não identificado" : url;
        if (displayUrl.length() > 130) displayUrl = displayUrl.substring(0, 126) + "…";
        TextView link = text(displayUrl, 11, TEXT, false);
        link.setMaxLines(2);
        link.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams linkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkParams.topMargin = dp(3);
        card.addView(link, linkParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        actionsParams.topMargin = dp(4);
        Button ignore = button("Ignorar", PANEL_LIGHT, TEXT);
        Button open = button("Abrir", PEACH, Color.rgb(29, 27, 22));
        actions.addView(ignore, new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.MATCH_PARENT);
        openParams.leftMargin = dp(6);
        actions.addView(open, openParams);
        card.addView(actions, actionsParams);
        ignore.setOnClickListener(v -> dismissBlockedNotice());
        open.setOnClickListener(v -> {
            String target = pendingBlockedUrl;
            dismissBlockedNotice();
            if (!TextUtils.isEmpty(target)) loadUserUrl(target);
        });
        if (!actionable) {
            open.setVisibility(View.GONE);
        }

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cardParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        webFrame.addView(card, cardParams);
        blockedPrompt = card;

        int seconds = getPromptSeconds();
        if (seconds > 0) {
            dismissPromptRunnable = this::dismissBlockedNotice;
            mainHandler.postDelayed(dismissPromptRunnable, seconds * 1000L);
        }
    }

    private void dismissBlockedNotice() {
        if (dismissPromptRunnable != null) mainHandler.removeCallbacks(dismissPromptRunnable);
        dismissPromptRunnable = null;
        if (webFrame != null && blockedPrompt != null) webFrame.removeView(blockedPrompt);
        blockedPrompt = null;
        pendingBlockedUrl = null;
        pendingBlockedReason = null;
    }

    private void handlePopupRequest(String rawUrl) {
        String url = rawUrl == null ? currentPageUrl : rawUrl.trim();
        if (TextUtils.isEmpty(url) || "null".equals(url)) url = currentPageUrl;
        Uri uri = Uri.parse(url);
        if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) return;
        if (isPopupBlockEnabled()) {
            showBlockedNotice(url, "pop-up ou nova janela", true);
        } else {
            loadUserUrl(url);
        }
    }

    private void promptForPassword(HttpAuthHandler handler) {
        EditText passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setTextColor(TEXT);
        passwordInput.setHintTextColor(MUTED);
        passwordInput.setHint("Senha do site");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        passwordInput.setBackground(roundRect(PANEL, dp(10), PEACH));
        if (sessionPassword != null) passwordInput.setText(sessionPassword);
        new AlertDialog.Builder(this)
                .setTitle("Autenticação do site")
                .setView(passwordInput)
                .setPositiveButton("Entrar", (dialog, which) -> {
                    String password = passwordInput.getText().toString();
                    if (TextUtils.isEmpty(password)) handler.cancel();
                    else {
                        sessionPassword = password;
                        triedSessionPassword = true;
                        handler.proceed("castor", password);
                    }
                })
                .setNegativeButton("Cancelar", (dialog, which) -> handler.cancel())
                .setOnCancelListener(dialog -> handler.cancel())
                .show();
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(8));
        panel.setBackgroundColor(BG);

        TextView intro = text("Proteção de navegação", 18, TEXT, true);
        panel.addView(intro);
        TextView description = text("Os bloqueios começam ativados. Os filtros são atualizados automaticamente quando há internet.", 12, MUTED, false);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(5);
        panel.addView(description, descParams);

        Switch ads = settingsSwitch("Bloqueador de anúncios e rastreadores", isAdBlockEnabled());
        Switch redirects = settingsSwitch("Bloqueador de redirecionamentos", isRedirectBlockEnabled());
        Switch popups = settingsSwitch("Bloqueador de pop-ups e novas janelas", isPopupBlockEnabled());
        panel.addView(ads, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(redirects, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(popups, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView durationLabel = text("Tempo do aviso: " + durationText(getPromptSeconds()), 13, TEXT, true);
        LinearLayout.LayoutParams durationParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        durationParams.topMargin = dp(12);
        panel.addView(durationLabel, durationParams);
        SeekBar duration = new SeekBar(this);
        duration.setMax(15);
        duration.setProgress(getPromptSeconds());
        duration.setProgressTintList(ColorStateList.valueOf(PEACH));
        duration.setThumbTintList(ColorStateList.valueOf(PEACH));
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                durationLabel.setText("Tempo do aviso: " + durationText(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        panel.addView(duration);
        TextView durationHint = text("0 = fica na tela até você escolher. Máximo: 15 segundos.", 11, MUTED, false);
        panel.addView(durationHint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(panel)
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            preferences.edit()
                    .putBoolean(KEY_ADBLOCK, ads.isChecked())
                    .putBoolean(KEY_REDIRECT_BLOCK, redirects.isChecked())
                    .putBoolean(KEY_POPUP_BLOCK, popups.isChecked())
                    .putInt(KEY_PROMPT_SECONDS, duration.getProgress())
                    .apply();
            dialog.dismiss();
            if (webView != null) webView.reload();
            Toast.makeText(this, "Configurações salvas.", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
        dialog.getWindow().setBackgroundDrawable(roundRect(PANEL, dp(18), null));
    }

    private Switch settingsSwitch(String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(13);
        toggle.setTextColor(TEXT);
        toggle.setChecked(checked);
        toggle.setPadding(0, dp(9), 0, dp(9));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            toggle.setThumbTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{PEACH, MUTED}));
            toggle.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.rgb(112, 80, 60), PANEL_LIGHT}));
        }
        return toggle;
    }

    private String durationText(int seconds) {
        return seconds == 0 ? "até escolher" : seconds + (seconds == 1 ? " segundo" : " segundos");
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setBackground(roundRect(background, dp(12), null));
        return button;
    }

    private GradientDrawable roundRect(int color, int radius, Integer stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (stroke != null) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (blockedPrompt != null) {
            dismissBlockedNotice();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (dismissPromptRunnable != null) mainHandler.removeCallbacks(dismissPromptRunnable);
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("CastorBridge");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        bootstrapExecutor.shutdownNow();
        super.onDestroy();
    }

    private final class CastorBridge {
        @JavascriptInterface
        public void onMediaList(String payload) {
            try {
                JSONArray array = new JSONArray(payload);
                for (int i = 0; i < array.length(); i++) {
                    String url = array.optString(i, "");
                    if (isSupportedMediaUrl(url)) captureMedia(url, "página");
                    else if (url.startsWith("blob:")) blobVideoDetected = true;
                }
            } catch (JSONException ignored) { }
        }

        @JavascriptInterface
        public void onBlobMedia() { blobVideoDetected = true; }

        @JavascriptInterface
        public void onPopup(String url) {
            mainHandler.post(() -> handlePopupRequest(url));
        }
    }

    private static final class VideoItem {
        final String url;
        final String title;
        final String source;
        VideoItem(String url, String title, String source) {
            this.url = url;
            this.title = title;
            this.source = source;
        }
    }
}
