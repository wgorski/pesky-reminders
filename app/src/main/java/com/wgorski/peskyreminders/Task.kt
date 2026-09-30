package com.wgorski.peskyreminders

/** The step a repeating task counts in. */
enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

/**
 * How often a task comes back after you tick it off.
 *
 * [ONCE], [DAILY], [WEEKLY] and [MONTHLY] are the task sheet's four presets, and
 * they are *loose*: [WEEKLY] names no weekday and [MONTHLY] no day of the month,
 * because a preset follows whatever date the task is on. [bindTo] pins those
 * fields to a date, and that bound form is what the store keeps — a monthly task
 * on the 31st remembers the 31st instead of drifting to the 28th after February.
 *
 * Anything else is a custom rule: every [every] [unit]s, on the [weekdays] of a
 * week, or on a [monthDay] / the [ordinal] [weekday] of a month. The date-side
 * maths lives in [Recurrence]; this is only the shape.
 */
data class Repeat(
    /** Null is "once" — no repeat at all. */
    val unit: RepeatUnit? = null,
    val every: Int = 1,
    /** `Calendar.SUNDAY..SATURDAY`. Weeks only; empty means the date's own weekday. */
    val weekdays: Set<Int> = emptySet(),
    /** 1..31 or [LAST]. Months by date only; 29–31 fall back to a shorter month's last day. */
    val monthDay: Int? = null,
    /** 1..4 or [LAST]. Months by weekday only, with [weekday]. */
    val ordinal: Int? = null,
    /** `Calendar.SUNDAY..SATURDAY`. Months by weekday only, with [ordinal]. */
    val weekday: Int? = null,
) {
    val repeats: Boolean get() = unit != null

    /**
     * The preset's chip label, or "Custom" for anything that is not one of the four
     * loose presets. Also the tag suffix of the sheet's chips (`repeat-Weekly`).
     */
    val label: String
        get() = when (this) {
            ONCE -> "Once"
            DAILY -> "Daily"
            WEEKLY -> "Weekly"
            MONTHLY -> "Monthly"
            else -> CUSTOM_LABEL
        }

    /**
     * Flattened to six ints, for `rememberSaveable`. [LAST] is -1 and null is 0,
     * which cannot collide: no field has 0 as a legal value.
     */
    fun toInts(): List<Int> = listOf(
        unit?.ordinal ?: -1,
        every,
        weekdays.fold(0) { mask, day -> mask or (1 shl day) },
        monthDay ?: 0,
        ordinal ?: 0,
        weekday ?: 0,
    )

    companion object {
        /** The last day of the month, or the last such weekday in it. */
        const val LAST = -1
        const val CUSTOM_LABEL = "Custom"

        val ONCE = Repeat()
        val DAILY = Repeat(RepeatUnit.DAY)
        val WEEKLY = Repeat(RepeatUnit.WEEK)
        val MONTHLY = Repeat(RepeatUnit.MONTH)

        /** The sheet's chips, in order. Custom is drawn after them. */
        val PRESETS = listOf(ONCE, DAILY, WEEKLY, MONTHLY)

        fun fromLabel(label: String?): Repeat = PRESETS.firstOrNull { it.label == label } ?: ONCE

        fun fromInts(ints: List<Int>): Repeat = Repeat(
            unit = ints[0].takeIf { it >= 0 }?.let { RepeatUnit.entries[it] },
            every = ints[1],
            weekdays = (1..7).filter { ints[2] and (1 shl it) != 0 }.toSet(),
            monthDay = ints[3].takeIf { it != 0 },
            ordinal = ints[4].takeIf { it != 0 },
            weekday = ints[5].takeIf { it != 0 },
        )
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
    val repeats: Boolean get() = repeat.repeats

    /**
     * The moment the repeat cycle counts from, and the one that decides whether the
     * task is "due yet" — the slot itself, never a snooze of it.
     */
    val slotMillis: Long get() = anchorMillis ?: dueMillis
}
