package com.example.myapplication;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ShimmerDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.d("DeviceAdmin", "Device admin enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.d("DeviceAdmin", "Device admin disabled");
    }
}
