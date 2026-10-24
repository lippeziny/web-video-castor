package com.castor.mobile;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Player persistente: mantém áudio, notificação e controles de mídia fora da Activity. */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
    private final Map<String, String> refererByUrl = new ConcurrentHashMap<>();
    private final List<com.castor.mobile.MediaItem> queueItems = new ArrayList<>();
    private final Set<Integer> resumedIndexes = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private MediaSession mediaSession;
    private Store store;
    private volatile String fallbackReferer = "";
    private boolean replacingQueue;
    private int lastIndex = -1;

    private final Runnable saver = new Runnable() {
        @Override public void run() {
            saveCurrent();
            if (player != null && player.getPlayWhenReady()) handler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setUserAgent(MainActivity.USER_AGENT_MOBILE);
        DataSource.Factory perItem = () -> {
            DefaultHttpDataSource ds = http.createDataSource();
            return new ResolvingDataSource(ds, dataSpec -> {
                String ref = refererByUrl.get(dataSpec.uri.toString());
                if (ref == null || ref.isEmpty()) ref = fallbackReferer;
                if (ref != null && !ref.isEmpty()) {
                    ds.setRequestProperty("Referer", ref);
                    try {
                        URI r = new URI(ref);
                        if (r.getScheme() != null && r.getHost() != null) {
                            ds.setRequestProperty("Origin", r.getScheme() + "://" + r.getHost());
                        }
                    } catch (Exception ignored) { }
                }
                return dataSpec;
            });
        };

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(perItem))
                .build();
        AudioAttributes mediaAudio = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player.setAudioAttributes(mediaAudio, true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        applyQualityPreference();
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                int index = player.getCurrentMediaItemIndex();
                if (replacingQueue) {
                    lastIndex = index;
                    return;
                }
                if (lastIndex >= 0 && lastIndex < queueItems.size() && index != lastIndex
                        && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    store.clearPosition(keyOf(queueItems.get(lastIndex)));
                }
                lastIndex = index;
                if (index >= 0 && index < queueItems.size() && !resumedIndexes.contains(index)) {
                    resumedIndexes.add(index);
                    long resume = savedPosition(index);
                    if (resume > 0) player.seekTo(index, resume);
                }
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && lastIndex >= 0 && lastIndex < queueItems.size()) {
                    store.clearPosition(keyOf(queueItems.get(lastIndex)));
                }
                handler.removeCallbacks(saver);
                if (player != null && player.getPlayWhenReady()) handler.postDelayed(saver, 5000);
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                handler.removeCallbacks(saver);
                if (isPlaying) handler.postDelayed(saver, 5000);
                else saveCurrent();
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare();
                    player.play();
                }
            }
        });
        mediaSession = new MediaSession.Builder(this, player).build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null && intent.hasExtra(PlayerActivity.EXTRA_ITEMS)) loadQueue(intent);
        return START_STICKY;
    }

    private void loadQueue(Intent intent) {
        List<com.castor.mobile.MediaItem> incoming = Store.queueFromJson(
                intent.getStringExtra(PlayerActivity.EXTRA_ITEMS));
        if (incoming.isEmpty() || player == null) return;
        saveCurrent();
        queueItems.clear();
        queueItems.addAll(incoming);
        refererByUrl.clear();
        for (com.castor.mobile.MediaItem item : queueItems) {
            if (!item.url.isEmpty() && !item.referer.isEmpty()) refererByUrl.put(item.url, item.referer);
        }
        int start = intent.getIntExtra(PlayerActivity.EXTRA_INDEX, 0);
        if (start < 0 || start >= queueItems.size()) start = 0;
        fallbackReferer = queueItems.get(start).referer;
        applyQualityPreference();

        List<MediaItem> mediaItems = new ArrayList<>();
        for (com.castor.mobile.MediaItem item : queueItems) {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setDisplayTitle(item.title)
                    .build();
            mediaItems.add(new MediaItem.Builder().setUri(item.url).setMediaMetadata(metadata).build());
        }
        resumedIndexes.clear();
        resumedIndexes.add(start);
        long resume = savedPosition(start);
        lastIndex = start;
        replacingQueue = true;
        player.setMediaItems(mediaItems, start, resume);
        player.prepare();
        player.play();
        replacingQueue = false;
        handler.removeCallbacks(saver);
        handler.postDelayed(saver, 5000);
    }

    private void applyQualityPreference() {
        if (player == null || store == null) return;
        String quality = store.quality();
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                .setForceHighestSupportedBitrate("high".equals(quality))
                .setForceLowestBitrate("low".equals(quality));
        if ("medium".equals(quality)) builder.setMaxVideoSize(1280, 720);
        else if ("low".equals(quality)) builder.setMaxVideoSize(854, 480);
        else builder.setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
        player.setTrackSelectionParameters(builder.build());
    }

    private static String keyOf(com.castor.mobile.MediaItem item) {
        return item.pageUrl.isEmpty() ? item.url : item.pageUrl;
    }

    private long savedPosition(int index) {
        if (index < 0 || index >= queueItems.size()) return 0;
        long[] saved = store.getPosition(keyOf(queueItems.get(index)));
        return saved == null ? 0 : Math.max(0, saved[0] - 2000);
    }

    private void saveCurrent() {
        if (player == null || store == null || queueItems.isEmpty()) return;
        int idx = player.getCurrentMediaItemIndex();
        if (idx < 0 || idx >= queueItems.size() || player.isCurrentMediaItemLive()) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur == C.TIME_UNSET || dur < 60000) return;
        String key = keyOf(queueItems.get(idx));
        if (pos >= dur - 15000) store.clearPosition(key);
        else if (pos >= 5000) store.setPosition(key, pos, dur);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveCurrent();
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        } else if (player != null) {
            player.release();
        }
        player = null;
        super.onDestroy();
    }
}
