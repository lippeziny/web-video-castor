package com.castor.mobile;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(0xFF000000);
        playerView.setShowNextButton(true);
        playerView.setShowPreviousButton(true);
        setContentView(playerView);
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
        player.seekTo(start, 0);
        player.prepare();
        player.play();
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
    protected void onStop() {
        super.onStop();
        if (player != null && !isInPictureInPictureMode()) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
