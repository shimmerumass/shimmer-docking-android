package com.example.myapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
    };

    private TextView timerText;
    private TextView statusText;
    private ListView deviceListView;
    private TextView progressText;
    private ProgressBar transferProgressBar;

    private String selectedMac = null;

    // Timer Receiver - updates UI with remaining scan time or device info.
    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String deviceInfo = intent.getStringExtra("device_info");
            if (deviceInfo != null) {
                runOnUiThread(() -> timerText.setText("Shimmer: " + deviceInfo));
            } else {
                long remainingTime = intent.getLongExtra("remaining_time", 0);
                runOnUiThread(() -> timerText.setText("Remaining: " + (remainingTime / 1000) + " sec"));
            }
        }
    };

    // Scan results Receiver - updates device list.
    private final BroadcastReceiver scanResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ArrayList<String> devices = intent.getStringArrayListExtra("devices");
            if (devices != null) {
                runOnUiThread(() -> updateDeviceList(devices));
            }
        }
    };

    // Status Receiver - updates status text.
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null) {
                runOnUiThread(() -> statusText.setText(status));
                Log.d("MainShimmer", "Status updated: " + status);
            }
        }
    };

    // Progress Receiver - shows transfer progress.
    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int progress = intent.getIntExtra("progress", -1);
            if (progress >= 0) {
                runOnUiThread(() -> {
                    transferProgressBar.setProgress(progress);
                    progressText.setText("Transfer Progress: " + progress + "%");
                });
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        deviceListView = findViewById(R.id.deviceListView);
        progressText = findViewById(R.id.progressText);
        transferProgressBar = findViewById(R.id.transferProgressBar);

        Button stopServiceButton = findViewById(R.id.stopServiceButton);
        stopServiceButton.setOnClickListener(v ->
                stopService(new Intent(MainActivity.this, ScanningService.class))
        );

        Button transferButton = findViewById(R.id.transferButton);
        transferButton.setOnClickListener(v -> {
            if (selectedMac == null) {
                Toast.makeText(this, "Please select a Shimmer device first.", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent transferIntent = new Intent(MainActivity.this, TransferService.class);
            transferIntent.putExtra(TransferService.EXTRA_MAC_ADDRESS, selectedMac);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(transferIntent);
            } else {
                startService(transferIntent);
            }
            Toast.makeText(this, "Transfer started", Toast.LENGTH_SHORT).show();
        });

        // Register receivers with export flag if needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, new IntentFilter(ScanningService.ACTION_TIMER_UPDATE),
                    Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(scanResultReceiver, new IntentFilter(ScanningService.ACTION_SCAN_RESULTS),
                    Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(statusReceiver, new IntentFilter(ScanningService.ACTION_STATUS_UPDATE),
                    Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(progressReceiver, new IntentFilter("com.example.myapplication.TRANSFER_PROGRESS"),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timerReceiver, new IntentFilter(ScanningService.ACTION_TIMER_UPDATE));
            registerReceiver(scanResultReceiver, new IntentFilter(ScanningService.ACTION_SCAN_RESULTS));
            registerReceiver(statusReceiver, new IntentFilter(ScanningService.ACTION_STATUS_UPDATE));
            registerReceiver(progressReceiver, new IntentFilter("com.example.myapplication.TRANSFER_PROGRESS"));
        }

        if (hasPermissions()) {
            startScanningService();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void startScanningService() {
        Intent serviceIntent = new Intent(this, ScanningService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateDeviceList(ArrayList<String> devices) {
        statusText.setText("Devices found: " + devices.size());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, devices);
        deviceListView.setAdapter(adapter);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = devices.get(position);
            String[] parts = deviceInfo.split(" - ");
            if (parts.length == 2) {
                selectedMac = parts[1];
                Toast.makeText(this, "Selected: " + selectedMac, Toast.LENGTH_SHORT).show();
                statusText.setText("Selected device: " + parts[0]);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(timerReceiver);
            unregisterReceiver(scanResultReceiver);
            unregisterReceiver(statusReceiver);
            unregisterReceiver(progressReceiver);
        } catch (Exception e) {
            Log.w("MainActivity", "Receiver unregistration error: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int result : grantResults) {
                granted &= (result == PackageManager.PERMISSION_GRANTED);
            }
            if (granted) {
                startScanningService();
            } else {
                Toast.makeText(this, "Permissions are required to run the scanning service", Toast.LENGTH_LONG).show();
                finish();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}