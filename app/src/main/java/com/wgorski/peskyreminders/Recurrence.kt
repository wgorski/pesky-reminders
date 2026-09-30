package com.wgorski.peskyreminders

import java.util.Calendar

/**
 * Pure maths and wording for [Repeat] rules — when the next one is, which dates a
 * rule allows, and how to say it.
 *
 * Same contract as [TaskTime]: "now" is always a parameter, and every step is
 * `Calendar` arithmetic, so a daily 09:00 stays at 09:00 across a DST change.
 * Weeks are cut where [TaskTime.weekdayOrder] says they start, which is what keeps
 * "every 2 weeks on Sat, Mon" agreeing with the list's week bands and the grid.
 */
object Recurrence {

    /**
     * Pin a loose preset's date-derived fields to [millis]: [Repeat.WEEKLY] gains
     * that date's weekday, [Repeat.MONTHLY] its day of the month. A rule that
     * already names its days comes back unchanged, so this is safe to repeat.
     */
    fun bindTo(repeat: Repeat, millis: Long): Repeat = when (repeat.unit) {
        RepeatUnit.WEEK ->
            if (repeat.weekdays.isEmpty()) repeat.copy(weekdays = setOf(TaskTime.dayOfWeekOf(millis)))
            else repeat

        RepeatUnit.MONTH -> when {
            repeat.ordinal != null && repeat.weekday == null ->
                repeat.copy(weekday = TaskTime.dayOfWeekOf(millis))
            repeat.ordinal == null && repeat.monthDay == null ->
                repeat.copy(monthDay = TaskTime.dayOf(millis))
            else -> repeat
        }

        else -> repeat
    }

    /**
     * The chip a rule shows as on [millis]: one of the loose presets if it means
     * the same thing there — every 1 week on only that date's weekday *is*
     * Weekly — or else the rule bound to the date. Keeps "Custom" meaning
     * "something the presets cannot say".
     */
    fun normalise(repeat: Repeat, millis: Long): Repeat {
        if (!repeat.repeats) return Repeat.ONCE
        val bound = bindTo(repeat, millis)
        return Repeat.PRESETS.firstOrNull { bindTo(it, millis) == bound } ?: bound
    }

    /** A rule the presets cannot express — the ones that constrain the date. */
    fun isCustom(repeat: Repeat): Boolean = repeat.repeats && repeat !in Repeat.PRESETS

    /** Whether [millis]'s date is one the rule fires on. Loose presets allow any. */
    fun matches(repeat: Repeat, millis: Long): Boolean {
        val c = cal(millis)
        return when (repeat.unit) {
            RepeatUnit.WEEK ->
                repeat.weekdays.isEmpty() || c.get(Calendar.DAY_OF_WEEK) in repeat.weekdays
            RepeatUnit.MONTH -> {
                val target = targetDay(c, repeat) ?: return true
                c.get(Calendar.DAY_OF_MONTH) == target
            }
            else -> true
        }
    }

    /**
     * The first moment on or after [millis] whose date the rule allows, at the same
     * time of day. What the task sheet snaps its date to under a custom rule, so the
     * first reminder lands on a day the rule actually names.
     */
    fun firstMatchOnOrAfter(millis: Long, repeat: Repeat): Long {
        val c = cal(millis)
        // A year and a day covers every rule there is: the sparsest, the last
        // Friday of a month, recurs within five weeks.
        for (day in 0..366) {
            if (matches(repeat, c.timeInMillis)) return c.timeInMillis
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return millis
    }

    /**
     * The first occurrence strictly after [nowMillis], stepping from [slotMillis]
     * by [repeat]. Returns [slotMillis] unchanged for [Repeat.ONCE].
     *
     * Stepping from the slot needs no separate series start, because every slot is
     * an occurrence: the sheet snaps the first one onto the rule, and each step
     * lands on the next.
     */
    fun nextOccurrence(slotMillis: Long, repeat: Repeat, nowMillis: Long): Long {
        if (!repeat.repeats) return slotMillis
        val rule = bindTo(repeat, slotMillis)
        val c = cal(slotMillis)
        do step(c, rule) while (c.timeInMillis <= nowMillis)
        return c.timeInMillis
    }

    // ---- wording --------------------------------------------------------------

    /**
     * The whole rule as a sentence — the task sheet's Repeat line and the custom
     * sheet's readout. "Every 2 weeks on Mon, Wed", "Monthly on the last Friday".
     */
    fun describe(repeat: Repeat, millis: Long): String {
        val r = bindTo(repeat, millis)
        val n = r.every
        return when (r.unit) {
            null -> "Once"
            RepeatUnit.DAY -> if (n == 1) "Daily" else "Every $n days"
            RepeatUnit.WEEK -> (if (n == 1) "Weekly" else "Every $n weeks") + " on " + days(r.weekdays)
            RepeatUnit.MONTH -> (if (n == 1) "Monthly" else "Every $n months") + " on the " +
                monthPlace(r, TaskTime::weekdayLong)
            RepeatUnit.YEAR -> if (n == 1) "Yearly" else "Every $n years"
        }
    }

    /**
     * What the list row's pill says: the preset's name, or a custom rule cut down
     * to what fits beside a due time — "Mon–Fri", "Monthly · 15th", "Every 2 weeks".
     */
    fun shortLabel(repeat: Repeat, millis: Long): String {
        val normal = normalise(repeat, millis)
        if (normal in Repeat.PRESETS) return normal.label
        val n = normal.every
        return when (normal.unit) {
            null -> Repeat.ONCE.label
            RepeatUnit.DAY -> "Every $n days"
            RepeatUnit.WEEK -> if (n == 1) days(normal.weekdays) else "Every $n weeks"
            RepeatUnit.MONTH ->
                if (n == 1) "Monthly · " + monthPlace(normal, TaskTime::weekdayShort)
                else "Every $n months"
            RepeatUnit.YEAR -> if (n == 1) "Yearly" else "Every $n years"
        }
    }

    /** "1st", "2nd", "3rd", "11th", "22nd". */
    fun ordinalSuffix(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    /**
     * Weekdays in locale order, with runs of three or more collapsed: "Mon, Wed",
     * "Mon–Fri". Mon–Fri is the commonest custom rule there is, and spelt out it
     * would not fit in a list row's pill.
     */
    private fun days(weekdays: Set<Int>): String {
        val order = TaskTime.weekdayOrder()
        val cols = weekdays.map { order.indexOf(it) }.sorted()
        val runs = mutableListOf<MutableList<Int>>()
        cols.forEach { col ->
            if (runs.isNotEmpty() && runs.last().last() == col - 1) runs.last().add(col)
            else runs.add(mutableListOf(col))
        }
        return runs.flatMap { run ->
            if (run.size >= 3) {
                listOf(TaskTime.weekdayShort(order[run.first()]) + "–" + TaskTime.weekdayShort(order[run.last()]))
            } else {
                run.map { TaskTime.weekdayShort(order[it]) }
            }
        }.joinToString(", ")
    }

    /** "15th", "last day", "2nd Tuesday", "last Friday" — which day of the month. */
    private fun monthPlace(r: Repeat, weekdayName: (Int) -> String): String {
        val ordinal = r.ordinal
        val weekday = r.weekday
        return if (ordinal != null && weekday != null) {
            (if (ordinal == Repeat.LAST) "last" else ordinalSuffix(ordinal)) + " " + weekdayName(weekday)
        } else {
            val day = r.monthDay ?: 1
            if (day == Repeat.LAST) "last day" else ordinalSuffix(day)
        }
    }

    // ---- stepping -------------------------------------------------------------

    private fun step(c: Calendar, r: Repeat) {
        when (r.unit) {
            RepeatUnit.DAY -> c.add(Calendar.DAY_OF_MONTH, r.every)
            RepeatUnit.WEEK -> stepWeek(c, r)
            RepeatUnit.MONTH -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, r.every)
                c.set(Calendar.DAY_OF_MONTH, targetDay(c, r) ?: 1)
            }
            RepeatUnit.YEAR -> c.add(Calendar.YEAR, r.every)
            null -> Unit
        }
    }

    /**
     * The next ticked weekday later in the same week; failing that, the first ticked
     * day of the week [Repeat.every] weeks on. Day steps via `Calendar.add` keep the
     * wall-clock time across DST.
     */
    private fun stepWeek(c: Calendar, r: Repeat) {
        val order = TaskTime.weekdayOrder()
        val col = order.indexOf(c.get(Calendar.DAY_OF_WEEK))
        val ticked = r.weekdays.map { order.indexOf(it) }.sorted()
        val later = ticked.firstOrNull { it > col }
        c.add(
            Calendar.DAY_OF_MONTH,
            if (later != null) later - col else 7 * r.every - col + ticked.first(),
        )
    }

    /**
     * The day of [c]'s month the rule lands on, or null when the rule does not pin
     * one (not a month rule, or a loose [Repeat.MONTHLY]).
     */
    private fun targetDay(c: Calendar, r: Repeat): Int? {
        val last = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val ordinal = r.ordinal
        val weekday = r.weekday
        if (ordinal != null && weekday != null) {
            val probe = c.clone() as Calendar
            return if (ordinal == Repeat.LAST) {
                probe.set(Calendar.DAY_OF_MONTH, last)
                last - (probe.get(Calendar.DAY_OF_WEEK) - weekday + 7) % 7
            } else {
                probe.set(Calendar.DAY_OF_MONTH, 1)
                1 + (weekday - probe.get(Calendar.DAY_OF_WEEK) + 7) % 7 + 7 * (ordinal - 1)
            }
        }
        val day = r.monthDay ?: return null
        return if (day == Repeat.LAST) last else minOf(day, last)
    }

    private fun cal(millis: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }
}
