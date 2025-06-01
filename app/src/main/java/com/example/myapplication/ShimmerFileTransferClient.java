package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

public class ShimmerFileTransferClient {
    private static final String TAG = "ShimmerTransfer";

    // Command identifiers
    private static final byte LIST_FILES_COMMAND       = (byte) 0xD0;
    private static final byte FILE_LIST_RESPONSE       = (byte) 0xD3;
    private static final byte TRANSFER_FILE_COMMAND    = (byte) 0xD1;
    private static final byte READY_FOR_CHUNKS_COMMAND = (byte) 0xD2;
    private static final byte CHUNK_DATA_ACK           = (byte) 0xD4;
    private static final byte CHUNK_DATA_NACK          = (byte) 0xD5; // Added for error handling
    private static final byte TRANSFER_START_PACKET    = (byte) 0xFD;
    private static final byte CHUNK_DATA_PACKET        = (byte) 0xFC;
    private static final byte TRANSFER_END_PACKET      = (byte) 0xFE;

    // Configuration
    private static final int CHUNK_GROUP_SIZE = 8;
    private static final int MAX_CHUNK_SIZE   = 240;

    private final Context context;
    private BluetoothSocket socket = null;

    public ShimmerFileTransferClient(Context ctx) {
        this.context = ctx;
    }

    public void transferOneFileFullFlow(String macAddress) {
        try {
            // --- STEP 1: Establish Bluetooth Connection ---
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

            // --- STEP 2: Request File Count ---
            out.write(new byte[]{LIST_FILES_COMMAND});
            out.flush();
            Log.d(TAG, "Sent LIST_FILES_COMMAND (0xD0)");

            int responseId = in.read();
            while (responseId == 0xFF) {
                responseId = in.read();
            }
            if (responseId != (FILE_LIST_RESPONSE & 0xFF)) {
                Log.e(TAG, "Expected FILE_LIST_RESPONSE (0xD3) but got: " + String.format("%02X", responseId));
                return;
            }
            int fileCount = in.read() & 0xFF;
            Log.d(TAG, "FILE_LIST_RESPONSE: File count = " + fileCount);
            if (fileCount <= 0) {
                Log.e(TAG, "No files available for transfer");
                return;
            }

            // --- STEP 3: Transfer Each File ---
            for (int fileIndex = 0; fileIndex < fileCount; fileIndex++) {
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

                // Send READY_FOR_CHUNKS_COMMAND
                out.write(new byte[]{READY_FOR_CHUNKS_COMMAND});
                out.flush();
                Log.d(TAG, "Sent READY_FOR_CHUNKS_COMMAND (0xD2)");

                // Receive file chunks
                File textFile = new File(context.getFilesDir(), relativeFilename + ".txt");
                File parentDir = textFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (parentDir.mkdirs()) {
                        Log.d(TAG, "Created directories: " + parentDir.getAbsolutePath());
                    } else {
                        Log.e(TAG, "Failed to create directories: " + parentDir.getAbsolutePath());
                        return; // Abort if directories cannot be created
                    }
                }

                try (FileWriter textWriter = new FileWriter(textFile)) {
                    Log.d(TAG, "File created successfully: " + textFile.getAbsolutePath());
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

                            byte[] fullPacket = new byte[245];
                            fullPacket[0] = (byte) packetId;
                            System.arraycopy(chunkNumBytes, 0, fullPacket, 1, 2);
                            System.arraycopy(totalBytes, 0, fullPacket, 3, 2);
                            System.arraycopy(chunkData, 0, fullPacket, 5, MAX_CHUNK_SIZE);

                            // Write hex string to file
                            StringBuilder chunkLine = new StringBuilder();
                            for (byte b : fullPacket) {
                                chunkLine.append(String.format("%02X ", b));
                            }
                            textWriter.write(chunkLine.toString().trim() + "\n");

                            if (i == 0) {
                                firstChunkNumBytes[0] = chunkNumBytes[0]; // LSB
                                firstChunkNumBytes[1] = chunkNumBytes[1]; // MSB
                            }

                            chunksProcessed++;
                        }

                        byte ackStatusToSend = chunksAreValid ? (byte) 0x01 : (byte) 0x00;

                        byte[] ackPacket = new byte[]{
                            CHUNK_DATA_ACK,
                            firstChunkNumBytes[0],
                            firstChunkNumBytes[1],
                            ackStatusToSend
                        };

                        out.write(ackPacket);
                        out.flush();
                        Log.d(TAG, "Sent ACK packet: " + String.format("%02X %02X %02X %02X",
                                ackPacket[0], ackPacket[1], ackPacket[2], ackPacket[3]));
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error during file transfer: " + e.getMessage(), e);
                    try {
                        Log.d(TAG, "Closing file due to error...");
                    } catch (Exception closeError) {
                        Log.e(TAG, "Error closing file: " + closeError.getMessage(), closeError);
                    }
                    return;
                }

                int endPacketId = in.read();
                while (endPacketId == 0xFF) {
                    endPacketId = in.read();
                }
                if (endPacketId != (TRANSFER_END_PACKET & 0xFF)) {
                    Log.e(TAG, "Expected TRANSFER_END_PACKET (0xFE) but got: " + String.format("%02X", endPacketId));
                    return;
                }
                int transferStatus = in.read();
                Log.d(TAG, "Received TRANSFER_END_PACKET with status: " + String.format("%02X", transferStatus));
                if (transferStatus != 0x00) {
                    Log.e(TAG, "File transfer failed for file: " + relativeFilename);
                }
            }
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Error during file transfer", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                    Log.d(TAG, "Socket closed after file transfer operation");
                } catch (IOException ignored) {
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
