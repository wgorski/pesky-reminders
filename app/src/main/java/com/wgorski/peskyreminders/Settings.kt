package com.wgorski.peskyreminders

import android.content.Context
import java.util.Calendar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User preferences, persisted in SharedPreferences.
 *
 * Read from the broadcast receiver as well as the UI, so it hydrates lazily the
 * same way [TaskStore] does.
 */
object Settings {

    private const val PREFS = "pesky_settings"
    private const val KEY_NAG_ENABLED = "nag_enabled"
    private const val KEY_NAG_MINUTES = "nag_minutes"
    private const val KEY_SWIPE_SNOOZE_MINUTES = "swipe_snooze_minutes"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start_minute"
    private const val KEY_QUIET_END = "quiet_end_minute"

    const val DEFAULT_NAG_MINUTES = 5
    const val MIN_NAG_MINUTES = 1
    const val MAX_NAG_MINUTES = 180

    /**
     * The intervals the sheet offers as chips, before the slider takes over.
     *
     * Its own list rather than one shared with [SWIPE_SNOOZE_PRESET_MINUTES], for
     * the same reason the bounds below are its own: the two answer different
     * questions and are free to diverge. They coincide today.
     */
    val NAG_PRESET_MINUTES = listOf(5, 15, 30)

    /**
     * How long a swipe hides a reminder for.
     *
     * Its own bounds rather than the nag's, even though all six numbers coincide
     * today: "how long to hide a reminder I just pushed away" and "how often to
     * buzz one I am ignoring" are different questions and are free to diverge.
     * Same call [SnoozeOptions.UNTIL_HOURS] makes in not borrowing [TaskTime]'s
     * morning hour.
     *
     * One minute is the closest thing left to the old put-it-straight-back
     * behaviour and is harmless. Three hours is where a swipe stops meaning "not
     * now" and starts meaning "not today" — longer answers are what the sheet's
     * ladder out to 72 hours is for, and a gesture that can hide a reminder for a
     * working day is a dismissal wearing a disguise.
     */
    const val DEFAULT_SWIPE_SNOOZE_MINUTES = 5
    const val MIN_SWIPE_SNOOZE_MINUTES = 1
    const val MAX_SWIPE_SNOOZE_MINUTES = 180

    /** As [NAG_PRESET_MINUTES], for the other question. */
    val SWIPE_SNOOZE_PRESET_MINUTES = listOf(5, 15, 30)

    /**
     * The night window in which nothing sounds or buzzes, as minutes since
     * midnight. 23:00 to 07:00 until someone changes it, and on by default: a
     * reminder app that wakes people at 3am does not stay installed.
     *
     * Settable only in [QUIET_STEP_MINUTES] steps, and a window wraps past
     * midnight whenever its start is later than its end — see [isQuietAt].
     */
    const val DEFAULT_QUIET_START = 23 * 60
    const val DEFAULT_QUIET_END = 7 * 60
    const val QUIET_STEP_MINUTES = 30
    private const val MINUTES_PER_DAY = 24 * 60

    /** Whether an ignored notification keeps buzzing. */
    var nagEnabled by mutableStateOf(true)
        private set

    /** How many minutes between buzzes. */
    var nagMinutes by mutableIntStateOf(DEFAULT_NAG_MINUTES)
        private set

    /**
     * How many minutes a swipe pushes a reminder back by.
     *
     * Read from [ReminderReceiver], which may be running in a process this
     * broadcast started, so that caller has to [hydrate] before reading it — the
     * same shape [Reminders.nag] uses for [nagEnabled].
     */
    var swipeSnoozeMinutes by mutableIntStateOf(DEFAULT_SWIPE_SNOOZE_MINUTES)
        private set

    /** Whether the night window applies at all. */
    var quietEnabled by mutableStateOf(true)
        private set

    /** When the night window opens, in minutes since midnight. */
    var quietStartMinute by mutableIntStateOf(DEFAULT_QUIET_START)
        private set

    /** When the night window closes, in minutes since midnight. */
    var quietEndMinute by mutableIntStateOf(DEFAULT_QUIET_END)
        private set

    private var hydrated = false

    @Synchronized
    fun hydrate(context: Context) {
        if (hydrated) return
        hydrated = true
        val prefs = prefs(context)
        nagEnabled = prefs.getBoolean(KEY_NAG_ENABLED, true)
        nagMinutes = coerceMinutes(prefs.getInt(KEY_NAG_MINUTES, DEFAULT_NAG_MINUTES))
        swipeSnoozeMinutes = coerceSwipeSnoozeMinutes(
            prefs.getInt(KEY_SWIPE_SNOOZE_MINUTES, DEFAULT_SWIPE_SNOOZE_MINUTES)
        )
        quietEnabled = prefs.getBoolean(KEY_QUIET_ENABLED, true)
        quietStartMinute = coerceQuietMinute(prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START))
        quietEndMinute = coerceQuietMinute(prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END))
    }

    fun setNagEnabled(context: Context, enabled: Boolean) {
        hydrate(context)
        nagEnabled = enabled
        prefs(context).edit().putBoolean(KEY_NAG_ENABLED, enabled).apply()
    }

    /** Stores [minutes] clamped into the supported range. */
    fun setNagMinutes(context: Context, minutes: Int) {
        hydrate(context)
        val safe = coerceMinutes(minutes)
        nagMinutes = safe
        prefs(context).edit().putInt(KEY_NAG_MINUTES, safe).apply()
    }

    /** Stores [minutes] clamped into the supported range. */
    fun setSwipeSnoozeMinutes(context: Context, minutes: Int) {
        hydrate(context)
        val safe = coerceSwipeSnoozeMinutes(minutes)
        swipeSnoozeMinutes = safe
        prefs(context).edit().putInt(KEY_SWIPE_SNOOZE_MINUTES, safe).apply()
    }

    fun setQuietEnabled(context: Context, enabled: Boolean) {
        hydrate(context)
        quietEnabled = enabled
        prefs(context).edit().putBoolean(KEY_QUIET_ENABLED, enabled).apply()
    }

    /** Stores the window's opening time, snapped onto the half-hour grid. */
    fun setQuietStartMinute(context: Context, minute: Int) {
        hydrate(context)
        val safe = coerceQuietMinute(minute)
        quietStartMinute = safe
        prefs(context).edit().putInt(KEY_QUIET_START, safe).apply()
    }

    /** Stores the window's closing time, snapped onto the half-hour grid. */
    fun setQuietEndMinute(context: Context, minute: Int) {
        hydrate(context)
        val safe = coerceQuietMinute(minute)
        quietEndMinute = safe
        prefs(context).edit().putInt(KEY_QUIET_END, safe).apply()
    }

    /**
     * Is [minuteOfDay] inside the window from [start] (inclusive) to [end]
     * (exclusive)? Pure, so the edges are unit-tested exactly.
     *
     * A start later than the end wraps past midnight, which is the ordinary case —
     * 23:00 to 07:00. A start equal to the end is an empty window rather than a
     * whole day: the sheet's steppers never produce one, and if a stored value
     * ever did, "never quiet" is the failure that keeps the app doing its job.
     */
    fun isQuietAt(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start < end -> minuteOfDay in start until end
        start > end -> minuteOfDay >= start || minuteOfDay < end
        else -> false
    }

    /**
     * Should a post at [nowMillis] keep quiet? Hydrates first, because the alarm
     * that asks may be what started the process.
     */
    fun isQuietNow(context: Context, nowMillis: Long): Boolean {
        hydrate(context)
        if (!quietEnabled) return false
        val c = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return isQuietAt(minute, quietStartMinute, quietEndMinute)
    }

    /**
     * One stepper press: [minute] moved by [direction] half-hours, wrapping round
     * midnight, and hopping over [other] so the window can never close up to
     * nothing. Pure, so the sheet holds no arithmetic of its own.
     */
    fun stepQuietMinute(minute: Int, direction: Int, other: Int): Int {
        fun step(m: Int) = Math.floorMod(m + direction * QUIET_STEP_MINUTES, MINUTES_PER_DAY)
        val next = step(minute)
        return if (next == other) step(next) else next
    }

    /** Snaps a stored time onto the half-hour grid inside one day. */
    fun coerceQuietMinute(minute: Int): Int =
        minute.coerceIn(0, MINUTES_PER_DAY - 1) / QUIET_STEP_MINUTES * QUIET_STEP_MINUTES

    fun nagIntervalMillis(context: Context): Long {
        hydrate(context)
        return nagMinutes * 60_000L
    }

    /**
     * Keeps a hand-typed interval sane: never zero (which would busy-loop the
     * alarm), never so long it stops being a nag.
     */
    fun coerceMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_NAG_MINUTES, MAX_NAG_MINUTES)

    /**
     * Keeps a hand-typed swipe snooze sane. Never zero — that is the behaviour
     * this setting replaced — and never long enough to be a dismissal.
     */
    fun coerceSwipeSnoozeMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_SWIPE_SNOOZE_MINUTES, MAX_SWIPE_SNOOZE_MINUTES)

    /**
     * How every minute count in the settings sheet is written: "5 min", "90 min".
     *
     * Deliberately **not** [SnoozeOptions.label], which coarsens past an hour —
     * "1h 30" for 90. That is right for a snooze ladder that runs out to three
     * days, and wrong here: both settings are a count of minutes, chosen on a
     * slider whose ends are marked in minutes, so the chips, the readout under
     * your thumb, the marks at either end of the track and [summarise] all have to
     * say the same word or the sheet contradicts itself. One place to change.
     */
    fun minutesLabel(minutes: Int): String = "$minutes min"

    /**
     * The single sentence at the foot of the settings sheet, stating what the two
     * settings add up to.
     *
     * Pure and here rather than in the sheet, next to the values it describes, for
     * the reason every string [ActionToast] can say lives in one place: this is
     * the app accounting for its own behaviour, and it must not be able to
     * describe a setting differently from the control that sets it.
     *
     * It says "snooze it or tick it off" rather than only the latter because a
     * snooze stops the buzzing too — [Reminders.snooze] cancels the nag chain —
     * and claiming otherwise would make the sentence untrue for the more common
     * of the two answers.
     *
     * The night window is named only when it applies, with its times written by
     * [TaskTime.formatMinuteOfDay] — the same words the card's steppers show.
     */
    fun summarise(
        nagEnabled: Boolean,
        nagMinutes: Int,
        swipeSnoozeMinutes: Int,
        quietEnabled: Boolean = false,
        quietStartMinute: Int = DEFAULT_QUIET_START,
        quietEndMinute: Int = DEFAULT_QUIET_END,
        use24h: Boolean = true,
    ): String {
        val buzzing = if (nagEnabled) {
            "Pesky buzzes every ${minutesLabel(nagMinutes)} until you snooze it or tick it off."
        } else {
            "Pesky buzzes once and waits."
        }
        val swipe = "A swipe pushes it back ${minutesLabel(swipeSnoozeMinutes)}."
        if (!quietEnabled) return "$buzzing $swipe"
        val from = TaskTime.formatMinuteOfDay(quietStartMinute, use24h)
        val to = TaskTime.formatMinuteOfDay(quietEndMinute, use24h)
        return "$buzzing $swipe Nothing buzzes from $from to $to."
    }

    /** Test seam — drops both the stored and in-memory values. */
    fun clear(context: Context) {
        hydrated = true
        prefs(context).edit().clear().apply()
        nagEnabled = true
        nagMinutes = DEFAULT_NAG_MINUTES
        swipeSnoozeMinutes = DEFAULT_SWIPE_SNOOZE_MINUTES
        quietEnabled = true
        quietStartMinute = DEFAULT_QUIET_START
        quietEndMinute = DEFAULT_QUIET_END
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
