package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ShimmerFileTransferClient {
    private static final String TAG = "ShimmerTransfer";
    private static final String FIREBASE_TAG = "FirebaseLogs";
    private static final String SYNC_TAG = "FileSync";
    private FirebaseAnalytics firebaseAnalytics;
    private FirebaseCrashlytics crashlytics;

    private final Context context;
    private BluetoothSocket socket = null;

    // Constructor
    public ShimmerFileTransferClient(Context context) {
        this.context = context;
        firebaseAnalytics = FirebaseAnalytics.getInstance(context.getApplicationContext());
        crashlytics = FirebaseCrashlytics.getInstance();
    }

    // Command identifiers
    private static final byte LIST_FILES_COMMAND       = (byte) 0xD0;
    private static final byte FILE_LIST_RESPONSE       = (byte) 0xD3;
    private static final byte TRANSFER_FILE_COMMAND    = (byte) 0xD1;
    private static final byte READY_FOR_CHUNKS_COMMAND = (byte) 0xD2;
    private static final byte CHUNK_DATA_ACK           = (byte) 0xD4;
    private static final byte CHUNK_DATA_NACK          = (byte) 0xD5;
    private static final byte TRANSFER_START_PACKET    = (byte) 0xFD;
    private static final byte CHUNK_DATA_PACKET        = (byte) 0xFC;
    private static final byte TRANSFER_END_PACKET      = (byte) 0xFE;

    // Configuration
    private static final int CHUNK_GROUP_SIZE = 16;

    public void transferOneFileFullFlow(String macAddress) {
        // Log the start of the file transfer
        Log.d(TAG, "Starting file transfer for MAC address: " + macAddress);
        Log.d(FIREBASE_TAG, "Logging file transfer start to Firebase for MAC address: " + macAddress);
        crashlytics.log("File transfer started for MAC address: " + macAddress);

        Bundle startBundle = new Bundle();
        startBundle.putString("mac_address", macAddress);
        firebaseAnalytics.logEvent("file_transfer_started", startBundle);

        try {
            // --- STEP 1: Establish Bluetooth Connection ---
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Previous socket closed before starting new transfer");
                } catch (IOException e) {
                    Log.e(TAG, "Error closing previous socket", e);
                    crashlytics.recordException(e);
                }
                socket = null;
            }
            

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission. Aborting file transfer.");
                crashlytics.log("Missing BLUETOOTH_CONNECT permission. Aborting file transfer.");
                return;
            }

            BluetoothDevice device = adapter.getRemoteDevice(macAddress);
            socket = device.createInsecureRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            adapter.cancelDiscovery();
            Thread.sleep(1000);

            boolean connected = false;
            for (int attempts = 1; attempts <= 3 && !connected; attempts++) {
                try {
                    socket.connect();
                    connected = true;
                } catch (IOException e) {
                    Log.e(TAG, "Socket connect attempt " + attempts + " failed.", e);
                    crashlytics.log("Socket connect attempt " + attempts + " failed");
                    crashlytics.recordException(e);
                    if (attempts < 3) Thread.sleep(1000);
                }
            }

            if (!connected) {
                Log.e(TAG, "Unable to connect to sensor after 3 retries");
                crashlytics.log("Unable to connect to sensor after 3 retries");
                showToast("Failed to connect to sensor. Please check if it's on and in range.");
                return;
            }
            Log.d(TAG, "Connected to Shimmer: " + macAddress);
            crashlytics.log("Connected to Shimmer");

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // --- STEP 2: Request File Count ---
            out.write(new byte[]{LIST_FILES_COMMAND});
            out.flush();
            Log.d(TAG, "Sent LIST_FILES_COMMAND (D0)");
            crashlytics.log("Sent LIST_FILES_COMMAND (D0)");

            int responseId = in.read();
            while (responseId == 0xFF) {
                responseId = in.read();
            }
            if (responseId != (FILE_LIST_RESPONSE & 0xFF)) {
                Log.e(TAG, "Expected FILE_LIST_RESPONSE (D3) but got: " + String.format("%02X", responseId));
                crashlytics.log("Expected FILE_LIST_RESPONSE (D3) but got: " + String.format("%02X", responseId));

                clearTransferProgressStateAndNotifyUI("Unexpected header, restarting after 1:00", 60);

                // Schedule restart after 1 minute
                new android.os.Handler(context.getMainLooper()).postDelayed(() -> {
                    clearTransferProgressStateAndNotifyUI("", 0);
                    Intent transferIntent = new Intent(context, TransferService.class);
                    transferIntent.putExtra(TransferService.EXTRA_MAC_ADDRESS, macAddress);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(transferIntent);
                    } else {
                        context.startService(transferIntent);
                    }
                }, 60_000);

                return;
            }
            int fileCount = in.read() & 0xFF;
            Log.d(TAG, "FILE_LIST_RESPONSE: File count = " + fileCount);
            crashlytics.log("FILE_LIST_RESPONSE: File count = " + fileCount);

            // Log file count to Firebase Analytics
            Bundle fileCountBundle = new Bundle();
            fileCountBundle.putString("mac_address", macAddress);
            fileCountBundle.putInt("file_count", fileCount);
            firebaseAnalytics.logEvent("file_count_received", fileCountBundle);

            if (fileCount <= 0) {
                Log.e(TAG, "No files available for transfer");
                crashlytics.log("No files available for transfer");
                return;
            }

            // --- STEP 3: Transfer Each File ---
            // Before starting the file transfer loop
            Intent progressIntent = new Intent("com.example.myapplication.TRANSFER_PROGRESS");
            progressIntent.setPackage(context.getPackageName());
            progressIntent.putExtra("progress", 0);
            progressIntent.putExtra("total", fileCount);
            progressIntent.putExtra("filename", "");
            context.getApplicationContext().sendBroadcast(progressIntent);

            for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
                Log.d(TAG, "Processing file index: " + fileIndex);
                Log.d(FIREBASE_TAG, "Logging file processing start to Firebase for file index: " + fileIndex);
                crashlytics.log("Processing file index: " + fileIndex);

                // Log file processing start to Firebase Analytics
                Bundle fileStartBundle = new Bundle();
                fileStartBundle.putString("mac_address", macAddress);
                fileStartBundle.putInt("file_index", fileIndex);
                firebaseAnalytics.logEvent("file_processing_started", fileStartBundle);

                // Send TRANSFER_FILE_COMMAND
                out.write(new byte[]{TRANSFER_FILE_COMMAND});
                out.flush();
                Log.d(TAG, "Sent TRANSFER_FILE_COMMAND (0xD1)");

                // Wait for TRANSFER_START_PACKET
                int startByte = in.read();
                while (startByte == 0xFF) {
                    startByte = in.read();
                }
                if (startByte != (TRANSFER_START_PACKET & 0xFF)) {
                    Log.e(TAG, "Expected TRANSFER_START_PACKET (FD) but got: " + String.format("%02X", startByte));
                    return;
                }

                // Extract metadata
                int protocolVersion = in.read();
                int filenameLen = in.read();
                byte[] filenameBytes = readExact(in, filenameLen);
                String relativeFilename = new String(filenameBytes);
                byte[] totalSizeBytes = readExact(in, 4);
                int totalFileSize = ((totalSizeBytes[3] & 0xFF) << 24) |
                        ((totalSizeBytes[2] & 0xFF) << 16) |
                        ((totalSizeBytes[1] & 0xFF) << 8) |
                        (totalSizeBytes[0] & 0xFF);
                byte[] chunkSizeBytes = readExact(in, 2);
                int chunkSize = ((chunkSizeBytes[1] & 0xFF) << 8) | (chunkSizeBytes[0] & 0xFF);
                byte[] totalChunksBytes = readExact(in, 2);
                int totalChunks = ((totalChunksBytes[1] & 0xFF) << 8) | (totalChunksBytes[0] & 0xFF);

                Log.d(TAG, "TRANSFER_START_PACKET: version=" + protocolVersion
                        + ", filename=" + relativeFilename
                        + ", totalSize=" + totalFileSize
                        + ", chunkSize=" + chunkSize
                        + ", totalChunks=" + totalChunks);

                // Log metadata to Firebase Analytics
                Bundle fileMetadataBundle = new Bundle();
                fileMetadataBundle.putString("mac_address", macAddress);
                fileMetadataBundle.putString("file_name", relativeFilename);
                fileMetadataBundle.putInt("file_size", totalFileSize);
                fileMetadataBundle.putInt("chunk_size", chunkSize);
                fileMetadataBundle.putInt("total_chunks", totalChunks);
                firebaseAnalytics.logEvent("file_metadata_received", fileMetadataBundle);

                // Log metadata to Crashlytics
                crashlytics.setCustomKey("file_name", relativeFilename);
                crashlytics.setCustomKey("file_size", totalFileSize);
                crashlytics.setCustomKey("chunk_size", chunkSize);
                crashlytics.setCustomKey("total_chunks", totalChunks);

                // Send READY_FOR_CHUNKS_COMMAND
                out.write(new byte[]{READY_FOR_CHUNKS_COMMAND});
                out.flush();
                Log.d(TAG, "Sent READY_FOR_CHUNKS_COMMAND (0xD2)");

                // Receive file chunks
                // Get username and timestamp ONCE per file
                String phoneMac = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (phoneMac == null || phoneMac.isEmpty()) phoneMac = "user";
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                String baseName = new File(relativeFilename).getName();
                String newFilename = phoneMac + "_" + timestamp + "_" + baseName + ".txt";
                File dataDir = new File(context.getFilesDir(), "data");
                if (!dataDir.exists()) dataDir.mkdirs();
                File outputFile = new File(dataDir, newFilename);

                // Create the file
                File debugFile = new File(context.getFilesDir(), "debug_log.txt");
                boolean transferSuccess = false; // Track transfer status

                try (FileWriter textWriter = new FileWriter(outputFile);
                     FileWriter debugWriter = new FileWriter(debugFile, true)) {

                    Log.d(TAG, "File created successfully: " + outputFile.getAbsolutePath());
                    Log.d(TAG, "Receiving chunks...");

                    int chunksProcessed = 0;
                    byte[] firstChunkNumBytes = new byte[2]; // Store the first chunk number of the group

                    while (chunksProcessed < totalChunks) {
                        int remainingChunks = totalChunks - chunksProcessed;
                        int chunksToRead = Math.min(CHUNK_GROUP_SIZE, remainingChunks);

                        // Log if this is the last chunk group
                        if (remainingChunks <= CHUNK_GROUP_SIZE) {
                            Log.d(TAG, "Processing the last chunk group. Remaining chunks: " + remainingChunks);
                        }

                        boolean chunksAreValid = true; // Flag to track if chunks are valid

                        for (int i = 0; i < chunksToRead; i++) {
                            int packetId = in.read();
                            while (packetId == 0xFF) {
                                packetId = in.read(); // Skip 0xFF
                            }

                            if (packetId != (CHUNK_DATA_PACKET & 0xFF)) {
                                Log.w(TAG, "Unexpected header packet received: " + String.format("%02X", packetId));
                                // Delete incomplete file and DB entry
                                if (outputFile.exists()) outputFile.delete();
                                FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
                                SQLiteDatabase db = dbHelper.getWritableDatabase();
                                db.delete("files", "FILE_PATH=?", new String[]{outputFile.getAbsolutePath()});
                                db.close();

                                clearTransferProgressStateAndNotifyUI("Unexpected header, restarting after 1:00", 60);

                                // Schedule restart after 1 minute
                                new android.os.Handler(context.getMainLooper()).postDelayed(() -> {
                                    clearTransferProgressStateAndNotifyUI("", 0);
                                    Intent transferIntent = new Intent(context, TransferService.class);
                                    transferIntent.putExtra(TransferService.EXTRA_MAC_ADDRESS, macAddress);
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(transferIntent);
                                    } else {
                                        context.startService(transferIntent);
                                    }
                                }, 60_000);
                                return;
                            }

                            byte[] chunkNumBytes = readExact(in, 2);
                           

                            byte[] totalBytes = readExact(in, 2);
                            int chunkSizeForThisChunk = ((totalBytes[1] & 0xFF) << 8) | (totalBytes[0] & 0xFF);
                            byte[] chunkData = readExact(in, chunkSizeForThisChunk);

                            Log.d(TAG, "Chunk number (raw bytes): " + String.format("%02X %02X", chunkNumBytes[0], chunkNumBytes[1])+
                                   ", Total bytes (raw bytes): " + String.format("%02X %02X", totalBytes[0], totalBytes[1]) +
                                   ", Chunk size: " + chunkSizeForThisChunk);

                            // Write ASCII-decoded data to the ASCII file
                            for (byte b : chunkData) {
                                if (b >= 32 && b <= 126) { // Printable ASCII range
                                    textWriter.write((char) b); // Write as text
                                } else {
                                    textWriter.write("."); // Replace non-printable characters with '.'
                                }
                            }

                            // Write raw hexadecimal data to the debug file with header
                            StringBuilder hexLine = new StringBuilder();
                            hexLine.append(String.format("%02X ", packetId)); // Add header (starting with FC)
                            hexLine.append(String.format("%02X %02X ", chunkNumBytes[0], chunkNumBytes[1])); // Add chunk number
                            hexLine.append(String.format("%02X %02X ", totalBytes[0], totalBytes[1])); // Add total bytes
                            for (byte b : chunkData) {
                                hexLine.append(String.format("%02X ", b)); // Add chunk data
                            }
                            debugWriter.write(hexLine.toString().trim() + "\n");

                            if (i == 0) {
                                firstChunkNumBytes[0] = chunkNumBytes[0]; // LSB
                                firstChunkNumBytes[1] = chunkNumBytes[1]; // MSB
                            }

                            chunksProcessed++;
                            
                        }

                        // Send ACK or NACK based on validity
                        byte ackStatusToSend = chunksAreValid ? (byte) 0x01 : (byte) 0x00;

                        byte[] ackPacket = new byte[]{
                                chunksAreValid ? CHUNK_DATA_ACK : CHUNK_DATA_NACK, // Send ACK or NACK
                                firstChunkNumBytes[0],
                                firstChunkNumBytes[1],
                                ackStatusToSend
                        };

                        // --- ACK Retry Protocol ---
                        boolean gotResponse = false;
                        int maxRetries = 2; // 1 initial attempt + 2 retries = 30 seconds total wait

                        for (int attempt = 0; attempt <= maxRetries; attempt++) {
                            // Send the ACK packet
                            out.write(ackPacket);
                            out.flush();

                            if (attempt == 0) {
                                Log.d(TAG, "Sent " + (chunksAreValid ? "ACK" : "NACK") + " packet: " +
                                        String.format("%02X %02X %02X %02X", ackPacket[0], ackPacket[1], ackPacket[2], ackPacket[3]));
                            } else {
                                Log.w(TAG, "No response. Retrying ACK send (Attempt " + attempt + " of " + maxRetries + ")");
                            }

                            // Wait for response with a 10-second timeout
                            long startTime = System.currentTimeMillis();
                            while (System.currentTimeMillis() - startTime < 10000) { // 10 seconds
                                // Wait for at least the 5-byte chunk header to be available.
                                if (in.available() >= 5) {
                                    gotResponse = true;
                                    break; // Exit the inner while loop
                                }
                                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                            }

                            if (gotResponse) {
                                break; // Exit the outer for loop if we got a response
                            }
                        }

                        if (!gotResponse) {
                            Log.e(TAG, "No response after " + maxRetries + " ACK retries. Scheduling transfer restart in 1 minute.");
                            showToast("Connection lost. No response from sensor.");
                            // Delete incomplete file and DB entry
                            if (outputFile.exists()) outputFile.delete();
                            FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
                            SQLiteDatabase db = dbHelper.getWritableDatabase();
                            db.delete("files", "FILE_PATH=?", new String[]{outputFile.getAbsolutePath()});
                            db.close();

                            clearTransferProgressStateAndNotifyUI("No response from sensor, restarting after 1:00", 60);

                            // Schedule restart after 1 minute
                            new android.os.Handler(context.getMainLooper()).postDelayed(() -> {
                                clearTransferProgressStateAndNotifyUI("", 0);
                                Intent transferIntent = new Intent(context, TransferService.class);
                                transferIntent.putExtra(TransferService.EXTRA_MAC_ADDRESS, macAddress);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(transferIntent);
                                } else {
                                    context.startService(transferIntent);
                                }
                            }, 60_000);
                            return; // Exit the transfer method
                        }
                        // --- END OF ACK Retry Protocol ---

                        // Log progress to Firebase
                        Bundle progressBundle = new Bundle();
                        progressBundle.putString("mac_address", macAddress);
                        progressBundle.putInt("chunks_processed", chunksProcessed);
                        progressBundle.putInt("total_chunks", totalChunks);
                        firebaseAnalytics.logEvent("file_transfer_progress", progressBundle);

                        // Restart transfer if chunks are invalid
                        if (!chunksAreValid) {
                            Log.e(TAG, "Chunks are invalid. Restarting transfer...");
                            transferOneFileFullFlow(macAddress); // Restart the transfer
                            return;
                        }
                    }
                    if (chunksProcessed >= totalChunks) {
                        Log.d(TAG, "Last chunk group processed. Skipping bytes until TRANSFER_END_PACKET with valid status...");
                        int packetId;
                        int transferStatus = -1;
                        while (true) {
                            packetId = in.read();
                            if (packetId == (TRANSFER_END_PACKET & 0xFF)) {
                                transferStatus = in.read();
                                Log.d(TAG, "Received TRANSFER_END_PACKET with status: " + String.format("%02X", transferStatus));
                                if (transferStatus == 0x00 || transferStatus == 0x01) {
                                    transferSuccess = true; // <-- Mark as success
                                    break;
                                } else {
                                    Log.d(TAG, "Status after FE was not 00 or 01, continuing to skip...");
                                }
                            }
                        }

                        if (transferStatus == 0x01) {
                            Log.d(TAG, "Transfer completed successfully.");
                            // ... success logic ...
                        } else {
                            Log.e(TAG, "File transfer failed for file: " + relativeFilename);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "!!! CHUNK-LEVEL IOException. Hard failure during active file writing.");
                    showToast("Network connection lost during transfer.");
                    Log.e(TAG, "Error during file transfer: " + e.getMessage(), e);
                    crashlytics.recordException(e);

                    // Log available bytes in the input stream before aborting
                    try {
                        int availableBytes = in.available();
                        try (FileWriter debugWriter = new FileWriter(debugFile, true)) {
                            debugWriter.write("Available bytes in stream before aborting: " + availableBytes + "\n");

                            byte[] debugBytes = new byte[availableBytes];
                            int bytesRead = in.read(debugBytes);
                            if (bytesRead > 0) {
                                StringBuilder debugData = new StringBuilder("Bytes in stream: ");
                                for (byte b : debugBytes) {
                                    debugData.append(String.format("%02X ", b));
                                }
                                debugWriter.write(debugData.toString().trim() + "\n");
                            }
                        }
                    } catch (IOException streamError) {
                        Log.e(TAG, "Error reading available bytes: " + streamError.getMessage(), streamError);
                    }
                } finally {
                    // Delete incomplete file if transfer was not successful
                    if (!transferSuccess && outputFile.exists()) {
                        Log.w(TAG, "Deleting incomplete file: " + outputFile.getAbsolutePath());
                        outputFile.delete();
                    }
                }

                // ---- ADD THIS BLOCK HERE: ----
                FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("TIMESTAMP", timestamp);
                values.put("FILE_PATH", outputFile.getAbsolutePath());
                values.put("SYNCED", 0);
                db.insert("files", null, values);
                db.close();
                // ---- END OF BLOCK ----

                progressIntent = new Intent("com.example.myapplication.TRANSFER_PROGRESS");
                progressIntent.setPackage(context.getPackageName());
                progressIntent.putExtra("progress", fileIndex + 1);
                progressIntent.putExtra("total", fileCount);
                progressIntent.putExtra("filename", newFilename);
                context.getApplicationContext().sendBroadcast(progressIntent);
            }
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "!!! TOP-LEVEL IOException. Hard failure outside the file writing loop.");
            showToast("Network error. Please try again.");
            Log.e(TAG, "Error during file transfer: " + e.getMessage(), e);
            crashlytics.log("Error during file transfer: " + e.getMessage());
            crashlytics.recordException(e);

            Bundle transferErrorBundle = new Bundle();
            transferErrorBundle.putString("mac_address", macAddress);
            transferErrorBundle.putString("error_message", e.getMessage());
            firebaseAnalytics.logEvent("file_transfer_error", transferErrorBundle);

            clearTransferProgressStateAndNotifyUI(e.getMessage(), 5);
        } finally {
            Log.d(TAG, "File transfer completed for MAC address: " + macAddress);
            Log.d(FIREBASE_TAG, "Logging file transfer completion to Firebase for MAC address: " + macAddress);
            crashlytics.log("File transfer completed for MAC address: " + macAddress);

            Bundle endBundle = new Bundle();
            endBundle.putString("mac_address", macAddress);
            firebaseAnalytics.logEvent("file_transfer_completed", endBundle);

            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Socket closed after file transfer operation");
                } catch (IOException ignored) {
                    crashlytics.log("Error closing socket after file transfer");
                }
                socket = null;
            }
        }
    }

    private byte[] readExact(InputStream in, int len) throws IOException {
        byte[] buffer = new byte[len];
        int totalRead = 0;
        while (totalRead < len) {
            int read = in.read(buffer, totalRead, len - totalRead);
            if (read == -1) {
                throw new EOFException("Stream ended unexpectedly. Needed " + len + " bytes, but only got " + totalRead);
            }
            totalRead += read;
        }
        return buffer;
    }

        public void transfer(String macAddress) {
        transferOneFileFullFlow(macAddress);
    }


    public List<File> getLocalUnsyncedFiles() {
        Log.d(SYNC_TAG, "Querying local DB for unsynced files...");
        FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<File> unsyncedFiles = new ArrayList<>();
        try (android.database.Cursor cursor = db.query("files", new String[]{"FILE_PATH"}, "SYNCED=0", null, null, null, null)) {
            while (cursor.moveToNext()) {
                String path = cursor.getString(0);
                File file = new File(path);
                if (file.exists()) {
                    unsyncedFiles.add(file);
                    Log.d(SYNC_TAG, " Unsynced file: " + file.getName());
                }
                else {
                    Log.w(SYNC_TAG, "File listed in DB but does not exist on disk: " + path);
                    db.delete("files", "FILE_PATH=?", new String[]{path});
                }
            }
        }
        db.close();
        return unsyncedFiles;
    }

    public List<String> getMissingFilesOnS3(List<File> localFiles) {
        Log.d(SYNC_TAG, "Checking which of " + localFiles.size() + " local files are missing on S3...");
        List<String> missing = new ArrayList<>();
        if (localFiles.isEmpty()) return missing;

        try {
            OkHttpClient client = new OkHttpClient();
            JSONArray filenames = new JSONArray();
            for (File file : localFiles) filenames.put(file.getName());

            RequestBody body = RequestBody.create(filenames.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url("https://odb777ddnc.execute-api.us-east-2.amazonaws.com/missing-files/")
                    .post(body)
                    .build();

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(SYNC_TAG, "Error checking missing files, server responded with: " + response.code());
                return localFiles.stream().map(File::getName).collect(Collectors.toList());
            }
            JSONObject result = new JSONObject(response.body().string());
              JSONArray missingArr = result.getJSONArray("missing_files");
            for (int i = 0; i < missingArr.length(); i++) {
                missing.add(missingArr.getString(i));
            }
            Log.d(SYNC_TAG, "Found " + missing.size() + " files missing on S3.");
        } catch (Exception e) {
            Log.e(SYNC_TAG, "Error checking missing files: " + e.getMessage());
            showToast("Cloud sync failed: No network connection");
            // On network error, assume all files are missing to be safe.
            // This prevents them from being incorrectly marked as synced.
            return localFiles.stream().map(File::getName).collect(Collectors.toList());
        }
        return missing;
    }


    public boolean uploadFileToS3(File file) {
        Log.d(SYNC_TAG, "Starting S3 upload for: " + file.getName());
        OkHttpClient client = new OkHttpClient();
        try {
            Request getUrlRequest = new Request.Builder()
                    .url("https://odb777ddnc.execute-api.us-east-2.amazonaws.com/generate-upload-url/?filename=" + file.getName())
                    .get()
                    .build();

            String uploadUrl;
            try (Response getUrlResponse = client.newCall(getUrlRequest).execute()) {
                if (!getUrlResponse.isSuccessful() || getUrlResponse.body() == null) {
                    Log.e(SYNC_TAG, "Failed to get pre-signed URL. Server responded with: " + getUrlResponse.code());
                    return false;
                }
                uploadUrl = new JSONObject(getUrlResponse.body().string()).getString("upload_url");
            }

            RequestBody fileBody = RequestBody.create(file, MediaType.parse("text/plain"));
            Request uploadRequest = new Request.Builder().url(uploadUrl).put(fileBody).build();

            try (Response uploadResponse = client.newCall(uploadRequest).execute()) {
                if (uploadResponse.isSuccessful()) {
                    Log.d(SYNC_TAG, "S3 upload successful for: " + file.getName());
                    return true;
                } else {
                    Log.e(SYNC_TAG, "S3 upload failed with code: " + uploadResponse.code());
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(SYNC_TAG, "Exception during S3 upload: " + e.getMessage(), e);
            showToast("File upload failed: No network connection");
            crashlytics.recordException(e);
            return false;
        }
    }

    public void markFileAsSynced(File file) {
        Log.d(SYNC_TAG, "Marking file as synced in DB: " + file.getName());
        FileMetaDatabaseHelper dbHelper = new FileMetaDatabaseHelper(context);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("SYNCED", 1);
        db.update("files", values, "FILE_PATH=?", new String[]{file.getAbsolutePath()});
        db.close();
    }

    /**
     * Clears transfer progress from SharedPreferences and sends a broadcast to update the UI.
     * @param message The error message to display.
     * @param retrySeconds The number of seconds until a retry will be attempted.
     */
    private void clearTransferProgressStateAndNotifyUI(String message, int retrySeconds) {
        Log.d(TAG, "[CLEAR_STATE] Clearing transfer progress state (reason: " + message + ")");
        SharedPreferences prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE);
        prefs.edit()
            .remove("transfer_progress")
            .remove("transfer_total")
            .remove("transfer_filename")
            .remove("progress_visibility")
            .apply();

        Intent intent = new Intent("com.example.myapplication.TRANSFER_ERROR");
        intent.setPackage(context.getPackageName());
        intent.putExtra("error_message", message);
        intent.putExtra("retry_seconds", retrySeconds);
        context.getApplicationContext().sendBroadcast(intent);
    }

    private void showToast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}