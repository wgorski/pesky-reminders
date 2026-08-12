package com.wgorski.peskyreminders

/** Shared constants and pure scheduling math for the reminder model. */
object ReminderContract {
    /**
     * A channel's settings are **frozen at creation**, so every change to how it
     * alerts needs a new id or no existing install ever picks it up.
     *
     * - `pesky_reminders` → `_v2` when vibration was added.
     * - `_v2` → `_v3` when the *channel* took over the arrival buzz from the app.
     *
     * [ReminderNotifier] deletes every older id, so the list is the migration.
     */
    const val CHANNEL_ID = "pesky_reminders_v3"
    val LEGACY_CHANNEL_IDS = listOf("pesky_reminders", "pesky_reminders_v2")

    /**
     * The sound-free twin of [CHANNEL_ID], for every post after the first.
     *
     * A channel is the only place a notification's sound can be turned off on
     * API 26+, and it cannot be overridden per post. The alternative —
     * `NotificationCompat.setSilent(true)` — works by moving the notification
     * into a group keyed "silent", which drops it out of the app's own group in
     * the shade; verified in dumpsys as `groupKey=silent` against the normal
     * `ranker_group`. A swiped reminder would visibly jump out of the stack. A
     * second channel costs one extra row in the system notification settings and
     * changes nothing else.
     */
    const val QUIET_CHANNEL_ID = "pesky_reminders_quiet"

    const val ACTION_FIRE = "com.wgorski.peskyreminders.FIRE"
    const val ACTION_DONE = "com.wgorski.peskyreminders.DONE"

    /**
     * The notification's one-tap snooze, for [SnoozeOptions.QUICK_MINUTES].
     *
     * A broadcast like [ACTION_DONE], not an activity, because it shows nothing —
     * the sheet of durations is a separate route and still has to be an activity.
     */
    const val ACTION_SNOOZE = "com.wgorski.peskyreminders.SNOOZE"

    /**
     * The notification's delete-intent — what the OS sends when it is swiped away.
     *
     * It **snoozes** now, for [Settings.swipeSnoozeMinutes], rather than putting the
     * notification straight back. Named for the gesture rather than the response,
     * because it was `REPOST` for as long as re-posting was the response and a
     * broadcast whose name contradicts its branch is the kind of lie that survives
     * for years. [Reminders.repost] itself is unchanged and still has two callers;
     * it is only unreachable *by broadcast*.
     *
     * The rename costs one narrow window: a notification posted before an app
     * update holds a PendingIntent carrying the old action string, so a swipe in
     * that window clears the notification and matches no branch, losing the snooze.
     * [BootReceiver] handles `MY_PACKAGE_REPLACED` and [Reminders.restoreAll]
     * re-posts every overdue task immediately, which replaces the notification and
     * its delete-intent — so the window is the seconds between install and that
     * broadcast, and no compatibility branch is kept for it.
     */
    const val ACTION_SWIPED = "com.wgorski.peskyreminders.SWIPED"
    const val ACTION_NAG = "com.wgorski.peskyreminders.NAG"

    const val EXTRA_TASK_ID = "extra_task_id"

    /** How often an ignored notification buzzes again. */
    const val NAG_MILLIS = 5 * 60 * 1000L

    fun triggerAtMillis(nowMillis: Long, offsetMillis: Long): Long = nowMillis + offsetMillis

    fun snoozeTriggerAtMillis(
        nowMillis: Long,
        minutes: Int = SnoozeOptions.DEFAULT_MINUTES,
    ): Long = nowMillis + minutes * 60_000L

    fun nagTriggerAtMillis(nowMillis: Long): Long = nowMillis + NAG_MILLIS

    /** Every task owns its own notification slot, so several can nag at once. */
    fun notificationId(taskId: Int): Int = taskId

    const val SLOT_FIRE = 1

    /** Value unchanged from when it was `SLOT_REPOST`, so no request code moves. */
    const val SLOT_SWIPED = 2
    const val SLOT_SNOOZE = 3
    const val SLOT_DONE = 4
    const val SLOT_NAG = 5

    /**
     * The tap on the notification's body, which opens the snooze sheet.
     *
     * It had no slot of its own while it *was* [SLOT_SNOOZE]'s PendingIntent —
     * one intent served the body and the Snooze action both. The action is a
     * broadcast now, so they are different intents and need different request
     * codes, or [requestCode] collapses them onto one another.
     */
    const val SLOT_OPEN = 6

    /**
     * PendingIntent request codes must differ per task *and* per action, or the
     * intents collapse onto one another. Task ids start at 1, so nothing here
     * collides with [SHOW_REQUEST_CODE].
     */
    fun requestCode(taskId: Int, slot: Int): Int = taskId * SLOT_SPAN + slot

    const val SHOW_REQUEST_CODE = 0

    private const val SLOT_SPAN = 8
}
