package com.castor.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Castor Browser: navegador com detecção de vídeos, fila de reprodução e bloqueio de anúncios,
 * pop-ups e redirecionamentos. Interface baseada no desenho do usuário.
 */
public final class MainActivity extends Activity {
    public static final String USER_AGENT_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String USER_AGENT_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final int BG = Color.rgb(23, 25, 21);
    private static final int PANEL = Color.rgb(36, 39, 32);
    private static final int PANEL_LIGHT = Color.rgb(47, 51, 42);
    private static final int TEXT = Color.rgb(255, 250, 242);
    private static final int MUTED = Color.rgb(179, 180, 166);
    private static final int PEACH = Color.rgb(242, 174, 134);
    private static final int GREEN = Color.rgb(170, 205, 145);

    private static final int SCREEN_LIBRARY = 0;
    private static final int SCREEN_BROWSER = 1;
    private static final int SCREEN_VIDEOS = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final FilterEngine filters = new FilterEngine();
    private final List<Tab> tabs = new ArrayList<>();

    private Store store;
    private String castorJs = "";
    private int current = -1;
    private boolean browserShown;
    private boolean bootStarted;

    // boot
    private TextView bootStatus;

    // interface principal
    private FrameLayout outer;
    private FrameLayout contentFrame;
    private FrameLayout webHolder;
    private ScrollView libraryScroll;
    private ScrollView videosScroll;
    private LinearLayout libraryBox;
    private LinearLayout videosBox;
    private EditText urlField;
    private TextView tabsCount;
    private ProgressBar progressBar;
    private ImageView btnLibrary;
    private ImageView btnBrowser;
    private ImageView btnVideos;
    private TextView videoBadge;
    private int screen = SCREEN_BROWSER;
    private int libraryTab = 0; // 0 = fila, 1 = histórico

    // aviso de bloqueio
    private LinearLayout noticeCard;
    private TextView noticeReason;
    private TextView noticeUrl;
    private Runnable noticeHide;
    private Tab noticeTab;
    private String noticeTarget = "";

    // tela cheia de vídeo
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private PopupWindow panel;

    private static final class Tab {
        WebView web;
        volatile String url = "";
        volatile String title = "";
        volatile boolean finished;
        volatile long lastTouch;
        volatile boolean blobSeen;
        volatile int blockedCount;
        String allowOnce = "";
        String pendingUrl; // aba restaurada: só carrega quando for aberta
        final LinkedHashMap<String, MediaItem> videos = new LinkedHashMap<>();

        synchronized boolean addVideo(MediaItem m) {
            if (videos.containsKey(m.url)) return false;
            if (videos.size() >= 120) {
                String first = videos.keySet().iterator().next();
                videos.remove(first);
            }
            videos.put(m.url, m);
            return true;
        }

        synchronized List<MediaItem> videoList() {
            return new ArrayList<>(videos.values());
        }

        synchronized int videoCount() {
            return videos.size();
        }

        synchronized void clearVideos() {
            videos.clear();
            blobSeen = false;
            blockedCount = 0;
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        castorJs = readAsset("castor.js");
        filters.loadCache(getApplicationContext());
        showBoot();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri data = intent.getData();
        if (browserShown && data != null) {
            newTab(data.toString(), true);
            showScreen(SCREEN_BROWSER);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabs();
        Tab t = cur();
        if (t != null && t.web != null) t.web.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveTabs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tab t = cur();
        if (t != null && t.web != null) t.web.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        for (Tab t : tabs) destroyWeb(t);
        tabs.clear();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (!browserShown) {
            super.onBackPressed();
            return;
        }
        if (panel != null && panel.isShowing()) {
            panel.dismiss();
            return;
        }
        if (screen != SCREEN_BROWSER) {
            showScreen(SCREEN_BROWSER);
            return;
        }
        Tab t = cur();
        if (t != null && t.web.canGoBack()) {
            t.web.goBack();
        } else if (tabs.size() > 1) {
            closeTab(current);
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ tela de início

    private void showBoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(32), dp(32), dp(32));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        root.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView title = text("Castor", 28, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(6));
        root.addView(title);

        bootStatus = text("Baixando ambiente", 15, MUTED, false);
        bootStatus.setGravity(Gravity.CENTER);
        root.addView(bootStatus);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(40), dp(40));
        plp.topMargin = dp(22);
        root.addView(pb, plp);

        setContentView(root);

        // download das listas de bloqueio em segundo plano
        io.execute(() -> {
            boolean stale = System.currentTimeMillis() - store.filterTime() > DAY_MS || filters.size() < 1500;
            if (stale && hasNetwork()) {
                if (filters.download(getApplicationContext())) {
                    store.setFilterTime(System.currentTimeMillis());
                }
            }
            ui.post(this::startBrowserSoon);
        });
        // nunca prende o usuário na tela de início
        ui.postDelayed(this::startBrowserSoon, 3500);
    }

    private void startBrowserSoon() {
        if (bootStarted) return;
        bootStarted = true;
        if (bootStatus != null) bootStatus.setText("Iniciando");
        ui.postDelayed(this::showBrowser, 450);
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ interface principal

    private void showBrowser() {
        if (browserShown) return;
        browserShown = true;

        outer = new FrameLayout(this);
        outer.setBackgroundColor(BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setBackgroundColor(BG);
        outer.addView(main, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- barra superior: endereço | abas | engrenagem
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(PANEL);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(TEXT);
        urlField.setHintTextColor(MUTED);
        urlField.setHint("Pesquisar ou digitar endereço");
        urlField.setTextSize(15);
        urlField.setBackground(roundRect(PANEL_LIGHT, dp(21), null));
        urlField.setPadding(dp(16), 0, dp(16), 0);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setSelectAllOnFocus(true);
        urlField.setOnEditorActionListener((v, actionId, event) -> {
            goFromField();
            return true;
        });
        top.addView(urlField, new LinearLayout.LayoutParams(0, dp(42), 1f));

        tabsCount = text("1", 14, TEXT, true);
        tabsCount.setGravity(Gravity.CENTER);
        tabsCount.setBackground(roundRect(Color.TRANSPARENT, dp(7), TEXT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        tlp.leftMargin = dp(10);
        tabsCount.setOnClickListener(v -> showTabsDialog());
        top.addView(tabsCount, tlp);

        ImageView gear = icon(R.drawable.ic_gear, TEXT);
        gear.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(42), dp(42));
        glp.leftMargin = dp(4);
        gear.setOnClickListener(v -> showBlockPanel(v));
        top.addView(gear, glp);

        main.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.INVISIBLE);
        main.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // ---- conteúdo: navegador / biblioteca / vídeos
        contentFrame = new FrameLayout(this);
        main.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webHolder = new FrameLayout(this);
        contentFrame.addView(webHolder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        libraryBox = new LinearLayout(this);
        libraryBox.setOrientation(LinearLayout.VERTICAL);
        libraryBox.setPadding(dp(12), dp(12), dp(12), dp(24));
        libraryScroll = new ScrollView(this);
        libraryScroll.setBackgroundColor(BG);
        libraryScroll.addView(libraryBox);
        libraryScroll.setVisibility(View.GONE);
        contentFrame.addView(libraryScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videosBox = new LinearLayout(this);
        videosBox.setOrientation(LinearLayout.VERTICAL);
        videosBox.setPadding(dp(12), dp(12), dp(12), dp(24));
        videosScroll = new ScrollView(this);
        videosScroll.setBackgroundColor(BG);
        videosScroll.addView(videosBox);
        videosScroll.setVisibility(View.GONE);
        contentFrame.addView(videosScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildNoticeCard();

        // ---- barra inferior: histórico/fila | navegador | vídeos
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(PANEL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));

        btnLibrary = circleButton(R.drawable.ic_menu);
        btnBrowser = circleButton(R.drawable.ic_globe);
        btnVideos = circleButton(R.drawable.ic_play);
        btnLibrary.setOnClickListener(v -> showScreen(SCREEN_LIBRARY));
        btnBrowser.setOnClickListener(v -> showScreen(SCREEN_BROWSER));
        btnVideos.setOnClickListener(v -> showScreen(SCREEN_VIDEOS));

        bottom.addView(weightWrap(btnLibrary), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(weightWrap(btnBrowser), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout videoWrap = new FrameLayout(this);
        videoWrap.addView(btnVideos, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        videoBadge = text("0", 11, BG, true);
        videoBadge.setGravity(Gravity.CENTER);
        videoBadge.setBackground(roundRect(PEACH, dp(10), null));
        videoBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        blp.leftMargin = dp(34);
        blp.bottomMargin = dp(34);
        videoWrap.addView(videoBadge, blp);
        bottom.addView(videoWrap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        main.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(outer);

        Uri data = getIntent() == null ? null : getIntent().getData();
        List<String[]> saved = store.savedTabs();
        if (!saved.isEmpty()) {
            for (String[] st : saved) restoreTab(st[0], st[1]);
            switchToTab(Math.max(0, Math.min(store.savedTabsCurrent(), tabs.size() - 1)));
            if (data != null) newTab(data.toString(), true);
        } else {
            newTab(data != null ? data.toString() : HOME_URL, true);
        }
        showScreen(SCREEN_BROWSER);
    }

    /** Guarda as abas abertas para reabrir no mesmo estado depois. */
    private void saveTabs() {
        if (!browserShown || tabs.isEmpty() || store == null) return;
        List<String[]> list = new ArrayList<>();
        for (Tab t : tabs) {
            String u = t.pendingUrl != null ? t.pendingUrl : t.url;
            if (u == null || u.isEmpty()) u = HOME_URL;
            list.add(new String[]{u, t.title == null ? "" : t.title});
        }
        store.saveTabs(list, Math.max(0, current));
    }

    private void restoreTab(String url, String title) {
        Tab t = new Tab(); // WebView só é criado quando a aba for aberta (economiza memória)
        t.url = url;
        t.title = title == null ? "" : title;
        t.pendingUrl = url;
        tabs.add(t);
        updateTabsCount();
    }

    private FrameLayout weightWrap(View v) {
        FrameLayout f = new FrameLayout(this);
        f.addView(v, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        return f;
    }

    private ImageView circleButton(int res) {
        ImageView iv = icon(res, TEXT);
        iv.setPadding(dp(14), dp(14), dp(14), dp(14));
        iv.setBackground(roundRect(PANEL_LIGHT, dp(27), MUTED));
        return iv;
    }

    private void showScreen(int s) {
        screen = s;
        if (panel != null && panel.isShowing()) panel.dismiss();
        hideKeyboard();
        webHolder.setVisibility(s == SCREEN_BROWSER ? View.VISIBLE : View.GONE);
        libraryScroll.setVisibility(s == SCREEN_LIBRARY ? View.VISIBLE : View.GONE);
        videosScroll.setVisibility(s == SCREEN_VIDEOS ? View.VISIBLE : View.GONE);
        if (s == SCREEN_LIBRARY) buildLibrary();
        if (s == SCREEN_VIDEOS) buildVideos();
        btnLibrary.setBackground(roundRect(PANEL_LIGHT, dp(27), s == SCREEN_LIBRARY ? PEACH : MUTED));
        btnBrowser.setBackground(roundRect(PANEL_LIGHT, dp(27), s == SCREEN_BROWSER ? PEACH : MUTED));
        btnVideos.setBackground(roundRect(PANEL_LIGHT, dp(27), s == SCREEN_VIDEOS ? PEACH : MUTED));
        hideNotice();
    }

    // ------------------------------------------------------------------ abas

    private Tab cur() {
        return current >= 0 && current < tabs.size() ? tabs.get(current) : null;
    }

    private void newTab(String url, boolean switchTo) {
        Tab t = new Tab();
        t.web = createWebView(t);
        tabs.add(t);
        if (switchTo) switchToTab(tabs.size() - 1);
        t.lastTouch = SystemClock.uptimeMillis();
        t.web.loadUrl(normalize(url));
        updateTabsCount();
        saveTabs();
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab old = cur();
        if (old != null && old.web != null) {
            old.web.onPause();
            webHolder.removeView(old.web);
        }
        current = index;
        Tab t = tabs.get(index);
        if (t.web == null) t.web = createWebView(t);
        webHolder.addView(t.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.web.onResume();
        if (t.pendingUrl != null) {
            String pending = t.pendingUrl;
            t.pendingUrl = null;
            t.lastTouch = SystemClock.uptimeMillis();
            t.web.loadUrl(normalize(pending));
        }
        progressBar.setVisibility(View.INVISIBLE);
        updateAddress();
        refreshBadge();
        updateTabsCount();
        hideNotice();
        saveTabs();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        if (tabs.size() == 1) {
            t.lastTouch = SystemClock.uptimeMillis();
            t.web.loadUrl(HOME_URL);
            return;
        }
        boolean wasCurrent = index == current;
        if (wasCurrent) webHolder.removeView(t.web);
        tabs.remove(index);
        destroyWeb(t);
        if (wasCurrent) {
            current = -1;
            switchToTab(Math.min(index, tabs.size() - 1));
        } else if (index < current) {
            current--;
        }
        updateTabsCount();
        saveTabs();
    }

    private void destroyWeb(Tab t) {
        if (t == null || t.web == null) return;
        try {
            t.web.stopLoading();
            t.web.removeJavascriptInterface("CastorBridge");
            t.web.destroy();
        } catch (Exception ignored) {
        }
        t.web = null;
    }

    private void updateTabsCount() {
        if (tabsCount != null) tabsCount.setText(String.valueOf(tabs.size()));
    }

    private void showTabsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(4), dp(8));
            row.setBackground(roundRect(idx == current ? PANEL_LIGHT : PANEL, dp(10), idx == current ? PEACH : null));
            String name = isHome(t.url) || t.url.isEmpty() ? "Início"
                    : (TextUtils.isEmpty(t.title) ? FilterEngine.hostOfUrl(t.url) : t.title);
            TextView label = text(name, 14, TEXT, false);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView close = icon(R.drawable.ic_close, MUTED);
            close.setPadding(dp(10), dp(10), dp(10), dp(10));
            close.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                closeTab(idx);
                showScreen(SCREEN_BROWSER);
            });
            row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
            row.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                switchToTab(idx);
                showScreen(SCREEN_BROWSER);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            box.addView(row, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        b.setTitle("Abas (" + tabs.size() + ")");
        b.setView(sv);
        b.setPositiveButton("Nova aba", (d, w) -> {
            newTab(HOME_URL, true);
            showScreen(SCREEN_BROWSER);
        });
        b.setNegativeButton("Fechar", null);
        dlg[0] = b.show();
    }

    // ------------------------------------------------------------------ WebView

    private WebView createWebView(final Tab tab) {
        WebView w = new WebView(this);
        w.setBackgroundColor(BG);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        applyUserAgent(s);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(tab), "CastorBridge");
        w.setOnTouchListener((v, e) -> {
            tab.lastTouch = SystemClock.uptimeMillis();
            return false;
        });
        w.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (mimeType != null && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) {
                addVideoForced(tab, url, mimeType.startsWith("audio/") ? "ÁUDIO" : "MP4");
                ui.post(() -> Toast.makeText(this, "Vídeo detectado", Toast.LENGTH_SHORT).show());
            }
        });
        w.setWebViewClient(new CastorClient(tab));
        w.setWebChromeClient(new CastorChrome(tab));
        return w;
    }

    private void applyUserAgent(WebSettings s) {
        boolean desktop = store.desktop();
        s.setUserAgentString(desktop ? USER_AGENT_DESKTOP : USER_AGENT_MOBILE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
    }

    private final class CastorClient extends WebViewClient {
        private final Tab tab;

        CastorClient(Tab tab) {
            this.tab = tab;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            String url = req.getUrl().toString();
            boolean blocked = false;
            if (!req.isForMainFrame() && store.ads()) {
                blocked = filters.shouldBlock(url, FilterEngine.hostOfUrl(tab.url));
            }
            if (blocked) {
                tab.blockedCount++;
                return emptyResponse();
            }
            if (isMediaUrl(url)) addVideo(tab, url);
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return handleNavigation(tab, req.getUrl().toString(), req.hasGesture(), req.isForMainFrame());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!stripFragment(url).equals(stripFragment(tab.url))) tab.clearVideos();
            tab.url = url;
            tab.finished = false;
            tab.title = "";
            view.evaluateJavascript(castorJs, null);
            if (tab == cur()) {
                updateAddress();
                refreshBadge();
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (url != null && !url.equals(tab.url)) {
                tab.url = url;
                if (tab == cur()) updateAddress();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            tab.finished = true;
            tab.url = url;
            String title = view.getTitle();
            if (title != null) tab.title = title;
            view.evaluateJavascript(castorJs, null);
            if (store.ads()) {
                String css = "var s=document.createElement('style');s.textContent="
                        + JSONObject.quote(FilterEngine.COSMETIC_CSS)
                        + ";(document.head||document.documentElement).appendChild(s);";
                view.evaluateJavascript("(function(){try{" + css + "}catch(e){}})();", null);
            }
            view.evaluateJavascript("window.__castorScan&&window.__castorScan(false)", null);
            if (!isHome(url)) store.addHistory(url, tab.title);
            saveTabs();
            if (tab == cur()) {
                updateAddress();
                progressBar.setVisibility(View.INVISIBLE);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            ui.post(() -> Toast.makeText(MainActivity.this,
                    "Certificado inválido: conexão bloqueada", Toast.LENGTH_LONG).show());
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
            if (!req.isForMainFrame()) return;
            String failing = req.getUrl().toString();
            String html = "<html><body style='background:#171915;color:#fffaf2;font-family:sans-serif;padding:32px'>"
                    + "<h2>Não foi possível carregar</h2><p style='color:#b3b4a6'>"
                    + android.text.TextUtils.htmlEncode(failing)
                    + "</p><p><a style='color:#f2ae86' href='" + android.text.TextUtils.htmlEncode(failing)
                    + "'>Tentar novamente</a></p></body></html>";
            view.loadDataWithBaseURL(failing, html, "text/html", "UTF-8", failing);
        }
    }

    private final class CastorChrome extends WebChromeClient {
        private final Tab tab;

        CastorChrome(Tab tab) {
            this.tab = tab;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (tab == cur()) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            tab.title = title == null ? "" : title;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final WebView tmp = new WebView(MainActivity.this);
            tmp.getSettings().setJavaScriptEnabled(true);
            final boolean[] handled = {false};
            tmp.setWebViewClient(new WebViewClient() {
                private void capture(String u) {
                    if (handled[0] || u == null || u.equals("about:blank")) return;
                    handled[0] = true;
                    ui.post(() -> {
                        handlePopupUrl(tab, u, isUserGesture);
                        try {
                            tmp.stopLoading();
                            tmp.destroy();
                        } catch (Exception ignored) {
                        }
                    });
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    capture(r.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String u, Bitmap f) {
                    capture(u);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    capture(r.getUrl().toString());
                    return emptyResponse();
                }
            });
            ui.postDelayed(() -> {
                if (!handled[0]) {
                    handled[0] = true;
                    try {
                        tmp.destroy();
                    } catch (Exception ignored) {
                    }
                }
            }, 5000);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(tmp);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            view.setBackgroundColor(Color.BLACK);
            outer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            outer.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        outer.removeView(customView);
        outer.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        customView = null;
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
    }

    // ------------------------------------------------------------------ navegação e bloqueios

    private boolean handleNavigation(Tab tab, String url, boolean gesture, boolean main) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean userDriven = gesture || (SystemClock.uptimeMillis() - tab.lastTouch) < 1500;

        if (scheme.equals("http") || scheme.equals("https")) {
            if (!main) return false;
            if (url.equals(tab.allowOnce)) {
                tab.allowOnce = "";
                return false;
            }
            if (!userDriven && tab.finished) {
                if (store.redirect()) {
                    String from = FilterEngine.hostOfUrl(tab.url);
                    String to = FilterEngine.hostOfUrl(url);
                    if (!FilterEngine.sameSite(from, to)) {
                        showNotice(tab, "Redirecionamento bloqueado", url);
                        return true;
                    }
                }
            }
            if (!userDriven && store.ads() && filters.shouldBlock(url, "")) {
                showNotice(tab, "Anúncio bloqueado", url);
                return true;
            }
            return false;
        }
        if (scheme.equals("about") || scheme.equals("javascript") || scheme.equals("data")
                || scheme.equals("blob") || scheme.equals("file")) {
            return false;
        }
        // esquemas externos: intent:, market:, deep links de apps...
        if (!userDriven && store.redirect()) {
            showNotice(tab, "Abertura de app bloqueada", url);
            return true;
        }
        openExternal(url);
        return true;
    }

    private void handlePopupUrl(Tab tab, String url, boolean gesture) {
        if (url == null || url.isEmpty()) return;
        if (!store.popup()) {
            tab.allowOnce = url;
            tab.lastTouch = SystemClock.uptimeMillis();
            if (tab.web != null) tab.web.loadUrl(url);
            return;
        }
        boolean sameSite = FilterEngine.sameSite(FilterEngine.hostOfUrl(url), FilterEngine.hostOfUrl(tab.url));
        if (gesture && sameSite && !(store.ads() && filters.shouldBlock(url, ""))) {
            newTab(url, true);
            showScreen(SCREEN_BROWSER);
            return;
        }
        showNotice(tab, "Pop-up bloqueado", url);
    }

    private void openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.setComponent(null);
                i.setSelector(null);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | java.net.URISyntaxException e) {
            Toast.makeText(this, "Nenhum app para abrir este link", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
        }
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", 204, "Blocked",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    // ------------------------------------------------------------------ aviso de bloqueio

    private void buildNoticeCard() {
        noticeCard = new LinearLayout(this);
        noticeCard.setOrientation(LinearLayout.VERTICAL);
        noticeCard.setBackground(roundRect(PANEL, dp(14), PEACH));
        noticeCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        noticeCard.setVisibility(View.GONE);
        noticeCard.setElevation(dp(8));

        noticeReason = text("", 13, PEACH, true);
        noticeCard.addView(noticeReason);

        noticeUrl = text("", 12, TEXT, false);
        noticeUrl.setMaxLines(2);
        noticeUrl.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        noticeUrl.setPadding(0, dp(2), 0, dp(6));
        noticeCard.addView(noticeUrl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        Button ignore = button("Ignorar", PANEL_LIGHT, TEXT);
        Button open = button("Abrir", PEACH, BG);
        ignore.setOnClickListener(v -> hideNotice());
        open.setOnClickListener(v -> {
            String target = noticeTarget;
            Tab t = noticeTab;
            hideNotice();
            if (target.isEmpty()) return;
            if (target.startsWith("http://") || target.startsWith("https://")) {
                if (t != null && t.web != null) {
                    t.allowOnce = target;
                    t.lastTouch = SystemClock.uptimeMillis();
                    t.web.loadUrl(target);
                }
            } else {
                openExternal(target);
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(96), dp(38));
        blp.leftMargin = dp(8);
        row.addView(ignore, blp);
        row.addView(open, blp);
        noticeCard.addView(row);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(dp(10), dp(10), dp(10), dp(10));
        contentFrame.addView(noticeCard, lp);
    }

    private void showNotice(Tab tab, String reason, String url) {
        ui.post(() -> {
            if (tab != cur() || screen != SCREEN_BROWSER || noticeCard == null) return;
            noticeTab = tab;
            noticeTarget = url;
            noticeReason.setText(reason);
            noticeUrl.setText(url);
            noticeCard.setVisibility(View.VISIBLE);
            if (noticeHide != null) ui.removeCallbacks(noticeHide);
            int secs = store.noticeSeconds();
            if (secs > 0) {
                noticeHide = this::hideNotice;
                ui.postDelayed(noticeHide, secs * 1000L);
            }
        });
    }

    private void hideNotice() {
        if (noticeHide != null) ui.removeCallbacks(noticeHide);
        if (noticeCard != null) noticeCard.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ vídeos

    private static final class Ext {
        static String typeOf(String url) {
            if (url == null) return "";
            String u = url.toLowerCase(Locale.ROOT);
            int q = u.indexOf('?');
            int h = u.indexOf('#');
            int end = u.length();
            if (q >= 0) end = Math.min(end, q);
            if (h >= 0) end = Math.min(end, h);
            String path = u.substring(0, end);
            if (path.endsWith(".m3u8")) return "HLS";
            if (path.endsWith(".mpd")) return "DASH";
            if (path.endsWith(".mp4")) return "MP4";
            if (path.endsWith(".webm")) return "WEBM";
            if (path.endsWith(".m4v")) return "M4V";
            if (path.endsWith(".mov")) return "MOV";
            if (path.endsWith(".mkv")) return "MKV";
            if (path.endsWith(".flv")) return "FLV";
            if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".ogg") || path.endsWith(".wav")) {
                return "ÁUDIO";
            }
            return "";
        }
    }

    private boolean isMediaUrl(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.ROOT);
        if (!(l.startsWith("http://") || l.startsWith("https://"))) return false;
        return !Ext.typeOf(url).isEmpty();
    }

    private void addVideo(Tab tab, String url) {
        String type = Ext.typeOf(url);
        if (type.isEmpty()) return;
        addVideoForced(tab, url, type);
    }

    private void addVideoForced(Tab tab, String url, String type) {
        if (url == null || !url.startsWith("http")) return;
        if (store.ads() && filters.shouldBlock(url, FilterEngine.hostOfUrl(tab.url))) return;
        String page = TextUtils.isEmpty(tab.title) ? FilterEngine.hostOfUrl(tab.url) : tab.title;
        MediaItem m = new MediaItem(url, page + " · " + fileName(url), tab.url, tab.url, type);
        if (tab.addVideo(m)) ui.post(this::refreshBadge);
    }

    private void addFrame(Tab tab, String url) {
        if (store.ads() && filters.shouldBlock(url, FilterEngine.hostOfUrl(tab.url))) return;
        MediaItem m = new MediaItem(url, "Player embutido · " + FilterEngine.hostOfUrl(url), tab.url, tab.url, "PÁGINA");
        if (tab.addVideo(m)) ui.post(this::refreshBadge);
    }

    private String fileName(String url) {
        try {
            String seg = Uri.parse(url).getLastPathSegment();
            if (seg == null || seg.isEmpty()) return "vídeo";
            seg = Uri.decode(seg);
            return seg.length() > 50 ? seg.substring(0, 47) + "…" : seg;
        } catch (Exception e) {
            return "vídeo";
        }
    }

    private void refreshBadge() {
        if (videoBadge == null) return;
        Tab t = cur();
        int n = t == null ? 0 : t.videoCount();
        videoBadge.setText(n > 99 ? "99+" : String.valueOf(n));
        videoBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (screen == SCREEN_VIDEOS && videosScroll != null) buildVideos();
    }

    private final class Bridge {
        private final Tab tab;

        Bridge(Tab tab) {
            this.tab = tab;
        }

        @JavascriptInterface
        public void onMedia(String json) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length() && i < 200; i++) addVideo(tab, a.getString(i));
            } catch (JSONException ignored) {
            }
        }

        @JavascriptInterface
        public void onFrames(String json) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length() && i < 30; i++) addFrame(tab, a.getString(i));
            } catch (JSONException ignored) {
            }
        }

        @JavascriptInterface
        public void onBlob() {
            tab.blobSeen = true;
        }

        @JavascriptInterface
        public boolean popupBlocked() {
            return store.popup();
        }

        @JavascriptInterface
        public void onPopup(String url) {
            ui.post(() -> handlePopupUrl(tab, url, false));
        }

        @JavascriptInterface
        public void onScanDone(int media, int frames) {
            ui.post(() -> {
                String msg = media > 0 ? media + " vídeo(s) encontrados"
                        : (frames > 0 ? "Nenhum link direto; veja os players embutidos" : "Nenhum vídeo encontrado nesta página");
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                refreshBadge();
            });
        }
    }

    private void forceSearch() {
        Tab t = cur();
        if (t == null || t.web == null) return;
        Toast.makeText(this, "Procurando vídeos na página…", Toast.LENGTH_SHORT).show();
        t.web.evaluateJavascript("window.__castorScan&&window.__castorScan(true)", null);
        ui.postDelayed(() -> showScreen(SCREEN_VIDEOS), 2300);
    }

    private void play(List<MediaItem> list, int index) {
        if (list.isEmpty()) return;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ITEMS, Store.queueToJson(list));
        i.putExtra(PlayerActivity.EXTRA_INDEX, index);
        startActivity(i);
    }

    // ------------------------------------------------------------------ tela: vídeos do site

    private void buildVideos() {
        videosBox.removeAllViews();
        Tab t = cur();
        List<MediaItem> list = t == null ? new ArrayList<MediaItem>() : t.videoList();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Vídeos neste site (" + list.size() + ")", 17, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button force = button("Force search", PEACH, BG);
        force.setOnClickListener(v -> forceSearch());
        head.addView(force, new LinearLayout.LayoutParams(dp(130), dp(40)));
        if (CastManager.get(this).isActive()) {
            Button ctl = button("📺", PANEL_LIGHT, TEXT);
            ctl.setOnClickListener(v -> CastUi.show(this, null, 0));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(48), dp(40));
            cp.leftMargin = dp(8);
            head.addView(ctl, cp);
        }
        videosBox.addView(head);

        if (t != null && !t.url.isEmpty() && !isHome(t.url)) {
            TextView host = text(FilterEngine.hostOfUrl(t.url), 12, MUTED, false);
            host.setPadding(0, dp(4), 0, dp(8));
            videosBox.addView(host);
        }

        if (t != null && t.blobSeen) {
            TextView info = text("Esta página usa vídeo em formato protegido/blob. Nem sempre existe um link direto.", 12, GREEN, false);
            info.setPadding(0, 0, 0, dp(8));
            videosBox.addView(info);
        }

        if (list.isEmpty()) {
            TextView empty = text("Nenhum vídeo encontrado ainda. Toque play na página e depois em Force search.", 14, MUTED, false);
            empty.setPadding(0, dp(24), 0, 0);
            videosBox.addView(empty);
            return;
        }

        List<MediaItem> playable = new ArrayList<>();
        for (MediaItem m : list) if (!m.type.equals("PÁGINA")) playable.add(m);

        if (playable.size() > 1) {
            Button all = button("▶  Reproduzir todos em fila (" + playable.size() + ")", PANEL_LIGHT, TEXT);
            all.setOnClickListener(v -> play(playable, 0));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
            alp.bottomMargin = dp(10);
            videosBox.addView(all, alp);
        }

        for (final MediaItem m : list) {
            LinearLayout card = card();
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView tag = text(m.type, 11, BG, true);
            tag.setGravity(Gravity.CENTER);
            tag.setBackground(roundRect(m.type.equals("PÁGINA") ? GREEN : PEACH, dp(6), null));
            tag.setPadding(dp(8), dp(2), dp(8), dp(2));
            line.addView(tag);
            TextView name = text(m.title, 13, TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(8), 0, 0, 0);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(line);

            TextView u = text(m.url, 11, MUTED, false);
            u.setMaxLines(2);
            u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            u.setPadding(0, dp(4), 0, dp(8));
            card.addView(u);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            if (m.type.equals("PÁGINA")) {
                Button open = button("Abrir player", PEACH, BG);
                open.setOnClickListener(v -> {
                    Tab ct = cur();
                    if (ct != null) {
                        ct.allowOnce = m.url;
                        ct.lastTouch = SystemClock.uptimeMillis();
                        ct.web.loadUrl(m.url);
                    }
                    showScreen(SCREEN_BROWSER);
                });
                actions.addView(open, actionParams());
            } else {
                Button playBtn = button("Reproduzir", PEACH, BG);
                playBtn.setOnClickListener(v -> {
                    List<MediaItem> one = new ArrayList<>();
                    one.add(m);
                    play(one, 0);
                });
                actions.addView(playBtn, actionParams());
                Button queue = button("+ Fila", PANEL_LIGHT, TEXT);
                queue.setOnClickListener(v -> {
                    boolean added = store.addToQueue(m);
                    Toast.makeText(this, added ? "Adicionado à fila" : "Já está na fila", Toast.LENGTH_SHORT).show();
                });
                actions.addView(queue, actionParams());
            }
            Button castBtn = button("Cast", PANEL_LIGHT, TEXT);
            castBtn.setOnClickListener(v -> CastUi.show(this, m, -1));
            actions.addView(castBtn, actionParams());
            Button more = button("⋯", PANEL_LIGHT, TEXT);
            more.setOnClickListener(v -> showVideoMenu(m));
            actions.addView(more, new LinearLayout.LayoutParams(dp(48), dp(38)));
            card.addView(actions);
            videosBox.addView(card);
        }
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    private void showVideoMenu(final MediaItem m) {
        String[] items = {"Copiar link", "Compartilhar link", "Abrir em outro app", "Transmitir para a TV…"};
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(m.type)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("vídeo", m.url));
                        Toast.makeText(this, "Link copiado", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        Intent s = new Intent(Intent.ACTION_SEND);
                        s.setType("text/plain");
                        s.putExtra(Intent.EXTRA_TEXT, m.url);
                        startActivity(Intent.createChooser(s, "Compartilhar"));
                    } else if (which == 3) {
                        CastUi.show(this, m, -1);
                    } else {
                        try {
                            Intent v = new Intent(Intent.ACTION_VIEW);
                            v.setDataAndType(Uri.parse(m.url), m.type.equals("HLS") ? "application/x-mpegURL"
                                    : (m.type.equals("DASH") ? "application/dash+xml" : "video/*"));
                            startActivity(Intent.createChooser(v, "Abrir com"));
                        } catch (Exception e) {
                            Toast.makeText(this, "Nenhum app compatível", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ tela: histórico e fila

    private void buildLibrary() {
        libraryBox.removeAllViews();

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        Button bq = button("Fila", libraryTab == 0 ? PEACH : PANEL_LIGHT, libraryTab == 0 ? BG : TEXT);
        Button bh = button("Histórico", libraryTab == 1 ? PEACH : PANEL_LIGHT, libraryTab == 1 ? BG : TEXT);
        bq.setOnClickListener(v -> {
            libraryTab = 0;
            buildLibrary();
        });
        bh.setOnClickListener(v -> {
            libraryTab = 1;
            buildLibrary();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sp.rightMargin = dp(8);
        seg.addView(bq, sp);
        seg.addView(bh, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.bottomMargin = dp(12);
        libraryBox.addView(seg, segLp);

        if (libraryTab == 0) buildQueueList();
        else buildHistoryList();
    }

    private void buildQueueList() {
        final List<MediaItem> q = store.queue();
        TextView title = text("Fila de reprodução (" + q.size() + ")", 16, TEXT, true);
        title.setPadding(0, 0, 0, dp(8));
        libraryBox.addView(title);

        if (q.isEmpty()) {
            libraryBox.addView(text("A fila está vazia. Em “Vídeos”, toque em + Fila.", 14, MUTED, false));
            return;
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button playAll = button("▶  Reproduzir fila", PEACH, BG);
        Button clear = button("Limpar", PANEL_LIGHT, TEXT);
        playAll.setOnClickListener(v -> play(q, 0));
        clear.setOnClickListener(v -> {
            store.clearQueue();
            buildLibrary();
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        a1.rightMargin = dp(8);
        actions.addView(playAll, a1);
        actions.addView(clear, new LinearLayout.LayoutParams(dp(100), dp(42)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.bottomMargin = dp(10);
        libraryBox.addView(actions, alp);

        for (int i = 0; i < q.size(); i++) {
            final int idx = i;
            final MediaItem m = q.get(i);
            LinearLayout card = card();
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView num = text(String.valueOf(i + 1), 13, PEACH, true);
            num.setPadding(0, 0, dp(10), 0);
            line.addView(num);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(m.title, 13, TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(name);
            TextView host = text(m.type + " · " + FilterEngine.hostOfUrl(m.url), 11, MUTED, false);
            host.setSingleLine(true);
            info.addView(host);
            line.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView playIcon = icon(R.drawable.ic_play, PEACH);
            playIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
            playIcon.setOnClickListener(v -> play(q, idx));
            line.addView(playIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
            ImageView del = icon(R.drawable.ic_close, MUTED);
            del.setPadding(dp(10), dp(10), dp(10), dp(10));
            del.setOnClickListener(v -> {
                store.removeFromQueue(idx);
                buildLibrary();
            });
            line.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));
            card.addView(line);
            libraryBox.addView(card);
        }
    }

    private void buildHistoryList() {
        List<String[]> hist = store.history();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Histórico (" + hist.size() + ")", 16, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!hist.isEmpty()) {
            Button clear = button("Limpar", PANEL_LIGHT, TEXT);
            clear.setOnClickListener(v -> {
                store.clearHistory();
                buildLibrary();
            });
            head.addView(clear, new LinearLayout.LayoutParams(dp(100), dp(38)));
        }
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(8);
        libraryBox.addView(head, hlp);

        if (hist.isEmpty()) {
            libraryBox.addView(text("Nenhuma página visitada ainda.", 14, MUTED, false));
            return;
        }
        for (final String[] h : hist) {
            LinearLayout card = card();
            TextView name = text(h[1].isEmpty() ? FilterEngine.hostOfUrl(h[0]) : h[1], 13, TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(name);
            TextView u = text(h[0], 11, MUTED, false);
            u.setSingleLine(true);
            u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(u);
            card.setOnClickListener(v -> {
                Tab t = cur();
                if (t != null) {
                    t.lastTouch = SystemClock.uptimeMillis();
                    t.web.loadUrl(h[0]);
                }
                showScreen(SCREEN_BROWSER);
            });
            libraryBox.addView(card);
        }
    }

    // ------------------------------------------------------------------ painel Block ADS

    private void showBlockPanel(View anchor) {
        if (panel != null && panel.isShowing()) {
            panel.dismiss();
            return;
        }
        Tab t = cur();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(PANEL, dp(16), MUTED));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("Block ADS", 17, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int n = t == null ? 0 : t.blockedCount;
        titleRow.addView(text(n + " bloqueados", 12, GREEN, false));
        box.addView(titleRow);

        box.addView(switchRow("Popup", store.popup(), (b, on) -> store.setPopup(on)));
        box.addView(switchRow("Redirect", store.redirect(), (b, on) -> store.setRedirect(on)));
        box.addView(switchRow("Ads", store.ads(), (b, on) -> store.setAds(on)));

        View line = new View(this);
        line.setBackgroundColor(PANEL_LIGHT);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(10);
        llp.bottomMargin = dp(10);
        box.addView(line, llp);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        Button force = button("Force search", PEACH, BG);
        force.setOnClickListener(v -> {
            if (panel != null) panel.dismiss();
            forceSearch();
        });
        left.addView(force, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        final Button desktop = button(store.desktop() ? "Modo desktop: ligado" : "Modo desktop: desligado", PANEL_LIGHT, TEXT);
        desktop.setOnClickListener(v -> {
            store.setDesktop(!store.desktop());
            for (Tab tb : tabs) if (tb.web != null) applyUserAgent(tb.web.getSettings());
            if (panel != null) panel.dismiss();
            Tab ct = cur();
            if (ct != null && ct.web != null) ct.web.reload();
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        dlp.topMargin = dp(8);
        left.addView(desktop, dlp);
        bottomRow.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        ImageView more = icon(R.drawable.ic_gear, PEACH);
        more.setPadding(dp(10), dp(10), dp(10), dp(10));
        more.setBackground(roundRect(PANEL_LIGHT, dp(24), PEACH));
        more.setOnClickListener(v -> {
            if (panel != null) panel.dismiss();
            showSettingsDialog();
        });
        right.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView moreLabel = text("Mais\nconfig.", 10, MUTED, false);
        moreLabel.setGravity(Gravity.CENTER);
        right.addView(moreLabel);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(10);
        bottomRow.addView(right, rlp);
        box.addView(bottomRow);

        int w = dp(300);
        panel = new PopupWindow(box, w, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        panel.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        panel.setOutsideTouchable(true);
        panel.setElevation(dp(10));
        panel.showAsDropDown(anchor, -(w - anchor.getWidth()), dp(6));
    }

    private View switchRow(String label, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(2));
        row.addView(text(label, 15, TEXT, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        return row;
    }

    // ------------------------------------------------------------------ configurações

    private void showSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        box.addView(text("Tempo do aviso de bloqueio", 14, TEXT, true));
        final TextView secLabel = text(secondsText(store.noticeSeconds()), 13, MUTED, false);
        box.addView(secLabel);
        SeekBar sb = new SeekBar(this);
        sb.setMax(15);
        sb.setProgress(store.noticeSeconds());
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                store.setNoticeSeconds(progress);
                secLabel.setText(secondsText(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(sb);

        final TextView filterInfo = text("Regras de bloqueio carregadas: " + filters.size(), 12, MUTED, false);
        filterInfo.setPadding(0, dp(16), 0, dp(4));
        box.addView(filterInfo);
        Button upd = button("Atualizar listas de bloqueio", PANEL_LIGHT, TEXT);
        upd.setOnClickListener(v -> {
            Toast.makeText(this, "Atualizando listas…", Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                boolean ok = filters.download(getApplicationContext());
                if (ok) store.setFilterTime(System.currentTimeMillis());
                ui.post(() -> {
                    filterInfo.setText("Regras de bloqueio carregadas: " + filters.size());
                    Toast.makeText(this, ok ? "Listas atualizadas" : "Sem conexão com as listas", Toast.LENGTH_SHORT).show();
                });
            });
        });
        box.addView(upd, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        Button clearData = button("Limpar cookies e dados de sites", PANEL_LIGHT, TEXT);
        clearData.setOnClickListener(v -> {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            Tab t = cur();
            if (t != null && t.web != null) t.web.clearCache(true);
            Toast.makeText(this, "Dados limpos", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        clp.topMargin = dp(8);
        box.addView(clearData, clp);

        Button clearHist = button("Limpar histórico", PANEL_LIGHT, TEXT);
        clearHist.setOnClickListener(v -> {
            store.clearHistory();
            Toast.makeText(this, "Histórico limpo", Toast.LENGTH_SHORT).show();
        });
        box.addView(clearHist, clp);

        TextView castTitle = text("Transmissão (cast)", 14, TEXT, true);
        castTitle.setPadding(0, dp(18), 0, dp(4));
        box.addView(castTitle);
        box.addView(switchRow("Usar proxy do app (recomendado)", store.castProxy(), (b, on) -> store.setCastProxy(on)));
        TextView proxyHint = text("A TV não envia Referer nem cookies; o proxy do app faz isso por ela. "
                + "Mantém o app ativo enquanto transmite.", 11, MUTED, false);
        box.addView(proxyHint);
        TextView remoteLabel = text("Servidor Castor remoto (api-server) — opcional", 13, TEXT, false);
        remoteLabel.setPadding(0, dp(12), 0, dp(2));
        box.addView(remoteLabel);
        final EditText remoteUrl = new EditText(this);
        remoteUrl.setHint("http://192.168.0.10:8411");
        remoteUrl.setText(store.remoteUrl());
        remoteUrl.setSingleLine(true);
        remoteUrl.setTextSize(13);
        remoteUrl.setTextColor(TEXT);
        remoteUrl.setHintTextColor(MUTED);
        remoteUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(remoteUrl);
        final EditText remoteToken = new EditText(this);
        remoteToken.setHint("Token (api.token)");
        remoteToken.setText(store.remoteToken());
        remoteToken.setSingleLine(true);
        remoteToken.setTextSize(13);
        remoteToken.setTextColor(TEXT);
        remoteToken.setHintTextColor(MUTED);
        remoteToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(remoteToken);
        TextView remoteHint = text("Rode “castor api-server”, “castor media-server” e “castor scraping-server” num PC, "
                + "NAS ou Docker da mesma rede. O servidor converte o vídeo e queima legendas.", 11, MUTED, false);
        box.addView(remoteHint);

        TextView about = text("Castor 2.1 · Detecta links diretos (MP4, HLS, DASH…). Não contorna DRM.", 11, MUTED, false);
        about.setPadding(0, dp(16), 0, 0);
        box.addView(about);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Configurações")
                .setView(sv)
                .setPositiveButton("OK", (d, w) ->
                        store.setRemote(remoteUrl.getText().toString(), remoteToken.getText().toString()))
                .show();
    }

    private String secondsText(int s) {
        return s == 0 ? "Fica na tela até você escolher" : "Some após " + s + " s";
    }

    // ------------------------------------------------------------------ endereço

    private void goFromField() {
        String in = urlField.getText().toString();
        Tab t = cur();
        if (t == null) return;
        t.lastTouch = SystemClock.uptimeMillis();
        t.web.loadUrl(normalize(in));
        hideKeyboard();
        urlField.clearFocus();
        showScreen(SCREEN_BROWSER);
    }

    private String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return HOME_URL;
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file:///android_asset/")) return s;
        if (s.equals("localhost") || s.startsWith("localhost:")) return "http://" + s;
        if (!s.contains(" ") && s.contains(".") && !s.endsWith(".")) return "https://" + s;
        return "https://www.google.com/search?q=" + Uri.encode(s);
    }

    private void updateAddress() {
        Tab t = cur();
        if (t == null || urlField == null) return;
        if (urlField.hasFocus()) return;
        urlField.setText(isHome(t.url) ? "" : t.url);
    }

    private boolean isHome(String url) {
        return url == null || url.startsWith("file:///android_asset/");
    }

    private static String stripFragment(String url) {
        if (url == null) return "";
        int i = url.indexOf('#');
        return i >= 0 ? url.substring(0, i) : url;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View v = getCurrentFocus();
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ utilidades de UI

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(roundRect(PANEL, dp(12), null));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private ImageView icon(int res, int tint) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(foreground);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(roundRect(background, dp(20), null));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    private GradientDrawable roundRect(int color, int radius, Integer stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != null) g.setStroke(dp(2), stroke);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
