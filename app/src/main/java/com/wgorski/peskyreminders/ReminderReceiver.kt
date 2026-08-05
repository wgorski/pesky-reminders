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
            // FIRE, REPOST and NAG stay silent: those are the app talking to
            // itself, not a user action there is anything to confirm.
            ReminderContract.ACTION_FIRE -> Reminders.notify(context, taskId)
            // Silent. This is the swipe being undone — the user just tried to
            // get rid of it, and answering with a sound and a buzz reads as the
            // app arguing back. The notification reappearing is the whole
            // statement.
            ReminderContract.ACTION_REPOST -> Reminders.repost(context, taskId)
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
