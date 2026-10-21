package top.weixiansen574.hybridfilexfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** User-started sessions only; never restores listening ports after process death. */
public final class SessionService extends Service {
    private static final String CHANNEL = "file-transfer";
    private static final String STOP = "top.weixiansen574.hybridfilexfer.STOP_SESSIONS";
    private static final Map<String, Runnable> owners = new LinkedHashMap<>();
    private static SessionService runningInstance;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void acquire(Context context, String owner, Runnable stop) {
        owners.put(owner, stop);
        try {
            if (runningInstance != null) runningInstance.updateNotification();
            else ContextCompat.startForegroundService(context, new Intent(context, SessionService.class));
        } catch (RuntimeException e) {
            owners.remove(owner);
            throw e;
        }
    }

    public static void release(Context context, String owner) {
        owners.remove(owner);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (owners.isEmpty()) context.stopService(new Intent(context, SessionService.class));
        }, 100);
    }

    @Override public void onCreate() {
        super.onCreate();
        runningInstance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.session_notification_title),
                            NotificationManager.IMPORTANCE_LOW));
        }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":transfer");
        wakeLock.acquire();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HybridFileXfer:transfer");
            wifiLock.acquire();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopOwners();
            stopSelf();
            return START_NOT_STICKY;
        }
        updateNotification();
        if (owners.isEmpty()) stopSelf();
        return START_NOT_STICKY;
    }

    private void updateNotification() {
        Intent show = new Intent(this, owners.containsKey("client") ? ClientActivity.class : MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SessionService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.baseline_phonelink_ring_24)
                .setContentTitle(getString(R.string.session_notification_title))
                .setContentText(getString(R.string.session_notification_message))
                .setContentIntent(content)
                .setOngoing(true)
                .addAction(R.drawable.ic_close, getString(R.string.ting_zhi_fu_wu), stop)
                .build();
        startForeground(5740, notification);
    }

    private static void stopOwners() {
        ArrayList<Runnable> stopping = new ArrayList<>(owners.values());
        owners.clear();
        for (Runnable stop : stopping) stop.run();
    }

    @Override public void onDestroy() {
        if (runningInstance == this) runningInstance = null;
        stopOwners();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
