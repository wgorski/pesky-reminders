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
    const val ACTION_REPOST = "com.wgorski.peskyreminders.REPOST"
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
    const val SLOT_REPOST = 2
    const val SLOT_SNOOZE = 3
    const val SLOT_DONE = 4
    const val SLOT_NAG = 5

    /**
     * PendingIntent request codes must differ per task *and* per action, or the
     * intents collapse onto one another. Task ids start at 1, so nothing here
     * collides with [SHOW_REQUEST_CODE].
     */
    fun requestCode(taskId: Int, slot: Int): Int = taskId * SLOT_SPAN + slot

    const val SHOW_REQUEST_CODE = 0

    private const val SLOT_SPAN = 8
}
