package com.peskyreminders.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Schedules the reminder to fire at an exact time using an alarm clock. */
object ReminderScheduler {

    fun schedule(context: Context, text: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context))
        alarmManager.setAlarmClock(info, firePendingIntent(context, text))
    }

    private fun firePendingIntent(context: Context, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_FIRE
            putExtra(ReminderContract.EXTRA_TEXT, text)
        }
        return PendingIntent.getBroadcast(
            context,
            ReminderContract.REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
