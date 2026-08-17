package com.wgorski.peskyreminders

import android.content.Context
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
     */
    fun summarise(nagEnabled: Boolean, nagMinutes: Int, swipeSnoozeMinutes: Int): String {
        val buzzing = if (nagEnabled) {
            "Pesky buzzes every ${minutesLabel(nagMinutes)} until you snooze it or tick it off."
        } else {
            "Pesky buzzes once and waits."
        }
        return "$buzzing A swipe pushes it back ${minutesLabel(swipeSnoozeMinutes)}."
    }

    /** Test seam — drops both the stored and in-memory values. */
    fun clear(context: Context) {
        hydrated = true
        prefs(context).edit().clear().apply()
        nagEnabled = true
        nagMinutes = DEFAULT_NAG_MINUTES
        swipeSnoozeMinutes = DEFAULT_SWIPE_SNOOZE_MINUTES
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
