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
                task.dueMillis <= now -> notify(context, task.id)
                else -> ReminderScheduler.schedule(context, task)
            }
        }
    }

    /** Post (or re-post) a task's notification, unless it is already dealt with. */
    fun notify(context: Context, taskId: Int) {
        val task = TaskStore.find(context, taskId) ?: return
        if (task.done) return
        ReminderNotifier.post(context, task)
        scheduleNextNag(context, taskId)
    }

    /**
     * Buzz again for a notification the user is still ignoring, then queue the
     * next one. Snooze and Done both clear the notification, so a missing one
     * is how the chain stops.
     */
    fun nag(context: Context, taskId: Int) {
        Settings.hydrate(context)
        if (!Settings.nagEnabled) {
            ReminderScheduler.cancelNag(context, taskId)
            return
        }
        val task = TaskStore.find(context, taskId) ?: return
        // Snooze and Done both clear the notification, so a missing one is how
        // the chain stops. (Not "is the due time in the future?" — an alarm that
        // fires a moment early would cancel its own chain.)
        if (task.done || !ReminderNotifier.isShowing(context, taskId)) {
            ReminderScheduler.cancelNag(context, taskId)
            return
        }
        ReminderNotifier.post(context, task) // post() does the buzzing
        scheduleNextNag(context, taskId)
    }

    /**
     * Arms the next buzz, or cancels the chain outright when the user has
     * turned nagging off. Interval comes from [Settings], not a constant.
     */
    private fun scheduleNextNag(context: Context, taskId: Int) {
        Settings.hydrate(context)
        if (!Settings.nagEnabled) {
            ReminderScheduler.cancelNag(context, taskId)
            return
        }
        ReminderScheduler.scheduleNag(
            context,
            taskId,
            System.currentTimeMillis() + Settings.nagIntervalMillis(context),
        )
    }

    /**
     * Re-arm (or drop) the nags for notifications that are already on screen,
     * so a settings change takes effect without waiting for the next buzz.
     */
    fun applyNagSettings(context: Context) {
        TaskStore.hydrate(context)
        TaskStore.tasks.forEach { task ->
            if (!task.done && ReminderNotifier.isShowing(context, task.id)) {
                scheduleNextNag(context, task.id)
            }
        }
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
        ReminderScheduler.cancelNag(context, taskId)

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

    /**
     * Remove one task outright, whatever state it is in.
     *
     * The only way to be rid of a repeating task. Ticking one off rolls it to its
     * next occurrence rather than completing it (see [toggle]), so it never lands
     * in the done list and [clearDone] can never reach it — without this it would
     * pester forever.
     *
     * Cancels the same three things [clearDone] does, and for the same reason:
     * after the task is gone its id cannot be reached again.
     */
    fun delete(context: Context, taskId: Int) {
        val task = TaskStore.find(context, taskId) ?: return
        ReminderNotifier.cancel(context, task.id)
        ReminderScheduler.cancel(context, task.id)
        ReminderScheduler.cancelNag(context, task.id)
        TaskStore.remove(context, task.id)
    }

    /**
     * Throw away every completed task, and return how many went.
     *
     * The first delete in the app, so it takes the belt-and-braces route: a done
     * task should already have had its alarm, nag and notification cancelled by
     * [toggle], but this is the last moment any of them can be reached by id. A
     * survivor would fire on a task that no longer exists — harmless, since
     * [notify] looks the task up and finds nothing, but it would sit in the
     * alarm table until the next reboot.
     *
     * Repeating tasks are never done (see [toggle]), so this can only ever take
     * one-offs; nothing here can roll forward and come back.
     */
    fun clearDone(context: Context): Int {
        TaskStore.hydrate(context)
        val done = TaskStore.tasks.filter { it.done }
        if (done.isEmpty()) return 0
        done.forEach { task ->
            ReminderNotifier.cancel(context, task.id)
            ReminderScheduler.cancel(context, task.id)
            ReminderScheduler.cancelNag(context, task.id)
        }
        TaskStore.removeAll(context, done.map { it.id }.toSet())
        return done.size
    }

    /**
     * Push a reminder to [minutes] from now.
     *
     * Always counted from the clock, never from the task's own due time. "Snooze
     * 15 minutes" means fifteen minutes from the moment you asked, whether the
     * reminder has just gone off or is not due until tomorrow.
     *
     * Note this can move a task *earlier*: rescheduling something due tomorrow
     * by 30 minutes brings it to half an hour from now. That is the trade for a
     * rule you can predict without knowing when the task was due.
     *
     * Because the result is always in the future, it can never schedule an alarm
     * in the past — which would fire the instant it was set.
     */
    fun snooze(context: Context, taskId: Int, minutes: Int = SnoozeOptions.DEFAULT_MINUTES) {
        val task = TaskStore.find(context, taskId) ?: return
        ReminderNotifier.cancel(context, taskId)
        ReminderScheduler.cancelNag(context, taskId)
        val from = System.currentTimeMillis()
        val next = task.copy(dueMillis = ReminderContract.snoozeTriggerAtMillis(from, minutes))
        TaskStore.replace(context, next)
        ReminderScheduler.schedule(context, next)
    }
}
