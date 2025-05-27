package com.example.myapplication;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class TransferService extends Service {

    public static final String EXTRA_MAC_ADDRESS = "mac_address";
    private static final String CHANNEL_ID = "ShimmerTransferChannel";
    private static final int NOTIFICATION_ID = 42;
    private static final String TAG = "TransferService";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "TransferService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String macAddress = intent.getStringExtra(EXTRA_MAC_ADDRESS);
        if (macAddress == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shimmer File Transfer")
                .setContentText("Downloading from: " + macAddress)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> {
            try {
                Log.d(TAG, "Starting transfer for MAC: " + macAddress);
                ShimmerFileTransferClient client = new ShimmerFileTransferClient(this);
                client.transfer(macAddress);
                Log.d(TAG, "Finished transfer for MAC: " + macAddress);

                // Notify UI that transfer is done
                Intent doneIntent = new Intent("com.example.myapplication.TRANSFER_DONE");
                doneIntent.putExtra("mac", macAddress);
                sendBroadcast(doneIntent);

            } catch (Exception e) {
                Log.e(TAG, "Transfer failed", e);
            } finally {
                stopForeground(true);
                stopSelf();
            }
        }).start();

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Shimmer File Transfer",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
