package com.example.myapplication;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.ActivityCompat;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


public class DockingManager {
    public interface DockingCallback {
        void onDocked();
        void onUndocked();
        void onAmbiguous();
        void onFileTransferStart();
    }

    private static final String TAG = "DockingManager";
    private final Context context;
    private final Handler handler = new Handler();
    private final BluetoothAdapter adapter;
    private DockingCallback callback;

    // Track current Shimmer MAC being processed
    private String shimmerMac;

    // Timing variables (can be set for testing)
    public long monitoringPhaseDurationMs = 1 * 60 * 1000; // 5 min
    public long scanPeriodMs = 20 * 1000; // 20 sec
    public long scanDurationMs = 30 * 1000; // 30 sec
    public long undockedTimeoutMs = 60 * 1000; // 1 min
    public long silentStateDurationMs = 60 * 1000; // 15 min
    public long waitBeforeTransferDuration = 60 * 1000; // wait between dock query and transfer connect

    // Night mode window (settable for testing)
    public int nightStartHour = 20; // 8 PM
    public int nightEndHour = 17;    // 9 AM

    // State
    private boolean isMonitoring = false;
    private long monitoringStartTime;
    private long lastSeenTime;
    // Track found Shimmer devices (up to 2)
    private final java.util.Set<String> shimmerMacs = new java.util.LinkedHashSet<>();
    
    // Track successfully completed Shimmers
    private final java.util.Set<String> completedShimmers = new java.util.HashSet<>();

    // Track if a Shimmer was found during scan
    private boolean shimmerFound = false;

    // Track retry counts per Shimmer MAC
    private final java.util.Map<String, Integer> silentRetryCounts = new java.util.HashMap<>();
    private static final int MAX_RETRIES = 3;

    // Track device receiver registration to avoid IllegalArgumentException on unregister
    private boolean deviceReceiverRegistered = false;

    // Prevent duplicate silent-state transitions/logs
    private boolean silentActive = false;

    // Notification support for Docking status
    private static final String DOCKING_CHANNEL_ID = "ShimmerDockingChannel"; // unused but kept for clarity

    // Prevent duplicate protocol runs; set true on start, reset only after silent state ends
    private final java.util.concurrent.atomic.AtomicBoolean protocolActive = new java.util.concurrent.atomic.AtomicBoolean(false);


    private static final int MAX_SILENT_RETRIES = 2; // Maximum retries for silent state
    
    // Executor for handling background tasks
    private final Executor executor = Executors.newSingleThreadExecutor();

    public DockingManager(Context ctx, DockingCallback cb) {
        this.context = ctx;
        this.callback = cb;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
        Log.d(TAG, "DockingManager constructed");
    }

    // One-shot receivers to gate transfer advancement in round-robin and single-device flows
    private BroadcastReceiver transferDoneReceiver;
    private BroadcastReceiver transferFailedReceiver;
    private boolean transferReceiversRegistered = false;

private void registerTransferReceivers(Runnable onSuccess, Runnable onFailure) {
    if (transferReceiversRegistered) {
        unregisterTransferReceivers();
    }

    transferDoneReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            try { unregisterTransferReceivers(); } catch (Exception ignored) {}
            Log.d(TAG, "Transfer DONE broadcast received: " + intent.getAction());
            if (onSuccess != null) onSuccess.run();
        }
    };
    transferFailedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            try { unregisterTransferReceivers(); } catch (Exception ignored) {}
            Log.d(TAG, "Transfer FAILED broadcast received: " + intent.getAction());
            if (onFailure != null) onFailure.run();
        }
    };

    try {
        // Listen to the same actions DockingService registers
        IntentFilter doneFilter = new IntentFilter(DockingService.ACTION_TRANSFER_DONE);
        IntentFilter failedFilter = new IntentFilter(DockingService.ACTION_TRANSFER_FAILED);

       context.registerReceiver(transferDoneReceiver, doneFilter, Context.RECEIVER_NOT_EXPORTED);
       context.registerReceiver(transferFailedReceiver, failedFilter, Context.RECEIVER_NOT_EXPORTED);

        transferReceiversRegistered = true;
    } catch (Exception e) {
        Log.e(TAG, "Failed to register transfer receivers: " + e.getMessage());
        transferReceiversRegistered = false;
    }
}



private void unregisterTransferReceivers() {
        if (!transferReceiversRegistered) return;
        try { context.unregisterReceiver(transferDoneReceiver); } catch (Exception ignored) {}
        try { context.unregisterReceiver(transferFailedReceiver); } catch (Exception ignored) {}
        transferReceiversRegistered = false;
    }
    
    private void processShimmerQueue() {
        if (shimmerMacs.isEmpty()) {
            Log.d(TAG, "All Shimmers processed. Protocol complete.");
            protocolActive.set(false);
            return;
        }

        // Get next Shimmer from queue
        String nextMac = shimmerMacs.iterator().next();
        Log.d(TAG, "Round robin: processing Shimmer " + nextMac);
        shimmerMac = nextMac;
        
        // Start periodic monitoring for this Shimmer with callback
        startPeriodicMonitoringRoundRobin(nextMac, this::processShimmerQueue);
    }

    // Constructor continues below

    // Ensure the shared notification channel exists for Docking updates
    private void ensureDockingChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel("ShimmerScanChannel") == null) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "ShimmerScanChannel",
                        "Shimmer Scan Channel",
                        android.app.NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Channel for Shimmer Scan notifications");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void notifyDocking(String msg) {
        ensureDockingChannel();
        try {
            // Permission check for POST_NOTIFICATIONS (Android 13+)
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.NotificationCompat.Builder builder =
                        new androidx.core.app.NotificationCompat.Builder(context, "ShimmerScanChannel")
                                .setContentTitle("Shimmer Docking")
                                .setContentText(msg)
                                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                                .setOnlyAlertOnce(true);
                androidx.core.app.NotificationManagerCompat.from(context).notify(1002, builder.build());
            } else {
                Log.w(TAG, "Missing POST_NOTIFICATIONS permission for notifyDocking");
            }
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in notifyDocking: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in notifyDocking: " + e.getMessage());
        }
    }

    // Helper to notify UI about docking status directly
    private void sendDockingStatus(String status) {
        // Persist latest docking status for UI restoration
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE);
            prefs.edit().putString("status_text", status == null ? "" : status).apply();
        } catch (Exception ignored) {}

        Intent i = new Intent("com.example.myapplication.DOCKING_STATUS");
        i.setPackage(context.getPackageName());
        i.putExtra("status", status);
        context.sendBroadcast(i);
        notifyDocking(status);
    }

    // Centralized handler: when Bluetooth is OFF anywhere in Docking protocol
    private void handleBluetoothOffAndSilent(String where) {
        Log.d(TAG, "Bluetooth OFF (" + where + "). Entering silent state.");
        sendDockingStatus("Bluetooth is off. Entering silent state (15 min). Please turn on Bluetooth.");
        enterSilentState();
    }

    // State 1: Initialization Scan
    private void startInitializationScan() {
        // Reset silent-state guard when a new cycle begins
        silentActive = false;
    shimmerMacs.clear();
    Log.d(TAG, "Initialization scan started.");

        // If Bluetooth is OFF, do not mark undocked; go to silent state and inform UI
        if (adapter == null || !adapter.isEnabled()) {
            handleBluetoothOffAndSilent("init scan start");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission");
            return;
        }
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            adapter.startDiscovery();
            // Ensure we only register once
            registerDeviceReceiverIfNeeded();
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth scan failed due to missing permission", e);
            return;
        }

        handler.postDelayed(() -> {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
            safeUnregisterDeviceReceiver();

            // If Bluetooth turned OFF during scan window, do not mark undocked
            if (adapter == null || !adapter.isEnabled()) {
                handleBluetoothOffAndSilent("init scan end");
                return;
            }

            if (!shimmerMacs.isEmpty()) {
                // Iterate over a copy to avoid concurrent modification
                java.util.List<String> macsToProcess = new java.util.ArrayList<>(shimmerMacs);
                java.util.Iterator<String> iterator = macsToProcess.iterator();
                processNextShimmer(iterator);
            } else {
                // Only mark undocked if Bluetooth is ON
                if (adapter.isEnabled()) {
                    callback.onUndocked();
                } else {
                    sendDockingStatus("Bluetooth is off. Entering silent state (15 min)...");
                }
                enterSilentState();
            }
        }, scanDurationMs);
    }

    private void onShimmerFound(String mac) {
        Log.d(TAG, "Shimmer found: " + mac);
        startPeriodicMonitoring(mac);
    }

    // State 2: Periodic Monitoring State
    private void startPeriodicMonitoring(String mac) {
        isMonitoring = true;
        monitoringStartTime = System.currentTimeMillis();
        lastSeenTime = System.currentTimeMillis();
        shimmerMac = mac;
        Log.d(TAG, "Periodic monitoring started for MAC: " + mac);
        runPeriodicScan();
    }

    private void runPeriodicScan() {
        if (!isMonitoring) return;
        long elapsed = System.currentTimeMillis() - monitoringStartTime;
        if (elapsed >= monitoringPhaseDurationMs) {
            // Monitoring phase complete, ambiguous state
            isMonitoring = false;
            callback.onAmbiguous();
            startDirectQueryAndConnection();
            return;
        }

        // If Bluetooth is OFF, do not mark undocked; go to silent state and inform UI
        if (adapter == null || !adapter.isEnabled()) {
            handleBluetoothOffAndSilent("periodic scan start");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission");
            return;
        }
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            shimmerFound = false;
            adapter.startDiscovery();
            // Ensure single registration
            registerDeviceReceiverIfNeeded();
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth scan failed due to missing permission", e);
            return;
        }

        handler.postDelayed(() -> {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
            safeUnregisterDeviceReceiver();

            // If Bluetooth turned OFF during scan window, do not mark undocked
            if (adapter == null || !adapter.isEnabled()) {
                handleBluetoothOffAndSilent("periodic scan end");
                return;
            }

            // Check undocked condition when BT is ON
            if (!shimmerFound && (System.currentTimeMillis() - lastSeenTime) > undockedTimeoutMs) {
                // Only mark undocked if Bluetooth is still ON
                if (adapter.isEnabled()) {
                    isMonitoring = false;
                    callback.onUndocked();
                } else {
                    sendDockingStatus("Bluetooth is off. Entering silent state (15 min)...");
                }
                enterSilentState();
                return;
            }
            // Schedule next scan
            runPeriodicScan();
        }, scanPeriodMs);
    }

    // State 4: Silent State
    private void enterSilentState() {
        if (silentActive) return; // idempotent guard
        silentActive = true;
        Log.d(TAG, "Entering silent state...");
    // Clean up any pending transfer receivers to avoid leaks
    unregisterTransferReceivers();
        // Cancel any pending scan callbacks to avoid re-entry
        handler.removeCallbacksAndMessages(null);
        // Make sure monitoring loop halts
        isMonitoring = false;
        // Reflect in UI and Notification
        sendDockingStatus("Entering silent state (15 min)...");
        // Do NOT toggle Bluetooth here; user may turn it back on. We only pause.
        // Schedule resume after the silent window
        handler.postDelayed(() -> {
            silentActive = false;
        // Check if we have a current Shimmer to retry
        if (shimmerMac != null) {
            // Ensure retry count exists for current Shimmer
            if (!silentRetryCounts.containsKey(shimmerMac)) {
                silentRetryCounts.put(shimmerMac, 0);
            }
            handleSilentStateRetry();
        } else if (!shimmerMacs.isEmpty()) {
            // No current Shimmer but queue not empty - continue processing
            processShimmerQueue();
        } else {
            // Queue is truly empty - protocol complete
            Log.d(TAG, "No Shimmers in queue. Protocol complete.");
            protocolActive.set(false);
        }
        }, silentStateDurationMs);
    }

    // NEW: Handle retry logic after silent state
    private void handleSilentStateRetry() {
        if (shimmerMac == null) {
            startInitializationScan();
            return;
        }

        int retryCount = silentRetryCounts.getOrDefault(shimmerMac, 0);
        if (retryCount >= MAX_SILENT_RETRIES) {
            Log.d(TAG, "Max silent retries (" + MAX_SILENT_RETRIES + ") reached for " + shimmerMac + ". Moving to next Shimmer but keeping in queue.");
            
            // Reset retry count for next round
            silentRetryCounts.put(shimmerMac, 0);
            
            // Get next Shimmer to process (keeping current one in queue)
            String currentMac = shimmerMac;
            shimmerMac = null;
            
            // Find next MAC after current one
            boolean foundCurrent = false;
            String nextMac = null;
            
            for (String mac : shimmerMacs) {
                if (foundCurrent) {
                    nextMac = mac;
                    break;
                }
                if (mac.equals(currentMac)) {
                    foundCurrent = true;
                }
            }
            
            // If no next MAC, go back to first one
            if (nextMac == null && !shimmerMacs.isEmpty()) {
                nextMac = shimmerMacs.iterator().next();
            }
            
            if (nextMac != null) {
                processShimmer(nextMac, null);
            } else {
                Log.d(TAG, "No Shimmers in queue. Protocol complete.");
                protocolActive.set(false);
            }
        } else {
            Log.d(TAG, "Retrying " + shimmerMac + " after silent state (attempt " + (retryCount + 1) + "/" + MAX_SILENT_RETRIES + ")");
            silentRetryCounts.put(shimmerMac, retryCount + 1);
            startPeriodicMonitoringRoundRobin(shimmerMac, this::processShimmerQueue);
        }
    }

    // State 5: Direct Query and Connection
    private void startDirectQueryAndConnection() {
        Log.d(TAG, "Direct query and connection...");
        // If Bluetooth is OFF, do not proceed; go silent and inform UI
        if (adapter == null || !adapter.isEnabled()) {
            handleBluetoothOffAndSilent("direct query start");
            return;
        }
        new Thread(() -> {
            int dockStatus = queryDockStateFromShimmer(shimmerMac);
            handler.post(() -> handleDockStateResponse(dockStatus));
        }).start();
    }

    // State 6: Response Handling
    private void handleDockStateResponse(int status) {
        if (status < 0) { // -1 => Bluetooth off or not available
            handleBluetoothOffAndSilent("direct query response");
            return;
        }
        if (status == 0) {
            Log.d(TAG, "Shimmer is undocked.");
            callback.onUndocked();
            enterSilentState();
        } else if (status == 1) {
            Log.d(TAG, "Shimmer is docked.");
            callback.onDocked();
            callback.onFileTransferStart();
            // Stop discovery if active and wait briefly before opening RFCOMM for transfer
//            try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) {}
            Log.d(TAG, "Waiting " + waitBeforeTransferDuration + "ms before starting file transfer...");
            handler.postDelayed(this::startFileTransfer, waitBeforeTransferDuration);
        }
    }

    // State 7: File Transfer
    private void startFileTransfer() {
        Log.d(TAG, "Starting file transfer...");
        // Gate S3 sync on explicit success broadcast; enter silent on failure
        registerTransferReceivers(
            () -> {
                Log.d(TAG, "Non-RR transfer succeeded; starting S3 sync.");
                startS3Sync();
            },
            () -> {
                Log.d(TAG, "Non-RR transfer failed; entering silent state.");
                enterSilentState();
            }
        );
        new Thread(() -> {
            ShimmerFileTransferClient client = new ShimmerFileTransferClient(context);
            try {
                client.transfer(shimmerMac);
            } catch (Exception e) {
                Log.e(TAG, "Transfer threw exception: " + e.getMessage());
                // Failure path is handled by broadcast as well, but ensure silent as fallback
                enterSilentState();
            }
        }).start();
    }

    // State 8: S3 File Sync
    private void startS3Sync() {
    Log.d(TAG, "Starting S3 file sync...");
    SyncService.startSyncService(context);
    }

    private int queryDockStateFromShimmer(String macAddress) {
        // If Bluetooth is OFF, signal with -1 instead of treating as undocked
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth OFF during dock state query");
            return -1;
        }
        BluetoothSocket socket = null;
        try {
            // Permission check
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
                return 0; // Treat as undocked if permission not granted
            }

            BluetoothDevice device = adapter.getRemoteDevice(macAddress);
            socket = device.createInsecureRfcommSocketToServiceRecord(
                java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            adapter.cancelDiscovery();

            // Initialize retry count for this Shimmer if not exists
            if (!silentRetryCounts.containsKey(macAddress)) {
                silentRetryCounts.put(macAddress, 0);
            }

            // Retry connection up to 3 times
            boolean connected = false;
            for (int attempts = 1; attempts <= 3 && !connected; attempts++) {
                try {
                    socket.connect();
                    connected = true;
                } catch (IOException e) {
                    Log.e(TAG, "Socket connect attempt " + attempts + " failed.", e);
                    if (attempts < 3) try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    // If BT toggled OFF during retries, bail with -1
                    if (adapter == null || !adapter.isEnabled()) return -1;
                }
            }
            if (!connected) {
                Log.e(TAG, "Unable to connect to Shimmer after 3 retries");
                // Track failure in retries map
                int currentRetries = silentRetryCounts.getOrDefault(macAddress, 0);
                silentRetryCounts.put(macAddress, currentRetries + 1);
                return 0;
            }
            Log.d(TAG, "Connected to Shimmer: " + macAddress);

            // Send CHECK_DOCK_STATE (0xD5)
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{(byte) 0xD5});
            out.flush();
            Log.d(TAG, "Sent CHECK_DOCK_STATE (0xD5)");

            // Increase wait time before reading response
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Log.e(TAG, "Sleep interrupted before reading response", ie);
            }

            // Read RESPONSE_DOCK_STATE (0xD6) and status byte, skipping all 0xFF
            InputStream in = socket.getInputStream();
            int firstByte;
            do {
                firstByte = in.read();
                if (firstByte == -1) {
                    Log.e(TAG, "Stream ended before receiving response");
                    // Track failure in retries map
                    int currentRetries = silentRetryCounts.getOrDefault(macAddress, 0);
                    silentRetryCounts.put(macAddress, currentRetries + 1);
                    return 0;
                }
            } while (firstByte == 0xFF);

            if (firstByte == 0xD6) {
                int statusByte = in.read();
                if (statusByte == -1) {
                    Log.e(TAG, "Stream ended before receiving status byte");
                    return 0;
                }
                Log.d(TAG, "Received dock status from Shimmer: " + statusByte);
                return statusByte; // 0 = Undocked, 1 = Docked
            } else {
                Log.e(TAG, String.format("Unexpected non-FF, non-D6 byte from Shimmer: 0x%02X (%d)", firstByte, firstByte));
                return 0;
            }
        } catch (SecurityException se) {
            Log.e(TAG, "Bluetooth connect failed due to missing permission", se);
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error querying dock state: " + e.getMessage(), e);
            return 0;
        } finally {
            if (socket != null) {
                try { socket.close();
                    Log.d(TAG, "Bluetooth socket closed");
                
                } catch (Exception ignored) {
                    Log.e(TAG, "Error closing Bluetooth socket", ignored);
                }
            }
        }
    }

    // Receiver to handle found devices
    private final android.content.BroadcastReceiver deviceFoundReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (device != null && device.getName() != null && device.getName().toLowerCase().contains("shimmer")) {
                String mac = device.getAddress();
                
                // PREVENT re-adding completed Shimmers
                if (completedShimmers.contains(mac)) {
                    Log.d(TAG, "Ignoring already completed Shimmer: " + mac);
                    return;
                }
                
                // Only allow new Shimmer additions during initialization scan (not during round robin)
                if (!isMonitoring) {
                    if (shimmerMacs.size() < 2 && !shimmerMacs.contains(mac)) {
                        shimmerMacs.add(mac);
                        Log.d(TAG, "Found Shimmer: " + mac);
                    }
                } else {
                    // During round robin, only update for the current shimmerMac
                    if (shimmerMac != null && mac.equals(shimmerMac)) {
                        lastSeenTime = System.currentTimeMillis();
                        shimmerFound = true;
                        Log.d(TAG, "Found monitored Shimmer: " + mac);
                    }
                }
            }
        }
    };

    // Round robin: process each shimmer sequentially
    private void processNextShimmer(java.util.Iterator<String> iterator) {
        if (!iterator.hasNext()) {
            // All shimmers processed, round robin naturally ends
            Log.d(TAG, "Round robin completed. No more shimmers to process.");
            return;
        }
        String mac = iterator.next();
        processShimmer(mac, () -> processNextShimmer(iterator));
    }

    // For each shimmer: docking, transfer, sync, then callback to next - MODIFIED
    private void processShimmer(String mac, Runnable onComplete) {
        Log.d(TAG, "Round robin: processing Shimmer " + mac);
        // Reset retry count for this Shimmer
        silentRetryCounts.put(mac, 0);
        // Docking, transfer, sync
        startPeriodicMonitoringRoundRobin(mac, () -> {
            // MAC removal is now handled in retry logic, not here
            if (onComplete != null) onComplete.run();
        });
    }

    // Modified monitoring for round robin: after done, call onComplete
    private void startPeriodicMonitoringRoundRobin(String mac, Runnable onComplete) {
        isMonitoring = true;
        monitoringStartTime = System.currentTimeMillis();
        lastSeenTime = System.currentTimeMillis();
        shimmerMac = mac;
        Log.d(TAG, "Periodic monitoring (round robin) started for MAC: " + mac);
        runPeriodicScanRoundRobin(mac, onComplete);
    }

    // Periodic scan loop for round robin
    private void runPeriodicScanRoundRobin(String mac, Runnable onComplete) {
        if (!isMonitoring) return;
        long elapsed = System.currentTimeMillis() - monitoringStartTime;
        if (elapsed >= monitoringPhaseDurationMs) {
            // Monitoring phase complete, proceed to dock/transfer/sync
            isMonitoring = false;
            int dockStatus = queryDockStateFromShimmer(mac);
            handleDockStateResponseRoundRobin(dockStatus, mac, onComplete);
            return;
        }

        // If Bluetooth is OFF, do not mark undocked; go to silent state and inform UI
        if (adapter == null || !adapter.isEnabled()) {
            handleBluetoothOffAndSilent("periodic scan start (round robin)");
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission");
            return;
        }
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            shimmerFound = false;
            adapter.startDiscovery();
            // Ensure single registration
            registerDeviceReceiverIfNeeded();
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth scan failed due to missing permission", e);
            return;
        }

        handler.postDelayed(() -> {
            try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
            safeUnregisterDeviceReceiver();

            // If Bluetooth turned OFF during scan window, do not mark undocked
            if (adapter == null || !adapter.isEnabled()) {
                handleBluetoothOffAndSilent("periodic scan end (round robin)");
                return;
            }

            // Check undocked condition when BT is ON
            if (!shimmerFound && (System.currentTimeMillis() - lastSeenTime) > undockedTimeoutMs) {
                // Only mark undocked if Bluetooth is still ON
                if (adapter.isEnabled()) {
                    isMonitoring = false;
                    callback.onUndocked();
                } else {
                    sendDockingStatus("Bluetooth is off. Entering silent state (15 min)...");
                }
                enterSilentState();
                // Don't call onComplete here - let retry logic handle it
                return;
            }
            // Schedule next scan
            runPeriodicScanRoundRobin(mac, onComplete);
        }, scanPeriodMs);
    }

    // Modified response handler for round robin - MODIFIED
    private void handleDockStateResponseRoundRobin(int status, String mac, Runnable onComplete) {
        if (status < 0) {
            handleBluetoothOffAndSilent("direct query response");
            // Don't call onComplete here - let retry logic handle it
            return;
        }
        if (status == 0) {
            Log.d(TAG, "Shimmer is undocked (round robin).");
            callback.onUndocked();
            enterSilentState();
            // Don't call onComplete here - let retry logic handle it
        } else if (status == 1) {
            Log.d(TAG, "Shimmer is docked (round robin).");
            callback.onDocked();
            callback.onFileTransferStart();
            // Stop discovery if active and wait briefly before opening RFCOMM for transfer
//            try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) {}
            Log.d(TAG, "Waiting " + waitBeforeTransferDuration + "ms before starting file transfer (round robin)...");
            handler.postDelayed(() -> startFileTransferRoundRobin(mac, onComplete), waitBeforeTransferDuration);
        }
    }

    // Modified file transfer for round robin - MODIFIED to only remove Shimmer after successful transfer
    private void startFileTransferRoundRobin(String mac, Runnable onComplete) {
        Log.d(TAG, "Starting file transfer (round robin)...");
        // Register one-shot receivers before starting transfer
        registerTransferReceivers(
            () -> {
                // Success: remove from queue, reset counters, then S3 and continue
                Log.d(TAG, "Transfer success for " + mac + "; removing from queue and starting S3 sync.");
                synchronized (shimmerMacs) {
                    shimmerMacs.remove(mac);
                    completedShimmers.add(mac);
                }
                silentRetryCounts.remove(mac);
                shimmerMac = null;

                startS3SyncRoundRobin(() -> {
                    processShimmerQueue();
                });
            },
            () -> {
                // Failure: enter silent; keep in queue; retry logic will handle rotation
                Log.d(TAG, "Transfer failed for " + mac + "; entering silent state and keeping in queue.");
                enterSilentState();
            }
        );

        new Thread(() -> {
            ShimmerFileTransferClient client = new ShimmerFileTransferClient(context);
            try {
                client.transfer(mac);
            } catch (Exception e) {
                Log.d(TAG, "Transfer threw exception for " + mac + ": " + e.getMessage());
                // Failure will also be broadcast by client; ensure silent as fallback
                enterSilentState();
            }
        }).start();
    }

    // Modified S3 sync for round robin
    private void startS3SyncRoundRobin(Runnable onComplete) {
        Log.d(TAG, "Starting S3 file sync (round robin)...");
        SyncService.startSyncService(context);
        
        // After sync delay, continue processing queue
        handler.postDelayed(() -> {
            processShimmerQueue();
        }, 3000); // 3s delay for demo
    }

    // Safely unregister the device receiver if it was registered
    private void safeUnregisterDeviceReceiver() {
        if (!deviceReceiverRegistered) return;
        try {
            context.unregisterReceiver(deviceFoundReceiver);
        } catch (IllegalArgumentException | SecurityException ignored) {
            // ignore if not registered or missing permission
        } finally {
            deviceReceiverRegistered = false;
        }
    }

    // Register the receiver only once to avoid IllegalArgumentException on double-register
    private void registerDeviceReceiverIfNeeded() {
        if (deviceReceiverRegistered) return;
        try {
            context.registerReceiver(
                    deviceFoundReceiver,
                    new android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
            );
            deviceReceiverRegistered = true;
        } catch (SecurityException ignored) {
            deviceReceiverRegistered = false;
        }
    }

    // Force protocol into Silent State immediately (used on Bluetooth OFF).
    public void forceSilentState() {
        try {
            if (adapter != null && adapter.isDiscovering()) {
                // Explicit permission check for BLUETOOTH_SCAN
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    adapter.cancelDiscovery();
                } else {
                    Log.w(TAG, "Missing BLUETOOTH_SCAN permission for cancelDiscovery in forceSilentState");
                }
            }
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in forceSilentState: " + se.getMessage());
        } catch (Exception ignored) {}
        // Safe unregister if registered
        safeUnregisterDeviceReceiver();

        isMonitoring = false;
        // Do NOT call callback.onUndocked() here to avoid misleading UI when BT is off.
        enterSilentState();
    }

    // Night docking entry point (called by DockingService)
    public void startNightDockingFlow() {
        Log.d(TAG, "startNightDockingFlow() called");
        // Single-flight: ignore if protocol is already active (including during silent window)
        if (!protocolActive.compareAndSet(false, true)) {
            Log.d(TAG, "Docking protocol already active. Ignoring startNightDockingFlow.");
            return;
        }
        if (!isNightWindow()) {
            Log.d(TAG, "Not in night window, docking protocol will NOT start.");
            // Release guard since we didn’t actually start
            protocolActive.set(false);
            return;
        }
        Log.d(TAG, "Starting night docking protocol...");
        // Ensure ScanningService is not running to avoid conflicting timers/UI
        // try { context.stopService(new Intent(context, ScanningService.class)); } catch (Exception ignored) {}
        
        // Reset state for new protocol run
        silentRetryCounts.clear();
        Log.d(TAG, "Cleared silent retry counts.");
        completedShimmers.clear();  // Clear completed Shimmers for new run
        Log.d(TAG, "Cleared completed Shimmers.");
        
        sendDockingStatus("Docking protocol started.");
        startInitializationScan();
    }

    // Night window check
    private boolean isNightWindow() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
        Log.d(TAG, "isNightWindow: now=" + hour + ", start=" + nightStartHour + ", end=" + nightEndHour);
        if (nightStartHour < nightEndHour) {
            return hour >= nightStartHour && hour < nightEndHour;
        } else {
            return hour >= nightStartHour || hour < nightEndHour;
        }
    }
}