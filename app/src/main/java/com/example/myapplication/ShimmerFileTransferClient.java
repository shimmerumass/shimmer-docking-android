package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

public class ShimmerFileTransferClient {
    private static final String TAG = "ShimmerTransfer";

    // Command identifiers.
    private static final byte LIST_FILES_COMMAND = (byte) 0xD0;
    private static final byte FILE_LIST_RESPONSE = (byte) 0xD3;

    private final Context context;

    public ShimmerFileTransferClient(Context ctx) {
        this.context = ctx;
    }

    /**
     * Entry point for a transfer.
     * Currently calls listFiles() for file listing.
     */
    public void transfer(String macAddress) {
        listFiles(macAddress);
    }

    /**
     * Sends the LIST_FILES command (0xD0) to the sensor at the given MAC address,
     * then reads and broadcasts the file list response.
     */
    public void listFiles(String macAddress) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission. Aborting file list.");
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(macAddress);
        BluetoothSocket socket = null;
        try {
            // Create an RFCOMM socket using the well-known SPP UUID.
            socket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
            adapter.cancelDiscovery();
            socket.connect();
            Log.d(TAG, "Connected to Shimmer for file listing");

            InputStream in = socket.getInputStream();

            // Send the LIST_FILES command.
            socket.getOutputStream().write(new byte[]{LIST_FILES_COMMAND});
            socket.getOutputStream().flush();
            Log.d(TAG, "Sent LIST_FILES_COMMAND (0xD0)");

            // Read header while skipping any extra 0xFF bytes.
            byte[] responseHeader = readHeaderSkippingFF(in, 2);
            Log.d(TAG, "Received header: " + bytesToHex(responseHeader));

            byte responseCode = responseHeader[0];
            int fileCount = responseHeader[1] & 0xFF;
            if (responseCode != FILE_LIST_RESPONSE) {
                Log.e(TAG, "Invalid response code. Expected: "
                        + String.format("%02X", FILE_LIST_RESPONSE)
                        + ", Got: " + String.format("%02X", responseCode));
                return;
            }
            Log.d(TAG, "File count: " + fileCount);

            ArrayList<String> fileNames = new ArrayList<>();
            // Read each file name from the stream.
            for (int i = 0; i < fileCount; i++) {
                int nameLen = in.read();
                if (nameLen == -1) {
                    throw new EOFException("Stream ended unexpectedly while reading name length");
                }
                byte[] nameBytes = readExact(in, nameLen);
                String fileName = new String(nameBytes);
                fileNames.add(fileName);
            }

            // Broadcast the file list.
            Intent listIntent = new Intent("com.example.myapplication.FILE_LIST");
            listIntent.putStringArrayListExtra("files", fileNames);
            listIntent.setPackage(context.getPackageName());
            Log.d(TAG, "Broadcasting file list: " + fileNames);
            context.sendBroadcast(listIntent);

        } catch (IOException e) {
            Log.e(TAG, "File listing error", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Reads exactly expectedLen valid bytes from the InputStream,
     * skipping any extra 0xFF bytes before the header begins.
     */
    private byte[] readHeaderSkippingFF(InputStream in, int expectedLen) throws IOException {
        ArrayList<Byte> headerBytes = new ArrayList<>();
        while (headerBytes.size() < expectedLen) {
            int next = in.read();
            if (next == -1) {
                throw new EOFException("Stream ended early while reading header");
            }
            // Skip any 0xFF bytes until at least one valid header byte is found.
            if (headerBytes.isEmpty() && (next & 0xFF) == 0xFF) {
                Log.d(TAG, "Skipping extra header byte: FF");
                continue;
            } else {
                headerBytes.add((byte) next);
            }
        }
        // Convert ArrayList<Byte> to byte[]
        byte[] header = new byte[expectedLen];
        for (int i = 0; i < expectedLen; i++) {
            header[i] = headerBytes.get(i);
        }
        return header;
    }

    /**
     * Reads exactly len bytes from the given InputStream.
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

    /**
     * Helper method to convert a byte array to a hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}