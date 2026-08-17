package com.wgorski.peskyreminders.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slider's arithmetic, exactly.
 *
 * The gesture that feeds it is not here on purpose. Compose's pointer injection
 * misroutes drags inside these sheets under Robolectric — the same reason
 * [PeskySheetTest] tests `shouldDismiss` rather than a swipe — so the part with
 * the judgement in it is pure and pinned here, and the drag is an emulator check.
 */
class PeskySliderTest {

    private val MIN = 1
    private val MAX = 180

    // ---- value to thumb position --------------------------------------------

    @Test fun the_ends_of_the_range_are_the_ends_of_the_track() {
        assertEquals(0f, sliderFraction(MIN, MIN, MAX), 1e-6f)
        assertEquals(1f, sliderFraction(MAX, MIN, MAX), 1e-6f)
    }

    @Test fun the_middle_of_the_range_is_the_middle_of_the_track() {
        // 1..181 so the midpoint is a whole number: 91 is 90 of 180 steps along.
        assertEquals(0.5f, sliderFraction(91, 1, 181), 1e-6f)
    }

    @Test fun a_value_outside_the_range_is_pinned_to_an_end() {
        assertEquals(0f, sliderFraction(-5, MIN, MAX), 1e-6f)
        assertEquals(1f, sliderFraction(9_999, MIN, MAX), 1e-6f)
    }

    /** A range with nowhere to travel must not divide by zero. */
    @Test fun a_collapsed_range_sits_at_the_left() {
        assertEquals(0f, sliderFraction(5, 5, 5), 1e-6f)
        assertEquals(0f, sliderFraction(5, 10, 3), 1e-6f)
    }

    // ---- thumb position to value --------------------------------------------

    @Test fun the_ends_of_the_track_are_the_ends_of_the_range() {
        assertEquals(MIN, sliderValue(0f, MIN, MAX))
        assertEquals(MAX, sliderValue(1f, MIN, MAX))
    }

    @Test fun a_fraction_off_the_end_of_the_track_still_lands_in_range() {
        assertEquals(MIN, sliderValue(-0.4f, MIN, MAX))
        assertEquals(MAX, sliderValue(1.4f, MIN, MAX))
    }

    /**
     * Rounding, not truncating. Truncating would make the top of every step
     * unreachable and [MAX] reachable only at exactly 1.0.
     */
    @Test fun a_fraction_lands_on_the_nearest_whole_value() {
        // 1..181, so each step is exactly 1/180 of the track.
        assertEquals(2, sliderValue(1.0f / 180f, 1, 181))
        assertEquals(2, sliderValue(1.4f / 180f, 1, 181))
        assertEquals(3, sliderValue(1.6f / 180f, 1, 181))
        // Half a step up goes up, which is all that matters — that it picks a side.
        assertEquals(4, sliderValue(2.5f / 180f, 1, 181))
    }

    /** Every value in range has to survive the round trip, or the thumb drifts. */
    @Test fun every_value_survives_a_round_trip() {
        for (value in MIN..MAX) {
            assertEquals(
                "round trip of $value",
                value,
                sliderValue(sliderFraction(value, MIN, MAX), MIN, MAX),
            )
        }
    }

    // ---- touch position to thumb position -----------------------------------

    /**
     * The thumb's centre travels between the two half-widths, so a touch at the
     * thumb's own centre-line at either extreme reads as an end of the track — not
     * the row's outer edges, where the thumb would hang off.
     */
    @Test fun a_touch_at_either_end_of_the_travel_reads_as_an_end() {
        assertEquals(0f, sliderFractionAt(x = 11f, width = 300f, thumb = 22f), 1e-6f)
        assertEquals(1f, sliderFractionAt(x = 289f, width = 300f, thumb = 22f), 1e-6f)
    }

    @Test fun a_touch_halfway_along_the_travel_reads_as_the_middle() {
        assertEquals(0.5f, sliderFractionAt(x = 150f, width = 300f, thumb = 22f), 1e-6f)
    }

    /** Past the ends of the travel, including into the row's own margins. */
    @Test fun a_touch_beyond_the_travel_is_pinned() {
        assertEquals(0f, sliderFractionAt(x = 0f, width = 300f, thumb = 22f), 1e-6f)
        assertEquals(0f, sliderFractionAt(x = -80f, width = 300f, thumb = 22f), 1e-6f)
        assertEquals(1f, sliderFractionAt(x = 300f, width = 300f, thumb = 22f), 1e-6f)
        assertEquals(1f, sliderFractionAt(x = 900f, width = 300f, thumb = 22f), 1e-6f)
    }

    /**
     * The first frame, before the row has been measured. A negative span would
     * otherwise produce a fraction that runs backwards.
     */
    @Test fun an_unmeasured_row_reads_as_the_left_end() {
        assertEquals(0f, sliderFractionAt(x = 40f, width = 0f, thumb = 22f), 1e-6f)
        assertEquals(0f, sliderFractionAt(x = 40f, width = 10f, thumb = 22f), 1e-6f)
    }
}
