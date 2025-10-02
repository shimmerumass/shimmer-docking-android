package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("BootCompletedReceiver", "Device rebooted. Rescheduling docking protocol.");
            // 1️⃣ Start continuous scanning immediately
            Intent scanIntent = new Intent(context, ScanningService.class);
            androidx.core.content.ContextCompat.startForegroundService(context, scanIntent);

            // 2️⃣ Schedule docking service
            DockingScheduler.scheduleDailyDocking(context);

            // 3️⃣ Launch MainActivity to start Lock Task Mode
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mainIntent);
        }
    }
}
