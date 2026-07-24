package com.peskyreminders.poc

/** Shared constants and pure scheduling math for the reminder model. */
object ReminderContract {
    const val CHANNEL_ID = "pesky_reminders"
    const val NOTIFICATION_ID = 1001
    const val REQUEST_CODE = 2001

    const val ACTION_FIRE = "com.peskyreminders.poc.FIRE"
    const val ACTION_SNOOZE = "com.peskyreminders.poc.SNOOZE"
    const val ACTION_DONE = "com.peskyreminders.poc.DONE"
    const val ACTION_REPOST = "com.peskyreminders.poc.REPOST"

    const val EXTRA_TEXT = "extra_text"

    const val SNOOZE_MILLIS = 5 * 60 * 1000L

    fun triggerAtMillis(nowMillis: Long, offsetMillis: Long): Long = nowMillis + offsetMillis

    fun snoozeTriggerAtMillis(nowMillis: Long): Long = nowMillis + SNOOZE_MILLIS
}
