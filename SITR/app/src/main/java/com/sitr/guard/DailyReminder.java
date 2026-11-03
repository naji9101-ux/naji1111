package com.sitr.guard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

public final class DailyReminder {
    private DailyReminder() { }

    private static PendingIntent intent(Context c) {
        return PendingIntent.getBroadcast(c, 44,
                new Intent(c, ReminderReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context c) {
        AlarmManager manager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        if (!c.getSharedPreferences("sitr", Context.MODE_PRIVATE)
                .getBoolean("daily", true)) {
            manager.cancel(intent(c));
            return;
        }
        manager.cancel(intent(c));
        manager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_DAY,
                AlarmManager.INTERVAL_DAY, intent(c));
    }

    public static void cancel(Context c) {
        AlarmManager manager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) manager.cancel(intent(c));
    }
}
