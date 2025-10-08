package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class DockingStartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.example.myapplication.START_DOCKING".equals(intent.getAction())) return;
        Log.d("DockingStartReceiver", "Alarm fired. Starting DockingService.");

        // Start service
        Intent serviceIntent = new Intent(context, DockingService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
