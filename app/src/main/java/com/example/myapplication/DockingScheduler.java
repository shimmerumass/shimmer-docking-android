package com.example.myapplication;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import java.util.Calendar;

public class DockingScheduler {
    private static final String TAG = "DockingScheduler";
    private static final String ACTION_START_DOCKING = "com.example.myapplication.START_DOCKING";

    public static void scheduleDailyDocking(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // Cancel any start alarm
        PendingIntent startIntent = PendingIntent.getBroadcast(
            context, 1001,
            new Intent(context, DockingStartReceiver.class).setAction(ACTION_START_DOCKING),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(startIntent);

        // Cancel any end alarm
        PendingIntent endIntent = PendingIntent.getBroadcast(
            context, 1002,
            new Intent(context, DockingEndReceiver.class).setAction("com.example.myapplication.END_DOCKING"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.cancel(endIntent);

        // Load user-configured docking window from SharedPreferences
        android.content.SharedPreferences prefs = context.getSharedPreferences("docking_prefs", Context.MODE_PRIVATE);
        int startHour = prefs.getInt("night_start_hour", 20); // default 8 PM
        int startMinute = prefs.getInt("night_start_minute", 0);

        // End = start + 5 hours (minutes unchanged)
        int endHour = (startHour + 5) % 24;
        int endMinute = startMinute;

        // Schedule START at user-selected hour
        Calendar startTime = Calendar.getInstance();
        startTime.set(Calendar.HOUR_OF_DAY, startHour);
        startTime.set(Calendar.MINUTE, startMinute);
        startTime.set(Calendar.SECOND, 0);
        startTime.set(Calendar.MILLISECOND, 0);
        if (startTime.getTimeInMillis() <= System.currentTimeMillis()) {
            startTime.add(Calendar.DAY_OF_MONTH, 1);
        }

        // Compute end time
        Calendar endTime = (Calendar) startTime.clone();
        endTime.set(Calendar.HOUR_OF_DAY, endHour);
        endTime.set(Calendar.MINUTE, endMinute);
        // If end is before start, add a day
        if (endTime.getTimeInMillis() <= startTime.getTimeInMillis()) {
            endTime.add(Calendar.DAY_OF_MONTH, 1);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startTime.getTimeInMillis(), startIntent);
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime.getTimeInMillis(), endIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, startTime.getTimeInMillis(), startIntent);
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, endTime.getTimeInMillis(), endIntent);
            }
            long when = startTime.getTimeInMillis();
            Log.d(TAG, "Docking protocol START scheduled for: " + startTime.getTime());
            Log.d(TAG, "Docking protocol END scheduled for: " + endTime.getTime());
            try {
                context.getSharedPreferences("docking_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("next_docking_trigger_at", when)
                        .putLong("next_docking_end_at", endTime.getTimeInMillis())
                        .apply();
            } catch (Exception ignored) {}
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Cannot schedule exact alarm", e);
        }
    }
}
