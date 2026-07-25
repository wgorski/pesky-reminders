package com.peskyreminders.poc

/**
 * The durations offered when snoozing.
 *
 * Presets cover the common cases; the wheel walks quarter-hours for everything
 * else. Note 5 minutes is deliberately *not* on the wheel — it isn't a multiple
 * of the step — so it survives only as a preset.
 */
object SnoozeOptions {

    val PRESETS = listOf(5, 15, 30, 60)

    const val STEP_MINUTES = 15
    const val MAX_MINUTES = 180

    /** 15, 30, 45 … 180. */
    val WHEEL: List<Int> = (STEP_MINUTES..MAX_MINUTES step STEP_MINUTES).toList()

    const val DEFAULT_MINUTES = 5

    /** "45 min", "1 hr", "1 hr 15", "3 hr". */
    fun label(minutes: Int): String {
        if (minutes < 60) return "$minutes min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "$hours hr" else "$hours hr $rest"
    }

    /** Short form for the preset chips, which have a unit caption of their own. */
    fun chipLabel(minutes: Int): String =
        if (minutes < 60) minutes.toString() else (minutes / 60).toString()

    fun chipUnit(minutes: Int): String = if (minutes < 60) "min" else "hr"
}
