package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class FileSyncService extends Service {
    private static final String TAG = "FileSyncService";
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIF_ID = 201;

    // Add public actions for UI/Activity listeners
    public static final String ACTION_SYNC_START = "com.example.myapplication.ACTION_SYNC_START";
    public static final String ACTION_SYNC_DONE = "com.example.myapplication.ACTION_SYNC_DONE";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        // Start in foreground so notification is visible immediately
        startForeground(NOTIF_ID, buildNotification("Preparing to sync..."));
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Notify UI that sync started
        try {
            Intent start = new Intent(ACTION_SYNC_START);
            start.setPackage(getPackageName());
            sendBroadcast(start);
        } catch (Exception ignored) {}

        new Thread(() -> {
            try {
                updateNotif("Syncing to cloud...");
                if (!isWifiConnected()) {
                    updateNotif("Wi‑Fi is OFF. Will sync next cycle.");
                    // No retries and no UI broadcasts; just stop
                    stopSelf();
                    return;
                }
                // ...existing upload logic...
                updateNotif("Cloud sync complete.");
            } catch (Exception e) {
                updateNotif("Sync failed. Will sync next cycle.");
            } finally {
                // Always notify UI that sync ended
                try {
                    Intent done = new Intent(ACTION_SYNC_DONE);
                    done.setPackage(getPackageName());
                    sendBroadcast(done);
                } catch (Exception ignored) {}
                stopSelf();
            }
        }, "FileSyncWorker").start();
        return START_STICKY;
    }

    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "File Sync",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notifications for Shimmer cloud sync");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shimmer Cloud Sync")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String message) {
        Log.d(TAG, message);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(message));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}