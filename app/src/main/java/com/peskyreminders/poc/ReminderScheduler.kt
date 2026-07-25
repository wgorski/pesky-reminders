package com.peskyreminders.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Schedules a task's reminder at an exact time using an alarm clock. */
object ReminderScheduler {

    fun schedule(context: Context, task: Task) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(task.dueMillis, showIntent(context))
        alarmManager.setAlarmClock(info, firePendingIntent(context, task.id))
    }

    fun cancel(context: Context, taskId: Int) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(firePendingIntent(context, taskId))
    }

    private fun firePendingIntent(context: Context, taskId: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_FIRE
            putExtra(ReminderContract.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            ReminderContract.requestCode(taskId, ReminderContract.SLOT_FIRE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            ReminderContract.SHOW_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
