package com.wgorski.peskyreminders

/** Shared constants and pure scheduling math for the reminder model. */
object ReminderContract {
    /**
     * Bumped from "pesky_reminders" when vibration was added: a channel's
     * settings are frozen at creation, so an existing install would never have
     * started buzzing. [ReminderNotifier] deletes the old one.
     */
    const val CHANNEL_ID = "pesky_reminders_v2"
    const val LEGACY_CHANNEL_ID = "pesky_reminders"

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
