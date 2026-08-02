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

    /**
     * How loudly a post is allowed to announce itself.
     *
     * The three cases are genuinely different events wearing the same
     * notification, and conflating them is what made a *swipe* — a gesture the
     * user makes to get rid of something — answer back with a full alert.
     *
     * - [FULL] the reminder arriving. Sound and a buzz; this is the moment the
     *   app exists for.
     * - [BUZZ_ONLY] the nag from Settings. A buzz, no sound: it repeats on an
     *   interval, and a chime every few minutes is what makes people uninstall.
     * - [SILENT] the same reminder being put back, or refreshed after an edit.
     *   Nothing. It was already on screen; nothing happened that the user does
     *   not already know about.
     *
     * Note the asymmetry in *who* does it. Sound is always Android's, from the
     * channel, and can only be chosen per-post by choosing the channel.
     *
     * The buzz is split. [FULL] lets the **channel** vibrate, because that is
     * played by the system as part of posting the notification: atomic with it,
     * and immune to this process being frozen or killed the moment `onReceive`
     * returns — which a waveform we start ourselves is not, and is the likeliest
     * reason an arriving reminder was sometimes not felt with the screen off.
     * [BUZZ_ONLY] has to do it itself, because a nag only ever *updates* a
     * notification already on screen and `setOnlyAlertOnce` stops the channel
     * re-alerting; that flag is also what keeps the nag silent, so it cannot go.
     *
     * The trade this makes: a channel vibration follows notification rules, so it
     * is suppressed in full silent mode, where the app-driven one survived by
     * declaring `USAGE_ALARM`. The nag still survives it. Reliability was judged
     * worth more than ringing through silent mode on arrival.
     */
    enum class Alert(internal val selfBuzz: Boolean, internal val sounds: Boolean) {
        FULL(selfBuzz = false, sounds = true),
        BUZZ_ONLY(selfBuzz = true, sounds = false),
        SILENT(selfBuzz = false, sounds = false),
        ;

        /**
         * Which channel carries it. Only [FULL] gets the one with a sound, so
         * everything after the reminder's first appearance is silent *by
         * construction* rather than by relying on `setOnlyAlertOnce` — that flag
         * only suppresses re-alerts of a notification still on screen, and a nag
         * that somehow posted fresh would otherwise chime.
         */
        internal val channelId: String
            get() = if (sounds) ReminderContract.CHANNEL_ID else ReminderContract.QUIET_CHANNEL_ID
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Older generations of this channel, whose alerting can't be changed in
        // place. Dropping them is the migration — see CHANNEL_ID.
        ReminderContract.LEGACY_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        val reminder = NotificationChannel(
            ReminderContract.CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A reminder coming due. Sounds and buzzes once, when it arrives."
            // The channel buzzes for an arriving reminder, and post() no longer
            // does. The system plays this as part of posting the notification, so
            // it cannot be cut short by our process being frozen the moment the
            // receiver returns. Nothing races it: only Alert.BUZZ_ONLY buzzes
            // itself now, and that lands on the quiet channel, which does not.
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
        }
        // Same in every respect except the sound, which is the one thing a
        // notification cannot override per post — see QUIET_CHANNEL_ID. Keeping
        // the importance identical is what stops a re-posted reminder looking or
        // sorting differently from the one it replaces.
        val quiet = NotificationChannel(
            ReminderContract.QUIET_CHANNEL_ID,
            "Repeat buzz and re-posts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description =
                "The same reminder repeating, or put back after you swipe it away. Never sounds."
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(reminder)
        manager.createNotificationChannel(quiet)
    }

    /** Is this task's notification still sitting in the shade, being ignored? */
    fun isShowing(context: Context, taskId: Int): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .any { it.id == ReminderContract.notificationId(taskId) }

    /**
     * Buzz the device directly — now only for the **nag**.
     *
     * An arriving reminder is buzzed by its channel instead, which is more
     * reliable because the system plays it as part of posting the notification.
     * A nag cannot use that route: it only ever updates a notification already on
     * screen, and `setOnlyAlertOnce` — which is what keeps the nag from chiming —
     * stops the channel re-alerting. Cancel-then-post would re-alert, but it makes
     * the notification flicker and jump the shade.
     *
     * The one thing this route still does better: `USAGE_ALARM` below survives
     * silent mode, which a channel vibration does not.
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

    fun post(context: Context, task: Task, alert: Alert = Alert.FULL) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        // Not negated. It was, which showed a 24-hour device "5:17 PM" while the
        // list row beside it read "17:17" for the same task — and now the toast
        // that reports snoozing it would have disagreed too.
        val due = TaskTime.formatFull(task.dueMillis, now, DateFormat.is24HourFormat(context))

        val open = openSheetIntent(context, task.id)
        val notification = NotificationCompat.Builder(context, alert.channelId)
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
            // on update — otherwise a refresh buzzes twice. This is also what
            // keeps the nag's *sound* off: a nag only ever updates a
            // notification already on screen, and this flag is what Android
            // checks before re-alerting one. Do not turn it off to "make the
            // nag repeat" — the repeat is ours, and losing this would put the
            // notification chime on every interval.
            .setOnlyAlertOnce(true)
            // No setSilent() here, deliberately: it silences by moving the
            // notification into a group keyed "silent", which drops it out of the
            // app's stack in the shade. The quiet channel does the same job with
            // no side effect — see ReminderContract.QUIET_CHANNEL_ID.
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
        // Only the nag. An arriving reminder is buzzed by its channel — see Alert.
        if (alert.selfBuzz) vibrate(context)
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
