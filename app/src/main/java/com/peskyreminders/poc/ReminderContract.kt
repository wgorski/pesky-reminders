package com.peskyreminders.poc

/** Shared constants and pure scheduling math for the reminder model. */
object ReminderContract {
    const val CHANNEL_ID = "pesky_reminders"

    const val ACTION_FIRE = "com.peskyreminders.poc.FIRE"
    const val ACTION_SNOOZE = "com.peskyreminders.poc.SNOOZE"
    const val ACTION_DONE = "com.peskyreminders.poc.DONE"
    const val ACTION_REPOST = "com.peskyreminders.poc.REPOST"

    const val EXTRA_TASK_ID = "extra_task_id"

    const val SNOOZE_MILLIS = 5 * 60 * 1000L

    fun triggerAtMillis(nowMillis: Long, offsetMillis: Long): Long = nowMillis + offsetMillis

    fun snoozeTriggerAtMillis(nowMillis: Long): Long = nowMillis + SNOOZE_MILLIS

    /** Every task owns its own notification slot, so several can nag at once. */
    fun notificationId(taskId: Int): Int = taskId

    const val SLOT_FIRE = 1
    const val SLOT_REPOST = 2
    const val SLOT_SNOOZE = 3
    const val SLOT_DONE = 4

    /**
     * PendingIntent request codes must differ per task *and* per action, or the
     * intents collapse onto one another. Task ids start at 1, so nothing here
     * collides with [SHOW_REQUEST_CODE].
     */
    fun requestCode(taskId: Int, slot: Int): Int = taskId * SLOT_SPAN + slot

    const val SHOW_REQUEST_CODE = 0

    private const val SLOT_SPAN = 8
}
