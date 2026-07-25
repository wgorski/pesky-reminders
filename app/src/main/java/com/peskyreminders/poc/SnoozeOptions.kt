package com.peskyreminders.poc

/**
 * The durations offered when snoozing or rescheduling.
 *
 * Presets cover the common cases; the wheel covers everything else, out to three
 * days. Note 5 minutes is deliberately *not* on the wheel — it isn't a multiple
 * of the finest step — so it survives only as a preset.
 */
object SnoozeOptions {

    val PRESETS = listOf(5, 15, 30, 60)

    /** The finest step on the wheel, and the granularity every entry is aligned to. */
    const val STEP_MINUTES = 15

    const val MAX_MINUTES = 72 * 60

    private const val HOUR = 60
    private const val DAY = 24 * HOUR

    private data class Band(val step: Int, val upTo: Int)

    /**
     * The wheel coarsens as it goes.
     *
     * Reaching 72 hours in quarter-hour steps would be 288 entries to scroll past,
     * and quarter-hour precision is meaningless three days out — a reminder you
     * pushed to "the day after tomorrow" does not care about 15 minutes. Every
     * step stays a multiple of [STEP_MINUTES], so the boundaries land on times
     * that read cleanly.
     */
    private val BANDS = listOf(
        Band(step = STEP_MINUTES, upTo = 2 * HOUR),   // quarter hours, to 2 hr
        Band(step = 30, upTo = 6 * HOUR),             // half hours, to 6 hr
        Band(step = HOUR, upTo = DAY),                // whole hours, to 24 hr
        Band(step = 6 * HOUR, upTo = 3 * DAY),        // six-hour jumps, to 72 hr
    )

    /** 15, 30 … 2 hr, 2 hr 30 … 6 hr, 7 hr … 1 day, 1 day 6 hr … 3 days. */
    val WHEEL: List<Int> = buildList {
        var previous = 0
        for ((step, upTo) in BANDS) {
            for (minutes in (previous + step)..upTo step step) add(minutes)
            previous = upTo
        }
    }

    const val DEFAULT_MINUTES = 5

    /**
     * Past this, a duration stops being something you can picture, so the wheel
     * shows the clock time it lands on next to it — see [landsAtAClockTime].
     */
    const val CLOCK_TIME_ABOVE_MINUTES = 3 * HOUR

    /** Whether [minutes] is long enough to be worth spelling out as a time. */
    fun landsAtAClockTime(minutes: Int): Boolean = minutes > CLOCK_TIME_ABOVE_MINUTES

    /** "45 min", "1h", "1h 15", "3h", "24h", "72h". */
    fun label(minutes: Int): String {
        if (minutes < HOUR) return "$minutes min"
        val hours = minutes / HOUR
        val rest = minutes % HOUR
        return if (rest == 0) "${hours}h" else "${hours}h $rest"
    }

    /** Short form for the preset chips, which have a unit caption of their own. */
    fun chipLabel(minutes: Int): String =
        if (minutes < HOUR) minutes.toString() else (minutes / HOUR).toString()

    fun chipUnit(minutes: Int): String = if (minutes < HOUR) "min" else "hr"
}
