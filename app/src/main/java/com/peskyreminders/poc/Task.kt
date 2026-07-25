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
    val dueMillis: Long,
    val repeat: Repeat = Repeat.ONCE,
    val done: Boolean = false,
) {
    val repeats: Boolean get() = repeat != Repeat.ONCE
}
