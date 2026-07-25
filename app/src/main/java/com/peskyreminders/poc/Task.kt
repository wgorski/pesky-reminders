package com.peskyreminders.poc

/** How often a task comes back after you tick it off. */
enum class Repeat(val label: String) {
    ONCE("Once"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly");

    companion object {
        fun fromLabel(label: String?): Repeat =
            entries.firstOrNull { it.label == label } ?: ONCE
    }
}

/**
 * One thing to be pestered about.
 *
 * [id] doubles as the notification id and the base for the alarm request codes,
 * so it must be stable and unique for the lifetime of the task.
 */
data class Task(
    val id: Int,
    val name: String,
    /** When it fires. A snooze moves this; everything on screen reads it. */
    val dueMillis: Long,
    val repeat: Repeat = Repeat.ONCE,
    val done: Boolean = false,
    /**
     * The recurring slot a snoozed repeater came from, or null when [dueMillis] is
     * itself the slot.
     *
     * Snoozing a daily 9am task at 9:05 has to buzz again at 9:35 *without* moving
     * the task to 9:35 every day after. So the snooze moves [dueMillis] and parks
     * the original 9am here; the next occurrence is then counted from this, and it
     * is cleared once the cycle turns over. Only repeating tasks ever set it — for
     * a one-off there is no cycle to protect.
     */
    val anchorMillis: Long? = null,
) {
    val repeats: Boolean get() = repeat != Repeat.ONCE

    /**
     * The moment the repeat cycle counts from, and the one that decides whether the
     * task is "due yet" — the slot itself, never a snooze of it.
     */
    val slotMillis: Long get() = anchorMillis ?: dueMillis
}
