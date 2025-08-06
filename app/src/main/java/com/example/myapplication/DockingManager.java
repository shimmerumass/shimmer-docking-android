package com.example.myapplication;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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

    // Timing variables (can be set for testing)
    public long monitoringPhaseDurationMs = 1 * 60 * 1000; // 5 min
    public long scanPeriodMs = 20 * 1000; // 20 sec
    public long scanDurationMs = 10 * 1000; // 10 sec
    public long undockedTimeoutMs = 60 * 1000; // 1 min
    public long silentStateDurationMs = 15 * 60 * 1000; // 15 min

    // Night mode window (settable for testing)
    public int nightStartHour = 20; // 8 PM
    public int nightEndHour = 17;    // 9 AM

    // State
    private boolean isMonitoring = false;
    private long monitoringStartTime;
    private long lastSeenTime;
    private boolean shimmerFound = false;
    private String shimmerMac = null;

    public DockingManager(Context ctx, DockingCallback cb) {
        this.context = ctx;
        this.callback = cb;
        this.adapter = BluetoothAdapter.getDefaultAdapter();
        Log.d(TAG, "DockingManager constructed");
    }

    public void startNightDockingFlow() {
        Log.d(TAG, "startNightDockingFlow() called");
        if (!isNightWindow()) {
            Log.d(TAG, "Not in night window, docking protocol will NOT start.");
            return;
        }
        Log.d(TAG, "Starting night docking protocol...");
        startInitializationScan();
    }

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

    // State 1: Initialization Scan
    private void startInitializationScan() {
        shimmerFound = false;
        shimmerMac = null;
        Log.d(TAG, "Initialization scan started.");

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission");
            return;
        }
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            adapter.startDiscovery();
            context.registerReceiver(deviceFoundReceiver, new android.content.IntentFilter(BluetoothDevice.ACTION_FOUND));
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth scan failed due to missing permission", e);
            return;
        }

        handler.postDelayed(() -> {
            try {
                adapter.cancelDiscovery();
                context.unregisterReceiver(deviceFoundReceiver);
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth cancel/unregister failed", e);
            }
            if (shimmerFound && shimmerMac != null) {
                onShimmerFound(shimmerMac);
            } else {
                callback.onUndocked();
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
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission");
            return;
        }
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            shimmerFound = false;
            adapter.startDiscovery();
            context.registerReceiver(deviceFoundReceiver, new android.content.IntentFilter(BluetoothDevice.ACTION_FOUND));
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth scan failed due to missing permission", e);
            return;
        }

        handler.postDelayed(() -> {
            try {
                adapter.cancelDiscovery();
                context.unregisterReceiver(deviceFoundReceiver);
            } catch (Exception ignored) {}
            // Check undocked condition
            if (!shimmerFound && (System.currentTimeMillis() - lastSeenTime) > undockedTimeoutMs) {
                isMonitoring = false;
                callback.onUndocked();
                enterSilentState();
                return;
            }
            // Schedule next scan
            runPeriodicScan();
        }, scanPeriodMs);
    }

    // State 4: Silent State
    private void enterSilentState() {
        Log.d(TAG, "Entering silent state...");
        if (adapter.isEnabled()) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLUETOOTH_CONNECT permission");
                return;
            }
            adapter.disable();
        }
        handler.postDelayed(this::startInitializationScan, silentStateDurationMs);
    }

    // State 5: Direct Query and Connection
    private void startDirectQueryAndConnection() {
        Log.d(TAG, "Direct query and connection...");
        new Thread(() -> {
            int dockStatus = queryDockStateFromShimmer(shimmerMac);
            handler.post(() -> handleDockStateResponse(dockStatus));
        }).start();
    }

    // State 6: Response Handling
    private void handleDockStateResponse(int status) {
        if (status == 0) {
            Log.d(TAG, "Shimmer is undocked.");
            callback.onUndocked();
            enterSilentState();
        } else if (status == 1) {
            Log.d(TAG, "Shimmer is docked.");
            callback.onDocked();
            callback.onFileTransferStart();
            startFileTransfer();
        }
    }

    // State 7: File Transfer
    private void startFileTransfer() {
        Log.d(TAG, "Starting file transfer...");
        new Thread(() -> {
            ShimmerFileTransferClient client = new ShimmerFileTransferClient(context);
            client.transfer(shimmerMac);
        }).start();
    }

    private int queryDockStateFromShimmer(String macAddress) {
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

            // Retry connection up to 3 times
            boolean connected = false;
            for (int attempts = 1; attempts <= 3 && !connected; attempts++) {
                try {
                    socket.connect();
                    connected = true;
                } catch (IOException e) {
                    Log.e(TAG, "Socket connect attempt " + attempts + " failed.", e);
                    if (attempts < 3) Thread.sleep(1000);
                }
            }
            if (!connected) {
                Log.e(TAG, "Unable to connect to Shimmer after 3 retries");
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
                Thread.sleep(500); // Wait 500ms (or try 1000ms if needed)
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
                try { socket.close(); } catch (Exception ignored) {}
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
                shimmerFound = true;
                shimmerMac = device.getAddress();
                lastSeenTime = System.currentTimeMillis();
                Log.d(TAG, "Found Shimmer: " + shimmerMac);
            }
        }
    };
}