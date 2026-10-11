package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Test

/** The clamping that keeps a hand-typed nag interval sane. */
class SettingsTest {

    @Test fun a_sensible_interval_is_left_alone() {
        assertEquals(5, Settings.coerceMinutes(5))
        assertEquals(1, Settings.coerceMinutes(1))
        assertEquals(180, Settings.coerceMinutes(180))
    }

    @Test fun zero_and_negatives_are_pulled_up_to_the_minimum() {
        // A zero-minute interval would re-arm the alarm the instant it fired.
        assertEquals(1, Settings.coerceMinutes(0))
        assertEquals(1, Settings.coerceMinutes(-7))
    }

    @Test fun an_absurd_interval_is_pulled_down_to_the_maximum() {
        assertEquals(180, Settings.coerceMinutes(181))
        assertEquals(180, Settings.coerceMinutes(100_000))
        assertEquals(180, Settings.coerceMinutes(Int.MAX_VALUE))
    }

    @Test fun the_default_sits_inside_the_range() {
        assertEquals(
            Settings.DEFAULT_NAG_MINUTES,
            Settings.coerceMinutes(Settings.DEFAULT_NAG_MINUTES),
        )
    }

    // ---- how long a swipe hides a reminder ----------------------------------

    @Test fun a_sensible_swipe_snooze_is_left_alone() {
        assertEquals(5, Settings.coerceSwipeSnoozeMinutes(5))
        assertEquals(1, Settings.coerceSwipeSnoozeMinutes(1))
        assertEquals(180, Settings.coerceSwipeSnoozeMinutes(180))
    }

    @Test fun a_zero_swipe_snooze_is_pulled_up_to_the_minimum() {
        // Zero would put the notification straight back, which is the behaviour
        // this setting exists to replace.
        assertEquals(1, Settings.coerceSwipeSnoozeMinutes(0))
        assertEquals(1, Settings.coerceSwipeSnoozeMinutes(-7))
    }

    @Test fun an_absurd_swipe_snooze_is_pulled_down_to_the_maximum() {
        assertEquals(180, Settings.coerceSwipeSnoozeMinutes(181))
        assertEquals(180, Settings.coerceSwipeSnoozeMinutes(Int.MAX_VALUE))
    }

    @Test fun the_swipe_snooze_default_sits_inside_its_own_range() {
        assertEquals(
            Settings.DEFAULT_SWIPE_SNOOZE_MINUTES,
            Settings.coerceSwipeSnoozeMinutes(Settings.DEFAULT_SWIPE_SNOOZE_MINUTES),
        )
    }

    /**
     * The two clamps have their own bounds even though all six numbers coincide
     * today. They answer different questions and are free to diverge; this pins
     * that neither reads the other's constants.
     */
    @Test fun the_swipe_snooze_clamp_uses_its_own_bounds() {
        assertEquals(
            Settings.MIN_SWIPE_SNOOZE_MINUTES,
            Settings.coerceSwipeSnoozeMinutes(Settings.MIN_SWIPE_SNOOZE_MINUTES - 1),
        )
        assertEquals(
            Settings.MAX_SWIPE_SNOOZE_MINUTES,
            Settings.coerceSwipeSnoozeMinutes(Settings.MAX_SWIPE_SNOOZE_MINUTES + 1),
        )
    }

    // ---- the chips the sheet offers ------------------------------------------

    /**
     * A preset outside its own range would offer a value the clamp then refuses,
     * so the chip would appear not to work.
     */
    @Test fun every_nag_preset_is_inside_the_nag_range() {
        Settings.NAG_PRESET_MINUTES.forEach {
            assertEquals("preset $it", it, Settings.coerceMinutes(it))
        }
    }

    @Test fun every_swipe_preset_is_inside_the_swipe_range() {
        Settings.SWIPE_SNOOZE_PRESET_MINUTES.forEach {
            assertEquals("preset $it", it, Settings.coerceSwipeSnoozeMinutes(it))
        }
    }

    /** The sheet lays them out in the order given, so it has to be ascending. */
    @Test fun the_presets_ascend() {
        assertEquals(Settings.NAG_PRESET_MINUTES.sorted(), Settings.NAG_PRESET_MINUTES)
        assertEquals(
            Settings.SWIPE_SNOOZE_PRESET_MINUTES.sorted(),
            Settings.SWIPE_SNOOZE_PRESET_MINUTES,
        )
    }

    // ---- how a minute count is written --------------------------------------

    /**
     * Minutes all the way up, deliberately: [SnoozeOptions.label] would coarsen 90
     * to "1h 30", and the slider whose ends are marked in minutes would then be
     * disagreeing with the number under the thumb.
     */
    @Test fun a_minute_count_stays_in_minutes_however_large() {
        assertEquals("1 min", Settings.minutesLabel(1))
        assertEquals("5 min", Settings.minutesLabel(5))
        assertEquals("90 min", Settings.minutesLabel(90))
        assertEquals("180 min", Settings.minutesLabel(180))
    }

    /** The chips are labelled by it, so they have to come out as the design has them. */
    @Test fun the_presets_read_as_the_chips_are_meant_to() {
        assertEquals(
            listOf("5 min", "15 min", "30 min"),
            Settings.NAG_PRESET_MINUTES.map(Settings::minutesLabel),
        )
    }

    // ---- the sentence at the foot of the sheet -------------------------------

    @Test fun the_summary_states_both_settings_when_nagging_is_on() {
        assertEquals(
            "Pesky buzzes every 5 min until you snooze it or tick it off. " +
                "A swipe pushes it back 5 min.",
            Settings.summarise(nagEnabled = true, nagMinutes = 5, swipeSnoozeMinutes = 5),
        )
    }

    @Test fun the_summary_reads_back_the_stored_intervals_not_the_defaults() {
        assertEquals(
            "Pesky buzzes every 45 min until you snooze it or tick it off. " +
                "A swipe pushes it back 20 min.",
            Settings.summarise(nagEnabled = true, nagMinutes = 45, swipeSnoozeMinutes = 20),
        )
    }

    /** With nagging off there is no interval to report, but the swipe still holds. */
    @Test fun the_summary_drops_the_interval_when_nagging_is_off() {
        assertEquals(
            "Pesky buzzes once and waits. A swipe pushes it back 15 min.",
            Settings.summarise(nagEnabled = false, nagMinutes = 45, swipeSnoozeMinutes = 15),
        )
    }

    /**
     * A snooze cancels the nag chain just as ticking off does, so the sentence
     * has to offer both. Pinned because the design's copy named only one.
     */
    @Test fun the_summary_credits_a_snooze_with_stopping_the_buzzing() {
        val summary = Settings.summarise(true, 5, 5)
        assert(summary.contains("snooze it or tick it off")) { summary }
    }

    // ---- the night window ----------------------------------------------------

    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test fun the_default_window_is_23_to_7() {
        assertEquals(hm(23), Settings.DEFAULT_QUIET_START)
        assertEquals(hm(7), Settings.DEFAULT_QUIET_END)
    }

    @Test fun a_window_across_midnight_holds_its_edges() {
        val (s, e) = hm(23) to hm(7)
        assertEquals(false, Settings.isQuietAt(hm(22, 59), s, e))
        assertEquals(true, Settings.isQuietAt(hm(23), s, e))
        assertEquals(true, Settings.isQuietAt(0, s, e))
        assertEquals(true, Settings.isQuietAt(hm(6, 59), s, e))
        assertEquals(false, Settings.isQuietAt(hm(7), s, e))
        assertEquals(false, Settings.isQuietAt(hm(12), s, e))
    }

    @Test fun a_window_inside_one_day_holds_its_edges() {
        val (s, e) = hm(13) to hm(14, 30)
        assertEquals(false, Settings.isQuietAt(hm(12, 59), s, e))
        assertEquals(true, Settings.isQuietAt(hm(13), s, e))
        assertEquals(true, Settings.isQuietAt(hm(14, 29), s, e))
        assertEquals(false, Settings.isQuietAt(hm(14, 30), s, e))
        assertEquals(false, Settings.isQuietAt(hm(23), s, e))
    }

    /** Never quiet, rather than always: the failure that keeps the app working. */
    @Test fun an_empty_window_is_never_quiet() {
        assertEquals(false, Settings.isQuietAt(hm(3), hm(7), hm(7)))
        assertEquals(false, Settings.isQuietAt(hm(7), hm(7), hm(7)))
    }

    @Test fun a_step_moves_half_an_hour_and_wraps_round_midnight() {
        assertEquals(hm(23, 30), Settings.stepQuietMinute(hm(23), 1, hm(7)))
        assertEquals(0, Settings.stepQuietMinute(hm(23, 30), 1, hm(7)))
        assertEquals(hm(23, 30), Settings.stepQuietMinute(0, -1, hm(7)))
    }

    @Test fun a_step_never_lands_on_the_other_end() {
        assertEquals(hm(7, 30), Settings.stepQuietMinute(hm(6, 30), 1, hm(7)))
        assertEquals(hm(22, 30), Settings.stepQuietMinute(hm(23, 30), -1, hm(23)))
    }

    @Test fun a_stored_time_is_snapped_onto_the_half_hour_grid() {
        assertEquals(hm(23), Settings.coerceQuietMinute(hm(23, 17)))
        assertEquals(hm(23, 30), Settings.coerceQuietMinute(hm(23, 59)))
        assertEquals(0, Settings.coerceQuietMinute(-40))
        assertEquals(hm(23, 30), Settings.coerceQuietMinute(99_999))
    }

    @Test fun the_summary_names_the_window_only_while_it_is_on() {
        val base = "Pesky buzzes once and waits. A swipe pushes it back 5 min."
        assertEquals(
            "$base Nothing buzzes from 23:00 to 07:00.",
            Settings.summarise(false, 5, 5, true, hm(23), hm(7), use24h = true),
        )
        assertEquals(
            "$base Nothing buzzes from 10:30 PM to 6:00 AM.",
            Settings.summarise(false, 5, 5, true, hm(22, 30), hm(6), use24h = false),
        )
        assertEquals(base, Settings.summarise(false, 5, 5, false, hm(23), hm(7), true))
    }
}
