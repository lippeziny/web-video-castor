package com.castor.mobile;

import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;

/**
 * REX-Player-inspired glass control surface over the app's persistent Media3 playback session.
 * The same control language is used for TV casting; a TV itself decodes the remote stream.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_INDEX = "index";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private PlayerView playerView;
    private TextView remotePlaceholder;
    private boolean remoteMode;
    private List<com.castor.mobile.MediaItem> items;
    private Store store;
    private TextView castBtn;
    private TextView titleText;
    private TextView timeText;
    private TextView playBtn;
    private TextView seekFeedback;
    private View controls;
    private SeekBar seekBar;
    private Player.Listener playbackListener;
    private int initialIndex;
    private boolean dragging;
    private boolean controlsVisible = true;
    private long lastSavedAt;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateRemoteMode();
            updateProgressUi();
            if (!isTvRemoteMode() && player != null && player.isPlaying()
                    && System.currentTimeMillis() - lastSavedAt >= 5000L) {
                saveCurrent();
                lastSavedAt = System.currentTimeMillis();
            }
            handler.postDelayed(this, 500L);
        }
    };
    private final Runnable hideControls = () -> {
        if (isPlaybackActiveForUi() && !dragging && !isInPictureInPictureMode()) {
            setControlsVisible(false);
        }
    };
    private final Runnable hideSeekFeedback = () -> {
        if (seekFeedback != null) seekFeedback.animate().alpha(0f).setDuration(160L)
                .withEndAction(() -> seekFeedback.setVisibility(View.GONE)).start();
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(this);
        ThemePalette.apply(this);
        items = Store.queueFromJson(getIntent().getStringExtra(EXTRA_ITEMS));
        initialIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (items.isEmpty()) {
            Toast.makeText(this, "Nada para reproduzir", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (initialIndex < 0 || initialIndex >= items.size()) initialIndex = 0;
        buildPlayerUi();
        hideSystemUi();
        connectToPlaybackService();
        handler.post(ticker);
    }

    private void buildPlayerUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setKeepContentOnPlayerReset(true);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        remotePlaceholder = new TextView(this);
        remotePlaceholder.setGravity(Gravity.CENTER);
        remotePlaceholder.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        remotePlaceholder.setTextColor(Color.WHITE);
        remotePlaceholder.setTextSize(16);
        remotePlaceholder.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        remotePlaceholder.setPadding(dp(28), dp(24), dp(28), dp(24));
        remotePlaceholder.setBackground(glass(0xB91A1B1E, dp(28)));
        remotePlaceholder.setText("▣\nPREPARANDO A TV…");
        remotePlaceholder.setVisibility(View.GONE);
        FrameLayout.LayoutParams remoteLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        remoteLp.setMargins(dp(30), dp(100), dp(30), dp(140));
        root.addView(remotePlaceholder, remoteLp);

        controls = new FrameLayout(this);
        root.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(8), dp(8));
        top.setBackground(glass(0xC51A1B1E, dp(22)));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topLp.setMargins(dp(12), dp(22), dp(12), 0);
        controls.addView(top, topLp);

        TextView back = pill("‹", 30, ThemePalette.TEXT, 0x662A2C2F);
        top.addView(back, new LinearLayout.LayoutParams(dp(44), dp(42)));
        back.setOnClickListener(v -> { saveCurrent(); finish(); });

        titleText = new TextView(this);
        titleText.setText(items.get(initialIndex).title);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(14);
        titleText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleText.setMaxLines(1);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        titleLp.leftMargin = dp(10);
        titleLp.rightMargin = dp(8);
        top.addView(titleText, titleLp);

        castBtn = pill("TV  ↗", 13, Color.WHITE, 0xDCA85F43);
        top.addView(castBtn, new LinearLayout.LayoutParams(dp(74), dp(42)));
        castBtn.setOnClickListener(v -> openCast());

        seekFeedback = new TextView(this);
        seekFeedback.setTextColor(Color.WHITE);
        seekFeedback.setTextSize(18);
        seekFeedback.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        seekFeedback.setGravity(Gravity.CENTER);
        seekFeedback.setPadding(dp(22), dp(12), dp(22), dp(12));
        seekFeedback.setBackground(glass(0xD91C1D20, dp(28)));
        seekFeedback.setVisibility(View.GONE);
        seekFeedback.setAlpha(0f);
        FrameLayout.LayoutParams feedbackLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        controls.addView(seekFeedback, feedbackLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(18), dp(14), dp(18), dp(14));
        bottom.setBackground(glass(0xE3191A1D, dp(26)));
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottomLp.setMargins(dp(12), 0, dp(12), dp(20));
        controls.addView(bottom, bottomLp);

        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setOrientation(LinearLayout.HORIZONTAL);
        TextView currentLabel = new TextView(this);
        currentLabel.setText("CONTINUAR ASSISTINDO");
        currentLabel.setTextColor(ThemePalette.ACCENT);
        currentLabel.setTextSize(10);
        currentLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        times.addView(currentLabel, new LinearLayout.LayoutParams(0, dp(20), 1f));
        timeText = new TextView(this);
        timeText.setTextColor(0xFFF2F2F2);
        timeText.setTextSize(12);
        timeText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        times.addView(timeText);
        bottom.addView(times);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ThemePalette.ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ThemePalette.ACCENT));
        seekBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x77FFFFFF));
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        seekLp.topMargin = dp(3);
        bottom.addView(seekBar, seekLp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (isTvRemoteMode()) {
                    long duration = CastManager.get(PlayerActivity.this).lastDur();
                    if (duration > 0) timeText.setText(formatTime(duration * progress / 1000L)
                            + " / " + formatTime(duration));
                } else if (player != null) {
                    long duration = player.getDuration();
                    if (duration != C.TIME_UNSET && duration > 0) {
                        timeText.setText(formatTime(duration * progress / 1000L) + " / " + formatTime(duration));
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                handler.removeCallbacks(hideControls);
                setControlsVisible(true);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                long duration;
                if (isTvRemoteMode()) {
                    CastManager cm = CastManager.get(PlayerActivity.this);
                    duration = cm.lastDur();
                    if (duration > 0) {
                        long target = Math.max(0L, Math.min(duration - 1000L, duration * bar.getProgress() / 1000L));
                        cm.seekTo(target);
                        showSeekFeedback("↔  " + formatTime(target));
                    }
                } else if (player != null) {
                    duration = player.getDuration();
                    if (duration != C.TIME_UNSET && duration > 0) {
                        long target = Math.max(0L, Math.min(duration - 1000L, duration * bar.getProgress() / 1000L));
                        player.seekTo(target);
                        showSeekFeedback("↔  " + formatTime(target));
                    }
                }
                updateProgressUi();
                scheduleHideControls();
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        actionsLp.topMargin = dp(4);
        bottom.addView(actions, actionsLp);
        TextView rewind = pill("↶ 10", 15, Color.WHITE, 0x572C2D30);
        TextView pause = pill("Ⅱ", 24, Color.rgb(35, 31, 28), ThemePalette.ACCENT);
        playBtn = pause;
        TextView forward = pill("10 ↷", 15, Color.WHITE, 0x572C2D30);
        LinearLayout.LayoutParams leftRight = new LinearLayout.LayoutParams(0, dp(48), 1f);
        LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(dp(76), dp(52));
        center.leftMargin = dp(10); center.rightMargin = dp(10);
        actions.addView(rewind, leftRight);
        actions.addView(pause, center);
        actions.addView(forward, leftRight);
        rewind.setOnClickListener(v -> seekBy(-10_000L));
        forward.setOnClickListener(v -> seekBy(10_000L));
        pause.setOnClickListener(v -> toggleLocalPlayback());

        TextView hint = new TextView(this);
        hint.setText("Toque duas vezes nas laterais para pular 10 s · arraste a barra para buscar");
        hint.setTextColor(0xFFB8B8BA);
        hint.setTextSize(10);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18));
        hintLp.topMargin = dp(3);
        bottom.addView(hint, hintLp);

        android.view.GestureDetector gestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        setControlsVisible(!controlsVisible);
                        if (controlsVisible) scheduleHideControls();
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (playerView == null) return true;
                        float x = e.getX();
                        float w = isTvRemoteMode() && remotePlaceholder != null
                                ? remotePlaceholder.getWidth() : playerView.getWidth();
                        if (x < w * 0.38f) seekBy(-10_000L);
                        else if (x > w * 0.62f) seekBy(10_000L);
                        else toggleLocalPlayback();
                        return true;
                    }
                });
        playerView.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
        remotePlaceholder.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
        setContentView(root);
    }

    private void openCast() {
        CastManager cm = CastManager.get(this);
        if (cm.isActive()) {
            CastUi.show(this, null, 0L);
            return;
        }
        if (player == null) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= items.size()) return;
        saveCurrent();
        long position = Math.max(0L, player.getCurrentPosition());
        CastUi.show(this, items.get(idx), position);
    }

    private TextView pill(String text, int size, int foreground, int background) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(foreground);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(background, dp(22)));
        t.setMinHeight(dp(40));
        return t;
    }

    private GradientDrawable glass(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), 0x38FFFFFF);
        return d;
    }

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

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
                    @Override public void onIsPlayingChanged(boolean isPlaying) {
                        updatePlayButton();
                        if (isPlaying) scheduleHideControls(); else setControlsVisible(true);
                    }
                    @Override public void onMediaItemTransition(androidx.media3.common.MediaItem mediaItem, int reason) {
                        int idx = player.getCurrentMediaItemIndex();
                        if (idx >= 0 && idx < items.size()) titleText.setText(items.get(idx).title);
                        updateProgressUi();
                    }
                    @Override public void onPlaybackStateChanged(int state) {
                        updateProgressUi();
                    }
                    @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                                                  Player.PositionInfo newPosition, int reason) {
                        updateProgressUi();
                    }
                };
                player.addListener(playbackListener);
                boolean tvAlreadyPlaying = CastManager.get(PlayerActivity.this).isActive();
                if (!tvAlreadyPlaying && !queueMatchesPlayer()) {
                    long[] saved = store.getPosition(keyOf(items.get(initialIndex)));
                    if (saved == null && !items.get(initialIndex).pageUrl.isEmpty()) {
                        saved = store.getPosition(items.get(initialIndex).url);
                    }
                    if (saved != null && saved[0] > 0) toastResume(Math.max(0L, saved[0] - 2000L));
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(playbackServiceIntent());
                        else startService(playbackServiceIntent());
                    } catch (Exception e) {
                        Toast.makeText(this, "Não foi possível iniciar o player em segundo plano", Toast.LENGTH_LONG).show();
                    }
                } else if (!tvAlreadyPlaying) {
                    long[] saved = store.getPosition(keyOf(items.get(initialIndex)));
                    if (saved == null && !items.get(initialIndex).pageUrl.isEmpty()) {
                        saved = store.getPosition(items.get(initialIndex).url);
                    }
                    long currentPosition = Math.max(0L, player.getCurrentPosition());
                    // If the service retained the same queue but its cursor reset (process/service
                    // recreation), actually restore the saved position instead of only showing a toast.
                    if (saved != null && saved[0] > 10000L && currentPosition < 10000L) {
                        long resumeAt = Math.max(0L, saved[0] - 2000L);
                        player.seekTo(resumeAt);
                        toastResume(resumeAt);
                    }
                }
                updatePlayButton();
                updateProgressUi();
                setControlsVisible(true);
                scheduleHideControls();
            } catch (Exception e) {
                Toast.makeText(PlayerActivity.this, "Falha ao conectar o player: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, Runnable::run);
    }

    private boolean queueMatchesPlayer() {
        if (player == null || player.getMediaItemCount() != items.size()
                || player.getCurrentMediaItemIndex() != initialIndex) return false;
        for (int i = 0; i < items.size(); i++) {
            try {
                String currentUri = player.getMediaItemAt(i).localConfiguration == null ? ""
                        : player.getMediaItemAt(i).localConfiguration.uri.toString();
                if (!items.get(i).url.equals(currentUri)) return false;
            } catch (Exception e) { return false; }
        }
        return true;
    }

    private Intent playbackServiceIntent() {
        return new Intent(this, PlaybackService.class)
                .putExtra(EXTRA_ITEMS, Store.queueToJson(items))
                .putExtra(EXTRA_INDEX, initialIndex);
    }

    private void seekBy(long deltaMs) {
        if (isTvRemoteMode()) {
            CastManager.get(this).seekBy(deltaMs);
            showSeekFeedback((deltaMs < 0 ? "−10 s" : "+10 s") + "  ·  TV");
            setControlsVisible(true);
            scheduleHideControls();
            return;
        }
        if (player == null || player.isCurrentMediaItemLive()) return;
        long current = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        long target = Math.max(0L, current + deltaMs);
        if (duration != C.TIME_UNSET && duration > 0) target = Math.min(target, Math.max(0L, duration - 1000L));
        player.seekTo(target);
        showSeekFeedback((deltaMs < 0 ? "−10 s" : "+10 s") + "  ·  " + formatTime(target));
        setControlsVisible(true);
        scheduleHideControls();
    }

    private void toggleLocalPlayback() {
        if (isTvRemoteMode()) {
            CastManager.get(this).togglePlayPause();
        } else {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
        }
        updatePlayButton();
        setControlsVisible(true);
        scheduleHideControls();
    }

    private void updatePlayButton() {
        if (playBtn != null) {
            boolean playing;
            if (isTvRemoteMode()) {
                String state = CastManager.get(this).lastState().toUpperCase(Locale.ROOT);
                playing = !state.startsWith("PAUS") && !state.startsWith("STOP") && !state.isEmpty();
            } else {
                playing = player != null && player.isPlaying();
            }
            playBtn.setText(playing ? "Ⅱ" : "▶");
            playBtn.setContentDescription(playing ? "Pausar" : "Reproduzir");
        }
    }

    private void updateProgressUi() {
        if (timeText == null || seekBar == null) return;
        long position;
        long duration;
        if (isTvRemoteMode()) {
            CastManager cm = CastManager.get(this);
            position = Math.max(0L, cm.lastPos());
            duration = cm.lastDur();
        } else {
            if (player == null) return;
            position = Math.max(0L, player.getCurrentPosition());
            duration = player.getDuration();
            if (duration == C.TIME_UNSET) duration = -1L;
        }
        if (!dragging && duration > 0) {
            seekBar.setEnabled(true);
            seekBar.setProgress((int) Math.min(1000L, position * 1000L / duration));
            timeText.setText(formatTime(position) + " / " + formatTime(duration));
        } else if (duration <= 0) {
            seekBar.setEnabled(false);
            if (!dragging) timeText.setText(formatTime(position) + " / --:--");
        } else {
            seekBar.setEnabled(true);
        }
        updatePlayButton();
    }

    private boolean isTvRemoteMode() {
        return CastManager.get(this).isActive();
    }

    private boolean isPlaybackActiveForUi() {
        if (isTvRemoteMode()) {
            String state = CastManager.get(this).lastState().toUpperCase(Locale.ROOT);
            return state.startsWith("PLAY") || state.startsWith("BUFFER") || state.startsWith("CONNECT");
        }
        return player != null && player.isPlaying();
    }

    /** The player surface becomes a TV remote, never a second decoder, during casting. */
    private void updateRemoteMode() {
        if (remotePlaceholder == null || playerView == null) return;
        CastManager cm = CastManager.get(this);
        boolean remote = cm.isActive();
        if (remote != remoteMode) {
            remoteMode = remote;
            playerView.setVisibility(remote ? View.GONE : View.VISIBLE);
            remotePlaceholder.setVisibility(remote ? View.VISIBLE : View.GONE);
            if (remote) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else if (player != null && player.isPlaying()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (castBtn != null) castBtn.setText(remote ? "TV ✓" : "TV ↗");
        }
        if (remote) {
            com.castor.mobile.MediaItem remoteItem = cm.activeItem();
            CastDevice device = cm.activeDevice();
            if (remoteItem != null && titleText != null) titleText.setText(remoteItem.title);
            String deviceName = device == null ? "Conectando à TV…" : device.name;
            String remoteText = "▣\nREPRODUZINDO NA TV\n" + deviceName
                    + "\n\nO vídeo fica apenas na TV.\nUse os controles para buscar ou pausar.";
            if (!remoteText.contentEquals(remotePlaceholder.getText())) remotePlaceholder.setText(remoteText);
        } else if (player != null && titleText != null) {
            int idx = player.getCurrentMediaItemIndex();
            if (idx >= 0 && idx < items.size()) titleText.setText(items.get(idx).title);
        }
    }

    private void showSeekFeedback(String message) {
        if (seekFeedback == null) return;
        handler.removeCallbacks(hideSeekFeedback);
        seekFeedback.setText(message);
        seekFeedback.setVisibility(View.VISIBLE);
        seekFeedback.setAlpha(0f);
        seekFeedback.animate().alpha(1f).setDuration(120L).start();
        handler.postDelayed(hideSeekFeedback, 850L);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (controls != null) {
            controls.animate().cancel();
            if (visible) {
                controls.setVisibility(View.VISIBLE);
                controls.animate().alpha(1f).setDuration(140L).start();
            } else {
                controls.animate().alpha(0f).setDuration(180L)
                        .withEndAction(() -> { if (!controlsVisible && controls != null) controls.setVisibility(View.GONE); })
                        .start();
            }
        }
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideControls);
        if (isPlaybackActiveForUi()) handler.postDelayed(hideControls, 4500L);
    }

    private static String keyOf(com.castor.mobile.MediaItem item) {
        return item.pageUrl == null || item.pageUrl.isEmpty() ? item.url : item.pageUrl;
    }

    private void toastResume(long pos) {
        Toast.makeText(this, "Continuando de " + formatTime(pos), Toast.LENGTH_SHORT).show();
    }

    private void saveCurrent() {
        if (player == null || items == null) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= items.size() || player.isCurrentMediaItemLive()) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur != C.TIME_UNSET && dur > 0 && dur < 60000L) return;
        String key = keyOf(items.get(idx));
        if (dur > 0 && dur != C.TIME_UNSET && pos >= dur - 15000L) {
            store.clearPosition(key);
            if (!items.get(idx).url.equals(key)) store.clearPosition(items.get(idx).url);
        } else if (pos >= 5000L) {
            long duration = dur == C.TIME_UNSET ? 0L : Math.max(0L, dur);
            store.setPosition(key, pos, duration);
            if (!items.get(idx).url.equals(key)) store.setPosition(items.get(idx).url, pos, duration);
        }
    }

    private static String formatTime(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        return total >= 3600L
                ? String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600L, (total / 60L) % 60L, total % 60L)
                : String.format(Locale.ROOT, "%02d:%02d", total / 60L, total % 60L);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_VOLUME_UP && CastService.handleVolumeKey(this, 1)) return true;
            if (key == KeyEvent.KEYCODE_VOLUME_DOWN && CastService.handleVolumeKey(this, -1)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && !isTvRemoteMode() && player != null && player.isPlaying()) {
            try { enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build()); }
            catch (Exception ignored) { }
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        if (controls != null) controls.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (playerView != null) playerView.setUseController(inPip);
    }

    @Override protected void onPause() {
        super.onPause();
        saveCurrent();
    }

    @Override protected void onStop() {
        super.onStop();
        saveCurrent();
        // The MediaSessionService keeps playback alive for audio/screen-off playback.
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
