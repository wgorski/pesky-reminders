package com.wgorski.peskyreminders

import android.content.Context

/**
 * What [Reminders.toggle] did, so a caller can explain itself when nothing moved.
 *
 * The rule for *whether* a tick is allowed stays in [Reminders.toggle] — this is
 * how the outcome gets back out, rather than having the UI re-derive the same
 * condition and drift from it.
 */
enum class ToggleOutcome {
    /** A one-off ticked off. It is in the done list now. */
    COMPLETED,

    /** A done task un-ticked, back among the active ones. */
    REOPENED,

    /**
     * A repeater rolled on to its next occurrence. Never "done" — see [Reminders.toggle].
     *
     * Kept distinct from [REOPENED] because the two are not distinguishable from
     * the outside. A done one-off can be edited into a repeater — the editor is
     * reachable from a done row and [Reminders.update] carries `done` through — so
     * `done && repeats` is a reachable state, and un-ticking it takes the reopen
     * branch while looking exactly like a roll-forward. Anything inferring the
     * outcome from the task afterwards would get that case wrong.
     */
    ADVANCED,

    /** A repeater whose slot has not come. Nothing happened, deliberately. */
    NOT_DUE_YET,

    /** No such task — deleted from under the tap. */
    MISSING,
}

/**
 * What a snooze did.
 *
 * [ALREADY_PAST] belongs to [Reminders.snoozeUntil] alone: the sheet can sit open
 * across the very rung it is offering, and the task is then left overdue and
 * pestering rather than pushed. [Reminders.snooze] always lands in the future by
 * construction, so it can only ever report [MOVED] or [MISSING].
 */
enum class SnoozeOutcome {
    /** Pushed. The task's [Task.dueMillis] is where it landed. */
    MOVED,

    /** The target had already gone by. Still overdue, notification still up. */
    ALREADY_PAST,

    /** No such task — deleted from under the tap. */
    MISSING,
}

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

    /**
     * The reminder arriving: post it with sound and a buzz.
     *
     * This is the only path that makes a noise, and the alarm firing is the only
     * caller that should take it. Everything else that puts the same
     * notification back on screen goes through [repost].
     */
    fun notify(context: Context, taskId: Int) =
        post(context, taskId, ReminderNotifier.Alert.FULL)

    /**
     * Put the same notification back, or refresh it in place, saying nothing.
     *
     * Two callers, one shape: an edit that leaves the task overdue, and a snooze
     * onto a time that has already passed. In both the reminder was already on
     * screen and the user knows it, so a buzz would only be the app answering back.
     *
     * The swipe used to be the third and is not any more — it snoozes instead, so
     * there is nothing to put back. See [ReminderContract.ACTION_SWIPED].
     */
    fun repost(context: Context, taskId: Int) =
        post(context, taskId, ReminderNotifier.Alert.SILENT)

    private fun post(context: Context, taskId: Int, alert: ReminderNotifier.Alert) {
        val task = TaskStore.find(context, taskId) ?: return
        if (task.done) return
        ReminderNotifier.post(context, task, alert)
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
        // A buzz but no chime. The nag repeats on an interval the user chose, and
        // a notification sound every few minutes is intolerable; the buzz is what
        // they asked for. See ReminderNotifier.Alert.
        ReminderNotifier.post(context, task, ReminderNotifier.Alert.BUZZ_ONLY)
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
     *
     * Two things it deliberately will not do:
     *
     * - **Roll a repeater forward before its slot has come.** Ticking off a daily
     *   task that is not due until tomorrow used to skip tomorrow, throwing away
     *   the occurrence you could still act on. It is now a no-op — nothing is
     *   cancelled, nothing is rescheduled.
     * - **Count the next occurrence from a snooze.** The cycle steps from
     *   [Task.slotMillis], so snoozing this morning's 9am buzz to 9:35 leaves
     *   tomorrow's at 9am.
     *
     * Both tests read the slot rather than the fire time, which is what keeps
     * "snooze it, then finish it a minute later" working: the slot has passed even
     * though the snoozed firing has not.
     *
     * Returns what happened. A refused tick is otherwise indistinguishable from a
     * broken one, and the check circle is a real tap target that would just sit
     * there saying nothing — the caller uses [ToggleOutcome.NOT_DUE_YET] to say why.
     */
    fun toggle(context: Context, taskId: Int): ToggleOutcome {
        val task = TaskStore.find(context, taskId) ?: return ToggleOutcome.MISSING
        val now = System.currentTimeMillis()

        if (!task.done && task.repeats && task.slotMillis > now) {
            return ToggleOutcome.NOT_DUE_YET
        }

        ReminderNotifier.cancel(context, taskId)
        ReminderScheduler.cancelNag(context, taskId)

        // Named rather than re-tested below, so which branch ran and which outcome
        // is reported cannot drift apart.
        val advancing = !task.done && task.repeats
        val next = if (advancing) {
            task.copy(
                dueMillis = TaskTime.nextOccurrence(task.slotMillis, task.repeat, now),
                anchorMillis = null,
            )
        } else {
            task.copy(done = !task.done, anchorMillis = null)
        }
        TaskStore.replace(context, next)

        // Nothing to fire for a completed task, or for one that is already late:
        // an alarm in the past would go off the instant it is set.
        if (next.done || next.dueMillis <= now) ReminderScheduler.cancel(context, taskId)
        else ReminderScheduler.schedule(context, next)

        return when {
            advancing -> ToggleOutcome.ADVANCED
            next.done -> ToggleOutcome.COMPLETED
            else -> ToggleOutcome.REOPENED
        }
    }

    /**
     * Apply an edit from the task sheet — a new name, due time and repeat rule,
     * committed together. The id is kept, so the notification id and the alarm
     * request codes derived from it stay valid.
     *
     * The notification handling is the delicate part, and it is deliberately not
     * symmetrical:
     *
     * - **Moved into the future.** Whatever it was pestering about no longer
     *   applies, so the notification and its nag chain go, and the alarm is armed.
     * - **Still in the past.** The alarm cannot be armed — it would fire the
     *   instant it was set — and a notification already on screen has to
     *   *survive*. Cancelling it here meant opening an overdue task and pressing
     *   Save without changing a thing silently cleared a reminder the user is not
     *   allowed to dismiss, which is the whole point of the app. It is re-posted
     *   instead, so it picks up the new name, and the nagging carries on.
     * - **Done.** Nothing to fire and nothing to show.
     *
     * Nothing is ever *posted* from here. Saving a time that has already passed
     * leaves an overdue row and no notification, exactly as un-ticking a task
     * does; [restoreAll] stays the only place a past-due task is surfaced
     * without being asked for.
     */
    fun update(context: Context, taskId: Int, name: String, dueMillis: Long, repeat: Repeat) {
        val task = TaskStore.find(context, taskId) ?: return
        val now = System.currentTimeMillis()
        val showing = ReminderNotifier.isShowing(context, taskId)
        // An edit names the slot outright, so any snooze anchor it was carrying is
        // spent — the time the user just picked *is* the new cycle.
        val next = task.copy(
            name = name,
            dueMillis = dueMillis,
            repeat = repeat,
            anchorMillis = null,
        )
        TaskStore.replace(context, next)

        when {
            next.done -> {
                ReminderNotifier.cancel(context, taskId)
                ReminderScheduler.cancel(context, taskId)
                ReminderScheduler.cancelNag(context, taskId)
            }

            dueMillis > now -> {
                ReminderNotifier.cancel(context, taskId)
                ReminderScheduler.cancelNag(context, taskId)
                ReminderScheduler.schedule(context, next)
            }

            else -> {
                ReminderScheduler.cancel(context, taskId)
                // Silent: this only refreshes a notification the user is looking
                // at so it picks up the new name. They pressed Save a moment ago
                // — buzzing them about their own edit is noise.
                if (showing) repost(context, taskId)
            }
        }
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
     *
     * On a **repeating** task the snooze moves only this firing. The slot it came
     * from is parked in [Task.anchorMillis] so the next occurrence still counts
     * from there — snoozing a daily 9am reminder by half an hour does not drag
     * every following day to 9:30. The first snooze wins: snoozing again keeps the
     * slot already recorded rather than anchoring to the snooze.
     */
    fun snooze(
        context: Context,
        taskId: Int,
        minutes: Int = SnoozeOptions.DEFAULT_MINUTES,
    ): SnoozeOutcome {
        val task = TaskStore.find(context, taskId) ?: return SnoozeOutcome.MISSING
        ReminderNotifier.cancel(context, taskId)
        ReminderScheduler.cancelNag(context, taskId)
        val from = System.currentTimeMillis()
        val next = task.copy(
            dueMillis = ReminderContract.snoozeTriggerAtMillis(from, minutes),
            anchorMillis = if (task.repeats) task.slotMillis else null,
        )
        TaskStore.replace(context, next)
        ReminderScheduler.schedule(context, next)
        return SnoozeOutcome.MOVED
    }

    /**
     * Push a reminder to an absolute time rather than by a duration.
     *
     * The counterpart to [snooze], behind the sheet's time chips. The target
     * arrives already computed and is stored verbatim, which is the whole point:
     * converting it to minutes at composition time would drift by however long
     * the user takes to tap, and [ReminderActivity] snapshots its clock once at
     * `setContent` and never refreshes it — a sheet left open five minutes would
     * land "Tomorrow 08:00" at 08:05.
     *
     * The anchor is kept exactly as [snooze] keeps it: a snooze moves one firing,
     * not the cycle, so a daily 09:00 pushed to tomorrow morning still leaves the
     * day after at 09:00.
     *
     * Unlike [snooze], the target is not guaranteed to be in the future — see the
     * past branch below.
     */
    fun snoozeUntil(context: Context, taskId: Int, atMillis: Long): SnoozeOutcome {
        val task = TaskStore.find(context, taskId) ?: return SnoozeOutcome.MISSING
        val now = System.currentTimeMillis()
        // Read before anything is cancelled — the past branch needs to know
        // whether there was a notification to put back.
        val showing = ReminderNotifier.isShowing(context, taskId)
        ReminderScheduler.cancelNag(context, taskId)

        val next = task.copy(
            dueMillis = atMillis,
            anchorMillis = if (task.repeats) task.slotMillis else null,
        )
        TaskStore.replace(context, next)

        return if (atMillis > now) {
            ReminderNotifier.cancel(context, taskId)
            ReminderScheduler.schedule(context, next)
            SnoozeOutcome.MOVED
        } else {
            // The sheet sat open across the very rung it was offering. Never arm
            // setAlarmClock in the past — it fires at once. Take the line
            // `create` takes for a past due time instead: pester me now, so the
            // reminder stays overdue with its notification live.
            ReminderScheduler.cancel(context, taskId)
            // Silent, like the edit-refresh above: the notification never left,
            // and the toast already says "…has passed — still due."
            if (showing) repost(context, taskId)
            SnoozeOutcome.ALREADY_PAST
        }
    }
}
