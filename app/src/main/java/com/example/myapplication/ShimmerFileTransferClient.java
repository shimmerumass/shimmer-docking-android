package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

public class ShimmerFileTransferClient {
    private static final String TAG = "ShimmerTransfer";

    // Command identifiers.
    private static final byte LIST_FILES_COMMAND       = (byte) 0xD0;
    private static final byte FILE_LIST_RESPONSE       = (byte) 0xD3;
    private static final byte TRANSFER_FILE_COMMAND    = (byte) 0xD1;
    private static final byte READY_FOR_CHUNKS_COMMAND = (byte) 0xD2;
    private static final byte CHUNK_DATA_ACK           = (byte) 0xD4;
    // Shimmer-sent packets:
    private static final byte TRANSFER_START_PACKET    = (byte) 0xFD;
    private static final byte CHUNK_DATA_PACKET        = (byte) 0xFC;
    private static final byte TRANSFER_END_PACKET      = (byte) 0xFE;

    // Timeout and group configuration (adjust as needed)
    private static final int CHUNK_GROUP_SIZE = 8;
    private static final int MAX_CHUNK_SIZE   = 128;

    private final Context context;
    private BluetoothSocket socket = null;

    public ShimmerFileTransferClient(Context ctx) {
        this.context = ctx;
    }

    public void transferOneFileFullFlow(String macAddress) {
        // --- CLEAR PREVIOUS SOCKET IF EXISTS ---
        if (socket != null) {
            try {
                socket.close();
                Log.d(TAG, "Previous socket closed before starting new transfer");
            } catch (IOException e) {
                Log.e(TAG, "Error closing previous socket", e);
            }
            socket = null;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission. Aborting file transfer.");
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(macAddress);
        try {
            // --- CREATE NEW SOCKET AND CONNECT ---
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
                    Thread.sleep(1000);
                }
            }
            if (!connected) {
                Log.e(TAG, "Unable to connect to sensor after retries");
                return;
            }
            Log.d(TAG, "Connected to Shimmer");
            Thread.sleep(500);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // --- STEP A: Send LIST_FILES_COMMAND (0xD0) ---
            out.write(new byte[]{LIST_FILES_COMMAND});
            out.flush();
            Log.d(TAG, "Sent LIST_FILES_COMMAND (0xD0)");

            // --- STEP B: Receive FILE_LIST_RESPONSE (0xD3) ---
            int responseId = in.read();
            while (responseId == 0xFF) {
                responseId = in.read();
            }
            if (responseId != (FILE_LIST_RESPONSE & 0xFF)) {
                Log.e(TAG, "Expected FILE_LIST_RESPONSE (0xD3) but got: "
                        + String.format("%02X", responseId));
                return;
            }
            int fileCount = in.read() & 0xFF;
            Log.d(TAG, "FILE_LIST_RESPONSE: File count = " + fileCount);
            if (fileCount <= 0) {
                Log.e(TAG, "No files available for transfer");
                return;
            }

            // --- STEP C: Send TRANSFER_FILE_COMMAND (0xD1) ---
            out.write(new byte[]{TRANSFER_FILE_COMMAND});
            out.flush();
            Log.d(TAG, "Sent TRANSFER_FILE_COMMAND (0xD1)");

            // --- STEP D: Wait for TRANSFER_START_PACKET (0xFD) ---
            int startByte = in.read();
            while (startByte == 0xFF) {
                startByte = in.read();
            }
            if (startByte != (TRANSFER_START_PACKET & 0xFF)) {
                Log.e(TAG, "Expected TRANSFER_START_PACKET (0xFD) but got: "
                        + String.format("%02X", startByte));
                return;
            }
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

            // --- STEP E: Send READY_FOR_CHUNKS_COMMAND (0xD2) ---
            out.write(new byte[]{READY_FOR_CHUNKS_COMMAND});
            out.flush();
            Log.d(TAG, "Sent READY_FOR_CHUNKS_COMMAND (0xD2)");

            // --- STEP F: Receive file chunks (CHUNK_DATA_PACKET, 0xFC) in groups ---
            int currentChunk = 0;
            int groupStartChunk = 0;
            int chunksInGroup = 0;
            byte[] fileBuffer = new byte[totalFileSize];

            File binaryFile = new File(context.getFilesDir(), "response_data.bin");
        File textFile = new File(context.getFilesDir(), "response_data.txt");

        try (
            FileOutputStream binaryWriter = new FileOutputStream(binaryFile, true);
            FileWriter textWriter = new FileWriter(textFile, true)  // append mode
        ) {
            Log.d(TAG, "Entering chunk reception loop...");
            int chunksProcessed = 0;
            while (chunksProcessed < 8) {
                int packetId = in.read();
                while (packetId == 0xFF) {
                    packetId = in.read(); // Skip 0xFF
                }

                if (packetId != (CHUNK_DATA_PACKET & 0xFF)) {
                    Log.w(TAG, "Unexpected header packet received: " + String.format("%02X", packetId));
                    return;
                }

                byte[] chunkNumBytes = readExact(in, 2);
                byte[] totalBytes = readExact(in, 2);
                byte[] chunkData = readExact(in, 240);

                byte[] fullPacket = new byte[245];
                fullPacket[0] = (byte) packetId;
                System.arraycopy(chunkNumBytes, 0, fullPacket, 1, 2);
                System.arraycopy(totalBytes, 0, fullPacket, 3, 2);
                System.arraycopy(chunkData, 0, fullPacket, 5, 240);

                // Write binary data
                binaryWriter.write(fullPacket);

                // Write hex representation to text file (one line)
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < fullPacket.length; i++) {
                    sb.append(String.format("%02X ", fullPacket[i]));
                }
                textWriter.write(sb.toString().trim() + "\n");

                chunksProcessed++;
            }
            Log.d(TAG, "Processed 8 chunks and saved to binary and text files.");
        } catch (IOException e) {
            Log.e(TAG, "Error saving chunks", e);
        }

        Log.d(TAG, "Response file path: " + binaryFile.getAbsolutePath());

        // --- STEP G: Wait for TRANSFER_END_PACKET (0xFE) ---
            int endPacketId = in.read();
            while (endPacketId == 0xFF) {
                endPacketId = in.read();
            }
            if (endPacketId != (TRANSFER_END_PACKET & 0xFF)) {
                Log.e(TAG, "Expected TRANSFER_END_PACKET (0xFE) but got: " + String.format("%02X", endPacketId));
            } else {
                int transferStatus = in.read();
                Log.d(TAG, "Received TRANSFER_END_PACKET with status: " + String.format("%02X", transferStatus));
                if (transferStatus == 0x00) {
                    Log.d(TAG, "File transfer completed successfully for file: " + relativeFilename);
                    Intent doneIntent = new Intent("com.example.myapplication.TRANSFER_DONE");
                    context.sendBroadcast(doneIntent);
                } else {
                    Log.e(TAG, "File transfer failed with status: " + String.format("%02X", transferStatus));
                }
            }
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Error during file transfer", e);
        } finally {
            // --- CLEANUP ---
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Socket closed after file transfer operation");
                } catch (IOException ignored) { }
                socket = null;
            }
        }
    }

    /**
     * Reads exactly len bytes from the InputStream.
     * Handles large data sizes efficiently.
     */
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