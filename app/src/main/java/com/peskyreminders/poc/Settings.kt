package com.peskyreminders.poc

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

    const val DEFAULT_NAG_MINUTES = 5
    const val MIN_NAG_MINUTES = 1
    const val MAX_NAG_MINUTES = 180

    /** Whether an ignored notification keeps buzzing. */
    var nagEnabled by mutableStateOf(true)
        private set

    /** How many minutes between buzzes. */
    var nagMinutes by mutableIntStateOf(DEFAULT_NAG_MINUTES)
        private set

    private var hydrated = false

    @Synchronized
    fun hydrate(context: Context) {
        if (hydrated) return
        hydrated = true
        val prefs = prefs(context)
        nagEnabled = prefs.getBoolean(KEY_NAG_ENABLED, true)
        nagMinutes = coerceMinutes(prefs.getInt(KEY_NAG_MINUTES, DEFAULT_NAG_MINUTES))
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

    /** Test seam — drops both the stored and in-memory values. */
    fun clear(context: Context) {
        hydrated = true
        prefs(context).edit().clear().apply()
        nagEnabled = true
        nagMinutes = DEFAULT_NAG_MINUTES
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
