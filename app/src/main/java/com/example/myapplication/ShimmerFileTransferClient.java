package com.example.myapplication;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

public class ShimmerFileTransferClient{
    private static final String TAG = "ShimmerTransfer"; // General Logcat tag
    private static final String FIREBASE_TAG = "FirebaseLogs"; // Firebase-specific Logcat tag
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
    private static final int CHUNK_GROUP_SIZE = 8;
    private static final int MAX_CHUNK_SIZE   = 240;

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
            int attempts = 0;
            while (!connected && attempts < 3) {
                try {
                    socket.connect();
                    connected = true;
                } catch (IOException e) {
                    attempts++;
                    Log.e(TAG, "Socket connect attempt " + attempts + " failed", e);
                    crashlytics.log("Socket connect attempt " + attempts + " failed");
                    crashlytics.recordException(e);
                    Thread.sleep(1000);
                }
            }
            if (!connected) {
                Log.e(TAG, "Unable to connect to sensor after retries");
                crashlytics.log("Unable to connect to sensor after retries");
                return;
            }
            Log.d(TAG, "Connected to Shimmer");
            crashlytics.log("Connected to Shimmer");

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // --- STEP 2: Request File Count ---
            out.write(new byte[]{LIST_FILES_COMMAND});
            out.flush();
            Log.d(TAG, "Sent LIST_FILES_COMMAND (0xD0)");
            crashlytics.log("Sent LIST_FILES_COMMAND (0xD0)");

            int responseId = in.read();
            while (responseId == 0xFF) {
                responseId = in.read();
            }
            if (responseId != (FILE_LIST_RESPONSE & 0xFF)) {
                Log.e(TAG, "Expected FILE_LIST_RESPONSE (0xD3) but got: " + String.format("%02X", responseId));
                crashlytics.log("Expected FILE_LIST_RESPONSE (0xD3) but got: " + String.format("%02X", responseId));
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
                    Log.e(TAG, "Expected TRANSFER_START_PACKET (0xFD) but got: " + String.format("%02X", startByte));
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
                File outputFile = new File(context.getFilesDir(), relativeFilename); // Save as original file format

                // Ensure parent directories are created if the filename includes a path
                File parentDir = outputFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (parentDir.mkdirs()) {
                        Log.d(TAG, "Parent directories created: " + parentDir.getAbsolutePath());
                    } else {
                        Log.e(TAG, "Failed to create directories for path: " + parentDir.getAbsolutePath());
                        return; // Abort if directories cannot be created
                    }
                }

                // Create the file
                File debugFile = new File(context.getFilesDir(), "debug_log.txt");
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
                                chunksAreValid = false; // Mark chunks as invalid
                                break;
                            }

                            byte[] chunkNumBytes = readExact(in, 2);
                            Log.d(TAG, "Chunk number (raw bytes): " + String.format("%02X %02X", chunkNumBytes[0], chunkNumBytes[1]));

                            byte[] totalBytes = readExact(in, 2);
                            byte[] chunkData = readExact(in, MAX_CHUNK_SIZE);

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

                        out.write(ackPacket);
                        out.flush();
                        Log.d(TAG, "Sent " + (chunksAreValid ? "ACK" : "NACK") + " packet: " + String.format("%02X %02X %02X %02X",
                                ackPacket[0], ackPacket[1], ackPacket[2], ackPacket[3]));

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
                        int transferStatus;
                        while (true) {
                            packetId = in.read();
                            if (packetId == (TRANSFER_END_PACKET & 0xFF)) {
                                transferStatus = in.read();
                                Log.d(TAG, "Received TRANSFER_END_PACKET with status: " + String.format("%02X", transferStatus));
                                if (transferStatus == 0x00 || transferStatus == 0x01) {
                                    break;
                                } else {
                                    // If not a valid status, continue searching for the next FE
                                    Log.d(TAG, "Status after FE was not 00 or 01, continuing to skip...");
                                }
                            }
                        }

                        // (existing status handling/logging)
                        if (transferStatus == 0x01) {
                            Log.d(TAG, "Transfer completed successfully.");
                            Bundle successBundle = new Bundle();
                            successBundle.putString("mac_address", macAddress);
                            firebaseAnalytics.logEvent("file_transfer_success", successBundle);
                        } else {
                            Log.e(TAG, "File transfer failed for file: " + relativeFilename);
                        }
                    }
                } catch (IOException e) {
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
                }
            }
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Error during file transfer: " + e.getMessage(), e);
            crashlytics.log("Error during file transfer: " + e.getMessage());
            crashlytics.recordException(e);

            Bundle transferErrorBundle = new Bundle();
            transferErrorBundle.putString("mac_address", macAddress);
            transferErrorBundle.putString("error_message", e.getMessage());
            firebaseAnalytics.logEvent("file_transfer_error", transferErrorBundle);
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
                throw new EOFException("Stream ended early");
            }
            totalRead += read;
        }
        return buffer;
    }

    public void transfer(String macAddress) {
        transferOneFileFullFlow(macAddress);
    }

}
