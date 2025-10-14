# Shimmer Docking Android

A lightweight Android app that discovers Shimmer devices at night, determines dock state, transfers data over Bluetooth RFCOMM, and then syncs files to S3. It’s optimized for unattended nightly runs with robust retry and a “silent state” backoff when conditions aren’t favorable (e.g., Bluetooth off).

## Features
- Night docking flow with scan → dock-state query → file transfer → S3 sync
- Round-robin handling for up to two Shimmer devices per session
- Robust retries with silent backoff windows to avoid user disruption
- Detailed logging for Bluetooth, RTC timestamps, and file transfer
- Firebase Analytics + Crashlytics integration (optional)

## Build and Run
- Min SDK: 31
- Target/Compile SDK: 35
- Java: 11

Open in Android Studio and build the `app` module. Ensure required permissions are granted at runtime (Bluetooth, Notifications on Android 13+). For CLI builds, use the included Gradle wrapper.

## Protocol Highlights
- Bluetooth RFCOMM UUID: 00001101-0000-1000-8000-00805F9B34FB
- Dock query:
  - Host → Shimmer: 0xD5 (CHECK_DOCK_STATE)
  - Shimmer → Host: 0xD6 (RESPONSE_DOCK_STATE), then 1 status byte (0=undocked, 1=docked) and 8 bytes of RTC64
- RTC64 decoding:
  - 8-byte unsigned value, little-endian
  - Sanity check: ticks/32768.0 ≈ seconds
- File header stamping (written during transfer):
  - Bytes 44–51: Shimmer RTC64 (little-endian)
  - Bytes 52–55: Android RTC32 seconds (little-endian)

## Night Docking Flow
1) Initialization scan for Shimmer devices (up to 2)
2) Periodic monitoring per device (short window)
3) Direct dock-state query
4) If docked, record RTCs and start Bluetooth file transfer
5) On success, trigger S3 sync
6) On failure or ambiguous outcomes, enter a silent backoff and retry

Silent state avoids looping and lets the user fix conditions (e.g., turn on Bluetooth) before we try again.

## Codebase
High-level map of important classes and responsibilities. Class names refer to files in `app/src/main/java/com/example/myapplication/` unless otherwise noted.

- DockingManager
  - Orchestrates the entire night docking flow
  - Scanning, periodic monitoring, dock-state query, and transitioning into transfer/sync
  - Maintains per-device timestamps via `DockingTimestampModel`
  - Uses `lastDockedShimmerRtc` captured from the dock query response

- ShimmerFileTransferClient
  - Manages RFCOMM connection and the chunked binary transfer protocol
  - Stamps file headers with Shimmer RTC64 and Android RTC32
  - Logs per-chunk progress and performs ACK/NACK with retries

- DockingService, ScanningService, TransferService, SyncService
  - Foreground/background services coordinating long-running work and UI notifications
  - `SyncService` triggers S3 synchronization after successful transfers

- Broadcast Receivers
  - `BootCompletedReceiver`, `DockingStartReceiver`, `DockingEndReceiver`, etc., to kick off flows or react to system events

- DockingTimestampModel
  - Simple model to carry `shimmerRtc64` and `androidRtc32` through the pipeline

## Logging Conventions
- [RTC-RAW] Raw 8 bytes from Shimmer (hex, LSB..MSB)
- [RTC-DECODE] Decoded uint64 + approximate seconds (rtc/32768.0)
- [RTC-STORE] When and where we store the timestamps
- [HeaderVerify] After writing headers, we read them back and log to ensure correctness

## Permissions
- BLUETOOTH_SCAN, BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION (scanning requirements on some Android versions)
- POST_NOTIFICATIONS (Android 13+)

## Notes
- Ensure Bluetooth is ON before running; the app backs off when it detects Bluetooth is off
- Limit of two devices per session is configurable; increase if needed
- Keep the little-endian convention consistent for both reading RTC64 from the device and writing to file headers

## Roadmap / Next steps
- Optionally push other branches/tags to new repo; apply branch protections
- Add unit tests for byte-order decoding and header verification
- Add a settings screen to adjust timing windows and retry limits
