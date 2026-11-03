package com.sitr.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean ads = context.getSharedPreferences("sitr", Context.MODE_PRIVATE)
                .getBoolean("ads", false);
        boolean privacy = context.getSharedPreferences("sitr", Context.MODE_PRIVATE)
                .getBoolean("privacy", false);
        if (!ShieldVpnService.isRunning || (!ads && !privacy)) return;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel("sitr_daily",
                "تذكير الحماية اليومي", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, "sitr_daily")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("🛡️ سِتر يحمي جهازك")
                .setContentText("الحماية ما زالت تعمل. افتح التطبيق لمراجعة الإحصائيات.")
                .setAutoCancel(true).setContentIntent(open).build();
        nm.notify(1002, notification);
    }
}
