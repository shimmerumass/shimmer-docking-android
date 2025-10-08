package com.example.myapplication;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;

// Import DockingService for ACTION_FORCE_STOP
import com.example.myapplication.DockingService;

public class DockingEndReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
            if (!"com.example.myapplication.END_DOCKING".equals(intent.getAction())) return;
            Log.d("DockingEndReceiver", "Docking window ended. Sending FORCE_STOP_DOCKING broadcast.");
            
            // Show notification that end alarm fired
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel("docking_events", "Docking Events", NotificationManager.IMPORTANCE_DEFAULT);
                notificationManager.createNotificationChannel(channel);
            }
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "docking_events")
                    .setContentTitle("Docking End Alarm Fired")
                    .setContentText("Docking window has ended")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            
            notificationManager.notify(9999, builder.build());
            
            try {
                Log.d("DockingEndReciever", "Sending com.example.myapplication.FORCE_STOP_DOCKING");
                Intent stopIntent = new Intent(DockingService.ACTION_FORCE_STOP);
                stopIntent.setPackage(context.getPackageName());
                context.sendBroadcast(stopIntent);
            } catch (Exception e) {
                Log.e("DockingEndReceiver", "Failed to send FORCE_STOP_DOCKING: " + e.getMessage());
                
                // Show error notification
                NotificationCompat.Builder errorBuilder = new NotificationCompat.Builder(context, "docking_events")
                        .setContentTitle("Docking End Error")
                        .setContentText("Failed to stop docking service: " + e.getMessage())
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
                
                notificationManager.notify(9998, errorBuilder.build());
            }

            // Immediately schedule next day's alarm so it repeats daily
            try {
                DockingScheduler.scheduleDailyDocking(context.getApplicationContext());
                
                // Show notification that next day alarm scheduled
                NotificationCompat.Builder nextDayBuilder = new NotificationCompat.Builder(context, "docking_events")
                        .setContentTitle("Next Day Alarm Scheduled")
                        .setContentText("Docking protocol scheduled for tomorrow")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                
                notificationManager.notify(10000, nextDayBuilder.build());
                
            } catch (Exception e) {
                Log.e("DockingStartReceiver", "Failed to reschedule next docking alarm: " + e.getMessage());
                
                // Show error notification
                NotificationCompat.Builder errorBuilder = new NotificationCompat.Builder(context, "docking_events")
                        .setContentTitle("Scheduling Error")
                        .setContentText("Failed to schedule next docking alarm: " + e.getMessage())
                        .setSmallIcon(android.R.drawable.ic_dialog_alert)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
                
                notificationManager.notify(9997, errorBuilder.build());
            }
    }
}