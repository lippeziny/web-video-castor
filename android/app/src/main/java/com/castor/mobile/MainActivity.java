package com.castor.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Thin Android client for the official Castor web interface.
 * Media extraction and transcoding remain on the configured Castor server.
 */
public final class MainActivity extends Activity {
    private static final String PREFS = "castor_client";
    private static final String KEY_SERVER_URL = "server_url";
    private static final int BG = Color.rgb(23, 25, 21);
    private static final int PANEL = Color.rgb(36, 39, 32);
    private static final int TEXT = Color.rgb(255, 250, 242);
    private static final int MUTED = Color.rgb(179, 180, 166);
    private static final int PEACH = Color.rgb(242, 174, 134);

    private SharedPreferences preferences;
    private LinearLayout setupPanel;
    private EditText serverInput;
    private TextView statusText;
    private ProgressBar progress;
    private WebView webView;
    private String serverUrl;
    private String sessionPassword;
    private boolean triedSessionPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = preferences.getString(KEY_SERVER_URL, "");

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(18, 19, 15));
        window.setNavigationBarColor(Color.rgb(18, 19, 15));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        buildLayout();
        if (!TextUtils.isEmpty(serverUrl)) {
            serverInput.setText(serverUrl);
            connect(serverUrl);
        } else {
            setupPanel.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
            statusText.setText("Informe o endereço da interface Castor na sua rede.");
        }
    }

    private void buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(10), dp(16), dp(8));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("castor", 26, TEXT, true);
        TextView subtitle = text("ANDROID CLIENT", 10, PEACH, true);
        subtitle.setLetterSpacing(0.14f);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button settingsButton = button("Servidor", PANEL, TEXT);
        settingsButton.setOnClickListener(v -> showSetup());
        header.addView(settingsButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));

        Button refreshButton = button("Atualizar", PEACH, Color.rgb(29, 27, 22));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        refreshParams.leftMargin = dp(8);
        header.addView(refreshButton, refreshParams);
        refreshButton.setOnClickListener(v -> {
            if (webView.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(serverUrl)) {
                webView.reload();
            } else {
                showSetup();
            }
        });
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setupPanel = new LinearLayout(this);
        setupPanel.setOrientation(LinearLayout.VERTICAL);
        setupPanel.setPadding(dp(16), dp(16), dp(16), dp(16));
        setupPanel.setBackground(roundRect(PANEL, dp(18), null));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.topMargin = dp(14);

        setupPanel.addView(text("Conectar ao Castor", 20, TEXT, true));
        TextView description = text(
                "O app usa a interface original do Castor. O servidor Castor precisa estar ligado e acessível pela rede.",
                14, MUTED, false);
        description.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = dp(8);
        setupPanel.addView(description, descriptionParams);

        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setTextSize(15);
        serverInput.setTextColor(TEXT);
        serverInput.setHintTextColor(MUTED);
        serverInput.setHint("http://192.168.1.20:3000");
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setSelectAllOnFocus(true);
        serverInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        serverInput.setBackground(roundRect(Color.rgb(27, 29, 25), dp(10), PEACH));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        inputParams.topMargin = dp(14);
        setupPanel.addView(serverInput, inputParams);

        Button connectButton = button("Conectar", PEACH, Color.rgb(29, 27, 22));
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        connectParams.topMargin = dp(10);
        setupPanel.addView(connectButton, connectParams);
        connectButton.setOnClickListener(v -> {
            String entered = serverInput.getText().toString().trim();
            String normalized = validateServerUrl(entered);
            if (normalized == null) {
                Toast.makeText(this, "Use um endereço HTTP/HTTPS válido, por exemplo http://192.168.1.20:3000", Toast.LENGTH_LONG).show();
                return;
            }
            connect(normalized);
        });

        statusText = text("", 12, MUTED, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        setupPanel.addView(statusText, statusParams);
        root.addView(setupPanel, panelParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(PEACH));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.topMargin = dp(8);
        root.addView(progress, progressParams);

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webSettings.setSupportMultipleWindows(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webSettings.setSafeBrowsingEnabled(true);
        }
        webView.setBackgroundColor(BG);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
                if (sessionPassword != null && !triedSessionPassword) {
                    triedSessionPassword = true;
                    handler.proceed("castor", sessionPassword);
                    return;
                }
                promptForPassword(handler);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    statusText.setText("Não foi possível abrir o servidor. Verifique o endereço e a conexão.");
                    Toast.makeText(MainActivity.this, "Falha de conexão com o Castor", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Never bypass certificate errors.
                handler.cancel();
                Toast.makeText(MainActivity.this, "Certificado HTTPS inválido; conexão bloqueada.", Toast.LENGTH_LONG).show();
            }
        });
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void connect(String url) {
        String normalized = validateServerUrl(url);
        if (normalized == null) {
            showSetup();
            Toast.makeText(this, "Endereço do servidor inválido.", Toast.LENGTH_LONG).show();
            return;
        }
        serverUrl = normalized;
        preferences.edit().putString(KEY_SERVER_URL, serverUrl).apply();
        serverInput.setText(serverUrl);
        statusText.setText("Conectando a " + Uri.parse(serverUrl).getHost() + "…");
        setupPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        triedSessionPassword = false;
        webView.loadUrl(serverUrl);
    }

    private void showSetup() {
        setupPanel.setVisibility(View.VISIBLE);
        if (!TextUtils.isEmpty(serverUrl)) {
            serverInput.setText(serverUrl);
        }
        if (TextUtils.isEmpty(serverUrl)) {
            webView.setVisibility(View.GONE);
        }
    }

    private boolean handleNavigation(String url) {
        Uri candidate = Uri.parse(url);
        Uri base = TextUtils.isEmpty(serverUrl) ? null : Uri.parse(serverUrl);
        if (base != null && sameOrigin(base, candidate)) {
            return false;
        }
        String scheme = candidate.getScheme();
        if (scheme == null) return true;
        try {
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    || "mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
                startActivity(new Intent(Intent.ACTION_VIEW, candidate));
            }
        } catch (Exception ignored) {
            Toast.makeText(this, "Não foi possível abrir esse link.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean sameOrigin(Uri a, Uri b) {
        return a.getScheme() != null && b.getScheme() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && b.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private int effectivePort(Uri uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String validateServerUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        Uri uri = Uri.parse(raw.trim());
        String scheme = uri.getScheme();
        if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null) return null;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        return raw.trim().replaceAll("/+$", "");
    }

    private void promptForPassword(HttpAuthHandler handler) {
        EditText passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setTextColor(TEXT);
        passwordInput.setHintTextColor(MUTED);
        passwordInput.setHint("Senha do site Castor");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        passwordInput.setBackground(roundRect(PANEL, dp(10), PEACH));
        if (sessionPassword != null) passwordInput.setText(sessionPassword);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Entrar no Castor")
                .setMessage("Digite a senha web configurada no servidor. O nome de usuário é castor.")
                .setView(passwordInput)
                .setPositiveButton("Entrar", (d, which) -> {
                    String password = passwordInput.getText().toString();
                    if (TextUtils.isEmpty(password)) {
                        handler.cancel();
                        return;
                    }
                    sessionPassword = password;
                    triedSessionPassword = true;
                    handler.proceed("castor", password);
                })
                .setNegativeButton("Cancelar", (d, which) -> handler.cancel())
                .create();
        dialog.setOnCancelListener(d -> handler.cancel());
        dialog.show();
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
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
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (setupPanel != null && setupPanel.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(serverUrl)) {
            setupPanel.setVisibility(View.GONE);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
