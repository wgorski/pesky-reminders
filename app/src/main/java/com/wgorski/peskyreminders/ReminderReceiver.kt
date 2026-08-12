package com.wgorski.peskyreminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat

/** Central hub: fires, re-posts, snoozes and completes a task's reminder. The
 *  snooze *sheet* goes through [ReminderActivity] instead — it shows UI, which a
 *  receiver cannot do on Android 12+. The notification's one-tap snooze shows
 *  none, so it lands here beside Done. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getIntExtra(ReminderContract.EXTRA_TASK_ID, 0)
        if (taskId <= 0) return
        when (intent.action) {
            // FIRE and NAG stay silent: those are the app talking to itself, not a
            // user action there is anything to confirm.
            ReminderContract.ACTION_FIRE -> Reminders.notify(context, taskId)
            // A swipe buys a few minutes rather than nothing at all. Reminders.snooze
            // does the whole job — cancels the (already-gone) notification and the
            // nag chain, keeps a repeater's anchor so the cycle does not drag five
            // minutes later every day, and re-arms the alarm.
            //
            // Two things this has to do for itself, both because a receiver is not
            // the UI: hydrate before reading the setting, since the process may have
            // been started by this very broadcast with nothing having touched
            // Settings yet, and ask for the device's clock format, having no
            // composition to inherit it from.
            //
            // It answers back, which FIRE and NAG do not, because the notification
            // no longer reappears to speak for itself. Only in text, though — a
            // swipe met with a chime and a buzz reads as the app arguing with you,
            // and that judgement is unchanged.
            ReminderContract.ACTION_SWIPED -> {
                Settings.hydrate(context)
                val outcome =
                    Reminders.snooze(context, taskId, Settings.swipeSnoozeMinutes)
                ActionToast.swiped(
                    context,
                    outcome,
                    taskId,
                    System.currentTimeMillis(),
                    DateFormat.is24HourFormat(context),
                )
            }
            ReminderContract.ACTION_DONE -> {
                val outcome = Reminders.toggle(context, taskId)
                // The one surface with no composition to inherit the clock format
                // from, so it asks for itself.
                ActionToast.toggled(
                    context,
                    outcome,
                    taskId,
                    System.currentTimeMillis(),
                    DateFormat.is24HourFormat(context),
                )
            }
            // Not a trampoline: it changes state and posts a toast, exactly as
            // DONE does above. Only the sheet of durations has to be an activity
            // — see ReminderNotifier.openSheetIntent. Reminders.snooze cancels
            // the notification and the nag chain, keeps a repeater's anchor, and
            // re-arms the alarm; there is no extra step to do here.
            ReminderContract.ACTION_SNOOZE -> {
                val outcome =
                    Reminders.snooze(context, taskId, SnoozeOptions.QUICK_MINUTES)
                ActionToast.snoozed(
                    context,
                    outcome,
                    taskId,
                    System.currentTimeMillis(),
                    DateFormat.is24HourFormat(context),
                )
            }
            ReminderContract.ACTION_NAG -> Reminders.nag(context, taskId)
        }
    }
}
