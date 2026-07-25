package com.peskyreminders.poc

import android.content.Context

/**
 * The one place where the task list and the alarm/notification plumbing meet.
 *
 * Both the UI and [ReminderReceiver] go through here, so ticking a task off in
 * the app and tapping Done on the notification take exactly the same path.
 */
object Reminders {

    fun create(context: Context, name: String, dueMillis: Long, repeat: Repeat): Task {
        val task = TaskStore.add(context, name, dueMillis, repeat)
        ReminderScheduler.schedule(context, task)
        return task
    }

    /**
     * Re-arm every pending reminder — called after a reboot or an app update,
     * both of which silently drop all scheduled alarms.
     *
     * Anything whose moment passed while the device was off is posted straight
     * away rather than skipped: a reminder you are not allowed to dismiss is
     * not worth much if a reboot can swallow it. This is the one place a
     * past-due task is deliberately surfaced without the user asking.
     */
    fun restoreAll(context: Context) {
        val now = System.currentTimeMillis()
        TaskStore.hydrate(context)
        TaskStore.tasks.forEach { task ->
            when {
                task.done -> Unit
                task.dueMillis <= now -> ReminderNotifier.post(context, task)
                else -> ReminderScheduler.schedule(context, task)
            }
        }
    }

    /** Post (or re-post) a task's notification, unless it is already dealt with. */
    fun notify(context: Context, taskId: Int) {
        val task = TaskStore.find(context, taskId) ?: return
        if (task.done) return
        ReminderNotifier.post(context, task)
    }

    /**
     * Tick a task off. A repeating task is never "done" — it rolls forward to
     * its next occurrence, matching the design. A one-off flips between done
     * and not.
     */
    fun toggle(context: Context, taskId: Int) {
        val task = TaskStore.find(context, taskId) ?: return
        val now = System.currentTimeMillis()
        ReminderNotifier.cancel(context, taskId)

        val next = if (!task.done && task.repeats) {
            task.copy(dueMillis = TaskTime.nextOccurrence(task.dueMillis, task.repeat, now))
        } else {
            task.copy(done = !task.done)
        }
        TaskStore.replace(context, next)

        // Nothing to fire for a completed task, or for one that is already late:
        // an alarm in the past would go off the instant it is set.
        if (next.done || next.dueMillis <= now) ReminderScheduler.cancel(context, taskId)
        else ReminderScheduler.schedule(context, next)
    }

    fun snooze(context: Context, taskId: Int) {
        val task = TaskStore.find(context, taskId) ?: return
        ReminderNotifier.cancel(context, taskId)
        val next = task.copy(
            dueMillis = ReminderContract.snoozeTriggerAtMillis(System.currentTimeMillis())
        )
        TaskStore.replace(context, next)
        ReminderScheduler.schedule(context, next)
    }
}
