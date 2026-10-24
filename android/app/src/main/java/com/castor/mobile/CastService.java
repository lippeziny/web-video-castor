package com.castor.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;

/** Mantém o app vivo em segundo plano enquanto o proxy local entrega o vídeo para a TV. */
public final class CastService extends Service {
    private static final String CHANNEL = "castor_cast";
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    public static void start(Context c, String text) {
        Intent i = new Intent(c, CastService.class).putExtra("text", text);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, CastService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? "" : intent.getStringExtra("text");
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Transmissão",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_play)
                .setContentTitle("Castor transmitindo")
                .setContentText(text == null || text.isEmpty() ? "Mantenha a rede Wi-Fi conectada" : text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        boolean foreground = false;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(7, n);
            }
            foreground = true;
        } catch (Exception ignored) {
        }
        if (foreground) {
            acquireLocks();
            return START_STICKY;
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void acquireLocks() {
        try { if (cpuLock != null && !cpuLock.isHeld()) cpuLock.acquire(); } catch (Exception ignored) { }
        try { if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
