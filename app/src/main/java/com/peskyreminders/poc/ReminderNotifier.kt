package com.peskyreminders.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat

/** Builds and posts the ongoing, re-posting reminder notification for one task. */
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

    fun post(context: Context, task: Task) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val due = TaskTime.formatFull(task.dueMillis, now, !DateFormat.is24HourFormat(context))

        val notification = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(task.name)
            .setContentText(if (task.dueMillis < now) "Was due $due" else due)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(
                broadcast(context, ReminderContract.ACTION_REPOST, task.id, ReminderContract.SLOT_REPOST)
            )
            .addAction(
                0, "Snooze",
                broadcast(context, ReminderContract.ACTION_SNOOZE, task.id, ReminderContract.SLOT_SNOOZE)
            )
            .addAction(
                0, "Done",
                broadcast(context, ReminderContract.ACTION_DONE, task.id, ReminderContract.SLOT_DONE)
            )
            .build()

        manager.notify(ReminderContract.notificationId(task.id), notification)
    }

    /**
     * Clears the notification without firing its delete-intent — which is why
     * Snooze and Done can dismiss it but a swipe cannot.
     */
    fun cancel(context: Context, taskId: Int) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ReminderContract.notificationId(taskId))
    }

    private fun broadcast(context: Context, action: String, taskId: Int, slot: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderContract.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            ReminderContract.requestCode(taskId, slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
