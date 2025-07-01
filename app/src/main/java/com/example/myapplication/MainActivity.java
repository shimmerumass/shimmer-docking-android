package com.example.myapplication;

import com.google.android.material.button.MaterialButton;
import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    private LinearLayout progressSection;
    private com.google.android.material.card.MaterialCardView filesToSyncSection;
    private Button syncButton;
    private ListView fileListView;
    private ArrayAdapter<String> fileListAdapter;
    private List<File> filesToUpload;
    private List<Boolean> uploadStatus;
    private List<Boolean> uploading;
    private String selectedMac = null;
    private FirebaseAnalytics firebaseAnalytics;

    // Receivers for Bluetooth scanning and transfer progress
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

    private final BroadcastReceiver scanResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ArrayList<String> devices = intent.getStringArrayListExtra("devices");
            if (devices != null) {
                runOnUiThread(() -> updateDeviceList(devices));
            }
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status != null) {
                runOnUiThread(() -> {
                    statusText.setText(status);
                    persistStatus(status); // supply latest remainingTime
                });
            }
        }
    };

    // Bluetooth Transfer Progress Receiver
    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int progress = intent.getIntExtra("progress", -1);
            int total = intent.getIntExtra("total", 1);
            String filename = intent.getStringExtra("filename");
            Log.d("ProgressDebug", "progress=" + progress + ", total=" + total);

            if (progress > 0 && total > 0) {
                runOnUiThread(() -> {
                    int percent = (int) ((progress * 100.0f) / total);
                    String display;
                    if (progress >= total) {
                        display = "Transfer completed!";
                        showTransferCompletedNotification(); // <-- Add this line
                        progressSection.setVisibility(View.GONE);
                    } else {
                        display = "Transfer Progress: " + progress + "/" + total + " (" + percent + "%)";
                        if (filename != null && !filename.isEmpty()) {
                            display += "\nLast file: " + filename;
                        }
                        progressSection.setVisibility(View.VISIBLE);
                    }
                    progressText.setText(display);
                    transferProgressBar.setMax(total);
                    transferProgressBar.setProgress(progress);
                });
            }
            persistTransferProgress(progress, total, filename);
        }
    };

    private void updateThemeToggleIcon(MaterialButton themeToggleButton) {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            themeToggleButton.setText("🌒");
        } else {
            themeToggleButton.setText("🌔");
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FirebaseApp.initializeApp(this);
        firebaseAnalytics = FirebaseAnalytics.getInstance(this);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true);

        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectAll().penaltyLog().build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll().penaltyLog().build());

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
            runOnUiThread(() -> progressSection.setVisibility(View.VISIBLE));
        });

        syncButton = findViewById(R.id.syncButton);
        fileListView = findViewById(R.id.fileListRecyclerView);

        syncButton.setOnClickListener(v -> {
            syncFilesWithCloud();
            runOnUiThread(() -> filesToSyncSection.setVisibility(View.VISIBLE));
        });

        progressSection = findViewById(R.id.progressSection);
        filesToSyncSection = findViewById(R.id.filesToSyncSection);

        progressSection.setVisibility(View.GONE);
        filesToSyncSection.setVisibility(View.GONE);

        // Register receivers
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        1002 // any request code
                );
            }
        }

        if (hasPermissions()) {
            startScanningService();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }

        TextView userNameTextView = findViewById(R.id.user_name_text_view);

        String deviceName = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
        }
        if (deviceName != null && !deviceName.isEmpty()) {
            userNameTextView.setText("Welcome, " + deviceName + "!");
        } else {
            userNameTextView.setText("Welcome!");
        }

        MaterialButton themeToggleButton = findViewById(R.id.themeToggleButton);
        updateThemeToggleIcon(themeToggleButton);

        themeToggleButton.setOnClickListener(v -> {
            int mode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            if (mode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            }
        });

        if (savedInstanceState != null) {
            ArrayList<String> devices = savedInstanceState.getStringArrayList("devices");
            selectedMac = savedInstanceState.getString("selectedMac");
            if (devices != null) {
                updateDeviceList(devices);
            }
            int progress = savedInstanceState.getInt("progress", 0);
            int total = savedInstanceState.getInt("progress_total", 100);
            int visibility = savedInstanceState.getInt("progress_visibility", View.GONE);
            transferProgressBar.setMax(total);
            transferProgressBar.setProgress(progress);
            progressSection.setVisibility(visibility);
        }

        restoreUIState();
    }

    private void restoreUIState() {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);

        // Restore scanned devices
        String devicesJson = prefs.getString("scanned_devices", "[]");
        ArrayList<String> devices = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(devicesJson);
            for (int i = 0; i < arr.length(); i++) {
                devices.add(arr.getString(i));
            }
        } catch (JSONException ignored) {}
        updateDeviceList(devices);

        // Restore selected device
        selectedMac = prefs.getString("selected_mac", null);

        // Restore timer/status
        long remainingTime = prefs.getLong("remaining_time", 0);
        String scanningStatus = prefs.getString("scanning_status", "Status");
        timerText.setText("Remaining: " + (remainingTime / 1000) + " sec");
        statusText.setText(scanningStatus);

        // Restore transfer progress
        if (isTransferServiceRunning()) {
            int progress = prefs.getInt("transfer_progress", 0);
            int total = prefs.getInt("transfer_total", 1);
            String filename = prefs.getString("transfer_filename", "");
            if (progress >= 0 && total > 0) {
                int percent = (int) ((progress * 100.0f) / total);
                String display = "Transfer Progress: " + progress + "/" + total + " (" + percent + "%)";
                if (filename != null && !filename.isEmpty()) {
                    display += "\nLast file: " + filename;
                }
                progressSection.setVisibility(View.VISIBLE);
                transferProgressBar.setMax(total);
                transferProgressBar.setProgress(progress);
                progressText.setText(display);
                if (progress >= total) {
                    progressSection.setVisibility(View.GONE);
                }
            } else {
                progressSection.setVisibility(View.GONE);
            }
        } else {
            // Hide/reset progress bar if transfer not running
            progressSection.setVisibility(View.GONE);
        }

        if (!isTransferServiceRunning()) {
            // Defensive: clear transfer state if service is not running
            prefs.edit()
                .remove("transfer_progress")
                .remove("transfer_total")
                .remove("transfer_filename")
                .remove("progress_visibility")
                .apply();
            progressSection.setVisibility(View.GONE);
        }
        if (!isScanningServiceRunning()) {
            // Defensive: clear scan state if service is not running
            prefs.edit()
                .remove("scanning_status")
                .remove("remaining_time")
                .remove("scanned_devices")
                .apply();
            // Hide or reset scan-related UI
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (deviceListView != null && deviceListView.getAdapter() != null) {
            ArrayList<String> devices = new ArrayList<>();
            for (int i = 0; i < deviceListView.getAdapter().getCount(); i++) {
                devices.add((String) deviceListView.getAdapter().getItem(i));
            }
            outState.putStringArrayList("devices", devices);
        }
        outState.putString("selectedMac", selectedMac);
        outState.putInt("progress", transferProgressBar.getProgress());
        outState.putInt("progress_total", transferProgressBar.getMax());
        outState.putInt("progress_visibility", progressSection.getVisibility());
        outState.putBoolean("filesToSyncVisible", filesToSyncSection.getVisibility() == View.VISIBLE);
    }

    // Call this whenever you update the device list
    private void persistDeviceList(ArrayList<String> devices) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putString("scanned_devices", new JSONArray(devices).toString()).apply();
    }

    // Call this whenever you update timer/status
    private void persistStatus (String status) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit()
                .putString("scanning_status", status)
                .apply();
    }

    // Call this whenever you update transfer progress
    private void persistTransferProgress(int progress, int total, String filename) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit()
                .putInt("transfer_progress", progress)
                .putInt("transfer_total", total)
                .putString("transfer_filename", filename)
                .putInt("progress_visibility", progressSection.getVisibility())
                .apply();
    }

    // Call this whenever you select a device
    private void persistSelectedMac(String mac) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putString("selected_mac", mac).apply();
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
        runOnUiThread(() -> {
            statusText.setText("Devices found: " + devices.size());
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, devices);
            deviceListView.setAdapter(adapter);

            if (selectedMac != null) {
                for (int i = 0; i < devices.size(); i++) {
                    String deviceInfo = devices.get(i);
                    String[] parts = deviceInfo.split(" - ");
                    if (parts.length == 2 && selectedMac.equals(parts[1])) {
                        deviceListView.setItemChecked(i, true);
                        statusText.setText("Selected device: " + parts[0]);
                        break;
                    }
                }
            }

            deviceListView.setOnItemClickListener((parent, view, position, id) -> {
                String deviceInfo = devices.get(position);
                String[] parts = deviceInfo.split(" - ");
                if (parts.length == 2) {
                    selectedMac = parts[1];
                    Toast.makeText(this, "Selected: " + selectedMac, Toast.LENGTH_SHORT).show();
                    statusText.setText("Selected device: " + parts[0]);
                }
            });

            persistDeviceList(devices); // <-- Add this line
        });
    }

    private void syncFilesWithCloud() {
        ShimmerFileTransferClient client = new ShimmerFileTransferClient(this);

        new Thread(() -> {
            List<File> localUnsynced = client.getLocalUnsyncedFiles();
            List<String> missingOnS3 = client.getMissingFilesOnS3(localUnsynced);

            filesToUpload = new ArrayList<>();
            uploadStatus = new ArrayList<>();
            uploading = new ArrayList<>();
            for (File f : localUnsynced) {
                if (missingOnS3.contains(f.getName())) {
                    filesToUpload.add(f);
                    uploadStatus.add(false);
                    uploading.add(false);
                }
            }

            runOnUiThread(() -> {
                fileListAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
                    @Override
                    public int getCount() {
                        return filesToUpload != null ? filesToUpload.size() : 0;
                    }

                    @Override
                    public String getItem(int position) {
                        File file = filesToUpload.get(position);
                        if (uploadStatus.get(position)) {
                            return "✅ " + file.getName();
                        } else if (uploading.get(position)) {
                            return "⏳ " + file.getName();
                        } else {
                            return file.getName();
                        }
                    }
                };
                fileListView.setAdapter(fileListAdapter);
            });

            for (int i = 0; i < filesToUpload.size(); i++) {
                final int pos = i;
                runOnUiThread(() -> {
                    uploading.set(pos, true);
                    fileListAdapter.notifyDataSetChanged();
                });

                boolean uploaded = client.uploadFileToS3(filesToUpload.get(pos));
                if (uploaded) {
                    client.markFileAsSynced(filesToUpload.get(pos));
                    runOnUiThread(() -> {
                        uploading.set(pos, false);
                        uploadStatus.set(pos, true);
                        fileListAdapter.notifyDataSetChanged();
                    });
                } else {
                    runOnUiThread(() -> {
                        uploading.set(pos, false);
                        fileListAdapter.notifyDataSetChanged();
                        Toast.makeText(this, "Failed to upload: " + filesToUpload.get(pos).getName(), Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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

    private boolean isTransferServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.example.myapplication.TransferService".equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isScanningServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.example.myapplication.ScanningService".equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void showTransferCompletedNotification() {
        String channelId = "transfer_channel";
        String channelName = "Bluetooth Transfer";
        android.app.NotificationManager notificationManager =
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, channelName, android.app.NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }

        android.app.NotificationCompat.Builder builder = new android.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Bluetooth File Transfer")
                .setContentText("Transfer completed successfully!")
                .setPriority(android.app.NotificationManager.IMPORTANCE_DEFAULT)
                .setAutoCancel(true);

        notificationManager.notify(1001, builder.build());
    }
}
