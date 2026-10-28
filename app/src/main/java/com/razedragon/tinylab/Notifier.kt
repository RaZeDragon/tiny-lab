package com.razedragon.tinylab

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Posts a reminder notification when an alarm set by [Notifier.schedule] goes off. */
class Notifier : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Shroomology"
        val body = intent.getStringExtra("body") ?: return
        val id = intent.getIntExtra("id", 1)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Garden reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        // status bar: the white bell outline (Android only shows shapes there); inside the notification: the full-color bell
        val bell = try { android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.notify_bell) } catch (_: Exception) { null }
        if (bell != null) b.setLargeIcon(bell)
        if (Build.VERSION.SDK_INT >= 21) b.setColor(android.graphics.Color.rgb(150, 90, 230))
        val n = b.setSmallIcon(R.drawable.ic_notify_bell)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }

    companion object {
        private const val CHANNEL = "garden"

        private fun pending(context: Context, id: Int, title: String = "", body: String = ""): PendingIntent {
            val i = Intent(context, Notifier::class.java).putExtra("id", id).putExtra("title", title).putExtra("body", body)
            return PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun schedule(context: Context, id: Int, at: Long, title: String, body: String) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC_WAKEUP, at, pending(context, id, title, body))
        }

        fun cancelAll(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            for (id in 1..4) am.cancel(pending(context, id))
        }
    }
}
