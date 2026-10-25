package com.castor.mobile;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final String APP_VERSION = "3.0.0";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static int BG = 0xFF101114;
    private static int PANEL = 0xFF1A1B1F;
    private static int PANEL_LIGHT = 0xFF25262B;
    private static int OUTLINE = 0xFF30323A;
    private static int TEXT = 0xFFF3F4F6;
    private static int MUTED = 0xFF9A9CA6;
    private static int ACCENT = 0xFFE5484D;
    private static int ON_ACCENT = 0xFFFFFFFF;
    private static int ACCENT_SOFT = 0xFF3A2528;
    private static int GREEN = 0xFF4CC38A;
    private static final int REQUEST_CUSTOM_ICON_IMAGE = 8751;

    private static final int SCREEN_LIBRARY = 0;
    private static final int SCREEN_BROWSER = 1;
    private static final int SCREEN_VIDEOS = 2;
    private static final int SCREEN_SETTINGS = 3;

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
    private ScrollView settingsScroll;
    private LinearLayout libraryBox;
    private LinearLayout videosBox;
    private LinearLayout settingsBox;
    private LinearLayout settingsResults;
    private LinearLayout topBar;
    private EditText urlField;
    private ImageView omniIcon;
    private ImageView btnStar;
    private TextView tabsCount;
    private ProgressBar progressBar;
    private final LinearLayout[] navItems = new LinearLayout[4];
    private final ImageView[] navIcons = new ImageView[4];
    private final TextView[] navLabelViews = new TextView[4];
    private TextView videoBadge;
    private LinearLayout fabVideos;
    private TextView fabText;
    private LinearLayout castBar;
    private TextView castBarText;
    private int screen = SCREEN_BROWSER;
    private int libraryTab = 0; // 0 = fila, 1 = favoritos, 2 = histórico
    private String settingsPage = "";
    private String settingsQuery = "";
    private boolean rounded = true;
    private Dialog sheet;

    // reabre a mesma tela depois de recriar a activity (troca de tema/cor)
    private static int resumeScreen = -1;
    private static String resumePage = "";

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

    private interface BoolSetter {
        void set(boolean on);
    }

    private interface IndexPick {
        void pick(int index);
    }

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
        boolean autoPlayScheduled;
        boolean autoPlayLaunched;

        synchronized boolean addVideo(MediaItem m) {
            MediaItem old = videos.get(m.url);
            if (old != null) {
                String quality = m.quality.isEmpty() ? old.quality : m.quality;
                String language = m.language.isEmpty() ? old.language : m.language;
                String title = m.title.isEmpty() ? old.title : m.title;
                if (quality.equals(old.quality) && language.equals(old.language) && title.equals(old.title)) return false;
                videos.put(m.url, new MediaItem(m.url, title, m.referer.isEmpty() ? old.referer : m.referer,
                        m.pageUrl.isEmpty() ? old.pageUrl : m.pageUrl, m.type.isEmpty() ? old.type : m.type,
                        quality, language));
                return true;
            }
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
            autoPlayScheduled = false;
            autoPlayLaunched = false;
        }
    }

    private void applyThemePalette() {
        ThemePalette.apply(this);
        BG = ThemePalette.BG;
        PANEL = ThemePalette.PANEL;
        PANEL_LIGHT = ThemePalette.PANEL_LIGHT;
        OUTLINE = ThemePalette.OUTLINE;
        TEXT = ThemePalette.TEXT;
        MUTED = ThemePalette.MUTED;
        ACCENT = ThemePalette.ACCENT;
        ON_ACCENT = ThemePalette.ON_ACCENT;
        ACCENT_SOFT = ThemePalette.ACCENT_SOFT;
        GREEN = ThemePalette.GREEN;
        rounded = store.rounded();
        Window win = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            win.setStatusBarColor(BG);
            win.setNavigationBarColor(BG);
        }
        win.setBackgroundDrawable(new ColorDrawable(BG));
        int flags = win.getDecorView().getSystemUiVisibility();
        if (ThemePalette.LIGHT) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        win.getDecorView().setSystemUiVisibility(flags);
    }

    /** Recria a tela (troca de tema/cor) voltando para onde o usuário estava. */
    private void restartUi() {
        resumeScreen = screen;
        resumePage = settingsPage;
        recreate();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // tema "Sistema": acompanha o modo escuro/claro do Android sem precisar reabrir o app
        if (store != null && browserShown && "system".equals(store.theme())) {
            boolean nowLight = "light".equals(ThemePalette.mode(this));
            if (nowLight != ThemePalette.LIGHT) restartUi();
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        applyThemePalette();
        castorJs = readAsset("castor.js");
        filters.loadCache(getApplicationContext());
        if (resumeScreen >= 0) {
            bootStarted = true;
            showBrowser();
        } else {
            showBoot();
        }
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
        updateCastBar();
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                    && CastService.handleVolumeKey(this, 1)) return true;
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                    && CastService.handleVolumeKey(this, -1)) return true;
        }
        return super.dispatchKeyEvent(event);
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
        if (screen == SCREEN_SETTINGS && !settingsPage.isEmpty()) {
            settingsPage = "";
            buildSettings();
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
        pb.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
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
        outer.addView(main, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- barra superior: endereço | favorito | abas | cast | menu
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(BG);
        topBar.setPadding(dp(12), dp(8), dp(6), dp(8));

        LinearLayout omni = new LinearLayout(this);
        omni.setOrientation(LinearLayout.HORIZONTAL);
        omni.setGravity(Gravity.CENTER_VERTICAL);
        omni.setBackground(pillBg(PANEL));
        omni.setPadding(dp(16), 0, dp(4), 0);

        omniIcon = icon(R.drawable.ic_search, MUTED);
        omni.addView(omniIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(TEXT);
        urlField.setHintTextColor(MUTED);
        urlField.setHint("Pesquisar ou digitar endereço");
        urlField.setTextSize(15);
        urlField.setBackground(null);
        urlField.setHighlightColor(ThemePalette.withAlpha(ACCENT, 90));
        urlField.setPadding(dp(10), 0, dp(6), 0);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setSelectAllOnFocus(true);
        urlField.setOnEditorActionListener((v, actionId, event) -> {
            goFromField();
            return true;
        });
        omni.addView(urlField, new LinearLayout.LayoutParams(0, dp(46), 1f));

        btnStar = icon(R.drawable.ic_star, MUTED);
        btnStar.setPadding(dp(9), dp(9), dp(9), dp(9));
        btnStar.setOnClickListener(v -> toggleBookmarkCurrent());
        omni.addView(btnStar, new LinearLayout.LayoutParams(dp(38), dp(38)));
        topBar.addView(omni, new LinearLayout.LayoutParams(0, dp(46), 1f));

        FrameLayout tabsWrap = new FrameLayout(this);
        tabsCount = text("1", 12, TEXT, true);
        tabsCount.setGravity(Gravity.CENTER);
        tabsCount.setBackground(roundRect(Color.TRANSPARENT, dp(8), TEXT));
        tabsWrap.addView(tabsCount, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        tabsWrap.setOnClickListener(v -> showTabsDialog());
        LinearLayout.LayoutParams twp = new LinearLayout.LayoutParams(dp(42), dp(42));
        twp.leftMargin = dp(4);
        topBar.addView(tabsWrap, twp);

        topBar.addView(barIcon(R.drawable.ic_cast, v -> castFromToolbar()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBar.addView(barIcon(R.drawable.ic_more, v -> showMenuSheet()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        main.addView(topBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.INVISIBLE);
        progressBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        main.addView(progressBar, new LinearLayout.LayoutParams(MATCH, dp(3)));

        // ---- conteúdo: navegador / biblioteca / vídeos / configurações
        contentFrame = new FrameLayout(this);
        main.addView(contentFrame, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        webHolder = new FrameLayout(this);
        contentFrame.addView(webHolder, new FrameLayout.LayoutParams(MATCH, MATCH));

        libraryBox = new LinearLayout(this);
        libraryBox.setOrientation(LinearLayout.VERTICAL);
        libraryBox.setPadding(dp(16), dp(10), dp(16), dp(28));
        libraryScroll = new ScrollView(this);
        libraryScroll.setBackgroundColor(BG);
        libraryScroll.addView(libraryBox);
        libraryScroll.setVisibility(View.GONE);
        contentFrame.addView(libraryScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        videosBox = new LinearLayout(this);
        videosBox.setOrientation(LinearLayout.VERTICAL);
        videosBox.setPadding(dp(16), dp(10), dp(16), dp(28));
        videosScroll = new ScrollView(this);
        videosScroll.setBackgroundColor(BG);
        videosScroll.addView(videosBox);
        videosScroll.setVisibility(View.GONE);
        contentFrame.addView(videosScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setPadding(dp(16), dp(10), dp(16), dp(28));
        settingsScroll = new ScrollView(this);
        settingsScroll.setBackgroundColor(BG);
        settingsScroll.addView(settingsBox);
        settingsScroll.setVisibility(View.GONE);
        contentFrame.addView(settingsScroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        // botão flutuante: aparece quando a página tem vídeos
        fabVideos = new LinearLayout(this);
        fabVideos.setOrientation(LinearLayout.HORIZONTAL);
        fabVideos.setGravity(Gravity.CENTER_VERTICAL);
        fabVideos.setPadding(dp(16), 0, dp(18), 0);
        rippleBg(fabVideos, pillBg(ACCENT));
        fabVideos.setElevation(dp(6));
        fabVideos.setVisibility(View.GONE);
        fabVideos.addView(icon(R.drawable.ic_play_arrow, ON_ACCENT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        fabText = text("1 vídeo", 14, ON_ACCENT, true);
        fabText.setPadding(dp(6), 0, 0, 0);
        fabVideos.addView(fabText);
        fabVideos.setOnClickListener(v -> showScreen(SCREEN_VIDEOS));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(WRAP, dp(52), Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, dp(16), dp(16));
        contentFrame.addView(fabVideos, flp);

        buildNoticeCard();

        // ---- faixa "transmitindo" (aparece enquanto há cast ativo)
        castBar = new LinearLayout(this);
        castBar.setOrientation(LinearLayout.HORIZONTAL);
        castBar.setGravity(Gravity.CENTER_VERTICAL);
        castBar.setPadding(dp(14), 0, dp(14), 0);
        rippleBg(castBar, pillBg(ACCENT_SOFT));
        castBar.addView(icon(R.drawable.ic_cast, ACCENT), new LinearLayout.LayoutParams(dp(20), dp(20)));
        castBarText = text("Transmitindo", 13, TEXT, true);
        castBarText.setSingleLine(true);
        castBarText.setEllipsize(TextUtils.TruncateAt.END);
        castBarText.setPadding(dp(10), 0, dp(8), 0);
        castBar.addView(castBarText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        castBar.addView(text("Controle", 13, ACCENT, true));
        castBar.setOnClickListener(v -> CastUi.show(this, null, 0));
        castBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(MATCH, dp(44));
        cbp.setMargins(dp(14), dp(4), dp(14), dp(2));
        main.addView(castBar, cbp);

        // ---- barra inferior (pílula): navegar | vídeos | biblioteca | ajustes
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER);
        navBar.setBackground(pillBg(PANEL));
        navBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        navBar.setElevation(dp(4));
        navBar.addView(navItem(SCREEN_BROWSER, R.drawable.ic_globe, "Navegar"), navLp());
        navBar.addView(navItem(SCREEN_VIDEOS, R.drawable.ic_video, "Vídeos"), navLp());
        navBar.addView(navItem(SCREEN_LIBRARY, R.drawable.ic_queue, "Biblioteca"), navLp());
        navBar.addView(navItem(SCREEN_SETTINGS, R.drawable.ic_gear, "Ajustes"), navLp());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        nlp.setMargins(dp(14), dp(6), dp(14), dp(10));
        main.addView(navBar, nlp);

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
        if (resumeScreen >= 0) {
            int target = resumeScreen;
            String page = resumePage;
            resumeScreen = -1;
            resumePage = "";
            settingsPage = page == null ? "" : page;
            showScreen(target);
        }
    }

    private LinearLayout.LayoutParams navLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private View navItem(final int target, int iconRes, String label) {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        ImageView ic = icon(iconRes, MUTED);
        item.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView tv = text(label, 11, MUTED, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(0, dp(2), 0, 0);
        item.addView(tv);
        item.setOnClickListener(v -> {
            if (target == SCREEN_SETTINGS && screen == SCREEN_SETTINGS) {
                settingsPage = "";
                settingsQuery = "";
            }
            showScreen(target);
        });
        wrap.addView(item, new FrameLayout.LayoutParams(MATCH, MATCH));
        navItems[target] = item;
        navIcons[target] = ic;
        navLabelViews[target] = tv;
        if (target == SCREEN_VIDEOS) {
            videoBadge = text("0", 10, ON_ACCENT, true);
            videoBadge.setGravity(Gravity.CENTER);
            videoBadge.setSingleLine(true);
            videoBadge.setMinWidth(dp(18));
            videoBadge.setPadding(dp(4), 0, dp(4), 0);
            videoBadge.setBackground(pillBg(ACCENT));
            videoBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP, dp(18),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            blp.leftMargin = dp(26);
            blp.topMargin = dp(1);
            wrap.addView(videoBadge, blp);
        }
        return wrap;
    }

    private void updateNav() {
        boolean labels = store.navLabels();
        for (int i = 0; i < navItems.length; i++) {
            if (navItems[i] == null) continue;
            boolean on = i == screen;
            rippleBg(navItems[i], on ? pillBg(ACCENT_SOFT) : null);
            navIcons[i].setColorFilter(on ? ACCENT : MUTED);
            navLabelViews[i].setTextColor(on ? ACCENT : MUTED);
            navLabelViews[i].setVisibility(labels || on ? View.VISIBLE : View.GONE);
        }
    }

    private void updateFab() {
        if (fabVideos == null) return;
        Tab t = cur();
        int n = t == null ? 0 : t.videoCount();
        boolean show = screen == SCREEN_BROWSER && n > 0;
        fabVideos.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) fabText.setText(n == 1 ? "1 vídeo" : n + " vídeos");
    }

    private void updateCastBar() {
        if (castBar == null) return;
        CastManager cm = CastManager.get(this);
        boolean on = cm.isActive();
        castBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            CastDevice d = cm.activeDevice();
            String label = d == null ? "" : d.label();
            castBarText.setText("Transmitindo" + (label.isEmpty() ? "" : " para " + label));
        }
    }

    private ImageView barIcon(int res, View.OnClickListener click) {
        ImageView iv = icon(res, TEXT);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));
        iv.setOnClickListener(click);
        rippleBg(iv, null);
        return iv;
    }

    private void updateOmnibox(Tab t) {
        if (t == null) return;
        if (omniIcon != null) {
            boolean empty = t.url == null || t.url.isEmpty() || isHome(t.url);
            boolean secure = !empty && t.url.startsWith("https://");
            omniIcon.setImageResource(empty ? R.drawable.ic_search : (secure ? R.drawable.ic_lock : R.drawable.ic_globe));
            omniIcon.setColorFilter(secure ? GREEN : MUTED);
        }
        if (btnStar != null) {
            boolean fav = store.isBookmarked(t.url);
            btnStar.setImageResource(fav ? R.drawable.ic_star_filled : R.drawable.ic_star);
            btnStar.setColorFilter(fav ? ACCENT : MUTED);
        }
    }

    private void toggleBookmarkCurrent() {
        Tab t = cur();
        if (t == null || t.url == null || !t.url.startsWith("http")) {
            Toast.makeText(this, "Abra uma página para favoritar", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = store.toggleBookmark(t.url, t.title);
        Toast.makeText(this, on ? "Adicionado aos favoritos" : "Removido dos favoritos", Toast.LENGTH_SHORT).show();
        updateOmnibox(t);
    }

    private void castFromToolbar() {
        CastManager cm = CastManager.get(this);
        if (cm.isActive()) {
            CastUi.show(this, null, 0);
            return;
        }
        Tab t = cur();
        List<MediaItem> playable = new ArrayList<>();
        if (t != null) {
            for (MediaItem m : t.videoList()) if (!"PÁGINA".equals(m.type)) playable.add(m);
        }
        if (playable.size() == 1) {
            CastUi.show(this, playable.get(0), -1);
        } else if (playable.isEmpty()) {
            Toast.makeText(this, "Procurando vídeos nesta página…", Toast.LENGTH_SHORT).show();
            forceSearch();
        } else {
            showScreen(SCREEN_VIDEOS);
        }
    }

    private void shareCurrentPage() {
        Tab t = cur();
        if (t == null || t.url == null || !t.url.startsWith("http")) {
            Toast.makeText(this, "Abra uma página para compartilhar", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT, t.url);
        startActivity(Intent.createChooser(s, "Compartilhar página"));
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

    private void showScreen(int s) {
        screen = s;
        dismissSheet();
        hideKeyboard();
        if (s != SCREEN_SETTINGS) {
            settingsQuery = "";
            if (!"".equals(settingsPage)) settingsPage = "";
        }
        webHolder.setVisibility(s == SCREEN_BROWSER ? View.VISIBLE : View.GONE);
        libraryScroll.setVisibility(s == SCREEN_LIBRARY ? View.VISIBLE : View.GONE);
        videosScroll.setVisibility(s == SCREEN_VIDEOS ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(s == SCREEN_SETTINGS ? View.VISIBLE : View.GONE);
        topBar.setVisibility(s == SCREEN_BROWSER ? View.VISIBLE : View.GONE);
        progressBar.setAlpha(s == SCREEN_BROWSER ? 1f : 0f);
        if (s == SCREEN_LIBRARY) buildLibrary();
        if (s == SCREEN_VIDEOS) buildVideos();
        if (s == SCREEN_SETTINGS) buildSettings();
        updateNav();
        updateFab();
        updateCastBar();
        updateOmnibox(cur());
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

    // ------------------------------------------------------------------ painéis (bottom sheets)

    private void showSheet(View content) {
        dismissSheet();
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        wrap.setBackground(bg);
        wrap.setPadding(dp(18), dp(10), dp(18), dp(22));
        View handle = new View(this);
        handle.setBackground(pillBg(OUTLINE));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(38), dp(5));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        wrap.addView(handle, hp);
        wrap.addView(content, new LinearLayout.LayoutParams(MATCH, WRAP));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(MATCH, WRAP);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.55f);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            if (Build.VERSION.SDK_INT >= 21) w.setNavigationBarColor(PANEL);
        }
        d.show();
        sheet = d;
    }

    private void dismissSheet() {
        try {
            if (sheet != null && sheet.isShowing()) sheet.dismiss();
        } catch (Exception ignored) {
        }
        sheet = null;
    }

    private TextView sheetTitle(String title) {
        TextView t = text(title, 20, TEXT, true);
        t.setPadding(dp(2), 0, 0, dp(12));
        return t;
    }

    private View sheetRow(int iconRes, String label, boolean selected, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        rippleBg(row, selected ? roundRect(ACCENT_SOFT, dp(16), null) : null);
        row.addView(iconChip(iconRes, selected ? ACCENT : TEXT, dp(40)), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = text(label, 15, selected ? ACCENT : TEXT, selected);
        tv.setPadding(dp(14), 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.setOnClickListener(click);
        return row;
    }

    private View choiceRow(String label, boolean selected, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        rippleBg(row, selected ? roundRect(ACCENT_SOFT, dp(16), null) : null);
        TextView tv = text(label, 15, selected ? ACCENT : TEXT, selected);
        row.addView(tv, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (selected) row.addView(icon(R.drawable.ic_check, ACCENT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(click);
        row.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(52)));
        return row;
    }

    private void showChoiceSheet(String title, String[] labels, int selected, final IndexPick pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetTitle(title));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            box.addView(choiceRow(labels[i], i == selected, v -> {
                dismissSheet();
                pick.pick(idx);
            }));
        }
        showSheet(box);
    }

    private void showTabsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetTitle("Abas (" + tabs.size() + ")"));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab tb = tabs.get(i);
            boolean home = tb.url == null || tb.url.isEmpty() || isHome(tb.url);
            String host = home ? "Página inicial" : FilterEngine.hostOfUrl(tb.url);
            String name = home ? "Início" : (TextUtils.isEmpty(tb.title) ? host : tb.title);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(4), dp(8));
            rippleBg(row, roundRect(idx == current ? ACCENT_SOFT : PANEL_LIGHT, dp(18), null));
            row.addView(letterChip(home ? "C" : firstLetter(host), idx == current), new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(4), 0);
            TextView label = text(name, 14, TEXT, true);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(label);
            TextView sub = text(host, 12, MUTED, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
            ImageView close = icon(R.drawable.ic_close, MUTED);
            close.setPadding(dp(11), dp(11), dp(11), dp(11));
            close.setOnClickListener(v -> {
                dismissSheet();
                closeTab(idx);
                showScreen(SCREEN_BROWSER);
            });
            row.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
            row.setOnClickListener(v -> {
                dismissSheet();
                switchToTab(idx);
                showScreen(SCREEN_BROWSER);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, Math.min(tabs.size() * dp(64), dp(340))));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout add = primaryButton(R.drawable.ic_add, "Nova aba", v -> {
            dismissSheet();
            newTab(HOME_URL, true);
            showScreen(SCREEN_BROWSER);
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        alp.rightMargin = dp(8);
        actions.addView(add, alp);
        LinearLayout closeAll = tonalButton(R.drawable.ic_delete, "Fechar todas", v -> {
            dismissSheet();
            while (tabs.size() > 1) closeTab(tabs.size() - 1);
            closeTab(0);
            showScreen(SCREEN_BROWSER);
        });
        actions.addView(closeAll, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams albp = new LinearLayout.LayoutParams(MATCH, WRAP);
        albp.topMargin = dp(6);
        box.addView(actions, albp);
        showSheet(box);
    }

    private void showMenuSheet() {
        final Tab t = cur();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconChip(R.drawable.ic_shield, GREEN, dp(46)), new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        boolean anyOn = store.popup() || store.redirect() || store.ads();
        col.addView(text(anyOn ? "Proteção ativa" : "Proteção desligada", 17, TEXT, true));
        int n = t == null ? 0 : t.blockedCount;
        col.addView(text(n + (n == 1 ? " bloqueio nesta página" : " bloqueios nesta página"), 12, MUTED, false));
        head.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        hlp.bottomMargin = dp(14);
        box.addView(head, hlp);

        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        toggles.addView(toggleChip("Pop-ups", store.popup(), on -> store.setPopup(on)), chipLp());
        toggles.addView(toggleChip("Redirect", store.redirect(), on -> store.setRedirect(on)), chipLp());
        toggles.addView(toggleChip("Anúncios", store.ads(), on -> store.setAds(on)), chipLp());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tlp.bottomMargin = dp(12);
        box.addView(toggles, tlp);

        boolean fav = t != null && store.isBookmarked(t.url);
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(actionTile(R.drawable.ic_search, "Buscar vídeos", false, v -> {
            dismissSheet();
            forceSearch();
        }), tileLp());
        row1.addView(actionTile(R.drawable.ic_desktop, "Desktop", store.desktop(), v -> {
            store.setDesktop(!store.desktop());
            for (Tab tb : tabs) if (tb.web != null) applyUserAgent(tb.web.getSettings());
            dismissSheet();
            Tab ct = cur();
            if (ct != null && ct.web != null) ct.web.reload();
        }), tileLp());
        row1.addView(actionTile(fav ? R.drawable.ic_star_filled : R.drawable.ic_star, "Favorito", fav, v -> {
            dismissSheet();
            toggleBookmarkCurrent();
        }), tileLp());
        box.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(actionTile(R.drawable.ic_share, "Compartilhar", false, v -> {
            dismissSheet();
            shareCurrentPage();
        }), tileLp());
        row2.addView(actionTile(R.drawable.ic_refresh, "Atualizar", false, v -> {
            dismissSheet();
            Tab ct = cur();
            if (ct != null && ct.web != null) ct.web.reload();
        }), tileLp());
        row2.addView(actionTile(R.drawable.ic_gear, "Ajustes", false, v -> {
            dismissSheet();
            settingsPage = "";
            showScreen(SCREEN_SETTINGS);
        }), tileLp());
        box.addView(row2);
        showSheet(box);
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private View toggleChip(String label, boolean initial, final BoolSetter setter) {
        final boolean[] state = {initial};
        final TextView tv = text(label, 13, TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        styleToggle(tv, initial);
        tv.setOnClickListener(v -> {
            state[0] = !state[0];
            setter.set(state[0]);
            styleToggle(tv, state[0]);
        });
        return tv;
    }

    private void styleToggle(TextView tv, boolean on) {
        tv.setBackground(pillBg(on ? ACCENT : PANEL_LIGHT));
        tv.setTextColor(on ? ON_ACCENT : MUTED);
    }

    private View actionTile(int iconRes, String label, boolean active, View.OnClickListener click) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(14), dp(4), dp(12));
        rippleBg(tile, roundRect(active ? ACCENT_SOFT : PANEL_LIGHT, dp(20), null));
        tile.addView(icon(iconRes, active ? ACCENT : TEXT), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = text(label, 12, active ? ACCENT : TEXT, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(0, dp(8), 0, 0);
        tile.addView(tv);
        tile.setOnClickListener(click);
        return tile;
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
        s.setTextZoom(store.textZoom());
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
            if (isHome(url)) applyHomePageTheme(view);
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
            String html = "<html><body style='background:" + hex(BG) + ";color:" + hex(TEXT) + ";font-family:sans-serif;padding:32px'>"
                    + "<h2>Não foi possível carregar</h2><p style='color:" + hex(MUTED) + "'>"
                    + android.text.TextUtils.htmlEncode(failing)
                    + "</p><p><a style='color:" + hex(ACCENT) + "' href='" + android.text.TextUtils.htmlEncode(failing)
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
        noticeCard.setBackground(roundRect(PANEL, dp(22), OUTLINE));
        noticeCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        noticeCard.setVisibility(View.GONE);
        noticeCard.setElevation(dp(8));

        noticeReason = text("", 13, ACCENT, true);
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
        Button open = button("Abrir", ACCENT, ON_ACCENT);
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.setMargins(dp(12), dp(10), dp(12), dp(10));
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
        addVideoForced(tab, url, type, "", "");
    }

    private void addVideoForced(Tab tab, String url, String type) {
        addVideoForced(tab, url, type, "", "");
    }

    private void addVideoForced(Tab tab, String url, String type, String quality, String language) {
        if (url == null || !url.startsWith("http")) return;
        if (store.ads() && filters.shouldBlock(url, FilterEngine.hostOfUrl(tab.url))) return;
        String page = TextUtils.isEmpty(tab.title) ? FilterEngine.hostOfUrl(tab.url) : tab.title;
        String q = TextUtils.isEmpty(quality) ? qualityFromUrl(url) : quality;
        MediaItem m = new MediaItem(url, page + " · " + fileName(url), tab.url, tab.url, type, q, language);
        if (tab.addVideo(m)) {
            ui.post(() -> {
                refreshBadge();
                if (!"PÁGINA".equals(type) && !"ÁUDIO".equals(type)) scheduleAutoPlay(tab);
            });
        }
    }

    private String qualityFromUrl(String url) {
        if (url == null) return "";
        String u = url.toLowerCase(Locale.ROOT);
        if (u.matches(".*(?:^|[^a-z0-9])(?:4k|2160p?)(?:[^a-z0-9]|$).*")) return "2160p (4K)";
        if (u.matches(".*(?:^|[^a-z0-9])1440p?(?:[^a-z0-9]|$).*")) return "1440p (2K)";
        Matcher m = Pattern.compile("(?:^|[^0-9])(1080|720|576|480|360|240)p?(?:[^0-9]|$)", Pattern.CASE_INSENSITIVE).matcher(u);
        if (m.find()) return m.group(1) + "p";
        return "";
    }

    private int qualityScore(MediaItem m) {
        String q = m.quality + " " + m.url;
        if (q.contains("2160") || q.toLowerCase(Locale.ROOT).contains("4k")) return 2160;
        if (q.contains("1440")) return 1440;
        if (q.contains("1080")) return 1080;
        if (q.contains("720")) return 720;
        if (q.contains("576")) return 576;
        if (q.contains("480")) return 480;
        if (q.contains("360")) return 360;
        if (q.contains("240")) return 240;
        return 0;
    }

    private void scheduleAutoPlay(Tab tab) {
        if (!store.autoPlay()) return;
        final String scheduledPage;
        synchronized (tab) {
            if (tab.autoPlayScheduled || tab.autoPlayLaunched) return;
            tab.autoPlayScheduled = true;
            scheduledPage = tab.url;
        }
        ui.postDelayed(() -> {
            List<MediaItem> found;
            synchronized (tab) {
                tab.autoPlayScheduled = false;
                if (tab.autoPlayLaunched || tab != cur()
                        || !stripFragment(tab.url).equals(stripFragment(scheduledPage))) return;
                found = tab.videoList();
                tab.autoPlayLaunched = true;
            }
            MediaItem best = null;
            for (MediaItem item : found) {
                if ("PÁGINA".equals(item.type) || "ÁUDIO".equals(item.type)) continue;
                if (best == null || qualityScore(item) > qualityScore(best)) best = item;
            }
            if (best != null) {
                Toast.makeText(MainActivity.this, "Reprodução automática · "
                        + (best.quality.isEmpty() ? "melhor fonte detectada" : best.quality), Toast.LENGTH_SHORT).show();
                List<MediaItem> one = new ArrayList<>();
                one.add(best);
                play(one, 0);
            }
        }, 1800);
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
        updateFab();
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
        public void onMediaDetails(String json) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length() && i < 200; i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    String type = Ext.typeOf(url);
                    if (!type.isEmpty()) addVideoForced(tab, url, type,
                            o.optString("quality", ""), o.optString("language", ""));
                }
            } catch (JSONException ignored) { }
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
        Intent service = new Intent(this, PlaybackService.class)
                .putExtra(PlayerActivity.EXTRA_ITEMS, Store.queueToJson(list))
                .putExtra(PlayerActivity.EXTRA_INDEX, index);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível iniciar o serviço de mídia: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

        String site = t != null && t.url != null && !t.url.isEmpty() && !isHome(t.url)
                ? FilterEngine.hostOfUrl(t.url) : "abra um site com vídeo";
        LinearLayout trailing = new LinearLayout(this);
        trailing.setOrientation(LinearLayout.HORIZONTAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        if (CastManager.get(this).isActive()) {
            ImageView ctl = smallIcon(R.drawable.ic_tv, ACCENT, v -> CastUi.show(this, null, 0));
            ctl.setBackground(pillBg(ACCENT_SOFT));
            trailing.addView(ctl, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(WRAP, dp(42));
        slp.leftMargin = dp(8);
        trailing.addView(softButton(R.drawable.ic_search, "Buscar", v -> forceSearch()), slp);
        videosBox.addView(screenHeader("Vídeos",
                list.size() + (list.size() == 1 ? " encontrado" : " encontrados") + " · " + site, trailing));

        if (t != null && t.blobSeen) {
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.HORIZONTAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(14), dp(12), dp(14), dp(12));
            info.setBackground(roundRect(ACCENT_SOFT, dp(20), null));
            info.addView(icon(R.drawable.ic_info, ACCENT), new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView tv = text("Esta página usa vídeo protegido/blob. Nem sempre existe um link direto.", 12, TEXT, false);
            tv.setPadding(dp(10), 0, 0, 0);
            info.addView(tv, new LinearLayout.LayoutParams(0, WRAP, 1f));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(MATCH, WRAP);
            ilp.bottomMargin = dp(12);
            videosBox.addView(info, ilp);
        }

        if (list.isEmpty()) {
            videosBox.addView(emptyState(R.drawable.ic_video, "Nenhum vídeo encontrado",
                    "Abra uma página com vídeo, toque em play e depois em Buscar.",
                    "Buscar vídeos", v -> forceSearch()));
            return;
        }

        final List<MediaItem> playable = new ArrayList<>();
        for (MediaItem m : list) if (!m.type.equals("PÁGINA")) playable.add(m);

        if (playable.size() > 1) {
            LinearLayout all = tonalButton(R.drawable.ic_queue, "Reproduzir todos em fila (" + playable.size() + ")",
                    v -> play(playable, 0));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH, dp(46));
            alp.bottomMargin = dp(12);
            videosBox.addView(all, alp);
        }

        for (final MediaItem m : list) videosBox.addView(videoCard(m));
    }

    private View videoCard(final MediaItem m) {
        boolean isPage = m.type.equals("PÁGINA");
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.addView(chip(m.type, isPage ? GREEN : ACCENT));
        if (!isPage && !"ÁUDIO".equals(m.type) && !m.quality.isEmpty()) chips.addView(chip(m.quality, GREEN), chipMargin());
        if (!m.language.isEmpty()) chips.addView(chip(m.language, MUTED), chipMargin());
        card.addView(chips);

        TextView name = text(m.title, 15, TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(10), 0, 0);
        card.addView(name);

        if (!isPage && !"ÁUDIO".equals(m.type) && m.quality.isEmpty()) {
            TextView meta = text("Qualidade não informada pela fonte", 11, MUTED, false);
            meta.setPadding(0, dp(3), 0, 0);
            card.addView(meta);
        }

        TextView u = text(m.url, 11, MUTED, false);
        u.setSingleLine(true);
        u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        u.setPadding(0, dp(4), 0, dp(12));
        card.addView(u);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout primary;
        if (isPage) {
            primary = primaryButton(R.drawable.ic_globe, "Abrir player", v -> {
                Tab ct = cur();
                if (ct != null && ct.web != null) {
                    ct.allowOnce = m.url;
                    ct.lastTouch = SystemClock.uptimeMillis();
                    ct.web.loadUrl(m.url);
                }
                showScreen(SCREEN_BROWSER);
            });
        } else {
            primary = primaryButton(R.drawable.ic_play_arrow, "Reproduzir", v -> {
                List<MediaItem> one = new ArrayList<>();
                one.add(m);
                play(one, 0);
            });
        }
        actions.addView(primary, new LinearLayout.LayoutParams(0, dp(44), 1f));
        if (!isPage) {
            actions.addView(roundIconBtn(R.drawable.ic_queue, v -> {
                boolean added = store.addToQueue(m);
                Toast.makeText(this, added ? "Adicionado à fila" : "Já está na fila", Toast.LENGTH_SHORT).show();
            }), iconBtnLp());
        }
        actions.addView(roundIconBtn(R.drawable.ic_cast, v -> CastUi.show(this, m, -1)), iconBtnLp());
        actions.addView(roundIconBtn(R.drawable.ic_more, v -> showVideoMenu(m)), iconBtnLp());
        card.addView(actions);
        return card;
    }

    private LinearLayout.LayoutParams chipMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(6);
        return lp;
    }

    private LinearLayout.LayoutParams iconBtnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(8);
        return lp;
    }

    private void showVideoMenu(final MediaItem m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sheetTitle(m.type + (m.quality.isEmpty() ? "" : " · " + m.quality)));
        TextView u = text(m.url, 11, MUTED, false);
        u.setMaxLines(2);
        u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        u.setPadding(dp(2), 0, 0, dp(10));
        box.addView(u);

        box.addView(sheetRow(R.drawable.ic_cast, "Transmitir para a TV", false, v -> {
            dismissSheet();
            CastUi.show(this, m, -1);
        }));
        if (!m.type.equals("PÁGINA")) {
            box.addView(sheetRow(R.drawable.ic_queue, "Adicionar à fila", false, v -> {
                dismissSheet();
                boolean added = store.addToQueue(m);
                Toast.makeText(this, added ? "Adicionado à fila" : "Já está na fila", Toast.LENGTH_SHORT).show();
            }));
        }
        box.addView(sheetRow(R.drawable.ic_copy, "Copiar link", false, v -> {
            dismissSheet();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("vídeo", m.url));
            Toast.makeText(this, "Link copiado", Toast.LENGTH_SHORT).show();
        }));
        box.addView(sheetRow(R.drawable.ic_share, "Compartilhar link", false, v -> {
            dismissSheet();
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, m.url);
            startActivity(Intent.createChooser(s, "Compartilhar"));
        }));
        box.addView(sheetRow(R.drawable.ic_open, "Abrir em outro app", false, v -> {
            dismissSheet();
            try {
                Intent vi = new Intent(Intent.ACTION_VIEW);
                vi.setDataAndType(Uri.parse(m.url), m.type.equals("HLS") ? "application/x-mpegURL"
                        : (m.type.equals("DASH") ? "application/dash+xml" : "video/*"));
                startActivity(Intent.createChooser(vi, "Abrir com"));
            } catch (Exception e) {
                Toast.makeText(this, "Nenhum app compatível", Toast.LENGTH_SHORT).show();
            }
        }));
        showSheet(box);
    }

    // ------------------------------------------------------------------ tela: biblioteca (fila, favoritos, histórico)

    private void buildLibrary() {
        libraryBox.removeAllViews();
        libraryBox.addView(screenHeader("Biblioteca", "Fila, favoritos e histórico", null));
        int nq = store.queue().size();
        int nf = store.bookmarks().size();
        String[] labels = {"Fila (" + nq + ")", "Favoritos (" + nf + ")", "Histórico"};
        LinearLayout seg = segmented(labels, libraryTab, i -> {
            libraryTab = i;
            buildLibrary();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
        sp.bottomMargin = dp(14);
        libraryBox.addView(seg, sp);
        if (libraryTab == 0) buildQueueList();
        else if (libraryTab == 1) buildFavoritesList();
        else buildHistoryList();
    }

    private void buildQueueList() {
        final List<MediaItem> q = store.queue();
        if (q.isEmpty()) {
            libraryBox.addView(emptyState(R.drawable.ic_queue, "A fila está vazia",
                    "Em Vídeos, toque no botão de fila para guardar itens e reproduzir em sequência.", null, null));
            return;
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout playAll = primaryButton(R.drawable.ic_play_arrow, "Reproduzir fila", v -> play(q, 0));
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        a1.rightMargin = dp(8);
        actions.addView(playAll, a1);
        actions.addView(tonalButton(R.drawable.ic_delete, "Limpar", v -> {
            store.clearQueue();
            buildLibrary();
        }), new LinearLayout.LayoutParams(WRAP, dp(46)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH, WRAP);
        alp.bottomMargin = dp(12);
        libraryBox.addView(actions, alp);

        for (int i = 0; i < q.size(); i++) {
            final int idx = i;
            final MediaItem m = q.get(i);
            libraryBox.addView(listRow(String.valueOf(i + 1), m.title, m.type + " · " + FilterEngine.hostOfUrl(m.url),
                    v -> play(q, idx),
                    smallIcon(R.drawable.ic_play_arrow, ACCENT, v -> play(q, idx)),
                    smallIcon(R.drawable.ic_close, MUTED, v -> {
                        store.removeFromQueue(idx);
                        buildLibrary();
                    })));
        }
    }

    private void openInCurrentTab(String url) {
        Tab t = cur();
        if (t != null && t.web != null) {
            t.lastTouch = SystemClock.uptimeMillis();
            t.web.loadUrl(url);
        }
        showScreen(SCREEN_BROWSER);
    }

    private void buildFavoritesList() {
        List<String[]> favs = store.bookmarks();
        if (favs.isEmpty()) {
            libraryBox.addView(emptyState(R.drawable.ic_star, "Sem favoritos ainda",
                    "Toque na estrela da barra de endereço para guardar uma página aqui.", null, null));
            return;
        }
        for (int i = 0; i < favs.size(); i++) {
            final int idx = i;
            final String[] b = favs.get(i);
            String host = FilterEngine.hostOfUrl(b[0]);
            libraryBox.addView(listRow(firstLetter(host), b[1].isEmpty() ? host : b[1], b[0],
                    v -> openInCurrentTab(b[0]),
                    smallIcon(R.drawable.ic_close, MUTED, v -> {
                        store.removeBookmark(idx);
                        buildLibrary();
                    })));
        }
    }

    private void buildHistoryList() {
        List<String[]> hist = store.history();
        if (hist.isEmpty()) {
            libraryBox.addView(emptyState(R.drawable.ic_history, "Nenhuma página visitada",
                    "As páginas que você abrir aparecem aqui.", null, null));
            return;
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WRAP, dp(40));
        clp.bottomMargin = dp(12);
        clp.gravity = Gravity.END;
        libraryBox.addView(tonalButton(R.drawable.ic_delete, "Limpar histórico", v -> {
            store.clearHistory();
            buildLibrary();
        }), clp);
        for (final String[] h : hist) {
            String host = FilterEngine.hostOfUrl(h[0]);
            libraryBox.addView(listRow(firstLetter(host), h[1].isEmpty() ? host : h[1], h[0],
                    v -> openInCurrentTab(h[0])));
        }
    }

    // ------------------------------------------------------------------ tela: configurações

    private static final String[][] SETTING_INDEX = {
            {"Tema do app", "appearance", "claro escuro amoled sistema automatico preto noite"},
            {"Cor de destaque", "appearance", "cores paleta vermelho azul verde roxo rosa laranja ciano dinamica material you"},
            {"Cantos arredondados", "appearance", "bordas cartoes visual"},
            {"Rótulos da barra inferior", "appearance", "nomes textos navegacao menu"},
            {"Modo desktop", "browser", "versao completa computador user agent site"},
            {"Mecanismo de busca", "browser", "google duckduckgo bing brave ecosia startpage pesquisa"},
            {"Zoom do texto", "browser", "fonte tamanho letra"},
            {"Bloquear pop-ups", "blocking", "janelas popup"},
            {"Bloquear redirecionamentos", "blocking", "redirect desvio"},
            {"Bloquear anúncios", "blocking", "ads rastreadores easylist adblock propaganda"},
            {"Tempo do aviso de bloqueio", "blocking", "segundos notificacao aviso"},
            {"Atualizar listas de bloqueio", "blocking", "easylist easyprivacy regras filtros"},
            {"Reproduzir ao detectar", "playback", "auto play automatico iniciar video"},
            {"Qualidade preferida", "playback", "resolucao 1080 720 480 hd 4k"},
            {"Proxy do app", "cast", "referer cookies tv transmissao"},
            {"Bateria e tela apagada", "cast", "segundo plano economia energia"},
            {"Servidor Castor remoto", "cast", "api server token ffmpeg legendas"},
            {"Limpar cookies e dados", "data", "privacidade sites cache"},
            {"Limpar histórico", "data", "privacidade paginas visitadas"},
            {"Limpar favoritos", "data", "estrelas marcadores"},
            {"Limpar fila", "data", "reproducao playlist"},
            {"Ícone de atalho personalizado", "data", "imagem launcher"},
            {"Sobre o Castor", "about", "versao drm limitacoes"}
    };

    private static String fold(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private String pageTitle(String page) {
        if ("appearance".equals(page)) return "Aparência";
        if ("browser".equals(page)) return "Navegador";
        if ("blocking".equals(page)) return "Bloqueio de anúncios";
        if ("playback".equals(page)) return "Reprodução";
        if ("cast".equals(page)) return "Transmissão";
        if ("data".equals(page)) return "Dados e privacidade";
        return "Sobre";
    }

    private void openSettingsPage(String page) {
        settingsPage = page;
        settingsQuery = "";
        buildSettings();
    }

    private void buildSettings() {
        settingsBox.removeAllViews();
        settingsResults = null;
        if (settingsPage.isEmpty()) buildSettingsRoot();
        else buildSettingsPage(settingsPage);
        settingsScroll.scrollTo(0, 0);
    }

    private void buildSettingsRoot() {
        settingsBox.addView(screenHeader("Configurações", null, null));

        LinearLayout search = new LinearLayout(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(pillBg(PANEL));
        search.setPadding(dp(16), 0, dp(12), 0);
        search.addView(icon(R.drawable.ic_search, MUTED), new LinearLayout.LayoutParams(dp(18), dp(18)));
        final EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setHint("Buscar configurações");
        q.setText(settingsQuery);
        q.setTextSize(15);
        q.setTextColor(TEXT);
        q.setHintTextColor(MUTED);
        q.setBackground(null);
        q.setPadding(dp(10), 0, dp(4), 0);
        q.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                settingsQuery = s.toString();
                renderSettingsRoot();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        search.addView(q, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, dp(46));
        slp.bottomMargin = dp(14);
        settingsBox.addView(search, slp);

        settingsResults = new LinearLayout(this);
        settingsResults.setOrientation(LinearLayout.VERTICAL);
        settingsBox.addView(settingsResults, new LinearLayout.LayoutParams(MATCH, WRAP));
        renderSettingsRoot();
    }

    private void renderSettingsRoot() {
        if (settingsResults == null) return;
        settingsResults.removeAllViews();
        String q = fold(settingsQuery.trim());
        if (!q.isEmpty()) {
            LinearLayout g = group();
            int found = 0;
            for (String[] e : SETTING_INDEX) {
                if (fold(e[0] + " " + e[2]).contains(q)) {
                    final String page = e[1];
                    addRow(g, settingsRow(0, 0, e[0], pageTitle(page), true, v -> openSettingsPage(page)));
                    found++;
                }
            }
            if (found == 0) {
                settingsResults.addView(emptyState(R.drawable.ic_search, "Nada encontrado",
                        "Tente outra palavra, como tema, cor, anúncios ou qualidade.", null, null));
            } else {
                settingsResults.addView(g);
            }
            return;
        }
        settingsResults.addView(settingsHero());

        LinearLayout g1 = group();
        addRow(g1, settingsRow(R.drawable.ic_palette, 0xFF9168F5, "Aparência",
                ThemePalette.label(store.theme()) + " · " + ThemePalette.accentName(store.accent()), true,
                v -> openSettingsPage("appearance")));
        addRow(g1, settingsRow(R.drawable.ic_globe, 0xFF4C86F7, "Navegador",
                Store.SEARCH_NAMES[store.searchIndex()] + (store.desktop() ? " · desktop" : ""), true,
                v -> openSettingsPage("browser")));
        settingsResults.addView(g1);

        LinearLayout g2 = group();
        addRow(g2, settingsRow(R.drawable.ic_shield, 0xFFE5484D, "Bloqueio de anúncios",
                filters.size() + " regras carregadas", true, v -> openSettingsPage("blocking")));
        addRow(g2, settingsRow(R.drawable.ic_hd, 0xFF3FB56C, "Reprodução",
                "Qualidade: " + qualityLabel(store.quality()), true, v -> openSettingsPage("playback")));
        addRow(g2, settingsRow(R.drawable.ic_cast, 0xFF27B3CC, "Transmissão",
                store.castProxy() ? "Proxy do app ligado" : "Proxy do app desligado", true,
                v -> openSettingsPage("cast")));
        settingsResults.addView(g2);

        LinearLayout g3 = group();
        addRow(g3, settingsRow(R.drawable.ic_storage, 0xFFEE8A2B, "Dados e privacidade",
                "Cookies, histórico e favoritos", true, v -> openSettingsPage("data")));
        addRow(g3, settingsRow(R.drawable.ic_info, 0xFF8E8E98, "Sobre",
                "Castor " + APP_VERSION, true, v -> openSettingsPage("about")));
        settingsResults.addView(g3);
    }

    private View settingsHero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(ACCENT_SOFT, dp(26), null));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        top.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        col.addView(text("Castor", 20, TEXT, true));
        col.addView(text("Versão " + APP_VERSION + " · " + ThemePalette.label(store.theme()), 12, MUTED, false));
        top.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        card.addView(top);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(statTile(String.valueOf(filters.size()), "regras"), statLp(false));
        stats.addView(statTile(String.valueOf(store.bookmarks().size()), "favoritos"), statLp(true));
        stats.addView(statTile(String.valueOf(store.queue().size()), "na fila"), statLp(true));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        stlp.topMargin = dp(14);
        card.addView(stats, stlp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.bottomMargin = dp(14);
        card.setLayoutParams(clp);
        return card;
    }

    private LinearLayout.LayoutParams statLp(boolean margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        if (margin) lp.leftMargin = dp(8);
        return lp;
    }

    private View statTile(String value, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setBackground(roundRect(PANEL, dp(18), null));
        TextView v = text(value, 18, ACCENT, true);
        v.setGravity(Gravity.CENTER);
        t.addView(v);
        TextView l = text(label, 11, MUTED, false);
        l.setGravity(Gravity.CENTER);
        t.addView(l);
        return t;
    }

    private void buildSettingsPage(String page) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = icon(R.drawable.ic_back, TEXT);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setBackground(ovalBg(PANEL));
        back.setOnClickListener(v -> {
            settingsPage = "";
            buildSettings();
        });
        head.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text(pageTitle(page), 22, TEXT, true);
        title.setPadding(dp(14), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        hlp.bottomMargin = dp(14);
        settingsBox.addView(head, hlp);

        if ("appearance".equals(page)) buildAppearancePage();
        else if ("browser".equals(page)) buildBrowserPage();
        else if ("blocking".equals(page)) buildBlockingPage();
        else if ("playback".equals(page)) buildPlaybackPage();
        else if ("cast".equals(page)) buildCastPage();
        else if ("data".equals(page)) buildDataPage();
        else buildAboutPage();
    }

    private void buildAppearancePage() {
        settingsBox.addView(themePreview());

        settingsBox.addView(sectionLabel("Tema"));
        final String[] modes = {"system", "light", "dark", "amoled"};
        String[] labels = {"Sistema", "Claro", "Escuro", "AMOLED"};
        int sel = 0;
        for (int k = 0; k < modes.length; k++) if (modes[k].equals(store.theme())) sel = k;
        LinearLayout seg = segmented(labels, sel, i -> {
            store.setTheme(modes[i]);
            restartUi();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(MATCH, WRAP);
        sp.bottomMargin = dp(4);
        settingsBox.addView(seg, sp);
        settingsBox.addView(hint("Sistema segue o modo escuro do Android. AMOLED usa preto puro e economiza bateria em telas OLED."));

        settingsBox.addView(sectionLabel("Cor de destaque · " + ThemePalette.accentName(store.accent())));
        settingsBox.addView(swatches());

        LinearLayout g = group();
        addRow(g, switchSettingRow(R.drawable.ic_tune, 0xFF9168F5, "Cantos arredondados",
                "Cartões e painéis mais suaves", store.rounded(), on -> {
                    store.setRounded(on);
                    restartUi();
                }));
        addRow(g, switchSettingRow(R.drawable.ic_text, 0xFF4C86F7, "Rótulos na barra inferior",
                "Mostra o nome de todos os itens", store.navLabels(), on -> {
                    store.setNavLabels(on);
                    updateNav();
                }));
        settingsBox.addView(g);
    }

    private View swatches() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(roundRect(PANEL, dp(24), null));
        wrap.setPadding(dp(8), dp(10), dp(8), dp(10));
        int count = Build.VERSION.SDK_INT >= 31 ? ThemePalette.ACCENT_NAMES.length : ThemePalette.ACCENT_NAMES.length - 1;
        int perRow = 5;
        LinearLayout row = null;
        for (int k = 0; k < count; k++) {
            if (k % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                wrap.addView(row, new LinearLayout.LayoutParams(MATCH, dp(56)));
            }
            row.addView(swatch(k), new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        int rem = count % perRow;
        if (rem != 0 && row != null) {
            for (int k = rem; k < perRow; k++) row.addView(new View(this), new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(14);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    private View swatch(final int idx) {
        FrameLayout f = new FrameLayout(this);
        int color = ThemePalette.accentColor(this, idx, ThemePalette.LIGHT);
        boolean selected = store.accent() == idx;
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) g.setStroke(dp(3), TEXT);
        dot.setBackground(g);
        f.addView(dot, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        if (selected) {
            f.addView(icon(R.drawable.ic_check, ThemePalette.onColor(color)),
                    new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        }
        f.setOnClickListener(v -> {
            store.setAccent(idx);
            restartUi();
        });
        return f;
    }

    private View themePreview() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(BG, dp(26), OUTLINE));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout omni = new LinearLayout(this);
        omni.setOrientation(LinearLayout.HORIZONTAL);
        omni.setGravity(Gravity.CENTER_VERTICAL);
        omni.setBackground(pillBg(PANEL));
        omni.setPadding(dp(14), 0, dp(12), 0);
        omni.addView(icon(R.drawable.ic_lock, GREEN), new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView url = text("castor.app/filmes", 13, MUTED, false);
        url.setPadding(dp(8), 0, 0, 0);
        omni.addView(url, new LinearLayout.LayoutParams(0, WRAP, 1f));
        omni.addView(icon(R.drawable.ic_star_filled, ACCENT), new LinearLayout.LayoutParams(dp(18), dp(18)));
        card.addView(omni, new LinearLayout.LayoutParams(MATCH, dp(40)));

        LinearLayout vc = new LinearLayout(this);
        vc.setOrientation(LinearLayout.VERTICAL);
        vc.setBackground(roundRect(PANEL, dp(20), null));
        vc.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(chip("HLS", ACCENT));
        chips.addView(chip("1080p", GREEN), chipMargin());
        vc.addView(chips);
        TextView vt = text("Vídeo de exemplo", 15, TEXT, true);
        vt.setPadding(0, dp(8), 0, 0);
        vc.addView(vt);
        LinearLayout play = primaryButton(R.drawable.ic_play_arrow, "Reproduzir", null);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH, dp(42));
        plp.topMargin = dp(10);
        vc.addView(play, plp);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        vlp.topMargin = dp(10);
        card.addView(vc, vlp);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackground(pillBg(PANEL));
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        int[] res = {R.drawable.ic_globe, R.drawable.ic_video, R.drawable.ic_queue, R.drawable.ic_gear};
        for (int k = 0; k < res.length; k++) {
            FrameLayout cell = new FrameLayout(this);
            if (k == 0) cell.setBackground(pillBg(ACCENT_SOFT));
            cell.addView(icon(res[k], k == 0 ? ACCENT : MUTED), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            nav.addView(cell, new LinearLayout.LayoutParams(0, dp(36), 1f));
        }
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(MATCH, dp(46));
        nlp.topMargin = dp(10);
        card.addView(nav, nlp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.bottomMargin = dp(16);
        card.setLayoutParams(clp);
        return card;
    }

    private void buildBrowserPage() {
        LinearLayout g = group();
        addRow(g, switchSettingRow(R.drawable.ic_desktop, 0xFF4C86F7, "Modo desktop",
                "Mostra a versão completa dos sites", store.desktop(), on -> {
                    store.setDesktop(on);
                    for (Tab tb : tabs) if (tb.web != null) applyUserAgent(tb.web.getSettings());
                    Tab ct = cur();
                    if (ct != null && ct.web != null) ct.web.reload();
                }));
        addRow(g, settingsRow(R.drawable.ic_search, 0xFF4C86F7, "Mecanismo de busca",
                Store.SEARCH_NAMES[store.searchIndex()], true,
                v -> showChoiceSheet("Mecanismo de busca", Store.SEARCH_NAMES, store.searchIndex(), i -> {
                    store.setSearchIndex(i);
                    buildSettings();
                })));
        settingsBox.addView(g);

        LinearLayout z = group();
        final TextView zv = text(store.textZoom() + "%", 13, ACCENT, true);
        SeekBar sb = styledSeek(14, (store.textZoom() - 80) / 5, new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int zoom = 80 + progress * 5;
                store.setTextZoom(zoom);
                zv.setText(zoom + "%");
                for (Tab tb : tabs) if (tb.web != null) tb.web.getSettings().setTextZoom(zoom);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        z.addView(sliderRow("Zoom do texto", zv, sb));
        settingsBox.addView(z);
    }

    private void buildBlockingPage() {
        LinearLayout g = group();
        addRow(g, switchSettingRow(R.drawable.ic_block, 0xFFE5484D, "Bloquear pop-ups",
                "Impede janelas abertas sem você tocar", store.popup(), on -> store.setPopup(on)));
        addRow(g, switchSettingRow(R.drawable.ic_refresh, 0xFFE5484D, "Bloquear redirecionamentos",
                "Evita ser levado a outro site sem querer", store.redirect(), on -> store.setRedirect(on)));
        addRow(g, switchSettingRow(R.drawable.ic_shield, 0xFFE5484D, "Bloquear anúncios",
                "Listas EasyList/EasyPrivacy e filtros visuais", store.ads(), on -> store.setAds(on)));
        settingsBox.addView(g);

        LinearLayout n = group();
        final TextView sv = text(secondsText(store.noticeSeconds()), 12, ACCENT, true);
        SeekBar sb = styledSeek(15, store.noticeSeconds(), new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                store.setNoticeSeconds(progress);
                sv.setText(secondsText(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        n.addView(sliderRow("Tempo do aviso de bloqueio", sv, sb));
        settingsBox.addView(n);

        LinearLayout l = group();
        addRow(l, settingsRow(R.drawable.ic_shield, 0xFF3FB56C, "Regras carregadas",
                filters.size() + " regras ativas", false, null));
        addRow(l, settingsRow(R.drawable.ic_refresh, 0xFFE5484D, "Atualizar listas agora",
                "Baixa EasyList e EasyPrivacy", false, v -> {
                    Toast.makeText(this, "Atualizando listas…", Toast.LENGTH_SHORT).show();
                    io.execute(() -> {
                        boolean ok = filters.download(getApplicationContext());
                        if (ok) store.setFilterTime(System.currentTimeMillis());
                        ui.post(() -> {
                            Toast.makeText(this, ok ? "Listas atualizadas" : "Sem conexão com as listas",
                                    Toast.LENGTH_SHORT).show();
                            if (screen == SCREEN_SETTINGS && "blocking".equals(settingsPage)) buildSettings();
                        });
                    });
                }));
        settingsBox.addView(l);
    }

    private void buildPlaybackPage() {
        final String[] values = {"high", "medium", "low"};
        final String[] labels = {"Maior disponível (até 4K, conforme a fonte)", "Média (até 720p)", "Baixa (até 480p)"};
        int sel = "medium".equals(store.quality()) ? 1 : ("low".equals(store.quality()) ? 2 : 0);
        LinearLayout g = group();
        addRow(g, switchSettingRow(R.drawable.ic_bolt, 0xFF3FB56C, "Reproduzir ao detectar",
                "Abre o player assim que um vídeo é encontrado", store.autoPlay(), on -> store.setAutoPlay(on)));
        addRow(g, settingsRow(R.drawable.ic_hd, 0xFF3FB56C, "Qualidade preferida",
                qualityLabel(store.quality()), true,
                v -> showChoiceSheet("Qualidade de reprodução", labels, sel, i -> {
                    store.setQuality(values[i]);
                    buildSettings();
                })));
        settingsBox.addView(g);
        settingsBox.addView(hint("Maior tenta usar a maior qualidade disponível; média limita a 720p e baixa a 480p em fontes adaptativas (HLS/DASH)."));
    }

    private void buildCastPage() {
        LinearLayout g = group();
        addRow(g, switchSettingRow(R.drawable.ic_cast, 0xFF27B3CC, "Usar proxy do app",
                "A TV não envia Referer nem cookies; o proxy faz isso por ela.", store.castProxy(),
                on -> store.setCastProxy(on)));
        addRow(g, settingsRow(R.drawable.ic_battery, 0xFF27B3CC, "Bateria e tela apagada",
                "Defina “Sem restrições” nas informações do app", true, v -> {
                    try {
                        Intent st = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(st);
                    } catch (Exception e) {
                        Toast.makeText(this, "Abra as informações do Castor nas configurações do Android.",
                                Toast.LENGTH_LONG).show();
                    }
                }));
        settingsBox.addView(g);

        settingsBox.addView(sectionLabel("Servidor Castor remoto (opcional)"));
        LinearLayout r = group();
        r.setPadding(dp(14), dp(14), dp(14), dp(14));
        final EditText ru = settingsEdit("http://192.168.0.10:8411", store.remoteUrl(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText rt = settingsEdit("Token (api.token)", store.remoteToken(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        r.addView(ru, new LinearLayout.LayoutParams(MATCH, dp(48)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, dp(48));
        tlp.topMargin = dp(8);
        r.addView(rt, tlp);
        LinearLayout save = primaryButton(R.drawable.ic_check, "Salvar servidor", v -> {
            store.setRemote(ru.getText().toString(), rt.getText().toString());
            hideKeyboard();
            Toast.makeText(this, "Servidor salvo", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(MATCH, dp(46));
        svp.topMargin = dp(10);
        r.addView(save, svp);
        settingsBox.addView(r);
        settingsBox.addView(hint("Rode “castor api-server”, “castor media-server” e “castor scraping-server” num PC, NAS ou Docker da mesma rede. O servidor converte o vídeo e queima legendas."));
    }

    private void buildDataPage() {
        LinearLayout g = group();
        addRow(g, settingsRow(R.drawable.ic_delete, 0xFFEE8A2B, "Limpar cookies e dados de sites",
                "Desconecta você dos sites", false, v -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    Tab ct = cur();
                    if (ct != null && ct.web != null) ct.web.clearCache(true);
                    Toast.makeText(this, "Dados limpos", Toast.LENGTH_SHORT).show();
                }));
        addRow(g, settingsRow(R.drawable.ic_history, 0xFFEE8A2B, "Limpar histórico",
                "Apaga as páginas visitadas", false, v -> {
                    store.clearHistory();
                    Toast.makeText(this, "Histórico limpo", Toast.LENGTH_SHORT).show();
                }));
        addRow(g, settingsRow(R.drawable.ic_star, 0xFFEE8A2B, "Limpar favoritos",
                store.bookmarks().size() + " páginas guardadas", false, v ->
                        new AlertDialog.Builder(this, ThemePalette.dialogTheme(this))
                                .setTitle("Limpar favoritos?")
                                .setMessage("Todas as páginas favoritas serão removidas.")
                                .setPositiveButton("Limpar", (d, w) -> {
                                    store.clearBookmarks();
                                    buildSettings();
                                })
                                .setNegativeButton("Cancelar", null)
                                .show()));
        addRow(g, settingsRow(R.drawable.ic_queue, 0xFFEE8A2B, "Limpar fila",
                "Remove os itens da fila de reprodução", false, v -> {
                    store.clearQueue();
                    Toast.makeText(this, "Fila limpa", Toast.LENGTH_SHORT).show();
                }));
        settingsBox.addView(g);

        LinearLayout g2 = group();
        addRow(g2, settingsRow(R.drawable.ic_image, 0xFFEE8A2B, "Ícone de atalho personalizado",
                "Cria um atalho na tela inicial com a sua imagem", true, v -> chooseCustomIconImage()));
        settingsBox.addView(g2);
        settingsBox.addView(hint("O Android permite criar um atalho com a imagem escolhida; o ícone do app instalado não pode ser trocado dinamicamente em todos os launchers."));
    }

    private void buildAboutPage() {
        settingsBox.addView(settingsHero());
        LinearLayout g = group();
        addRow(g, settingsRow(R.drawable.ic_info, 0xFF8E8E98, "Castor " + APP_VERSION,
                "Navegador com detecção de vídeo, fila e cast para TV", false, null));
        addRow(g, settingsRow(R.drawable.ic_cast, 0xFF27B3CC, "Cast sem Google SDK",
                "DLNA/UPnP e Chromecast pela rede Wi-Fi", false, null));
        addRow(g, settingsRow(R.drawable.ic_shield, 0xFFE5484D, "Sem DRM",
                "Não contorna proteção DRM (Netflix, Prime Video etc.)", false, null));
        settingsBox.addView(g);
    }

    private String qualityLabel(String value) {
        if ("medium".equals(value)) return "Média (até 720p)";
        if ("low".equals(value)) return "Baixa (até 480p)";
        return "Maior disponível";
    }

    // ---- componentes de configurações

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(roundRect(PANEL, dp(24), null));
        g.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(14);
        g.setLayoutParams(lp);
        return g;
    }

    private void addRow(LinearLayout g, View row) {
        if (g.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(OUTLINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, 1);
            lp.leftMargin = dp(16);
            lp.rightMargin = dp(16);
            g.addView(d, lp);
        }
        g.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private LinearLayout settingsRow(int iconRes, int color, String title, String sub, boolean chevron,
                                     View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (click != null) rippleBg(row, null);
        if (iconRes != 0) row.addView(iconChip(iconRes, color, dp(38)), new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(iconRes != 0 ? dp(14) : dp(2), 0, dp(8), 0);
        col.addView(text(title, 15, TEXT, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 12, MUTED, false);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (chevron) row.addView(icon(R.drawable.ic_chevron, MUTED), new LinearLayout.LayoutParams(dp(20), dp(20)));
        if (click != null) row.setOnClickListener(click);
        return row;
    }

    private View switchSettingRow(int iconRes, int color, String title, String sub, boolean checked,
                                  final BoolSetter setter) {
        final Switch sw = new Switch(this);
        styleSwitch(sw);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> setter.set(on));
        LinearLayout row = settingsRow(iconRes, color, title, sub, false, null);
        row.addView(sw);
        rippleBg(row, null);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private void styleSwitch(Switch sw) {
        int[][] states = {{android.R.attr.state_checked}, new int[0]};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ON_ACCENT, MUTED}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, OUTLINE}));
    }

    private SeekBar styledSeek(int max, int progress, SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, progress)));
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));
        sb.setOnSeekBarChangeListener(l);
        return sb;
    }

    private View sliderRow(String title, TextView valueView, SeekBar sb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(title, 15, TEXT, false), new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(valueView);
        row.addView(top);
        row.addView(sb, new LinearLayout.LayoutParams(MATCH, WRAP));
        return row;
    }

    private EditText settingsEdit(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setInputType(inputType);
        e.setBackground(roundRect(PANEL_LIGHT, dp(16), null));
        e.setPadding(dp(14), 0, dp(14), 0);
        return e;
    }

    private TextView sectionLabel(String label) {
        TextView t = text(label, 13, MUTED, true);
        t.setPadding(dp(6), dp(6), 0, dp(8));
        return t;
    }

    private TextView hint(String label) {
        TextView t = text(label, 12, MUTED, false);
        t.setPadding(dp(6), dp(2), dp(6), dp(14));
        return t;
    }

    private void chooseCustomIconImage() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQUEST_CUSTOM_ICON_IMAGE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nenhum seletor de imagens disponível", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUSTOM_ICON_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 19 && (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) { }
            }
            store.setCustomIconUri(uri.toString());
            createPinnedShortcutWithImage(uri);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível usar essa imagem: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void createPinnedShortcutWithImage(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Atalho com ícone personalizado requer Android 8 ou superior", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "Seu launcher não permite fixar atalhos personalizados", Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap bitmap = loadSquareIcon(uri);
        if (bitmap == null) throw new IOException("arquivo de imagem inválido");
        Intent launch = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "castor-custom-icon")
                .setShortLabel("Castor")
                .setLongLabel("Castor · ícone personalizado")
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(launch)
                .build();
        boolean requested = sm.requestPinShortcut(info, null);
        Toast.makeText(this, requested ? "Pedido para adicionar o atalho com sua imagem. Confirme na tela inicial." : "O launcher recusou o atalho personalizado.", Toast.LENGTH_LONG).show();
    }

    private Bitmap loadSquareIcon(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap source;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            source = BitmapFactory.decodeStream(in, null, opts);
        }
        if (source == null) return null;
        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        Bitmap square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(square);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, new Rect(left, top, left + side, top + side), new Rect(0, 0, 512, 512), paint);
        source.recycle();
        return square;
    }

    private String hex(int c) {
        return String.format(Locale.ROOT, "#%06X", (0xFFFFFF & c));
    }

    private void applyHomePageTheme(WebView view) {
        String css = ":root{--bg:" + hex(BG) + ";--surface:" + hex(PANEL) + ";--surface2:" + hex(PANEL_LIGHT)
                + ";--text:" + hex(TEXT) + ";--muted:" + hex(MUTED) + ";--accent:" + hex(ACCENT)
                + ";--on-accent:" + hex(ON_ACCENT) + ";--soft:" + hex(ACCENT_SOFT) + ";--outline:" + hex(OUTLINE)
                + ";color-scheme:" + (ThemePalette.LIGHT ? "light" : "dark") + "}";
        JSONArray favs = new JSONArray();
        for (String[] b : store.bookmarks()) {
            JSONArray one = new JSONArray();
            one.put(b[0]);
            one.put(b[1]);
            favs.put(one);
        }
        String js = "(function(){var s=document.createElement('style');s.textContent=" + JSONObject.quote(css)
                + ";document.head.appendChild(s);window.CASTOR_SEARCH=" + JSONObject.quote(store.searchUrl())
                + ";window.CASTOR_FAVS=" + favs.toString()
                + ";if(window.CASTOR_READY)window.CASTOR_READY();})();";
        view.evaluateJavascript(js, null);
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
        return store.searchUrl() + Uri.encode(s);
    }

    private void updateAddress() {
        Tab t = cur();
        if (t == null || urlField == null) return;
        updateOmnibox(t);
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
        c.setBackground(roundRect(PANEL, dp(24), null));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(10);
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
        b.setBackground(pillBg(background));
        b.setStateListAnimator(null);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    /** Retângulo arredondado; o raio diminui quando "Cantos arredondados" está desligado. */
    private GradientDrawable roundRect(int color, int radius, Integer stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(rounded ? radius : Math.max(dp(4), Math.round(radius * 0.4f)));
        if (stroke != null) g.setStroke(Math.max(1, dp(1.5f)), stroke);
        return g;
    }

    private GradientDrawable pillBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(100));
        return g;
    }

    private GradientDrawable ovalBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Aplica fundo com efeito de toque (ripple). */
    private void rippleBg(View v, Drawable bg) {
        Drawable mask = null;
        if (bg != null) {
            Drawable.ConstantState cs = bg.getConstantState();
            if (cs != null) mask = cs.newDrawable();
        }
        v.setBackground(new RippleDrawable(
                ColorStateList.valueOf(ThemePalette.withAlpha(TEXT, 34)), bg, mask));
    }

    private FrameLayout iconChip(int iconRes, int tint, int size) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(roundRect(ThemePalette.blend(PANEL, tint, ThemePalette.LIGHT ? 0.16f : 0.22f), dp(12), null));
        int pad = Math.round(size * 0.24f);
        f.addView(icon(iconRes, tint), new FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER));
        return f;
    }

    private TextView letterChip(String label, boolean selected) {
        TextView t = text(label, 15, selected ? ON_ACCENT : ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(roundRect(selected ? ACCENT : ACCENT_SOFT, dp(14), null));
        return t;
    }

    private String firstLetter(String s) {
        if (s == null) return "?";
        String t = s.trim();
        if (t.isEmpty()) return "?";
        return t.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private TextView chip(String label, int color) {
        TextView t = text(label, 11, color, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(9), dp(3), dp(9), dp(3));
        t.setBackground(pillBg(ThemePalette.blend(PANEL, color, ThemePalette.LIGHT ? 0.16f : 0.24f)));
        return t;
    }

    private LinearLayout pillButton(int iconRes, String label, int bg, int fg, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(18), 0);
        rippleBg(b, pillBg(bg));
        if (iconRes != 0) b.addView(icon(iconRes, fg), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView tv = text(label, 14, fg, true);
        tv.setSingleLine(true);
        tv.setPadding(iconRes != 0 ? dp(8) : 0, 0, 0, 0);
        b.addView(tv);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private LinearLayout primaryButton(int iconRes, String label, View.OnClickListener click) {
        return pillButton(iconRes, label, ACCENT, ON_ACCENT, click);
    }

    private LinearLayout softButton(int iconRes, String label, View.OnClickListener click) {
        return pillButton(iconRes, label, ACCENT_SOFT, ACCENT, click);
    }

    private LinearLayout tonalButton(int iconRes, String label, View.OnClickListener click) {
        return pillButton(iconRes, label, PANEL_LIGHT, TEXT, click);
    }

    private ImageView smallIcon(int res, int tint, View.OnClickListener click) {
        ImageView iv = icon(res, tint);
        iv.setPadding(dp(11), dp(11), dp(11), dp(11));
        iv.setOnClickListener(click);
        rippleBg(iv, null);
        return iv;
    }

    private ImageView roundIconBtn(int res, View.OnClickListener click) {
        ImageView iv = icon(res, TEXT);
        iv.setPadding(dp(11), dp(11), dp(11), dp(11));
        rippleBg(iv, ovalBg(PANEL_LIGHT));
        iv.setOnClickListener(click);
        return iv;
    }

    private View screenHeader(String title, String subtitle, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(14));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 26, TEXT, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = text(subtitle, 13, MUTED, false);
            st.setSingleLine(true);
            st.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(st);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    private LinearLayout segmented(String[] labels, int selected, final IndexPick pick) {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(pillBg(PANEL));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            TextView tv = text(labels[i], 13, on ? ON_ACCENT : MUTED, true);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            if (on) tv.setBackground(pillBg(ACCENT));
            tv.setOnClickListener(v -> pick.pick(idx));
            seg.addView(tv, new LinearLayout.LayoutParams(0, dp(40), 1f));
        }
        return seg;
    }

    private View emptyState(int iconRes, String title, String body, String buttonLabel, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(40), dp(24), dp(24));
        FrameLayout circle = new FrameLayout(this);
        circle.setBackground(ovalBg(ACCENT_SOFT));
        circle.addView(icon(iconRes, ACCENT), new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        box.addView(circle, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView t = text(title, 18, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, dp(6));
        box.addView(t);
        TextView b = text(body, 14, MUTED, false);
        b.setGravity(Gravity.CENTER);
        box.addView(b);
        if (buttonLabel != null && click != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, dp(46));
            lp.topMargin = dp(18);
            box.addView(primaryButton(R.drawable.ic_search, buttonLabel, click), lp);
        }
        return box;
    }

    private LinearLayout listRow(String leading, String title, String sub, View.OnClickListener click,
                                 View... trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(6), dp(10));
        rippleBg(row, roundRect(PANEL, dp(20), null));
        row.addView(letterChip(leading, false), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(title, 14, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView s = text(sub, 11, MUTED, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        for (View tv : trailing) row.addView(tv, new LinearLayout.LayoutParams(dp(42), dp(42)));
        if (click != null) row.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
