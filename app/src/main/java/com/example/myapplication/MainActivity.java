package com.example.myapplication;

import com.google.android.material.button.MaterialButton;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
    };

    // --- Permission helpers ---
    private boolean hasAllRuntimePermissions() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private List<String> getMissingRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        return missing;
    }

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true; // Below 31 no special gate
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        return am.canScheduleExactAlarms();
    }

    private String humanReadablePermission(String perm) {
        switch (perm) {
            case Manifest.permission.BLUETOOTH_CONNECT: return "Bluetooth Connect";
            case Manifest.permission.BLUETOOTH_SCAN: return "Bluetooth Scan";
            case Manifest.permission.ACCESS_FINE_LOCATION: return "Location";
            case Manifest.permission.POST_NOTIFICATIONS: return "Notifications";
            default: return perm;
        }
    }

    private String buildMissingPermissionMessage() {
        StringBuilder sb = new StringBuilder();
        List<String> missing = getMissingRuntimePermissions();
        if (!missing.isEmpty()) {
            sb.append("Missing permissions:\n");
            for (String m : missing) sb.append("• ").append(humanReadablePermission(m)).append('\n');
            sb.append('\n');
        }
        if (!canScheduleExactAlarms()) {
            sb.append("Exact alarm permission not granted (Needed for scheduled docking). Open system settings to allow \"Alarms & reminders\" for this app.\n\n");
        }
        sb.append("Please grant these to continue scanning and scheduling.");
        return sb.toString();
    }

    private void openAppSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void openExactAlarmSettingsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            try { startActivity(i); } catch (Exception ignored) { openAppSettings(); }
        }
    }

    private void showMissingPermissionPreRequestDialog(List<String> missing) {
        if (missing == null || missing.isEmpty()) return;
        StringBuilder msg = new StringBuilder("The app needs these permissions:\n\n");
        for (String p : missing) msg.append("• ").append(humanReadablePermission(p)).append('\n');
        msg.append("\nThey enable Bluetooth scanning and notifications.");
        new AlertDialog.Builder(this)
                .setTitle("Permissions Needed")
                .setMessage(msg.toString())
                .setPositiveButton("Continue", (d,w) -> ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQUEST_CODE))
                .setNegativeButton("Exit", (d,w) -> finish())
                .setCancelable(false)
                .show();
    }

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

    // --- FIX: Initialize lists to prevent NullPointerException ---
    private List<File> filesToUpload = new ArrayList<>();
    private List<Boolean> uploadStatus = new ArrayList<>();
    private List<Boolean> uploading = new ArrayList<>();
    // --- END OF FIX ---

    private String selectedMac = null;
    private FirebaseAnalytics firebaseAnalytics;
    private Button transferButton;

    // Receiver to reflect transfer/sync lifecycle in UI


    private final BroadcastReceiver transferSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if ("com.example.myapplication.ACTION_TRANSFER_START".equals(action)) {
                showProgress("Transferring from sensor...");
            } else if ("com.example.myapplication.TRANSFER_DONE".equals(action)) {
                showProgress("Transfer complete. Syncing...");
            } else if ("com.example.myapplication.TRANSFER_FAILED".equals(action)) {
                showProgress("Transfer failed.");
                hideProgressDelayed(2000);
            } else if (FileSyncService.ACTION_SYNC_START.equals(action)) {
                showProgress("Syncing to cloud...");
            } else if (FileSyncService.ACTION_SYNC_DONE.equals(action)) {
                hideProgress();
            }
        }
    };

    private void showProgress(String msg) {
        if (progressSection != null) progressSection.setVisibility(View.VISIBLE);
        if (progressText != null) progressText.setText(msg);
        if (transferProgressBar != null) {
            transferProgressBar.setIndeterminate(true);
            transferProgressBar.setVisibility(View.VISIBLE);
        }
    }

    private void hideProgress() {
        if (progressSection != null) progressSection.setVisibility(View.GONE);
    }

    private void hideProgressDelayed(long ms) {
        if (progressSection != null) progressSection.postDelayed(this::hideProgress, ms);
    }

    // Receivers for Bluetooth scanning and transfer progress
    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long remainingTime = intent.getLongExtra("remaining_time", 0);
            runOnUiThread(() -> timerText.setText("Remaining: " + (remainingTime / 1000) + " sec"));
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
            // Ignore scanning status updates while DockingService is running to prevent 15s vs 15m conflicts
            if (isDockingServiceRunning()) return;
            String status = intent.getStringExtra("status");
            if (status != null) {
                runOnUiThread(() -> {
                    statusText.setText(status);
                    persistStatus(status);
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

            runOnUiThread(() -> {
                progressSection.setVisibility(View.VISIBLE);
                transferProgressBar.setVisibility(View.VISIBLE);

                // Disable buttons and hide stop scanning during transfer
                syncButton.setEnabled(false);
                transferButton.setEnabled(false);


                int percent = (int) ((progress * 100.0f) / total);
                String display;
                if (progress >= total) {
                    display = "Transfer completed!";
                    showTransferCompletedNotification();
                    progressSection.setVisibility(View.GONE);

                    // Re-enable buttons and show stop scanning after transfer
                    syncButton.setEnabled(true);
                    transferButton.setEnabled(true);

                } else {
                    display = "Transfer Progress: " + progress + "/" + total + " (" + percent + "%)";
                    if (filename != null && !filename.isEmpty()) {
                        display += "\nLast file: " + filename;
                    }
                }
                progressText.setText(display);
                transferProgressBar.setMax(total);
                transferProgressBar.setProgress(progress);
            });

            persistTransferProgress(progress, total, filename);
        }
    };

    private final BroadcastReceiver transferErrorReceiver = new BroadcastReceiver() {
        private android.os.CountDownTimer countDownTimer;

        @Override
        public void onReceive(Context context, Intent intent) {
            String errorMessage = intent.getStringExtra("error_message");
            int retrySeconds = intent.getIntExtra("retry_seconds", 0);

            Log.d("MainActivity", "Received transfer error: " + errorMessage + ", retrySeconds=" + retrySeconds);

            runOnUiThread(() -> {
                progressSection.setVisibility(View.VISIBLE);
                transferProgressBar.setVisibility(View.GONE);
                if (countDownTimer != null) countDownTimer.cancel();

                if (errorMessage != null && !errorMessage.isEmpty() && retrySeconds > 0) {
                    // Show error and timer
                    countDownTimer = new android.os.CountDownTimer(retrySeconds * 1000, 1000) {
                        public void onTick(long millisUntilFinished) {
                            int secs = (int) (millisUntilFinished / 1000);
                            progressText.setText(errorMessage.replace("1:00", String.format("0:%02d", secs)));
                        }
                        public void onFinish() {
                            progressText.setText("");
                            progressSection.setVisibility(View.GONE);
                            transferProgressBar.setVisibility(View.VISIBLE);
                        }
                    }.start();
                } else if (errorMessage != null && !errorMessage.isEmpty()) {
                    // Show error without timer
                    progressText.setText(errorMessage);
                    progressSection.setVisibility(View.VISIBLE);
                    transferProgressBar.setVisibility(View.GONE);
                } else {
                    // Hide error UI
                    progressSection.setVisibility(View.GONE);
                    transferProgressBar.setVisibility(View.VISIBLE);
                }
            });
        }
    };

    private final BroadcastReceiver dockingStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            if (status == null) return;
            try {
                TextView statusView = findViewById(R.id.statusText);
                if (statusView != null) {
                    statusView.setText(status);
                } else {
                    Toast.makeText(MainActivity.this, status, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {
                Toast.makeText(MainActivity.this, status, Toast.LENGTH_SHORT).show();
            }
        }
    };

    private void updateThemeToggleIcon(MaterialButton themeToggleButton) {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            themeToggleButton.setText("🌙");
        } else {
            themeToggleButton.setText("☀️");
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

        progressSection = findViewById(R.id.progressSection);
        filesToSyncSection = findViewById(R.id.filesToSyncSection);

        progressSection.setVisibility(View.GONE);
        filesToSyncSection.setVisibility(View.GONE);

        transferButton = findViewById(R.id.transferButton);
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
            registerReceiver(transferErrorReceiver, new IntentFilter("com.example.myapplication.TRANSFER_ERROR"),
                    Context.RECEIVER_NOT_EXPORTED);
            // Register Docking status updates for UI
            registerReceiver(dockingStatusReceiver, new IntentFilter("com.example.myapplication.DOCKING_STATUS"),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timerReceiver, new IntentFilter(ScanningService.ACTION_TIMER_UPDATE));
            registerReceiver(scanResultReceiver, new IntentFilter(ScanningService.ACTION_SCAN_RESULTS));
            registerReceiver(statusReceiver, new IntentFilter(ScanningService.ACTION_STATUS_UPDATE));
            registerReceiver(progressReceiver, new IntentFilter("com.example.myapplication.TRANSFER_PROGRESS"));
            registerReceiver(transferErrorReceiver, new IntentFilter("com.example.myapplication.TRANSFER_ERROR"));
            // Register Docking status updates for UI
            registerReceiver(dockingStatusReceiver, new IntentFilter("com.example.myapplication.DOCKING_STATUS"));
        }

        // Request only missing runtime permissions first
        List<String> missingAtLaunch = getMissingRuntimePermissions();
    if (!missingAtLaunch.isEmpty()) { showMissingPermissionPreRequestDialog(missingAtLaunch); return; }
        // Prompt for special exact alarm separately (cannot be in normal request)
        maybePromptExactAlarmPermission();
        // if (!isDockingServiceRunning()) 
        startScanningService();

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

        // For demo/testing, start protocol on button click:
        Button dockingButton = findViewById(R.id.dockingButton);
        dockingButton.setOnClickListener(v -> {
            int now = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            int start = getDockingStartHour();
            int end = getDockingEndHour();
            if (!isWithinDockingWindow(now, start, end)) {
                Toast.makeText(this, "Docking protocol can only be started between " +
                        String.format("%02d:00 and %02d:00", start, end), Toast.LENGTH_LONG).show();
                return;
            }
            Log.d("MainActivity", "Docking button pressed");

            // Stop ScanningService if running
            // if (isScanningServiceRunning()) {
            //     Log.d("MainActivity", "Stopping ScanningService before starting DockingService");
            //     Intent stopScanIntent = new Intent(this, ScanningService.class);
            //     stopService(stopScanIntent);
            // }

            // Start DockingService
            Intent dockingIntent = new Intent(this, DockingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(dockingIntent);
            } else {
                startService(dockingIntent);
            }
        });

        // Shows the Android version in a Toast
        if (savedInstanceState == null) {
            String phoneMac = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            Toast.makeText(this, "Android version: " + Build.VERSION.RELEASE + "\nDevice ID: " + phoneMac, Toast.LENGTH_LONG).show();
        }

        // Show the About dialog on first launch
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean("is_first_launch", true);
        if (isFirstLaunch) {
            showAboutDialog();
            prefs.edit().putBoolean("is_first_launch", false).apply();
        }

        // Wire About and Map buttons
        MaterialButton aboutButton = findViewById(R.id.aboutButton);
        aboutButton.setOnClickListener(v -> showAboutDialog());
        android.view.View mapButton = findViewById(R.id.mapButton);
        if (mapButton != null) {
            mapButton.setOnClickListener(v -> onMapDeviceButtonClicked());
        }

        updateDockingHoursText();
        findViewById(R.id.changeDockingHoursButton).setOnClickListener(v -> showDockingHoursPopup());
    }

    // Restore on app start/reopen
    private void restoreUIState() {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);

        // Restore status text
        String lastStatus = prefs.getString("status_text", "");
        if (lastStatus != null && !lastStatus.isEmpty()) {
            statusText.setText(lastStatus);
        }

        // Restore scanned devices (prefer JSON, fallback to newline format)
        ArrayList<String> devices = new ArrayList<>();
        String devicesJson = prefs.getString("scanned_devices_json", null);
        if (devicesJson != null && !devicesJson.isEmpty()) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(devicesJson);
                for (int i = 0; i < arr.length(); i++) devices.add(arr.getString(i));
            } catch (Exception ignored) {}
        }
        if (devices.isEmpty()) {
            String saved = prefs.getString("scanned_devices", "");
            if (saved != null && !saved.isEmpty()) {
                String[] lines = saved.split("\n");
                for (String line : lines) {
                    if (line != null && !line.trim().isEmpty()) devices.add(line.trim());
                }
            }
        }
        updateDeviceList(devices);

        // Restore selected MAC
        String mac = prefs.getString("selected_mac", "");
        if (mac != null && !mac.isEmpty()) {
            selectedMac = mac;
        }
    }

    // Ensure we persist selection when user taps a device
    private void setupDeviceList() {
        // ...existing code that initializes deviceListView...
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = (String) parent.getItemAtPosition(position);
            String[] parts = deviceInfo.split(" - ");
            if (parts.length == 2) {
                selectedMac = parts[1];
                persistSelectedMacCompat(selectedMac);
            }
        });
    }

    // Call this whenever you update the device list
    private void persistDeviceListCompat(ArrayList<String> devices) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        // JSON format
        prefs.edit().putString("scanned_devices_json", new JSONArray(devices).toString()).apply();
        // Newline format for backward compatibility
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < devices.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(devices.get(i));
        }
        prefs.edit().putString("scanned_devices", sb.toString()).apply();
    }

    // Call this whenever you select a device
    private void persistSelectedMacCompat(String mac) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putString("selected_mac", mac).apply();
    }

    private void updateDeviceList(ArrayList<String> devices) {
        runOnUiThread(() -> {
            // If list is empty, clear selection and UI immediately
            if (devices == null || devices.isEmpty()) {
                selectedMac = null;
                ArrayAdapter<String> emptyAdapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, new ArrayList<>());
                deviceListView.setAdapter(emptyAdapter);
                deviceListView.clearChoices();
                emptyAdapter.notifyDataSetChanged();
                if (!isDockingServiceRunning()) {
                    statusText.setText("No devices found");
                }
                persistDeviceListCompat(new ArrayList<>());
                return;
            }

            // Do not overwrite Docking status text when DockingService is running
            if (!isDockingServiceRunning()) {
                statusText.setText("Devices found: " + devices.size());
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, devices);
            deviceListView.setAdapter(adapter);

            if (selectedMac != null) {
                for (int i = 0; i < devices.size(); i++) {
                    String deviceInfo = devices.get(i);
                    String[] parts = deviceInfo.split(" - ");
                    if (parts.length == 2 && selectedMac.equals(parts[1])) {
                        deviceListView.setItemChecked(i, true);
                        if (!isDockingServiceRunning()) {
                            statusText.setText("Selected device: " + parts[0]);
                        }
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
                    if (!isDockingServiceRunning()) {
                        statusText.setText("Selected device: " + parts[0]);
                    }
                }
            });

            persistDeviceListCompat(devices);
        });
    }

    // Persist incoming UI state from broadcasts
    private final BroadcastReceiver appReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ScanningService.ACTION_STATUS_UPDATE.equals(action)) {
                String status = intent.getStringExtra("status");
                if (status != null) {
                    statusText.setText(status);
                    persistStatus(status); // replaced persistStatusText
                }
            } else if ("com.example.myapplication.DOCKING_STATUS".equals(action)) {
                String status = intent.getStringExtra("status");
                if (status != null) {
                    statusText.setText(status);
                    persistStatus(status); // replaced persistStatusText
                }
            } else if (ScanningService.ACTION_SCAN_RESULTS.equals(action)) {
                ArrayList<String> devices = intent.getStringArrayListExtra("devices");
                if (devices == null) devices = new ArrayList<>();
                updateDeviceList(devices);
                persistDeviceListCompat(devices);
            } else if (ScanningService.ACTION_TIMER_UPDATE.equals(action)) {
                if (intent.hasExtra("remaining_time")) {
                    long remaining = intent.getLongExtra("remaining_time", 0);
                    SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
                    prefs.edit().putLong("remaining_time", remaining).apply();
                }
            } else if (ScanningService.ACTION_SLEEP_TIMER_UPDATE.equals(action)) {
                if (intent.hasExtra("remaining_sleep")) {
                    long remaining = intent.getLongExtra("remaining_sleep", 0);
                    SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
                    prefs.edit().putLong("remaining_sleep", remaining).apply();
                }
            }
        }
    };

    // Call this whenever you update the device list
    private void persistDeviceList(ArrayList<String> devices) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        // JSON format
        prefs.edit().putString("scanned_devices_json", new JSONArray(devices).toString()).apply();
        // Newline format for backward compatibility
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < devices.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(devices.get(i));
        }
        prefs.edit().putString("scanned_devices", sb.toString()).apply();
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

    // Call this whenever you sync files
    private void persistSyncState(List<File> files, List<Boolean> status, List<Boolean> uploading) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < files.size(); i++) {
            editor.putBoolean("file_" + i + "_status", status.get(i));
            editor.putBoolean("file_" + i + "_uploading", uploading.get(i));
        }
        editor.putInt("files_to_upload_size", files.size());
        editor.apply();
    }

    private void persistSyncDisplayList(ArrayAdapter<String> adapter) {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        JSONArray arr = new JSONArray();
        for (int i = 0; i < adapter.getCount(); i++) {
            arr.put(adapter.getItem(i));
        }
        prefs.edit().putString("sync_display_list", arr.toString()).apply();
    }

    private boolean hasPermissions() { return hasAllRuntimePermissions(); }

    private void maybePromptExactAlarmPermission() {
        if (canScheduleExactAlarms()) return;
        new AlertDialog.Builder(this)
                .setTitle("Exact Alarm Permission")
                .setMessage("Exact alarms are needed to auto-start the docking protocol. Open Settings and allow 'Alarms & reminders' for this app.")
                .setPositiveButton("Settings", (d,w) -> openExactAlarmSettingsIfNeeded())
                .setNegativeButton("Later", null)
                .show();
    }

    private void startScanningService() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permissions not granted. Please grant permissions to start scanning.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent serviceIntent = new Intent(this, ScanningService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
            // startService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void syncFilesWithCloud() {
        ShimmerFileTransferClient client = new ShimmerFileTransferClient(this);

        new Thread(() -> {
            // Layer 1: Get all files marked as unsynced in the local DB.
            List<File> localUnsynced = client.getLocalUnsyncedFiles();
            if (localUnsynced.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No new files to sync.", Toast.LENGTH_SHORT).show());
                return;
            }

            // Layer 2: Ask the server which of those files are actually missing.
            List<String> missingOnS3 = client.getMissingFilesOnS3(localUnsynced);

            filesToUpload.clear();
            uploadStatus.clear();
            uploading.clear();

            // --- MODIFIED LOGIC: UPLOAD OR CORRECT ---
            for (File f : localUnsynced) {
                if (missingOnS3.contains(f.getName())) {
                    // This file is genuinely missing on the server. Add it to the upload queue.
                    filesToUpload.add(f);
                    uploadStatus.add(false);
                    uploading.add(false);
                } else {
                    // The server already has this file, but our DB says SYNCED=0.
                    // Correct the local database entry.
                    Log.d("FileSync", "Correcting DB: Server has '" + f.getName() + "', marking as SYNCED=1.");
                    client.markFileAsSynced(f);
                }
            }
            // --- END OF MODIFIED LOGIC --- DockingScheduler

            // This check now correctly handles the case where all unsynced files
            // were corrected, leaving nothing to upload.
            if (filesToUpload.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "All local files are already on the server.", Toast.LENGTH_SHORT).show());
                return;
            }

            runOnUiThread(() -> {
                fileListAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
                    @Override
                    public int getCount() { return filesToUpload != null ? filesToUpload.size() : 0; }
                    @Override
                    public String getItem(int position) {
                        File file = filesToUpload.get(position);
                        if (uploadStatus.get(position)) {
                            return "✅ " + file.getName();
                        } else if (uploading.get(position)) {
                            return "⏳ " + file.getName();
                        } else {
                            return "❌ " + file.getName();
                        }
                    }
                };
                fileListView.setAdapter(fileListAdapter);
                filesToSyncSection.setVisibility(View.VISIBLE);

                persistSyncDisplayList(fileListAdapter); // <-- Add this line
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
                        persistSyncDisplayList(fileListAdapter); // <-- Add this line
                    });
                } else {
                    runOnUiThread(() -> {
                        uploading.set(pos, false);
                        fileListAdapter.notifyDataSetChanged();
                        persistSyncState(filesToUpload, uploadStatus, uploading);
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
            unregisterReceiver(transferErrorReceiver);
            unregisterReceiver(dockingStatusReceiver);
        } catch (Exception e) {
            Log.w("MainActivity", "Receiver unregistration error: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            List<String> stillMissing = getMissingRuntimePermissions();
            if (stillMissing.isEmpty()) {
                startScanningService();
                maybePromptExactAlarmPermission();
            } else {
                StringBuilder msg = new StringBuilder("The following permissions are required:\n\n");
                for (String p : stillMissing) msg.append("• ").append(humanReadablePermission(p)).append('\n');
                msg.append("\nWithout them scanning cannot proceed.");
                new AlertDialog.Builder(this)
                        .setTitle("Permissions Needed")
                        .setMessage(msg.toString())
                        .setPositiveButton("Retry", (d,w) -> ActivityCompat.requestPermissions(this, stillMissing.toArray(new String[0]), PERMISSION_REQUEST_CODE))
                        .setNeutralButton("App Settings", (d,w) -> openAppSettings())
                        .setNegativeButton("Exit", (d,w) -> finish())
                        .setCancelable(false)
                        .show();
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

    private boolean isSyncServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.example.myapplication.SyncService".equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isDockingServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.example.myapplication.DockingService".equals(service.service.getClassName())) {
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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId) // <-- Use correct class
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Bluetooth File Transfer")
                .setContentText("Transfer completed successfully!")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        notificationManager.notify(1001, builder.build());
    }

    private void showAboutDialog() {
        ScrollView scrollView = new ScrollView(this);
        TextView aboutText = new TextView(this);
        aboutText.setText(
                "About Shimmer Sensor App\n\n" +
                        "This app helps you connect to your Shimmer sensor, transfer files, and sync data to the cloud.\n\n" +
                        "How to Operate:\n" +
                        "1. Make sure Bluetooth is enabled on your device.\n" +
                        "2. Tap 'Start Transfer' to begin transferring sensor data.\n" +
                        "3. Tap 'Sync to Cloud' to upload files.\n" +
                        "4. Tap 'Start Docking' to connect to a docked sensor.\n\n" +
                        "Accessibility:\n" +
                        "- Large buttons and text for easy use.\n" +
                        "- High contrast colors for readability.\n" +
                        "- Designed for older adults and stroke patients.\n\n" +
                        "If you need help, ask a caregiver or family member."
        );
        aboutText.setPadding(32, 32, 32, 32);
        aboutText.setTextSize(18f);
        scrollView.addView(aboutText);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("About")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show();
    }

    // Docking hours popup and logic
    private void showDockingHoursPopup() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("Set Docking Protocol Hours");
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, 24);
        layout.addView(title);

        // Start time picker
        TextView startLabel = new TextView(this);
        startLabel.setText("Start Time:");
        layout.addView(startLabel);

        LinearLayout startTimeLayout = new LinearLayout(this);
        startTimeLayout.setOrientation(LinearLayout.HORIZONTAL);

    final NumberPicker startHourPicker = new NumberPicker(this);
        startHourPicker.setMinValue(1);
        startHourPicker.setMaxValue(12);
        int startHour24Value = getDockingStartHour();
        int startHour12 = (startHour24Value == 0 || startHour24Value == 12) ? 12 : startHour24Value % 12;
        startHourPicker.setValue(startHour12);
        startTimeLayout.addView(startHourPicker);

    final NumberPicker startMinutePicker = new NumberPicker(this);
    startMinutePicker.setMinValue(0);
    startMinutePicker.setMaxValue(59);
    startMinutePicker.setValue(getDockingStartMinute());
    startTimeLayout.addView(startMinutePicker);

        final NumberPicker startAmPmPicker = new NumberPicker(this);
        startAmPmPicker.setMinValue(0);
        startAmPmPicker.setMaxValue(1);
        startAmPmPicker.setDisplayedValues(new String[]{"AM", "PM"});
        startAmPmPicker.setValue(startHour24Value < 12 ? 0 : 1);
        startTimeLayout.addView(startAmPmPicker);

        layout.addView(startTimeLayout);

        // End time picker
        TextView endLabel = new TextView(this);
        endLabel.setText("End Time:");
        layout.addView(endLabel);

        LinearLayout endTimeLayout = new LinearLayout(this);
        endTimeLayout.setOrientation(LinearLayout.HORIZONTAL);

    final NumberPicker endHourPicker = new NumberPicker(this);
        endHourPicker.setMinValue(1);
        endHourPicker.setMaxValue(12);
        int endHour24Value = getDockingEndHour();
        int endHour12 = (endHour24Value == 0 || endHour24Value == 12) ? 12 : endHour24Value % 12;
        endHourPicker.setValue(endHour12);
        endTimeLayout.addView(endHourPicker);

    final NumberPicker endMinutePicker = new NumberPicker(this);
    endMinutePicker.setMinValue(0);
    endMinutePicker.setMaxValue(59);
    endMinutePicker.setValue(getDockingEndMinute());
    endTimeLayout.addView(endMinutePicker);

        final NumberPicker endAmPmPicker = new NumberPicker(this);
        endAmPmPicker.setMinValue(0);
        endAmPmPicker.setMaxValue(1);
        endAmPmPicker.setDisplayedValues(new String[]{"AM", "PM"});
        endAmPmPicker.setValue(endHour24Value < 12 ? 0 : 1);
        endTimeLayout.addView(endAmPmPicker);

        layout.addView(endTimeLayout);

        scrollView.addView(layout);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Docking Protocol Hours")
                .setView(scrollView)
                .setPositiveButton("Save", (dialog, which) -> {
                    int startHour = startHourPicker.getValue();
                    int startAmPm = startAmPmPicker.getValue();
                    int endHour = endHourPicker.getValue();
                    int endAmPm = endAmPmPicker.getValue();
                    int startMinute = startMinutePicker.getValue();
                    int endMinute = endMinutePicker.getValue();
                    // Compute 24-hour format directly
                    int startHour24 = (startHour % 12) + (startAmPm == 1 ? 12 : 0);
                    int endHour24 = (endHour % 12) + (endAmPm == 1 ? 12 : 0);
                    setDockingHours(startHour24, startMinute, endHour24, endMinute);
                    updateDockingHoursText();
                    DockingScheduler.scheduleDailyDocking(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateDockingHoursText() {
        TextView dockingHoursText = findViewById(R.id.dockingHoursText);
        int sh = getDockingStartHour();
        int sm = getDockingStartMinute();
        int eh = getDockingEndHour();
        int em = getDockingEndMinute();
        dockingHoursText.setText(String.format("Docking window: %02d:%02d - %02d:%02d", sh, sm, eh, em));
    }

    private static final String PREFS_DOCKING = "docking_prefs";
    private static final String KEY_START_HOUR = "night_start_hour";
    private static final String KEY_START_MIN = "night_start_minute";
    private static final String KEY_END_HOUR = "night_end_hour";
    private static final String KEY_END_MIN = "night_end_minute";

    private int getDockingStartHour() { return getSharedPreferences(PREFS_DOCKING, MODE_PRIVATE).getInt(KEY_START_HOUR, 20); }
    private int getDockingStartMinute() { return getSharedPreferences(PREFS_DOCKING, MODE_PRIVATE).getInt(KEY_START_MIN, 0); }
    private int getDockingEndHour() { return getSharedPreferences(PREFS_DOCKING, MODE_PRIVATE).getInt(KEY_END_HOUR, 9); }
    private int getDockingEndMinute() { return getSharedPreferences(PREFS_DOCKING, MODE_PRIVATE).getInt(KEY_END_MIN, 0); }
    private void setDockingHours(int sh, int sm, int eh, int em) {
        getSharedPreferences(PREFS_DOCKING, MODE_PRIVATE)
                .edit()
                .putInt(KEY_START_HOUR, sh)
                .putInt(KEY_START_MIN, sm)
                .putInt(KEY_END_HOUR, eh)
                .putInt(KEY_END_MIN, em)
                .apply();
    }

    private boolean isWithinDockingWindow(int currentHour, int startHour, int endHour) {
        if (startHour == endHour) return false;
        if (startHour < endHour) {
            return currentHour >= startHour && currentHour < endHour;
        } else {
            return currentHour >= startHour || currentHour < endHour;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Restore last known UI state (status text, device list, selection)
        restoreUIState();
    }


    private void onMapDeviceButtonClicked() {
        // Check Wi-Fi connectivity first
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean wifiConnected = false;
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network nw = cm.getActiveNetwork();
                if (nw != null) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
                    wifiConnected = nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                }
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                wifiConnected = ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
            }
        }
        if (!wifiConnected) {
            Toast.makeText(this, "Wi-Fi required for mapping", Toast.LENGTH_SHORT).show();
            Log.w("MapButton", "Wi-Fi not connected, aborting mapping dialog");
            return;
        }

        // get MAC from UI or fallback
        String mac = getAutoMacFromUi();
        Log.d("MapButton", "Auto MAC from UI: " + mac);
        if (mac == null || mac.isEmpty()) {
            mac = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID
            );
            Log.d("MapButton", "Fallback MAC (ANDROID_ID): " + mac);
        }
        if (mac == null || mac.isEmpty()) {
            Log.w("MapButton", "No MAC found for mapping dialog");
            Toast.makeText(this, "No MAC found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Do GET first
        String finalMac = mac;
        Log.d("MapButton", "Sending GET for MAC: " + finalMac);
        new Thread(() -> {
            try {
                String urlStr = "https://odb777ddnc.execute-api.us-east-2.amazonaws.com/ddb/device-patient-map/"
                        + URLEncoder.encode(finalMac, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("accept", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                String name = null;
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                    String resp = sb.toString();
                    name = parseNameFromResponse(resp, finalMac);
                    Log.d("MapButton", "GET success for MAC: " + finalMac + ", name: " + name);
                } else {
                    Log.d("MapButton", "GET failed for MAC: " + finalMac + ", code: " + code);
                }

                String finalName = name;
                int finalCode = code;
                runOnUiThread(() -> {
                    Log.d("MapButton", "Opening dialog for MAC: " + finalMac + ", mappingFound: " + (finalCode == 200 && finalName != null && !finalName.isEmpty()));
                    showDevicePatientMapDialog(finalMac, finalName,
                            finalCode == 200 && finalName != null && !finalName.isEmpty());
                });

            } catch (Exception e) {
                Log.e("MapButton", "Error checking mapping: " + e.getMessage());
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error checking mapping: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    showDevicePatientMapDialog(finalMac, null, false);
                });
            }
        }).start();
    }

    private void showDevicePatientMapDialog(String mac, String existingName, boolean mappingFound) {
        Log.d("MapButton", "showDevicePatientMapDialog: mac=" + mac + ", mappingFound=" + mappingFound + ", name=" + existingName);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        EditText macInput = new EditText(this);
        macInput.setHint("Device MAC");
        macInput.setText(mac);
        macInput.setEnabled(false);
        layout.addView(macInput);

        EditText nameInput = new EditText(this);
        nameInput.setHint("Patient name");
        if (mappingFound && existingName != null && !existingName.isEmpty()) {
            nameInput.setText(existingName);
            nameInput.setEnabled(false); // read-only if mapping exists
            Log.d("MapButton", "Mapping found for MAC, showing name read-only");
            Toast.makeText(this, "Mapping found for MAC", Toast.LENGTH_SHORT).show();
        } else {
            nameInput.setEnabled(true);
            Log.d("MapButton", "No mapping found, enabling name input");
            Toast.makeText(this, "No mapping found. Enter patient name.", Toast.LENGTH_SHORT).show();
        }
        layout.addView(nameInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Map Device to Patient")
                .setView(layout)
                .setNegativeButton("Cancel", (d, w) -> d.dismiss());

        AlertDialog dialog;
        if (mappingFound && existingName != null && !existingName.isEmpty()) {
            builder.setPositiveButton("Close", (d, w) -> d.dismiss());
            dialog = builder.create();
        } else {
            builder.setPositiveButton("Save", null);
            dialog = builder.create();
            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String nameVal = nameInput.getText().toString().trim();
                    if (nameInput.isEnabled() && nameVal.isEmpty()) {
                        Log.d("MapButton", "Save clicked but name empty");
                        Toast.makeText(this, "Enter patient name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Log.d("MapButton", "Save clicked, sending PUT for MAC: " + mac + ", name: " + nameVal);
                    new Thread(() -> putMapping(mac, nameVal, dialog)).start();
                });
            });
        }
        dialog.show();
    }


    // Replace old getMapping with dialog-aware version
    private void getMapping(String mac, EditText nameInput, EditText macInput, AlertDialog dialog) {
        try {
            String urlStr = "https://odb777ddnc.execute-api.us-east-2.amazonaws.com/ddb/device-patient-map/" + URLEncoder.encode(mac, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line; while ((line = br.readLine()) != null) sb.append(line);
                String resp = sb.toString();
                String name = parseNameFromResponse(resp, mac);
                runOnUiThread(() -> {
                    if (name != null && !name.isEmpty()) {
                        nameInput.setText(name);
                        nameInput.setEnabled(false);
                        macInput.setEnabled(false);
                        // Change Save to Close
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Close");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> dialog.dismiss());
                    } else {
                        nameInput.setEnabled(true);
                        macInput.setEnabled(true);
                        // Keep Save behavior
                    }
                });
            } else {
                runOnUiThread(() -> {
                    nameInput.setEnabled(true);
                    macInput.setEnabled(true);
                });
            }
        } catch (Exception e) {
            runOnUiThread(() -> {
                nameInput.setEnabled(true);
                macInput.setEnabled(true);
            });
        }
    }

    private void putMapping(String mac, String name, AlertDialog dialog) {
        try {
            String urlStr = "https://odb777ddnc.execute-api.us-east-2.amazonaws.com/ddb/device-patient-map";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            // Body per requirement: { "mac":"name" }
            JSONObject body = new JSONObject();
            body.put(mac, name);
            byte[] out = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) { os.write(out); }
            int code = conn.getResponseCode();
            runOnUiThread(() -> {
                if (code >= 200 && code < 300) {
                    Toast.makeText(this, "Mapping saved", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    Toast.makeText(this, "Save failed (" + code + ")", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Error saving: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private String parseNameFromResponse(String resp, String mac) {
        try {
            JSONObject obj = new JSONObject(resp);
            if (obj.has("name")) return obj.optString("name", "");
            if (obj.has(mac)) return obj.optString(mac, "");
            if (obj.length() == 1) {
                String key = obj.keys().next();
                return obj.optString(key, "");
            }
        } catch (Exception ignored) {}
        // Fallback: raw text if not JSON
        return resp != null && resp.length() > 0 ? resp : null;
    }

    private String getAutoMacFromUi() {
        try {
            android.widget.TextView ds = findViewById(R.id.dockingStatusText);
            if (ds != null) {
                String t = String.valueOf(ds.getText());
                String m = extractMac(t);
                if (m != null) return m;
            }
        } catch (Exception ignored) {}
        try {
            android.widget.TextView st = findViewById(R.id.statusText);
            if (st != null) {
                String t = String.valueOf(st.getText());
                String m = extractMac(t);
                if (m != null) return m;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractMac(String text) {
        if (text == null) return null;
        Matcher matcher = MAC_PATTERN.matcher(text);
        if (matcher.find()) return matcher.group();
        return null;
    }

    private static final java.util.regex.Pattern MAC_PATTERN = java.util.regex.Pattern.compile("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}");
}









