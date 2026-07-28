package com.wgorski.peskyreminders

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

    /**
     * The next buzz for a notification that is being ignored.
     *
     * Also an alarm clock rather than [AlarmManager.setExactAndAllowWhileIdle],
     * which Doze throttles to roughly once every nine minutes — a five-minute
     * nag would quietly slip.
     */
    fun scheduleNag(context: Context, taskId: Int, atMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val info = AlarmManager.AlarmClockInfo(atMillis, showIntent(context))
        alarmManager.setAlarmClock(info, nagPendingIntent(context, taskId))
    }

    fun cancelNag(context: Context, taskId: Int) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(nagPendingIntent(context, taskId))
    }

    private fun nagPendingIntent(context: Context, taskId: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_NAG
            putExtra(ReminderContract.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            ReminderContract.requestCode(taskId, ReminderContract.SLOT_NAG),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
