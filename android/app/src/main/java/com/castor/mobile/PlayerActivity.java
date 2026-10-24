package com.castor.mobile;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;

/** Interface do player. A reprodução real fica no MediaSessionService e continua fora desta tela. */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_INDEX = "index";

    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private PlayerView playerView;
    private List<com.castor.mobile.MediaItem> items;
    private Store store;
    private TextView castBtn;
    private Player.Listener playbackListener;
    private int initialIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(this);
        items = Store.queueFromJson(getIntent().getStringExtra(EXTRA_ITEMS));
        initialIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (items.isEmpty()) {
            Toast.makeText(this, "Nada para reproduzir", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (initialIndex < 0 || initialIndex >= items.size()) initialIndex = 0;

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setShowNextButton(true);
        playerView.setShowPreviousButton(true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
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
        connectToPlaybackService();
    }

    private Intent playbackServiceIntent() {
        return new Intent(this, PlaybackService.class)
                .putExtra(EXTRA_ITEMS, Store.queueToJson(items))
                .putExtra(EXTRA_INDEX, initialIndex);
    }

    private void connectToPlaybackService() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                if (isFinishing() || isDestroyed()) return;
                player = controllerFuture.get();
                playerView.setPlayer(player);
                playbackListener = new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException error) {
                        Toast.makeText(PlayerActivity.this,
                                "Erro ao reproduzir: " + error.getErrorCodeName(), Toast.LENGTH_LONG).show();
                    }
                };
                player.addListener(playbackListener);
                if (player.getMediaItemCount() == 0) {
                    // Recuperação caso o Android tenha criado o serviço somente quando a tela conectou.
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(playbackServiceIntent());
                        else startService(playbackServiceIntent());
                    } catch (Exception e) {
                        Toast.makeText(this, "Não foi possível iniciar o player em segundo plano", Toast.LENGTH_LONG).show();
                    }
                } else if (player.getCurrentMediaItemIndex() == initialIndex) {
                    long[] saved = store.getPosition(keyOf(items.get(initialIndex)));
                    if (saved != null && saved[0] > 0 && player.getCurrentPosition() < 10000) {
                        toastResume(Math.max(0, saved[0] - 2000));
                    }
                }
            } catch (Exception e) {
                Toast.makeText(PlayerActivity.this, "Falha ao conectar o player: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, Runnable::run);
    }

    private static String keyOf(com.castor.mobile.MediaItem item) {
        return item.pageUrl.isEmpty() ? item.url : item.pageUrl;
    }

    private void toastResume(long pos) {
        long s = pos / 1000;
        String t = s >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        Toast.makeText(this, "Continuando de " + t, Toast.LENGTH_SHORT).show();
    }

    private void saveCurrent() {
        if (player == null || items == null) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= items.size() || player.isCurrentMediaItemLive()) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur == androidx.media3.common.C.TIME_UNSET || dur < 60000) return;
        String key = keyOf(items.get(idx));
        if (pos >= dur - 15000) store.clearPosition(key);
        else if (pos >= 5000) store.setPosition(key, pos, dur);
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            CastManager cm = CastManager.get(this);
            if (cm.isActive() && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                if (cm.adjustVolume(1)) return true;
                Toast.makeText(this, "Esta TV/servidor não permite controlar o volume por aqui", Toast.LENGTH_SHORT).show();
            } else if (cm.isActive() && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (cm.adjustVolume(-1)) return true;
                Toast.makeText(this, "Esta TV/servidor não permite controlar o volume por aqui", Toast.LENGTH_SHORT).show();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && player != null && player.isPlaying()) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) { }
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
        // Não pausar nem liberar o player: o serviço continua reproduzindo com a tela apagada.
    }

    @Override
    protected void onDestroy() {
        saveCurrent();
        if (player != null && playbackListener != null) player.removeListener(playbackListener);
        if (playerView != null) playerView.setPlayer(null);
        player = null;
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        super.onDestroy();
    }
}
