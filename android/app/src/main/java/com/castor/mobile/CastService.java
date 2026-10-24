package com.castor.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

import androidx.core.app.NotificationCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

/** Keeps network/CPU alive, exposes TV controls in a persistent notification, and maps hardware
 * volume keys to the TV only while the phone is not playing other audio. */
public final class CastService extends Service {
    private static final String CHANNEL = "castor_cast";
    private static final int NOTIFICATION_ID = 7;

    private static final String ACTION_REFRESH = "com.castor.mobile.cast.REFRESH";
    private static final String ACTION_BACK = "com.castor.mobile.cast.BACK";
    private static final String ACTION_TOGGLE = "com.castor.mobile.cast.TOGGLE";
    private static final String ACTION_FORWARD = "com.castor.mobile.cast.FORWARD";
    private static final String ACTION_STOP = "com.castor.mobile.cast.STOP";

    private static volatile CastService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSessionCompat remoteVolumeSession;
    private VolumeProviderCompat remoteVolumeProvider;
    private String title = "";
    private String deviceName = "";
    private String playbackState = "PLAYING";
    private long positionMs;
    private long durationMs;
    private long lastNotificationAt;
    private int lastNotifiedSecond = -1;
    private boolean foregroundStarted;

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (instance != CastService.this) return;
            refreshRemoteVolumeSession();
            main.postDelayed(this, 2500L);
        }
    };

    public static void start(Context c, String text) {
        Intent i = new Intent(c, CastService.class).putExtra("text", text == null ? "" : text);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { }
    }

    public static void stop(Context c) {
        try { c.stopService(new Intent(c, CastService.class)); }
        catch (Exception ignored) { }
    }

    /** Called by CastManager as TV position/status changes. */
    public static void updateCastState(Context c, String title, String device, String state,
                                       long posMs, long durMs) {
        CastService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            s.title = title == null ? "" : title;
            s.deviceName = device == null ? "" : device;
            s.playbackState = state == null || state.isEmpty() ? "PLAYING" : state;
            s.positionMs = Math.max(0L, posMs);
            s.durationMs = Math.max(0L, durMs);
            s.refreshRemoteVolumeSession();
            s.refreshNotification(false);
        });
    }

    public static void updateRemoteVolume(float level) {
        CastService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            if (s.remoteVolumeProvider != null) {
                s.remoteVolumeProvider.setCurrentVolume(Math.max(0, Math.min(100, Math.round(level * 100f))));
            }
        });
    }

    /** Foreground Activity fallback; background volume keys are handled by MediaSessionCompat. */
    public static boolean handleVolumeKey(Context context, int direction) {
        if (!canHandleVolumeKeys(context)) return false;
        return CastManager.get(context).adjustVolume(direction);
    }

    public static boolean canHandleVolumeKeys(Context context) {
        if (PlaybackService.isLocallyPlaying()) return false;
        AudioManager audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.isMusicActive()) return false;
        CastManager cm = CastManager.get(context);
        return cm.isActive() && cm.supportsVolume();
    }

    /** A local player start should win volume-key ownership over a TV cast session. */
    public static void onLocalPlaybackChanged(Context context, boolean isPlaying) {
        CastService s = instance;
        if (s != null) s.main.post(s::refreshRemoteVolumeSession);
    }

    public static void refreshVolumeSession(Context context) {
        CastService s = instance;
        if (s != null) s.main.post(s::refreshRemoteVolumeSession);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Castor:CastCpu");
                cpuLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) { }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Castor:CastWifi");
                wifiLock.setReferenceCounted(false);
            }
        } catch (Exception ignored) { }
        ensureChannel();
        acquireLocks();
        main.post(monitor);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_BACK.equals(action)) CastManager.get(this).seekBy(-10_000L);
            else if (ACTION_FORWARD.equals(action)) CastManager.get(this).seekBy(10_000L);
            else if (ACTION_TOGGLE.equals(action)) CastManager.get(this).togglePlayPause();
            else if (ACTION_STOP.equals(action)) {
                CastManager.get(this).stop();
                return START_STICKY;
            } else if (ACTION_REFRESH.equals(action)) {
                refreshRemoteVolumeSession();
            }
            String incomingTitle = intent.getStringExtra("text");
            if (incomingTitle != null && !incomingTitle.isEmpty()) title = incomingTitle;
        }
        ensureChannel();
        refreshRemoteVolumeSession();
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            foregroundStarted = true;
            acquireLocks();
            return START_STICKY;
        } catch (Exception e) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Transmissão para TV", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void refreshRemoteVolumeSession() {
        if (instance != this) return;
        boolean allow = canHandleVolumeKeys(this);
        if (!allow) {
            releaseRemoteVolumeSession();
            refreshNotification(false);
            return;
        }
        if (remoteVolumeSession == null) {
            CastManager cm = CastManager.get(this);
            remoteVolumeSession = new MediaSessionCompat(this, "CastorTVRemote");
            remoteVolumeSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            remoteVolumeProvider = new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
                    100, Math.max(0, Math.min(100, Math.round(cm.volumeLevel() * 100f)))) {
                @Override public void onAdjustVolume(int direction) {
                    if (!canHandleVolumeKeys(CastService.this)) return;
                    if (CastManager.get(CastService.this).adjustVolume(direction)) {
                        int next = Math.max(0, Math.min(100, getCurrentVolume() + (direction < 0 ? -5 : 5)));
                        setCurrentVolume(next);
                    }
                }
                @Override public void onSetVolumeTo(int volume) {
                    if (!canHandleVolumeKeys(CastService.this)) return;
                    int next = Math.max(0, Math.min(100, volume));
                    if (CastManager.get(CastService.this).setVolume(next / 100f)) setCurrentVolume(next);
                }
            };
            remoteVolumeSession.setPlaybackToRemote(remoteVolumeProvider);
            remoteVolumeSession.setCallback(new MediaSessionCompat.Callback() {
                @Override public void onPlay() { CastManager.get(CastService.this).togglePlayPause(); }
                @Override public void onPause() { CastManager.get(CastService.this).togglePlayPause(); }
                @Override public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) { onPlay(); }
                @Override public void onFastForward() { CastManager.get(CastService.this).seekBy(10_000L); }
                @Override public void onRewind() { CastManager.get(CastService.this).seekBy(-10_000L); }
                @Override public void onSeekTo(long pos) { CastManager.get(CastService.this).seekTo(pos); }
                @Override public void onStop() { CastManager.get(CastService.this).stop(); }
            });
            remoteVolumeSession.setActive(true);
        }
        updateSessionMetadataAndState();
    }

    private void updateSessionMetadataAndState() {
        if (remoteVolumeSession == null) return;
        String titleForSession = title.isEmpty() ? "Castor" : title;
        remoteVolumeSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titleForSession)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                        deviceName.isEmpty() ? "Transmitindo para TV" : deviceName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0L, durationMs))
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_REWIND | PlaybackStateCompat.ACTION_FAST_FORWARD
                | PlaybackStateCompat.ACTION_STOP;
        boolean paused = playbackState.toUpperCase().startsWith("PAUS");
        boolean connecting = playbackState.toUpperCase().startsWith("CONNECT")
                || playbackState.toUpperCase().startsWith("TRANSITION");
        int state = connecting ? PlaybackStateCompat.STATE_BUFFERING
                : paused ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_PLAYING;
        remoteVolumeSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, Math.max(0L, positionMs), paused ? 0f : 1f)
                .build());
    }

    private void releaseRemoteVolumeSession() {
        if (remoteVolumeSession != null) {
            try { remoteVolumeSession.setActive(false); } catch (Exception ignored) { }
            try { remoteVolumeSession.release(); } catch (Exception ignored) { }
        }
        remoteVolumeSession = null;
        remoteVolumeProvider = null;
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent i = new Intent(this, CastService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        String displayTitle = title.isEmpty() ? "Transmissão ativa" : title;
        String destination = deviceName.isEmpty() ? "TV" : deviceName;
        String state = playbackState.toUpperCase().startsWith("PAUS") ? "Pausado" :
                playbackState.toUpperCase().startsWith("CONNECT") ? "Reconectando…" : "Reproduzindo na TV";
        String content = state + " · " + destination;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(displayTitle)
                .setContentText(content)
                .setSubText("Castor · transmissão em segundo plano")
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new NotificationCompat.Action(android.R.drawable.ic_media_rew, "Voltar 10 s",
                        serviceAction(ACTION_BACK, 71)))
                .addAction(new NotificationCompat.Action(
                        playbackState.toUpperCase().startsWith("PAUS") ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                        playbackState.toUpperCase().startsWith("PAUS") ? "Reproduzir" : "Pausar",
                        serviceAction(ACTION_TOGGLE, 72)))
                .addAction(new NotificationCompat.Action(android.R.drawable.ic_media_ff, "Avançar 10 s",
                        serviceAction(ACTION_FORWARD, 73)))
                .addAction(new NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Parar",
                        serviceAction(ACTION_STOP, 74)));
        if (durationMs > 0) {
            int max = (int) Math.min(Integer.MAX_VALUE, durationMs / 1000L);
            int progress = (int) Math.max(0L, Math.min((long) max, positionMs / 1000L));
            b.setProgress(Math.max(1, max), progress, false);
        }
        if (remoteVolumeSession != null) {
            b.setStyle(new MediaStyle().setMediaSession(remoteVolumeSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return b.build();
    }

    private void refreshNotification(boolean force) {
        if (!foregroundStarted && !force) return;
        long now = System.currentTimeMillis();
        int second = (int) (positionMs / 1000L);
        boolean important = force || now - lastNotificationAt >= 3000L
                || second / 5 != lastNotifiedSecond / 5;
        if (!important) return;
        lastNotificationAt = now;
        lastNotifiedSecond = second;
        Notification n = buildNotification();
        try {
            if (foregroundStarted) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NOTIFICATION_ID, n);
            } else {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIFICATION_ID, n);
            }
        } catch (Exception ignored) { }
    }

    private void acquireLocks() {
        try { if (cpuLock != null && !cpuLock.isHeld()) cpuLock.acquire(); } catch (Exception ignored) { }
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep the TV session alive; save a fresh checkpoint if the user dismisses the app task.
        CastManager.get(this).checkpoint();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        CastManager.get(this).checkpoint();
        main.removeCallbacksAndMessages(null);
        releaseRemoteVolumeSession();
        releaseLocks();
        foregroundStarted = false;
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
