package com.wgorski.peskyreminders

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * What the app says after a snooze or a done, and the only place it says it.
 *
 * Four surfaces can perform one of those two actions — the list's check circle,
 * the action panel raised from an overdue row, the same panel raised from the
 * notification, and the notification's own Done button. Every one routes through
 * here, so none of them can word the same event differently.
 *
 * It exists because [ui.ReminderSheet] deliberately has no confirm step and no
 * "back at …" footer: every chip and wheel row commits on the tap, so there is
 * never a held choice to preview. That leaves nothing on the way out to state
 * where the reminder actually landed, and a toast is the smallest thing that
 * closes the gap without adding the second tap the sheet was designed to avoid.
 *
 * [forToggle] and [forSnooze] are **pure** — `nowMillis` is a parameter rather
 * than a clock read, the same constraint that keeps [TaskTime] and [SnoozeOptions]
 * unit-testable. They return null for the cases that should stay silent, so "say
 * nothing" is one decision here rather than four at the call sites.
 *
 * Times come from [TaskTime] and nowhere else, which is what stops a toast
 * inventing a format or disagreeing with the sheet's chips about the 24-hour
 * clock. Landing times use [TaskTime.formatCompact], which drops the day when it
 * is today — a snooze usually lands within the hour, and "Snoozed until Today,
 * 3:45 PM" is two words too many. [ToggleOutcome.NOT_DUE_YET] keeps
 * [TaskTime.formatFull] and its comma, because it names a slot on a named day,
 * where the day is never redundant.
 */
object ActionToast {

    /** The message for a tick, or null if there is nothing worth saying. */
    fun forToggle(
        outcome: ToggleOutcome,
        task: Task?,
        nowMillis: Long,
        use24h: Boolean,
    ): String? = when (outcome) {
        ToggleOutcome.COMPLETED -> "Done."
        ToggleOutcome.REOPENED -> "Back on the list."
        // The one action with no visible consequence: the row seems to stay put,
        // having moved from OVERDUE to some future band, and nothing on screen
        // ever names the new time.
        ToggleOutcome.ADVANCED -> task?.let {
            "Done — next " + TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + "."
        }
        ToggleOutcome.NOT_DUE_YET -> task?.let {
            "Not due until " + TaskTime.formatFull(it.dueMillis, nowMillis, use24h) + "."
        }
        ToggleOutcome.MISSING -> null
    }

    /** The message for a snooze, or null if there is nothing worth saying. */
    fun forSnooze(
        outcome: SnoozeOutcome,
        task: Task?,
        nowMillis: Long,
        use24h: Boolean,
    ): String? = when (outcome) {
        SnoozeOutcome.MOVED -> task?.let {
            "Snoozed until " + TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + "."
        }
        // Never "snoozed until": the task did not move, and claiming it did would
        // be the one case where the toast contradicts what the app actually did.
        SnoozeOutcome.ALREADY_PAST -> task?.let {
            TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + " has passed — still due."
        }
        SnoozeOutcome.MISSING -> null
    }

    /**
     * The last toast shown, so the next one can replace it.
     *
     * Ticking five rows off in a row would otherwise queue five toasts and replay
     * them for ten seconds; only the newest action is worth reporting. Holding
     * this statically is safe only because [show] builds with the application
     * context — a `Toast` made from an Activity would retain it.
     */
    private var current: Toast? = null

    /**
     * Post [message], replacing anything already on screen.
     *
     * The application context is load-bearing twice over: it keeps [current] from
     * retaining an Activity, and it lets the toast outlive
     * [ReminderActivity]'s `finishAndRemoveTask()`, which fires the instant the
     * sheet commits.
     *
     * **Safe from any thread.** `Toast` throws outright off the main looper
     * ("Can't toast on a thread that has not called Looper.prepare()"), and while
     * every *production* caller is already there — Compose callbacks by
     * definition, `BroadcastReceiver.onReceive` by default — the instrumented
     * tests drive [ReminderReceiver] straight from the instrumentation thread,
     * which is a real caller shape and crashed. Requiring the main thread would
     * leave the same trap for the next caller, so it hops instead. The
     * already-there case stays synchronous, so a test can assert on the toast the
     * moment it has acted.
     */
    fun show(context: Context, message: String) {
        val app = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            replace(app, message)
        } else {
            Handler(Looper.getMainLooper()).post { replace(app, message) }
        }
    }

    private fun replace(appContext: Context, message: String) {
        current?.cancel()
        current = Toast.makeText(appContext, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    /**
     * Report what a tick did to task [taskId].
     *
     * The task is re-read rather than passed in, because the interesting field is
     * the one the action just wrote: [Reminders.toggle] puts the next occurrence
     * in [Task.dueMillis], and both [Reminders.snoozeUntil] branches store their
     * target there. That is reading state, not re-deriving a rule — the rule for
     * *what happened* stayed in [Reminders] and arrives as [outcome].
     */
    fun toggled(
        context: Context,
        outcome: ToggleOutcome,
        taskId: Int,
        nowMillis: Long,
        use24h: Boolean,
    ) {
        forToggle(outcome, TaskStore.find(context, taskId), nowMillis, use24h)
            ?.let { show(context, it) }
    }

    /** Report what a snooze did to task [taskId]. See [toggled]. */
    fun snoozed(
        context: Context,
        outcome: SnoozeOutcome,
        taskId: Int,
        nowMillis: Long,
        use24h: Boolean,
    ) {
        forSnooze(outcome, TaskStore.find(context, taskId), nowMillis, use24h)
            ?.let { show(context, it) }
    }
}
