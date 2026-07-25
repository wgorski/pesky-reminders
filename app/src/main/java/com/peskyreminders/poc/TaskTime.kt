package com.peskyreminders.poc

import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A "When?" shortcut in the add sheet: a stable key, a label, and the time it means. */
data class QuickPick(val key: String, val label: String, val whenMillis: Long)

/**
 * Pure date maths and labelling, ported from the design's script block.
 *
 * Everything here takes "now" as a parameter rather than reading the clock, so
 * it is exercisable from plain JVM unit tests. Calendar arithmetic is used in
 * place of the design's fixed-millisecond offsets so day and month steps stay
 * correct across DST boundaries.
 */
object TaskTime {

    private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS =
        arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTHS_FULL = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    const val DAY_MILLIS = 86_400_000L

    /** What the "Tonight" chip means. The only place the hour is written down. */
    private const val TONIGHT_HOUR = 20

    // ---- field access -------------------------------------------------------

    fun yearOf(millis: Long) = cal(millis).get(Calendar.YEAR)
    fun monthOf(millis: Long) = cal(millis).get(Calendar.MONTH)
    fun dayOf(millis: Long) = cal(millis).get(Calendar.DAY_OF_MONTH)
    fun hourOf(millis: Long) = cal(millis).get(Calendar.HOUR_OF_DAY)
    fun minuteOf(millis: Long) = cal(millis).get(Calendar.MINUTE)

    fun startOfDay(millis: Long): Long = cal(millis).atMidnight().timeInMillis

    /** Whole days from [nowMillis]'s calendar day to [millis]'s. Negative is the past. */
    fun dayDiff(millis: Long, nowMillis: Long): Int =
        ((startOfDay(millis) - startOfDay(nowMillis)).toDouble() / DAY_MILLIS).roundToInt()

    // ---- labelling ----------------------------------------------------------

    fun formatTime(millis: Long, use24h: Boolean): String {
        val c = cal(millis)
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        if (use24h) return "${h.toString().padStart(2, '0')}:$m"
        val h12 = (h % 12).let { if (it == 0) 12 else it }
        return "$h12:$m ${if (h < 12) "AM" else "PM"}"
    }

    /** "Today" / "Tomorrow" / "Yesterday" / "Thu" / "Thu 14 Aug". */
    fun formatDay(millis: Long, nowMillis: Long): String {
        val c = cal(millis)
        val weekday = WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
        return when (val k = dayDiff(millis, nowMillis)) {
            0 -> "Today"
            1 -> "Tomorrow"
            -1 -> "Yesterday"
            in 2..6 -> weekday
            else -> "$weekday ${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
        }
    }

    fun formatFull(millis: Long, nowMillis: Long, use24h: Boolean): String =
        "${formatDay(millis, nowMillis)}, ${formatTime(millis, use24h)}"

    /**
     * Like [formatFull] but says no more than it has to: today is just a time,
     * and there is no comma. For labels sitting alongside something else, where
     * "Today, 9:00 PM" is three words too many.
     */
    fun formatCompact(millis: Long, nowMillis: Long, use24h: Boolean): String =
        if (dayDiff(millis, nowMillis) == 0) {
            formatTime(millis, use24h)
        } else {
            "${formatDay(millis, nowMillis)} ${formatTime(millis, use24h)}"
        }

    /** The header stamp, e.g. "FRI 25 JUL". */
    fun todayLabel(nowMillis: Long): String {
        val c = cal(nowMillis)
        val weekday = WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]
        return "$weekday ${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}".uppercase()
    }

    // ---- repeats ------------------------------------------------------------

    /**
     * The first occurrence strictly after [nowMillis], stepping from [dueMillis]
     * by [repeat]. Returns [dueMillis] unchanged for [Repeat.ONCE].
     */
    fun nextOccurrence(dueMillis: Long, repeat: Repeat, nowMillis: Long): Long {
        if (repeat == Repeat.ONCE) return dueMillis
        val c = cal(dueMillis)
        do {
            when (repeat) {
                Repeat.DAILY -> c.add(Calendar.DAY_OF_MONTH, 1)
                Repeat.WEEKLY -> c.add(Calendar.DAY_OF_MONTH, 7)
                Repeat.MONTHLY -> c.add(Calendar.MONTH, 1)
                Repeat.ONCE -> Unit
            }
        } while (c.timeInMillis <= nowMillis)
        return c.timeInMillis
    }

    // ---- picking a time -----------------------------------------------------

    /** Where the sheet's steppers start from when nothing has been chosen yet. */
    fun defaultDue(nowMillis: Long): Long = cal(nowMillis).apply {
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.HOUR_OF_DAY, 3)
    }.timeInMillis

    /** The six "When?" shortcut chips, in the order the design lays them out. */
    fun quickPicks(nowMillis: Long): List<QuickPick> {
        val later = cal(nowMillis).apply {
            add(Calendar.HOUR_OF_DAY, 3)
            val minute = get(Calendar.MINUTE)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, ceil(minute / 15.0).toInt() * 15)
        }.timeInMillis

        // Sunday == 0, matching the design's Date#getDay().
        val weekday = cal(nowMillis).get(Calendar.DAY_OF_WEEK) - 1
        val untilSaturday = ((6 - weekday + 7) % 7).orAWeek()
        val untilMonday = ((8 - weekday) % 7).orAWeek()
        return listOf(
            QuickPick("later", "Later today", later),
            QuickPick("tonight", "Tonight", todayOrTomorrowAt(nowMillis, TONIGHT_HOUR)),
            QuickPick("tom-am", "Tomorrow morning", at(nowMillis, 1, 9)),
            QuickPick("tom-pm", "Tomorrow evening", at(nowMillis, 1, 19)),
            QuickPick("weekend", "This weekend", at(nowMillis, untilSaturday, 10)),
            QuickPick("nextweek", "Next week", at(nowMillis, untilMonday, 9)),
        )
    }

    fun plusDays(millis: Long, days: Int): Long =
        cal(millis).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    fun withDayOffset(millis: Long, nowMillis: Long, dayOffset: Int): Long =
        cal(millis).apply { add(Calendar.DAY_OF_MONTH, dayOffset - dayDiff(millis, nowMillis)) }
            .timeInMillis

    fun withDate(millis: Long, year: Int, month: Int, day: Int): Long =
        cal(millis).apply { set(year, month, day) }.timeInMillis

    fun withHour(millis: Long, hour: Int): Long =
        cal(millis).apply { set(Calendar.HOUR_OF_DAY, hour) }.timeInMillis

    fun withMinute(millis: Long, minute: Int): Long =
        cal(millis).apply { set(Calendar.MINUTE, minute) }.timeInMillis

    /** Snap to a whole hour of the same day — the "Morning 9:00" style chips. */
    fun withTimeOfDay(millis: Long, hour: Int): Long = cal(millis).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun shiftHours(millis: Long, hours: Int): Long =
        cal(millis).apply { add(Calendar.HOUR_OF_DAY, hours) }.timeInMillis

    fun shiftMinutes(millis: Long, minutes: Int): Long =
        cal(millis).apply { add(Calendar.MINUTE, minutes) }.timeInMillis

    // ---- month grid ---------------------------------------------------------

    /** Midnight on the 1st of the month [monthOffset] months from [nowMillis]. */
    fun monthStart(nowMillis: Long, monthOffset: Int): Long = cal(nowMillis).atMidnight().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, monthOffset)
    }.timeInMillis

    fun monthTitle(monthStartMillis: Long): String {
        val c = cal(monthStartMillis)
        return "${MONTHS_FULL[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    fun daysInMonth(monthStartMillis: Long): Int =
        cal(monthStartMillis).getActualMaximum(Calendar.DAY_OF_MONTH)

    /** Blank cells before the 1st, with Sunday as the first column. */
    fun leadingBlanks(monthStartMillis: Long): Int =
        cal(monthStartMillis).get(Calendar.DAY_OF_WEEK) - 1

    // ---- internals ----------------------------------------------------------

    private fun cal(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    private fun Calendar.atMidnight(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Midnight [dayOffset] days from [base], at [hour] o'clock. */
    private fun at(base: Long, dayOffset: Int, hour: Int): Long = cal(base).atMidnight().apply {
        add(Calendar.DAY_OF_MONTH, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
    }.timeInMillis

    /**
     * Today at [hour] while that is still ahead, otherwise tomorrow at [hour].
     *
     * Compares the actual instant rather than the hour-of-day. The version that
     * tested `HOUR_OF_DAY >= 19` for a chip pointing at 20:00 sent "Tonight" to
     * tomorrow for the whole 19:00–20:00 hour, while tonight was still to come.
     * Deriving the roll from the chip's own hour is what stops the two drifting.
     */
    private fun todayOrTomorrowAt(nowMillis: Long, hour: Int): Long {
        val today = at(nowMillis, 0, hour)
        return if (today > nowMillis) today else at(nowMillis, 1, hour)
    }

    /** The design's `|| 7`: "this Saturday" never means today. */
    private fun Int.orAWeek() = if (this == 0) 7 else this
}
