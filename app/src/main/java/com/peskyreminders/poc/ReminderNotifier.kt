package com.peskyreminders.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Builds and posts the ongoing, re-posting reminder notification. */
object ReminderNotifier {

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ReminderContract.CHANNEL_ID,
            "Pesky Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Reminders you must snooze or complete" }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, text: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Pesky Reminder")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(broadcast(context, ReminderContract.ACTION_REPOST, text))
            .addAction(0, "Snooze", broadcast(context, ReminderContract.ACTION_SNOOZE, text))
            .addAction(0, "Done", broadcast(context, ReminderContract.ACTION_DONE, text))
            .build()

        manager.notify(ReminderContract.NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ReminderContract.NOTIFICATION_ID)
    }

    private fun broadcast(context: Context, action: String, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderContract.EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(), // distinct request code per action
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
