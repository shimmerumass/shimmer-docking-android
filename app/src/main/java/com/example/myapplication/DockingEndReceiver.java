package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

// Import DockingService for ACTION_FORCE_STOP
import com.example.myapplication.DockingService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class DockingEndReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
            if (!"com.example.myapplication.END_DOCKING".equals(intent.getAction())) return;
            Log.d("DockingEndReceiver", "Docking window ended. Sending FORCE_STOP_DOCKING broadcast.");
            try {
                Log.d("DockingEndReciever", "Sending com.example.myapplication.FORCE_STOP_DOCKING");
                Intent stopIntent = new Intent(DockingService.ACTION_FORCE_STOP);
                stopIntent.setPackage(context.getPackageName());
                context.sendBroadcast(stopIntent);
            } catch (Exception e) {
                Log.e("DockingEndReceiver", "Failed to send FORCE_STOP_DOCKING: " + e.getMessage());
            }
    }
}
