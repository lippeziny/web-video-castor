package com.castor.mobile;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Player interno com fila (ExoPlayer/Media3): MP4, HLS (m3u8), DASH (mpd) e outros. */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_INDEX = "index";

    private ExoPlayer player;
    private PlayerView playerView;
    private List<com.castor.mobile.MediaItem> items;
    private Store store;
    private TextView castBtn;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> resumed = new HashSet<>();
    private int lastIndex = -1;
    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            saveCurrent();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(0xFF000000);
        playerView.setShowNextButton(true);
        playerView.setShowPreviousButton(true);
        store = new Store(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        castBtn = new TextView(this);
        castBtn.setText("Cast");
        castBtn.setTextColor(Color.WHITE);
        castBtn.setTextSize(14);
        castBtn.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        castBtn.setPadding((int) (14 * d), (int) (8 * d), (int) (14 * d), (int) (8 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(20 * d);
        castBtn.setBackground(bg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.topMargin = (int) (40 * d);
        clp.rightMargin = (int) (12 * d);
        root.addView(castBtn, clp);
        castBtn.setOnClickListener(v -> {
            if (player == null) return;
            int idx = player.getCurrentMediaItemIndex();
            if (idx < 0 || idx >= items.size()) return;
            saveCurrent();
            player.pause();
            CastUi.show(this, items.get(idx), player.getCurrentPosition());
        });
        setContentView(root);
        hideSystemUi();

        Intent intent = getIntent();
        items = Store.queueFromJson(intent.getStringExtra(EXTRA_ITEMS));
        int start = intent.getIntExtra(EXTRA_INDEX, 0);
        if (items.isEmpty()) {
            Toast.makeText(this, "Nada para reproduzir", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (start < 0 || start >= items.size()) start = 0;

        // Cabeçalhos: muitos servidores de vídeo exigem Referer/User-Agent da página original.
        final com.castor.mobile.MediaItem first = items.get(start);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setUserAgent(MainActivity.USER_AGENT_MOBILE);

        // Cada item pode ter seu próprio Referer: usamos uma fábrica que escolhe por URL.
        final Map<String, String> refererByUrl = new HashMap<>();
        for (com.castor.mobile.MediaItem m : items) refererByUrl.put(m.url, m.referer);
        androidx.media3.datasource.DataSource.Factory perItem = () -> {
            DefaultHttpDataSource ds = http.createDataSource();
            return new androidx.media3.datasource.ResolvingDataSource(ds, (dataSpec) -> {
                String ref = refererByUrl.get(dataSpec.uri.toString());
                if (ref == null) ref = first.referer;
                if (ref != null && !ref.isEmpty()) {
                    ds.setRequestProperty("Referer", ref);
                    try {
                        java.net.URI r = new java.net.URI(ref);
                        ds.setRequestProperty("Origin", r.getScheme() + "://" + r.getHost());
                    } catch (Exception ignored) {
                    }
                }
                return dataSpec;
            });
        };

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(perItem))
                .build();
        playerView.setPlayer(player);

        for (com.castor.mobile.MediaItem m : items) {
            MediaItem.Builder b = new MediaItem.Builder()
                    .setUri(m.url)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(m.title).build());
            player.addMediaItem(b.build());
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                if (lastIndex >= 0 && lastIndex < items.size() && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // terminou o item anterior: não precisa lembrar onde parou
                    store.clearPosition(keyOf(items.get(lastIndex)));
                }
                lastIndex = player.getCurrentMediaItemIndex();
                if (!resumed.contains(lastIndex)) {
                    resumed.add(lastIndex);
                    long pos = savedPosition(lastIndex);
                    if (pos > 0) {
                        player.seekTo(lastIndex, pos);
                        toastResume(pos);
                    }
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && lastIndex >= 0 && lastIndex < items.size()) {
                    store.clearPosition(keyOf(items.get(lastIndex)));
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this,
                        "Erro ao reproduzir: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                }
            }
        });
        // continua de onde parou: a posição é guardada pelo endereço da página, não do vídeo
        long resume = savedPosition(start);
        resumed.add(start);
        lastIndex = start;
        player.seekTo(start, Math.max(0, resume));
        if (resume > 0) toastResume(resume);
        player.prepare();
        player.play();
        handler.postDelayed(saver, 5000);
    }

    private static String keyOf(com.castor.mobile.MediaItem m) {
        return m.pageUrl.isEmpty() ? m.url : m.pageUrl;
    }

    private long savedPosition(int index) {
        if (index < 0 || index >= items.size()) return 0;
        long[] saved = store.getPosition(keyOf(items.get(index)));
        return saved == null ? 0 : Math.max(0, saved[0] - 2000); // volta 2 s para se situar
    }

    private void toastResume(long pos) {
        long s = pos / 1000;
        String t = s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        Toast.makeText(this, "Continuando de " + t, Toast.LENGTH_SHORT).show();
    }

    /** Guarda a posição atual, desde que seja um vídeo longo, não ao vivo e que não tenha acabado. */
    private void saveCurrent() {
        if (player == null || items == null) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= items.size()) return;
        if (player.isCurrentMediaItemLive()) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur == androidx.media3.common.C.TIME_UNSET || dur < 60000) return;
        String key = keyOf(items.get(idx));
        if (pos >= dur - 15000) {
            store.clearPosition(key);
        } else if (pos >= 5000) {
            store.setPosition(key, pos, dur);
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && player != null && player.isPlaying()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        if (castBtn != null) castBtn.setVisibility(inPip ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrent();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveCurrent();
        if (player != null && !isInPictureInPictureMode()) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        saveCurrent();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
