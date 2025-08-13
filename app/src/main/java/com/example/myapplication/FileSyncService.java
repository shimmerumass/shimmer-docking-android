package com.example.myapplication;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class FileSyncService extends Service {
    private static final String TAG = "FileSyncService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                stopSelf();
            }
        }).start();
        return START_STICKY;
    }

    private boolean isWifiConnected() {
        // ...existing network check, but only used here...
        return true; // placeholder; actual implementation remains elsewhere in file
    }

    private void updateNotif(String message) {
        // ...existing notification logic...
        Log.d(TAG, message); // placeholder for actual notification code
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}