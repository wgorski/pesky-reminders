package com.peskyreminders.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Central hub: fires, re-posts, snoozes, and completes a task's reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getIntExtra(ReminderContract.EXTRA_TASK_ID, 0)
        if (taskId <= 0) return
        when (intent.action) {
            ReminderContract.ACTION_FIRE -> Reminders.notify(context, taskId)
            ReminderContract.ACTION_REPOST -> Reminders.notify(context, taskId)
            ReminderContract.ACTION_DONE -> Reminders.toggle(context, taskId)
            ReminderContract.ACTION_SNOOZE -> Reminders.snooze(context, taskId)
        }
    }
}
