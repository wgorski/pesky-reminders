package com.wgorski.peskyreminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat

/** Builds and posts the ongoing, re-posting reminder notification for one task. */
object ReminderNotifier {

    /** Two short buzzes — long enough to notice, short enough not to be a siren. */
    private val VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The pre-vibration channel; its settings can't be changed in place.
        manager.deleteNotificationChannel(ReminderContract.LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            ReminderContract.CHANNEL_ID,
            "Pesky Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders you must snooze or complete"
            // We buzz ourselves in post(), on every repost and every nag. If the
            // channel also vibrated, the two would race and the platform would
            // cut ours short ("cancelled_superseded" in dumpsys vibrator_manager).
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Is this task's notification still sitting in the shade, being ignored? */
    fun isShowing(context: Context, taskId: Int): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { it.id == ReminderContract.notificationId(taskId) }

    /**
     * Buzz the device directly — the single source of vibration for this app.
     *
     * Re-posting a notification that is already showing does not reliably
     * re-alert, and cancel-then-post would make it flicker and jump the shade.
     * Driving the vibrator ourselves keeps every nag identical.
     */
    fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)

        // The usage MUST be declared. An unattributed vibrate() is logged as
        // USAGE_UNKNOWN, which real devices treat as incidental haptic feedback:
        // it gets gated behind touch-feedback settings, ring mode and Do Not
        // Disturb, and is silently dropped. Emulators do not enforce any of
        // that, so this only shows up on a real phone. ALARM matches how the
        // reminder is scheduled (setAlarmClock) and is the one usage that
        // survives silent mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    fun post(context: Context, task: Task) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        // Not negated. It was, which showed a 24-hour device "5:17 PM" while the
        // list row beside it read "17:17" for the same task — and now the toast
        // that reports snoozing it would have disagreed too.
        val due = TaskTime.formatFull(task.dueMillis, now, DateFormat.is24HourFormat(context))

        val open = openSheetIntent(context, task.id)
        val notification = NotificationCompat.Builder(context, ReminderContract.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.name)
            // Always the present tense, late or not: the notification is here
            // *because* the thing still wants doing, and "Was due" reads like a
            // report on something already gone. The list rows keep "Was due …" —
            // there, being late is the fact worth stating.
            .setContentText("Is due $due")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            // The nag drives its own vibration, so silence Android's re-alert
            // on update — otherwise a refresh buzzes twice.
            .setOnlyAlertOnce(true)
            // A tap on the body opens the same sheet the Snooze action does.
            // autoCancel stays off: tapping is not one of the two sanctioned
            // ways to clear a notification you are not allowed to dismiss.
            .setContentIntent(open)
            .setDeleteIntent(
                broadcast(context, ReminderContract.ACTION_REPOST, task.id, ReminderContract.SLOT_REPOST)
            )
            .addAction(0, "Snooze", open)
            .addAction(
                0, "Done",
                broadcast(context, ReminderContract.ACTION_DONE, task.id, ReminderContract.SLOT_DONE)
            )
            .build()

        manager.notify(ReminderContract.notificationId(task.id), notification)
        vibrate(context)
    }

    /**
     * Clears the notification without firing its delete-intent — which is why
     * Snooze and Done can dismiss it but a swipe cannot.
     */
    fun cancel(context: Context, taskId: Int) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ReminderContract.notificationId(taskId))
    }

    /**
     * Opens the reminder's action sheet — Done, the snooze chips and the wheel.
     *
     * Serves both the body tap and the Snooze action: same intent, same request
     * code, so it is literally the same PendingIntent and needs no slot of its
     * own in [ReminderContract].
     *
     * An activity, not a broadcast: Android 12+ blocks a notification action
     * from bouncing through a receiver to show UI.
     */
    private fun openSheetIntent(context: Context, taskId: Int): PendingIntent {
        val intent = Intent(context, ReminderActivity::class.java).apply {
            putExtra(ReminderContract.EXTRA_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            ReminderContract.requestCode(taskId, ReminderContract.SLOT_SNOOZE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
