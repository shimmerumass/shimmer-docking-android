package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class DockingService extends Service implements DockingManager.DockingCallback {
    private static final String CHANNEL_ID = "docking_channel";
    private static final int NOTIF_ID = 101;
    private DockingManager dockingManager;
    private Handler handler = new Handler();

    private final BroadcastReceiver transferDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNotification("File transfer completed.");
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("DockingService", "DockingService created");
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Docking protocol running..."));

        // Always use latest hours from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("docking_prefs", MODE_PRIVATE);
        int startHour = prefs.getInt("night_start_hour", 20);
        int endHour = prefs.getInt("night_end_hour", 9);

        dockingManager = new DockingManager(this, this);
        dockingManager.nightStartHour = startHour;
        dockingManager.nightEndHour = endHour;

        handler.post(dockingManager::startNightDockingFlow);
        registerReceiver(
            transferDoneReceiver,
            new IntentFilter("com.example.myapplication.TRANSFER_DONE"),
            Context.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Optionally restart protocol here
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(transferDoneReceiver);

        // Restart ScanningService
        Intent scanIntent = new Intent(this, ScanningService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(scanIntent);
        } else {
            startService(scanIntent);
        }
        Log.d("DockingService", "DockingService destroyed, ScanningService restarted");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Docking Protocol", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shimmer Docking Protocol")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build();
    }

    // DockingManager.DockingCallback implementations
    @Override
    public void onDocked() {
        updateNotification("Shimmer docked. Preparing file transfer...");
    }
    @Override
    public void onUndocked() {
        updateNotification("Shimmer undocked. Entering silent state...");
    }
    @Override
    public void onAmbiguous() {
        updateNotification("Ambiguous state. Querying dock status...");
    }
    @Override
    public void onFileTransferStart() {
        updateNotification("File transfer started.");
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIF_ID, buildNotification(text));
    }
}