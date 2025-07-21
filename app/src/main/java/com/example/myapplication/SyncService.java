package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.List;

public class SyncService extends Service {
    private static final String TAG = "SyncService";
    public static final String CHANNEL_ID = "SyncServiceChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("File Sync")
                .setContentText("Syncing files...")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build();

        startForeground(2, notification);

        new Thread(() -> {
            Log.d(TAG, "Sync service started.");
            performSync();
            stopSelf(); // Stop the service when the work is done
        }).start();

        return START_NOT_STICKY;
    }

    private void performSync() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ShimmerFileTransferClient client = new ShimmerFileTransferClient(this);

        List<File> localFiles = client.getLocalUnsyncedFiles();
        if (localFiles.isEmpty()) {
            Log.d(TAG, "No files to sync.");
            updateNotification(notificationManager, "Sync Complete", "No new files to upload.");
            return;
        }

        List<String> missingFileNames = client.getMissingFilesOnS3(localFiles);
        if (missingFileNames.isEmpty()) {
            Log.d(TAG, "All local files are already on the server.");
            updateNotification(notificationManager, "Sync Complete", "All files are up to date.");
            return;
        }

        int totalToUpload = missingFileNames.size();
        for (int i = 0; i < totalToUpload; i++) {
            String filename = missingFileNames.get(i);
            File fileToUpload = findFileByName(localFiles, filename);

            if (fileToUpload != null) {
                String progressText = "Uploading " + (i + 1) + " of " + totalToUpload + ": " + filename;
                Log.d(TAG, progressText);
                updateNotification(notificationManager, "Syncing Files...", progressText);

                boolean success = client.uploadFileToS3(fileToUpload);
                if (success) {
                    client.markFileAsSynced(fileToUpload);
                }
            }
        }

        Log.d(TAG, "Sync process finished.");
        updateNotification(notificationManager, "Sync Complete", "File synchronization finished.");
    }

    private void updateNotification(NotificationManager manager, String title, String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build();
        manager.notify(2, notification);
    }

    private File findFileByName(List<File> files, String name) {
        for (File file : files) {
            if (file.getName().equals(name)) {
                return file;
            }
        }
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sync Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void startSyncService(Context context) {
        Intent syncIntent = new Intent(context, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(syncIntent);
        } else {
            context.startService(syncIntent);
        }
    }
}