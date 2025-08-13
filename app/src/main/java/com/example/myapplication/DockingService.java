package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class DockingService extends Service implements DockingManager.DockingCallback {
    private static final String CHANNEL_ID = "docking_channel";
    private static final int NOTIF_ID = 101;

    // Broadcasts
    public static final String ACTION_TRANSFER_DONE = "com.example.myapplication.TRANSFER_DONE";
    public static final String ACTION_TRANSFER_FAILED = "com.example.myapplication.TRANSFER_FAILED";

    // Retry
    private static final long RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(1);
    private long firstFailureAtMs = 0L;

    private DockingManager dockingManager;
    private Handler handler = new Handler();

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isWithinDockingWindow()) {
                updateNotification("Retrying docking protocol...");
                handler.post(() -> dockingManager.startNightDockingFlow());
            } else {
                long delay = millisUntilWindowOpens();
                updateNotification("Waiting for docking window to reopen...");
                handler.postDelayed(this, delay);
            }
        }
    };

    private final BroadcastReceiver transferDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNotification("File transfer completed.");
            cancelRetry();
            stopSelf();
        }
    };

    private final BroadcastReceiver transferFailedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            Log.w("DockingService", "Transfer failed. Reason=" + reason);
            updateNotification("Transfer failed (" + (reason != null ? reason : "error") + "). Will retry in 15 min.");
            scheduleRetry();
        }
    };

    private void sendDockingStatus(String status) {
        Intent i = new Intent("com.example.myapplication.DOCKING_STATUS");
        i.setPackage(getPackageName());
        i.putExtra("status", status);
        sendBroadcast(i);
    }

    private final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                Log.w("DockingService", "Bluetooth turned off. Forcing silent state and scheduling retry.");
                updateNotification("Bluetooth off. Entering silent state (15 min)...");
                sendDockingStatus("Bluetooth off. Entering silent state (15 min)...");
                if (dockingManager != null) dockingManager.forceSilentState();
                scheduleRetry();
            }
        }
    };

    // Basic connectivity watcher (covers Wi‑Fi off for upload step)
    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isNetworkConnected()) {
                Log.w("DockingService", "Network disconnected. Scheduling retry.");
                updateNotification("No network. Retrying in 15 min.");
                scheduleRetry();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("DockingService", "DockingService created");
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Docking protocol running..."));

        // Load latest window hours
        SharedPreferences prefs = getSharedPreferences("docking_prefs", MODE_PRIVATE);
        int startHour = prefs.getInt("night_start_hour", 20);
        int endHour = prefs.getInt("night_end_hour", 9);

        dockingManager = new DockingManager(this, this);
        dockingManager.nightStartHour = startHour;
        dockingManager.nightEndHour = endHour;

        handler.post(() -> dockingManager.startNightDockingFlow());

        // Receivers
        registerReceiver(transferDoneReceiver, new IntentFilter(ACTION_TRANSFER_DONE), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(transferFailedReceiver, new IntentFilter(ACTION_TRANSFER_FAILED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(btStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        cancelRetry();

        try { unregisterReceiver(transferDoneReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(transferFailedReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(connectivityReceiver); } catch (Exception ignored) {}

        // Restart ScanningService
        Intent scanIntent = new Intent(this, ScanningService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    // DockingManager.DockingCallback
    @Override
    public void onDocked() {
        cancelRetry();
        updateNotification("Shimmer docked. Preparing file transfer...");
        sendDockingStatus("Shimmer docked. Preparing file transfer...");
    }

    @Override
    public void onUndocked() {
        updateNotification("Shimmer undocked. Entering silent state...");
        sendDockingStatus("Shimmer undocked. Entering silent state...");
        scheduleRetry();
    }

    @Override
    public void onAmbiguous() {
        updateNotification("Ambiguous state. Querying dock status...");
        sendDockingStatus("Ambiguous state. Querying dock status...");
    }

    @Override
    public void onFileTransferStart() {
        cancelRetry();
        updateNotification("File transfer started.");
        sendDockingStatus("File transfer started.");
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIF_ID, buildNotification(text));
    }

    private void scheduleRetry() {
        if (firstFailureAtMs == 0L) {
            firstFailureAtMs = System.currentTimeMillis();
        }
        cancelRetry();
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void cancelRetry() {
        handler.removeCallbacks(retryRunnable);
        firstFailureAtMs = 0L;
    }

    private boolean isWithinDockingWindow() {
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int start = dockingManager.nightStartHour;
        int end = dockingManager.nightEndHour;
        if (start == end) return false;
        if (start < end) return now >= start && now < end;
        return now >= start || now < end;
    }

    private long millisUntilWindowOpens() {
        Calendar cal = Calendar.getInstance();
        int start = dockingManager.nightStartHour;

        Calendar next = (Calendar) cal.clone();
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (cal.get(Calendar.HOUR_OF_DAY) >= start) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        next.set(Calendar.HOUR_OF_DAY, start);
        long diff = next.getTimeInMillis() - cal.getTimeInMillis();
        return Math.max(1000L, diff);
    }

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }
}