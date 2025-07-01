package com.example.myapplication;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;

public class ScanningService extends Service {
    private static final String TAG = "BluetoothScanService";
    private static final String CHANNEL_ID = "ShimmerScanChannel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_TIMER_UPDATE = "com.example.myapplication.ACTION_TIMER_UPDATE";
    public static final String ACTION_SCAN_RESULTS = "com.example.myapplication.ACTION_SCAN_RESULTS";
    public static final String ACTION_SLEEP_TIMER_UPDATE = "com.example.myapplication.ACTION_SLEEP_TIMER_UPDATE";
    public static final String ACTION_STATUS_UPDATE = "com.example.myapplication.ACTION_STATUS_UPDATE";

    private BluetoothAdapter bluetoothAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> foundDevices = new ArrayList<>();

    // Extended search flag and elapsed time tracker.
    private long extendedSearchElapsed = 0;
    private boolean isExtendedSearch = false;

    // Timer durations and sleep intervals:
    private static final long SCAN_DURATION_MS = 15 * 1000;                 // 15 sec scan
    private static final long SCAN_INTERVAL_MS = 2 * 60 * 1000;               // 2 min interval for extended search scans
    private static final long EXTENDED_SEARCH_TOTAL_MS = 30 * 60 * 1000;      // total extended search period = 30 min
    private static final long SLEEP_30_MIN_MS = 30 * 60 * 1000;               // sleep 30 minutes if device found
    private static final long SLEEP_20_MIN_MS = 20 * 60 * 1000;               // sleep 20 minutes if no device found in extended search

    private long scanStartTime = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    // Add a flag to track service active state.
    private boolean isServiceActive = false;

    // Add a new field to prevent duplicate handling.
    private boolean scanFinishedHandled = false;

    // Timer runnable for scan countdown.
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsed = System.currentTimeMillis() - scanStartTime;
            long remaining = SCAN_DURATION_MS - elapsed;
            Intent intent = new Intent(ACTION_TIMER_UPDATE);
            intent.putExtra("remaining_time", Math.max(remaining, 0));
            intent.setPackage(getApplicationContext().getPackageName());
            Log.d(TAG, "Broadcasting scan timer: " + Math.max(remaining, 0) + " ms");
            sendBroadcast(intent);

            if (remaining > 0) {
                timerHandler.postDelayed(this, 1000);
            } else {
                Log.d(TAG, "Scan duration ended. Final broadcast sent.");
            }
        }
    };

    // Sleep timer runnable to count down sleep period.
    private class SleepTimerRunnable implements Runnable {
        private long remainingSleep;

        public void setRemainingSleep(long sleepMs) {
            this.remainingSleep = sleepMs;
        }

        @Override
        public void run() {
            remainingSleep -= 1000;
            Intent intent = new Intent(ACTION_SLEEP_TIMER_UPDATE);
            intent.putExtra("remaining_sleep", Math.max(remainingSleep, 0));
            intent.setPackage(getApplicationContext().getPackageName());
            Log.d(TAG, "Broadcasting sleep timer: " + Math.max(remainingSleep, 0) + " ms");
            sendBroadcast(intent);

            sendStatusUpdate("Sleeping: " + (remainingSleep / 60000) + " min " +
                    ((remainingSleep % 60000) / 1000) + " sec left");

            if (remainingSleep > 0) {
                handler.postDelayed(this, 1000);
            } else {
                Log.d(TAG, "Sleep timer ended.");
                // After sleep, always start with an initial scan.
                startInitialScan();
            }
        }
    }

    // Use our custom sleep timer runnable.
    private final SleepTimerRunnable sleepTimerRunnable = new SleepTimerRunnable();

    // Add a field to track the current SleepTimerRunnable instance.
    private SleepTimerRunnable currentSleepTimer = null;

    // Bluetooth discovery receiver.
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleBluetoothEvents(intent);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceActive = true;
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        Log.d(TAG, "Service created");

        // Register for Bluetooth discovery events.
        registerReceiver(discoveryReceiver, createBluetoothIntentFilter());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Service started"));

        // Begin with initial scan.
        startInitialScan();
    }

    // Create intent filter for Bluetooth discovery events.
    private IntentFilter createBluetoothIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        return filter;
    }

    // Build a persistent notification.
    private Notification buildNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shimmer Bluetooth Scanner")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // use high to ensure visibility
                .setOnlyAlertOnce(true)
                .build();
    }

    // Create notification channel for API 26+
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Shimmer Scan Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel for Shimmer Scan notifications");
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    // Update our persistent notification with the given message.
    private void updateNotification(String message) {
        Log.d(TAG, "Updating notification status to: " + message);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(message));
        }
    }

    // Handle Bluetooth discovery events.
    private void handleBluetoothEvents(Intent intent) {
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
            Log.d(TAG, "Discovery started");
            updateNotification("Scanning for Shimmer devices...");
            sendStatusUpdate("Scanning for Shimmer devices...");
        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            Log.d(TAG, "Discovery finished");
            onScanFinished();
        } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null)
                processFoundDevice(device);
        }
    }

    // Process a discovered Bluetooth device.
    private void processFoundDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String name = device.getName();
        if (name != null && name.startsWith("Shimmer")) {
            String deviceInfo = name + " - " + device.getAddress();
            if (!foundDevices.contains(deviceInfo)) {
                foundDevices.add(deviceInfo);
                Log.d(TAG, "Found device: " + deviceInfo);
                sendStatusUpdate("Device found: " + deviceInfo);
                // Cancel ongoing Bluetooth discovery if still running.
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                    Log.d(TAG, "Bluetooth discovery cancelled after device found.");
                }
                // Stop the scanning timer immediately.
                timerHandler.removeCallbacksAndMessages(null);
                // Broadcast device info via ACTION_TIMER_UPDATE.
                Intent infoIntent = new Intent(ACTION_TIMER_UPDATE);
                infoIntent.putExtra("device_info", deviceInfo);
                infoIntent.setPackage(getApplicationContext().getPackageName());
                sendBroadcast(infoIntent);
                // Proceed to finish the scan process.
                onScanFinished();
            }
        }
    }

    // Called when a scan completes.
    private void onScanFinished() {
        // If onScanFinished has already been handled for this cycle, skip further processing.
        if (scanFinishedHandled) {
            return;
        }
        scanFinishedHandled = true; // Mark as handled.

        timerHandler.removeCallbacksAndMessages(null);
        int count = foundDevices.size();
        Log.d(TAG, "Scan finished. Devices found: " + count);
        updateNotification("Scan finished. " + count + " device(s) found");
        sendStatusUpdate("Scan finished. " + count + " device(s) found");

        // Broadcast scan results.
        Intent updateIntent = new Intent(ACTION_SCAN_RESULTS);
        updateIntent.putStringArrayListExtra("devices", foundDevices);
        updateIntent.setPackage(getApplicationContext().getPackageName());
        sendBroadcast(updateIntent);

        // Adaptive logic:
        if (!isExtendedSearch) {  // Initial scan branch.
            if (count == 0) {
                Log.d(TAG, "No devices found in initial scan. Starting extended search.");
                startExtendedSearch();
            } else {
                Log.d(TAG, "Devices found in initial scan. Disabling Bluetooth and sleeping for 30 minutes.");
                disableBluetooth();
                sleepThenRestart(SLEEP_30_MIN_MS);
            }
        } else {  // Extended Search branch.
            if (count > 0) {
                Log.d(TAG, "Device found during extended search. Disabling Bluetooth and sleeping for 30 minutes.");
                disableBluetooth();
                sleepThenRestart(SLEEP_30_MIN_MS);
            } else {
                extendedSearchElapsed += SCAN_INTERVAL_MS;
                if (extendedSearchElapsed >= EXTENDED_SEARCH_TOTAL_MS) {
                    Log.d(TAG, "Extended search reached max duration. Sleeping for 20 minutes and resetting to initial scan.");
                    isExtendedSearch = false;
                    sleepThenRestart(SLEEP_20_MIN_MS);
                } else {
                    Log.d(TAG, "Extended search ongoing. Scheduling next scan after 2 minutes.");
                    scheduleNextScan();
                }
            }
        }
    }

    // Schedule the next scan after a 2-minute interval.
    private void scheduleNextScan() {
        updateNotification("Waiting 2 minutes before next scan");
        sendStatusUpdate("Waiting 2 minutes before next scan");
        Log.d(TAG, "Scheduling next scan in " + (SCAN_INTERVAL_MS / 1000) + " seconds");
        handler.postDelayed(() -> {
            // Double-check that the service is still active.
            if (isServiceActive) {
                startDiscovery();
            }
        }, SCAN_INTERVAL_MS);
    }

    // Sleep for a specified duration and then restart scanning.
    private void sleepThenRestart(long sleepMs) {
        updateNotification("Sleeping for " + (sleepMs / 60000) + " minutes");
        sendStatusUpdate("Sleeping for " + (sleepMs / 60000) + " minutes");
        Log.d(TAG, "Sleeping for " + sleepMs + " ms, then restart scan");

        // Remove any pending sleep timer callbacks from the previous instance.
        if (currentSleepTimer != null) {
            handler.removeCallbacks(currentSleepTimer);
        }

        // Create a fresh SleepTimerRunnable.
        currentSleepTimer = new SleepTimerRunnable();
        currentSleepTimer.setRemainingSleep(sleepMs);
        handler.post(currentSleepTimer);
    }

    // Start an initial scan.
    private void startInitialScan() {
        scanFinishedHandled = false; // Reset flag for new cycle.
        isExtendedSearch = false;
        extendedSearchElapsed = 0;
        foundDevices.clear();
        updateNotification("Starting initial scan");
        sendStatusUpdate("Starting initial scan");
        enableBluetoothIfNeeded(this::startDiscovery);
    }

    // Start an extended search cycle.
    private void startExtendedSearch() {
        scanFinishedHandled = false; // Reset flag for new cycle.
        isExtendedSearch = true;
        extendedSearchElapsed = 0;
        foundDevices.clear();
        updateNotification("Starting extended search");
        sendStatusUpdate("Starting extended search");
        scheduleNextScan();
    }

    // Start Bluetooth discovery.
    private void startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        foundDevices.clear();
        bluetoothAdapter.startDiscovery();
        Log.d(TAG, "Bluetooth discovery started");

        scanStartTime = System.currentTimeMillis();
        timerHandler.post(timerRunnable);

        handler.postDelayed(() -> {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
                Log.d(TAG, "Discovery cancelled after duration expired");
            }
        }, SCAN_DURATION_MS);
    }

    // Enable Bluetooth if it’s not enabled, then run onEnabled action.
    private void enableBluetoothIfNeeded(Runnable onEnabled) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter.isEnabled()) {
            onEnabled.run();
        } else {
            bluetoothAdapter.enable();
            Log.d(TAG, "Bluetooth not enabled. Enabling... Waiting 2 sec.");
            handler.postDelayed(onEnabled, 2000);
        }
    }

    // Disable Bluetooth.
    private void disableBluetooth() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter.isEnabled()) {
            bluetoothAdapter.disable();
            Log.d(TAG, "Bluetooth disabled for sleep period.");
        }
    }

    // Broadcast a status update.
    private void sendStatusUpdate(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra("status", status);
        intent.setPackage(getApplicationContext().getPackageName());
        Log.d(TAG, "Broadcasting status update: " + status);
        sendBroadcast(intent);
    }

    private void clearScanStatusState() {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit()
                .remove("scanning_status")
                .remove("remaining_time")
                .remove("scanned_devices")
                .apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy: Cleaning up timers and receivers");
        isServiceActive = false;
        timerHandler.removeCallbacksAndMessages(null);
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(discoveryReceiver);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        clearScanStatusState();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}