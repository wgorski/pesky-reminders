package com.wgorski.peskyreminders

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The bands the task list groups by, in the order they appear on screen.
 *
 * Every active task falls in exactly one, so the sections come out in
 * chronological order without any extra sorting.
 */
enum class DueGroup(val label: String) {
    OVERDUE("OVERDUE"),
    TODAY("TODAY"),
    TOMORROW("TOMORROW"),
    THIS_WEEK("THIS WEEK"),
    NEXT_WEEK("NEXT WEEK"),
    LATER("LATER"),
}

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

    /** Past this hour, "in an hour" is the middle of the night — see [defaultDue]. */
    private const val LATE_HOUR = 21

    /** Where [defaultDue] lands instead, on the following morning. */
    private const val MORNING_HOUR = 8

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

    /**
     * Whole calendar weeks from [nowMillis]'s week to [millis]'s, counted from
     * whichever day the locale starts its weeks on.
     *
     * Divided and rounded rather than subtracted in days, so the hour that a DST
     * change adds or removes inside a week cannot shift the answer.
     */
    fun weekDiff(millis: Long, nowMillis: Long): Int =
        ((startOfWeek(millis) - startOfWeek(nowMillis)).toDouble() / (7 * DAY_MILLIS)).roundToInt()

    /**
     * Which section of the list a task belongs in.
     *
     * Anything whose moment has passed is [DueGroup.OVERDUE] regardless of the day,
     * so a task due at 09:00 leaves "today" the instant it is late. The two named
     * days are tested before the weeks, which is what stops a Saturday's "tomorrow"
     * — already in next week — from being filed under [DueGroup.NEXT_WEEK].
     */
    fun groupOf(dueMillis: Long, nowMillis: Long): DueGroup = when {
        dueMillis < nowMillis -> DueGroup.OVERDUE
        dayDiff(dueMillis, nowMillis) == 0 -> DueGroup.TODAY
        dayDiff(dueMillis, nowMillis) == 1 -> DueGroup.TOMORROW
        else -> when (weekDiff(dueMillis, nowMillis)) {
            0 -> DueGroup.THIS_WEEK
            1 -> DueGroup.NEXT_WEEK
            else -> DueGroup.LATER
        }
    }

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

    /**
     * What the new-pester sheet opens on: **about an hour from now, on the hour**.
     *
     * Rounded to the *nearest* hour rather than up or down, so the default is never
     * less than half an hour away (which "in an hour" at 14:59 would otherwise be)
     * and never more than an hour and a half.
     *
     * Once the clock reads [LATE_HOUR] the answer stops being useful — an hour from
     * 22:30 is the middle of the night — so it becomes [MORNING_HOUR] tomorrow.
     *
     * This is a real preselection, not just where the steppers start: the sheet is
     * saveable as soon as it has a name.
     */
    fun defaultDue(nowMillis: Long): Long {
        if (hourOf(nowMillis) >= LATE_HOUR) {
            return cal(nowMillis).atMidnight().apply {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, MORNING_HOUR)
            }.timeInMillis
        }
        return cal(nowMillis).apply {
            add(Calendar.HOUR_OF_DAY, 1)
            if (get(Calendar.MINUTE) >= 30) add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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

    /** Snap to a whole hour of the same day — the calendar's time-of-day chips. */
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

    /**
     * Whole calendar months from [nowMillis] to [millis], so the picker can open
     * on the month a task is actually due in rather than on this one. Negative
     * for a task whose date has already gone by.
     *
     * Counts months, not 30-day steps: the 31st of one month and the 1st of the
     * next are one apart, however few hours separate them.
     */
    fun monthOffsetOf(millis: Long, nowMillis: Long): Int {
        val then = cal(millis)
        val now = cal(nowMillis)
        return (then.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 12 +
            (then.get(Calendar.MONTH) - now.get(Calendar.MONTH))
    }

    fun monthTitle(monthStartMillis: Long): String {
        val c = cal(monthStartMillis)
        return "${MONTHS_FULL[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
    }

    fun daysInMonth(monthStartMillis: Long): Int =
        cal(monthStartMillis).getActualMaximum(Calendar.DAY_OF_MONTH)

    /**
     * Blank cells before the 1st, counted from the locale's first day of week —
     * the same day [startOfWeek] cuts on, so the grid and the list's THIS WEEK /
     * NEXT WEEK bands agree about where a week begins.
     *
     * Field arithmetic rather than a subtraction of millis: a week containing a
     * DST change is 167 or 169 hours long, and dividing that by [DAY_MILLIS]
     * could put the 1st in the wrong column.
     *
     * The returned count *is* the column index [monthStartMillis] falls in — so
     * this also answers "which column is this day in" for any date, not only a
     * month's 1st, though the name and the rest of this doc talk only about the
     * grid's leading blanks.
     */
    fun leadingBlanks(monthStartMillis: Long): Int = cal(monthStartMillis).columnInWeek()

    /**
     * The grid's seven column headers, rotated so the first is the locale's
     * first day of week.
     *
     * This is the header's only source, which is what stops the letters
     * disagreeing with [leadingBlanks] about which day leads — the same
     * reasoning that has the snooze chip labels come from here rather than
     * being written out at the call site. Shares [WEEKDAYS] with [formatDay].
     *
     * English initials, merely rotated: [WEEKDAYS] is English and localising
     * day names is a separate job.
     */
    fun weekdayInitials(): List<String> {
        val first = Calendar.getInstance().firstDayOfWeek - 1
        return List(7) { WEEKDAYS[(first + it) % 7].take(1) }
    }

    // ---- internals ----------------------------------------------------------

    private fun cal(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    /**
     * Midnight on the first day of [millis]'s week.
     *
     * Takes the first day from the locale via [Calendar.getFirstDayOfWeek] — Sunday
     * in the US, Monday across most of Europe — because "this week" is a claim about
     * the user's calendar, not ours. The month grid asks the same question through
     * [leadingBlanks] and [weekdayInitials], so the two cannot disagree.
     */
    private fun startOfWeek(millis: Long): Long = cal(millis).atMidnight().apply {
        add(Calendar.DAY_OF_MONTH, -columnInWeek())
    }.timeInMillis

    /** How many columns [this] sits past the locale's first day of the week. */
    private fun Calendar.columnInWeek(): Int = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7

    private fun Calendar.atMidnight(): Calendar = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

}
