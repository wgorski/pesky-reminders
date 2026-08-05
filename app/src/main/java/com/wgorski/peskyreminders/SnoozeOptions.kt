package com.wgorski.peskyreminders

/**
 * The durations offered when snoozing.
 *
 * Four chips cover the common cases; the wheel covers everything else, out to
 * three days. The sheet commits on the tap, so neither of them holds a
 * selection — see [com.wgorski.peskyreminders.ui.ReminderSheet].
 */
object SnoozeOptions {

    /** The chips, in the order they are laid out: 15 min, 30 min, 1 hr, 3 hr. */
    val PRESETS = listOf(15, 30, 60, 180)

    /**
     * What the notification's Snooze action commits to, without asking.
     *
     * The shortest chip, deliberately. The action fires on one tap and shows no
     * sheet, so it has to be the answer that is hardest to regret — "not now"
     * rather than "not today" — and anything longer is still one tap on the
     * notification body away, where the whole ladder lives.
     *
     * Written out rather than derived from [PRESETS], so reordering the chips
     * cannot silently change what the notification does; a test pins the two
     * together instead. [com.wgorski.peskyreminders.ReminderNotifier] titles the
     * button `"Snooze " + label(QUICK_MINUTES)`, which is what stops the button
     * disagreeing with the chip offering the same duration.
     */
    const val QUICK_MINUTES = 15

    /** The finest step on the wheel, and the granularity every entry is aligned to. */
    const val STEP_MINUTES = 15

    const val MAX_MINUTES = 72 * 60

    private const val HOUR = 60
    private const val DAY = 24 * HOUR

    private data class Band(val step: Int, val upTo: Int)

    /** The one wheel entry that is not a multiple of [STEP_MINUTES]. See [WHEEL]. */
    private const val SHORTEST_MINUTES = 5

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

    /**
     * 5, then 15, 30 … 2 hr, 2 hr 30 … 6 hr, 7 hr … 1 day, 1 day 6 hr … 3 days.
     *
     * Five minutes is the first rung and the sole break in the [STEP_MINUTES]
     * alignment. It used to be a preset chip; the chips are now 15/30/1hr/3hr, so
     * this is the only place left to reach the shortest useful snooze.
     */
    val WHEEL: List<Int> = buildList {
        add(SHORTEST_MINUTES)
        var previous = 0
        for ((step, upTo) in BANDS) {
            for (minutes in (previous + step)..upTo step step) add(minutes)
            previous = upTo
        }
    }

    /**
     * The default argument on `Reminders.snooze` and
     * `ReminderContract.snoozeTriggerAtMillis`. The sheet pre-selects nothing, so
     * nothing in the UI reads this — it is the API's own fallback.
     */
    const val DEFAULT_MINUTES = 5

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

    // ---- the absolute-time ladder --------------------------------------------

    /**
     * The times of day the second chip row can land on, in the order they occur.
     *
     * Morning, afternoon, evening. The 8 here is *not* the same decision as
     * [TaskTime]'s own morning hour, which only governs where `defaultDue` lands
     * once the clock reads 21:00 — they coincide today and are free to diverge,
     * which is why this does not borrow that constant.
     */
    val UNTIL_HOURS = listOf(8, 13, 20)

    /** Chips in the row, matching the four duration chips above it. */
    const val UNTIL_COUNT = 4

    /**
     * Four days of candidates. Three would strictly do — the worst case is a tap
     * just after the evening rung, which spends today entirely and needs
     * tomorrow's three plus the next day's morning — so the fourth day is margin
     * that never reaches the screen.
     */
    private const val UNTIL_DAYS_AHEAD = 3

    /**
     * The next [UNTIL_COUNT] rung times strictly after [nowMillis], ascending.
     *
     * Today is not special-cased: it contributes all three rungs like any other
     * day, so at 06:00 the row opens with today's 08:00.
     *
     * Built with calendar arithmetic, so a rung keeps its wall-clock hour across
     * a DST change — the day containing a spring-forward is 23 hours long, and a
     * fixed-millisecond ladder would drift by an hour from there on.
     */
    fun untilPresets(nowMillis: Long): List<Long> =
        (0..UNTIL_DAYS_AHEAD)
            .flatMap { dayOffset ->
                val day = TaskTime.plusDays(nowMillis, dayOffset)
                UNTIL_HOURS.map { TaskTime.withTimeOfDay(day, it) }
            }
            .filter { it > nowMillis }
            .take(UNTIL_COUNT)
}
