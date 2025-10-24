# Shimmer Docking Android

---





## Quick Navigation

- [Overview](#1-overview)
- [File Sync Details](#2-file-sync-details)
- [Design Approach and Rationale](#3-design-approach-and-rationale)
- [Features](#4-features)
- [Architecture and Codebase](#5-architecture-and-codebase)
- [Protocol Details](#6-protocol-details)
- [Build and Setup](#7-build-and-setup)
- [Permissions](#8-permissions)
- [Usage Notes](#9-usage-notes)
- [User Interface and Button Functions](#11-user-interface-and-button-functions)
- [License](#12-license)
- [Appendix](#13-appendix)
    - [Protocol Details](#131-protocol-details)
        - [Docking Protocol](#docking-protocol)
        - [File Transfer Protocol](#file-transfer-protocol)
    - [Codebase Map](#132-codebase-map)

---


## 1. Overview

Shimmer Docking Android automates the nightly workflow for Shimmer sensor devices, ensuring reliable data collection, transfer, and cloud synchronization with minimal user intervention. The app is designed for research and deployment environments where unattended operation and robust error handling are critical.

**Core Workflow:**
1. The app scans for up to two Shimmer devices via Bluetooth, adding them to a processing queue.
2. Each device is monitored for docking state. When docked, the app queries the device and reads its RTC timestamp.
3. Data files are transferred from the device using a reliable, chunked protocol. File headers are stamped with both device and system timestamps for traceability.
4. Transferred files are queued for cloud sync. The app uploads files to an S3-compatible endpoint when network connectivity is available.
5. All operations are logged, and errors are reported via Crashlytics for diagnostics and support.

**Why This Approach?**
- Sequential, state-driven protocol ensures predictable resource usage and simplifies troubleshooting.
- Silent backoff and retry logic prevent repeated failures and allow time for user intervention.
- Minimal UI keeps most operations automatic, but manual controls are available for mapping, transfer, and sync.
- Security and privacy: Data is only synced when network is available, and files are stored in app-private directories.

Crashlytics is enabled via Firebase and the `google-services.json` configuration. All crashes and non-fatal errors are reported to the official Firebase project for proactive maintenance.


## 2. File Sync Details

After successful file transfer from a Shimmer device, files are stored in the app's local storage:
- **Internal storage:** `/data/data/com.example.myapplication/files/`
- **External (app-specific) storage:** `/storage/emulated/0/Android/data/com.example.myapplication/files/`

Files are queued for sync and listed in the "Files to Sync" section of the UI. When the user presses "Sync to Cloud" or the protocol triggers automatic sync, the app checks for network connectivity (WiFi or cellular).

**Sync Endpoint:**
- Files are uploaded to an S3-compatible cloud endpoint, as configured in the app (see code for endpoint details).
- The sync logic uses secure HTTP(S) requests to transfer files, with retries and error handling.
- Only files not already synced are uploaded; the app tracks sync status locally.

**Sync Workflow:**
1. Check for network connectivity.
2. For each file in the queue, initiate upload to the S3 endpoint.
3. On success, mark the file as synced and remove it from the queue.
4. On failure, retain the file for future retry and log the error (Crashlytics will report persistent issues).

This approach ensures data integrity, minimizes bandwidth usage, and provides clear status to the user. All sync operations are logged for audit and troubleshooting.

Shimmer Docking Android is a specialized application designed to automate the nightly discovery, docking state detection, data transfer, and cloud synchronization of Shimmer sensor devices. The app is optimized for unattended operation, robust error handling, and minimal user intervention, making it suitable for research and deployment environments where reliability is critical.





## 3. Design Approach and Rationale

This app was designed for robust, unattended nightly operation in research and deployment settings where reliability and minimal user intervention are essential. The protocol is intentionally sequential and state-driven, with silent backoff and retry logic to avoid repeated failures and user disruption. Limiting the session to two devices ensures predictable resource usage and simplifies error handling, but this can be adjusted as needed.

### Docking Protocol: Overnight Design and Scheduling

Docking is intended to occur overnight, typically within a configurable time window (e.g., 20:00 to 09:00). This ensures that data collection and transfer happen when devices are docked and users are not actively using them. The protocol is triggered automatically by scheduled alarms and can also be started manually via the UI.

#### Automatic Scheduling: Alarm and Receivers
- **DockingStartReceiver**: Listens for scheduled alarm events (set by `DockingScheduler`) to begin the docking protocol at the start of the overnight window.
- **DockingEndReceiver**: Listens for the end-of-window alarm to gracefully terminate the protocol and finalize any pending transfers or syncs.
- **BootCompletedReceiver**: Ensures alarms and scheduling are re-registered after device reboot, maintaining reliability.
- **DockingScheduler**: Manages alarm setup for start/end times, using Android's alarm manager for precise overnight scheduling.

This design allows the app to run the docking protocol automatically every night, minimizing user intervention and ensuring consistent data collection.

#### Manual Control: UI Button
- **Start Docking Button**: Users can manually trigger the docking protocol at any time, outside the scheduled window, for troubleshooting or ad-hoc transfers. This provides flexibility while maintaining the reliability of the automatic overnight process.

### Device Ownership and Phone Lock State

Each Shimmer device is assigned to a specific owner (e.g., patient, research subject, or deployment asset). This ownership model ensures:
- **Data integrity:** Files and metadata are always associated with the correct device and owner, reducing risk of mix-ups.
- **Auditability:** Ownership mapping allows for clear tracking of device usage, transfer history, and troubleshooting.
- **Security:** Only authorized devices are processed, and mapping is enforced in the app workflow.

The protocol also considers the phone's lock state (whether the phone is locked or unlocked) to control when data transfer and sync operations are allowed:
- **Why phone lock state?**
    - Prevents accidental or unauthorized file transfer when the phone is locked and unattended.
    - Ensures that sensitive operations (file transfer, cloud sync, device mapping) only occur when the phone is unlocked and the user is present.
    - Supports security and privacy by restricting access to patient/device data when the phone is locked.

Phone lock state is checked before initiating transfer and sync actions. This design choice provides a reliable safeguard for data privacy and user control, and supports robust unattended operation by automating the decision to transfer only when the phone is unlocked and conditions are met.

Key design choices include:
- **Stateful protocol:** Each step (scan, monitor, query, transfer, sync) is tracked and logged, allowing for recovery and troubleshooting.
- **Silent state backoff:** Instead of looping endlessly on errors, the app waits and retries, giving users time to fix issues (e.g., turn on Bluetooth).
- **Minimal UI:** Most operations are automatic, but manual controls are available for mapping, transfer, and sync.
- **Crashlytics and Analytics:** Integrated for real-time monitoring, diagnostics, and crash reporting. This helps track issues in the field and improve reliability.
- **Security and privacy:** Data is only synced when network is available, and local files are stored in app-private directories.

Crashlytics is enabled via Firebase and the `google-services.json` configuration. All crashes and non-fatal errors are reported to the official Firebase project, allowing for proactive maintenance and support.


## 4. Features

- Automated night docking protocol with scan, query, transfer, and sync steps.
- Round-robin processing for up to two Shimmer devices per session (configurable).
- Silent state backoff to avoid repeated failures and allow user intervention.
- Detailed logging for Bluetooth events, RTC timestamps, and file transfer progress.
- Firebase Analytics and Crashlytics integration for monitoring and diagnostics.


## 5. Architecture and Codebase

The codebase is organized around several key classes and services:

- **DockingManager**: Orchestrates the entire docking protocol, including scanning, monitoring, dock-state queries, and transitions to file transfer and sync. Maintains per-device timestamps and manages retry logic.
- **ShimmerFileTransferClient**: Handles RFCOMM connections and the binary file transfer protocol, including header stamping and chunked transfer with ACK/NACK and retries.
- **DockingService, ScanningService, TransferService, SyncService**: Foreground/background services that coordinate long-running operations and UI notifications. `SyncService` is responsible for S3 cloud synchronization.
- **Broadcast Receivers**: Includes receivers for system and app events, such as boot completion and docking flow triggers.
- **DockingTimestampModel**: Encapsulates the RTC values for each device and session.



## 6. Protocol Details

### 6.1 Dock Query

The Dock Query protocol is used to determine the docking state and timestamp of a Shimmer device. The Android app (via `DockingManager` and related services) sends a command to the device:

- **Command:** 0xD5 (CHECK_DOCK_STATE)
- **Response:** 0xD6 (RESPONSE_DOCK_STATE), followed by:
    - 1 status byte (0 = undocked, 1 = docked)
    - 8 bytes of RTC64 timestamp (device clock, little-endian)

This exchange is handled by the `DockingManager` and `DockingService`, which monitor the device and log the docking state and timestamp for each session. The status byte is used to decide whether to proceed with file transfer.

### 6.2 RTC64 Decoding

The RTC64 value is an 8-byte unsigned integer representing the device's clock ticks. It is sent in little-endian format and must be decoded for use in file headers and logs.

- **Decoding:**
    - Read 8 bytes as a little-endian unsigned integer.
    - To convert ticks to seconds: `seconds = rtc64 / 32768.0`

This decoding is performed in the `DockingManager`, `ShimmerFileTransferClient`, and `DockingTimestampModel` classes. The decoded timestamp is used for traceability and to align device data with system events.

### 6.3 File Header Stamping

During file transfer, the app stamps each file header with both the Shimmer device's RTC64 and the Android system's RTC32 timestamp. This ensures traceability and supports data integrity checks.

- **Header Format:**
    - Bytes 44–51: Shimmer RTC64 (little-endian)
    - Bytes 52–55: Android RTC32 (system time in seconds, little-endian)

The stamping is implemented in the `ShimmerFileTransferClient` and `TransferService` classes. RTC64 is obtained from the Dock Query, and RTC32 is read from the Android system clock at the time of transfer. These values allow for accurate alignment and verification of transferred data.


---


## 7. Build and Setup

### 7.1 Gradle Configuration

#### 7.2 Key Dependencies

- **Minimum SDK**: 31
- **Target/Compile SDK**: 35
- **Java Version**: 11

To build and run:
1. Open the project in Android Studio.
2. Build the `app` module.
3. Ensure runtime permissions are granted (Bluetooth, Notifications for Android 13+).
4. For command-line builds, use the included Gradle wrapper.

### Gradle Configuration

- Plugins: `com.android.application`, `com.google.gms.google-services`, `com.google.firebase.crashlytics`
- Namespace: `com.example.myapplication`
- Application ID: `com.example.myapplication`
- Version: 1.0 (versionCode: 1)
- Compile options: Java 11

#### Key Dependencies

- Firebase BoM (use only the latest, e.g., 33.15.0)
- com.google.firebase:firebase-analytics
- com.google.firebase:firebase-crashlytics:18.4.3
- AndroidX libraries: appcompat, activity, constraintlayout, material
- Networking: okhttp 4.12.0
- Utilities: org.json 20240303

**Note:**
- Only one Firebase BoM line should be present in `build.gradle` to avoid ambiguity.
- The `google-services.json` file must be present in the `app/` directory for Firebase services to initialize.
- The required Gradle plugins are already applied in the project.


## 8. Permissions

- BLUETOOTH_SCAN, BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION (required for scanning on some Android versions)
- POST_NOTIFICATIONS (required for notifications on Android 13+)


## 9. Usage Notes

- Ensure Bluetooth is enabled before running the app. The protocol will back off and retry if Bluetooth is off.
- The device/session limit (default: two Shimmers) can be increased by adjusting the relevant configuration.
- Always use little-endian format for RTC64 values when reading from the device and writing to file headers.




## 11. User Interface and Button Functions


### 11.1 Overall Flow

The main screen guides users through the nightly docking and sync protocol. The flow is:

1. Start the protocol automatically (scheduled) or manually via the Start Docking button.
2. The app scans for up to two Shimmer devices and lists them in "Available Devices." Status and timer are shown at the top.
3. Each device is monitored for docking state. When docked, the app queries the device and initiates file transfer.
4. Transfer progress is displayed. After transfer, files are listed in "Files to Sync."
5. The user can trigger cloud sync for unsynced files using the Sync to Cloud button. Sync progress and results are shown.
6. Errors, status, and docking state are always visible. The app uses silent backoff and retry for failures.
7. Additional controls allow mapping devices to patients, changing docking hours, toggling theme, and viewing app info.

### 11.2 Main Buttons

- **Start Docking** (`dockingButton`): Begins the scan and monitoring protocol for Shimmer devices.
- **Start Transfer** (`transferButton`): Initiates file transfer for the selected or docked device (enabled when ready).
- **Sync to Cloud** (`syncButton`): Uploads transferred files to the cloud (enabled when files are available and network is connected).
- **Map Device to Patient** (`mapButton`): Opens the mapping workflow to associate a device with a patient record (requires network).
- **Theme Toggle** (`themeToggleButton`): Switches between light and dark UI themes.
- **About** (`aboutButton`): Displays app information, version, and usage instructions.
- **Change Docking Hours** (`changeDockingHoursButton`): Adjusts the time window for automatic docking operations.

### 11.3 Status and Progress

The main screen of the app provides a clear overview of status, available devices, transfer progress, and key actions. Here is how the overall flow and buttons work:

---

### 11.4 Main Screen Layout and Components

The main UI is defined in `activity_main.xml` and is designed for clarity, accessibility, and efficient workflow. The layout uses Material Design components and is organized as follows:

#### Top Bar (Always Visible)
- **Theme Toggle Button (`themeToggleButton`)**: Top-right, switches between light and dark themes.
- **About Button (`aboutButton`)**: Top-right, shows app info and usage instructions.
- **Map Device to Patient Button (`mapButton`)**: Top-right, opens device-patient mapping workflow.

#### Welcome Card
- **User Name Text (`user_name_text_view`)**: Centered welcome message, can display the logged-in user or generic greeting.

#### Timer & Status Card
- **Timer Text (`timerText`)**: Shows remaining time for the current protocol session.
- **Status Text (`statusText`)**: Displays current protocol status (e.g., scanning, transferring, syncing).
- **Docking Status Text (`dockingStatusText`)**: Shows docking state for each device (docked/undocked).

#### Available Devices Section
- **Title**: "Available Devices" (bold header).
- **Device List (`deviceListView`)**: ListView showing up to two nearby Shimmer devices, with status and selection.

#### Transfer Progress Section
- **Progress Text (`progressText`)**: Shows transfer progress percentage.
- **Transfer Progress Bar (`transferProgressBar`)**: Linear progress indicator for file transfer.

#### Files to Sync Section
- **Files to Sync Card (`filesToSyncSection`)**: Visible when files are queued for cloud sync.
- **File List (`fileListRecyclerView`)**: ListView showing files ready to sync.

#### Action Buttons (Bottom Row)
- **Start Transfer (`transferButton`)**: Initiates file transfer for selected/docked device.
- **Sync to Cloud (`syncButton`)**: Uploads transferred files to cloud endpoint.
- **Start Docking (`dockingButton`)**: Begins scan and monitoring protocol.

#### Docking Hours Section
- **Docking Hours Text (`dockingHoursText`)**: Shows current docking hours window.
- **Change Docking Hours Button (`changeDockingHoursButton`)**: Opens dialog to adjust docking hours.

---

### 11.5 UI Component Mapping

| UI Element                   | XML ID                      | Purpose/Functionality                                      |
|------------------------------|-----------------------------|------------------------------------------------------------|
| Theme Toggle Button          | `themeToggleButton`         | Switches between light/dark themes                         |
| About Button                 | `aboutButton`               | Shows app info and instructions                            |
| Map Device to Patient Button | `mapButton`                 | Opens device-patient mapping workflow                      |
| Welcome Text                 | `user_name_text_view`       | Displays welcome/user name                                 |
| Timer Text                   | `timerText`                 | Shows remaining protocol time                              |
| Status Text                  | `statusText`                | Displays current protocol status                           |
| Docking Status Text          | `dockingStatusText`         | Shows docking state for each device                        |
| Device List                  | `deviceListView`            | Lists available Shimmer devices                            |
| Transfer Progress Text       | `progressText`              | Shows file transfer progress                               |
| Transfer Progress Bar        | `transferProgressBar`       | Progress indicator for file transfer                       |
| Files to Sync Section        | `filesToSyncSection`        | Card for files queued for cloud sync                       |
| File List                    | `fileListRecyclerView`      | Lists files ready to sync                                  |
| Start Transfer Button        | `transferButton`            | Initiates file transfer                                    |
| Sync to Cloud Button         | `syncButton`                | Uploads files to cloud endpoint                            |
| Start Docking Button         | `dockingButton`             | Begins scan/monitoring protocol                            |
| Docking Hours Text           | `dockingHoursText`          | Shows current docking hours window                         |
| Change Docking Hours Button  | `changeDockingHoursButton`  | Adjusts docking hours                                      |

---

### 11.6 UI Flow and Interactions

1. **Protocol Start**: User presses "Start Docking" or protocol starts automatically. Timer and status update; device scan begins.
2. **Device Discovery**: Available devices appear in the list. Docking state is shown for each.
3. **Docking State Monitoring**: When a device is docked, status updates and transfer becomes available.
4. **File Transfer**: "Start Transfer" button is enabled; progress bar and text show transfer status.
5. **Files to Sync**: After transfer, files appear in "Files to Sync" section. "Sync to Cloud" button is enabled if network is available.
6. **Cloud Sync**: User presses "Sync to Cloud"; progress and results are shown. Synced files are removed from the list.
7. **Additional Controls**: User can map devices, change docking hours, toggle theme, or view app info at any time.

All status, errors, and progress are visible in real time. The UI is designed for minimal manual intervention, with most operations running automatically in the background.


## 12. License

This project is licensed under the MIT License. See the `LICENSE` file for full details.

---



## 13. Appendix

### 13.1 Protocol Details

#### Docking Protocol

The docking protocol is a multi-step process that ensures reliable detection, monitoring, and data transfer from Shimmer sensor devices. The steps are:

1. **Scan for Devices:**
    - The app scans for Bluetooth devices matching the Shimmer naming pattern.
    - Up to two devices are added to the processing queue (configurable).
    - The scan window is timed and can be adjusted.

2. **Monitor Docking State:**
    - For each device, the app periodically checks availability and docking state.
    - If a device is not found for a set timeout, it is considered undocked or unavailable.

3. **Dock-State Query:**
    - The app sends a 0xD5 (CHECK_DOCK_STATE) command to the Shimmer device.
    - The device responds with 0xD6 (RESPONSE_DOCK_STATE), a status byte (0 = undocked, 1 = docked), and an 8-byte RTC64 timestamp.
    - The timestamp is decoded (little-endian) and used for logging and file header stamping.

4. **Silent Backoff and Retry:**
    - If any step fails (e.g., Bluetooth off, device not found), the app enters a silent backoff period before retrying.
    - After the silent period, the protocol resumes from the appropriate step.

#### File Transfer Protocol

The file transfer protocol ensures reliable and traceable data movement from the Shimmer device to the Android app:

1. **Initiate RFCOMM Connection:**
    - When a device is docked, the app establishes a Bluetooth RFCOMM connection.

2. **File Header Stamping:**
    - The app writes two timestamps into the file header:
        - Bytes 44–51: Shimmer RTC64 (little-endian)
        - Bytes 52–55: Android RTC32 (system time in seconds, little-endian)

3. **Chunked Data Transfer:**
    - Data is sent in chunks, each followed by an ACK/NACK from the receiver.
    - If a chunk fails, the sender retries until successful or a retry limit is reached.

4. **Completion and Verification:**
    - After all chunks are sent, the app verifies file integrity and logs the transfer.
    - Successfully transferred files are queued for cloud sync.

5. **Error Handling:**
    - Any transfer errors are logged and reported via Crashlytics.
    - Failed transfers remain in the queue for future retry.

**RTC64 Decoding:**
    - 8-byte unsigned integer, little-endian
    - To convert to seconds: `ticks / 32768.0`

---

### 13.2 Codebase Map

Key classes and responsibilities (located in `app/src/main/java/com/example/myapplication/`):

- `DockingManager`: Orchestrates protocol, manages scan, monitor, query, transfer, sync, and retry logic.
- `ShimmerFileTransferClient`: Handles RFCOMM connection, file transfer, header stamping, chunked protocol.
- `DockingService`, `ScanningService`, `TransferService`, `SyncService`: Foreground/background services for protocol steps and notifications.
- `Broadcast Receivers`: e.g., `BootCompletedReceiver`, `DockingStartReceiver`, `DockingEndReceiver` for system/app events.
- `DockingTimestampModel`: Model for carrying `shimmerRtc64` and `androidRtc32` through the pipeline.

Refer to these classes for implementation details and protocol logic.
