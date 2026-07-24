package com.peskyreminders.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Central hub: fires, re-posts, snoozes, and completes the reminder. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderContract.EXTRA_TEXT) ?: "Reminder"
        when (intent.action) {
            ReminderContract.ACTION_FIRE -> ReminderNotifier.post(context, text)
            ReminderContract.ACTION_REPOST -> ReminderNotifier.post(context, text)
            ReminderContract.ACTION_DONE -> ReminderNotifier.cancel(context)
        }
    }
}
