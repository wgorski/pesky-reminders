package com.wgorski.peskyreminders.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared sheet chrome, driven directly rather than through one of the four
 * sheets built on it — the drag belongs to [PeskySheet], so it is covered here
 * once instead of four times.
 *
 * **The drag gesture itself is not tested here, deliberately.** Compose's
 * synthetic pointer injection misroutes drags in this sheet under Robolectric:
 * a `performTouchInput` swipe on the grabber leaks through to the scrim behind
 * and dismisses, so every such test "passes" or fails for reasons that have
 * nothing to do with the code. This was chased down with a probe — the same
 * gesture on a device is correct, and the isolated equivalent under Robolectric
 * is correct too, so it is the harness, not the app. Rather than assert on that,
 * the letting-go rule is a pure function ([shouldDismiss]) and is tested exactly
 * below; the gesture that feeds it was verified by hand on the emulator (short
 * drag springs back, long drag dismisses, mid-drag tracks the finger and fades
 * the scrim, on both the task sheet and the action panel).
 *
 * Same limitation as [AddTaskSheetTest], and the same reason it is written down.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PeskySheetTest {

    @get:Rule val compose = createComposeRule()

    private var dismissed = false

    private fun showSheet() {
        compose.setContent {
            PeskySheet(title = "Take the bins out", onDismiss = { dismissed = true }) {
                Text("body")
                Box(Modifier.fillMaxWidth().height(300.dp))
            }
        }
    }

    // ---- the letting-go rule ------------------------------------------------

    /** A 400px sheet: the threshold sits at 140px. */
    private val height = 400f
    private val fling = 1000f

    @Test fun a_drag_past_a_third_of_the_way_dismisses() {
        assertTrue(shouldDismiss(dragged = 200f, height = height, velocity = 0f, flingVelocity = fling))
    }

    @Test fun a_short_drag_does_not() {
        assertFalse(shouldDismiss(dragged = 40f, height = height, velocity = 0f, flingVelocity = fling))
    }

    /** Exactly on the line stays: the rule is strictly *past* the threshold. */
    @Test fun sitting_on_the_threshold_stays() {
        assertFalse(shouldDismiss(dragged = 140f, height = height, velocity = 0f, flingVelocity = fling))
    }

    /** A flick lets go early — the point of tracking velocity at all. */
    @Test fun a_hard_flick_dismisses_from_barely_anywhere() {
        assertTrue(shouldDismiss(dragged = 10f, height = height, velocity = 2000f, flingVelocity = fling))
    }

    /** Upward velocity is not a downward fling, however fast. */
    @Test fun a_flick_upwards_never_dismisses() {
        assertFalse(shouldDismiss(dragged = 0f, height = height, velocity = -4000f, flingVelocity = fling))
    }

    /**
     * The first frame, before the sheet has been measured. Dismissing here would
     * mean a stray touch could close a sheet that had not finished appearing.
     */
    @Test fun an_unmeasured_sheet_never_dismisses() {
        assertFalse(shouldDismiss(dragged = 900f, height = 0f, velocity = 9000f, flingVelocity = fling))
    }

    // ---- the scrim fade -----------------------------------------------------

    @Test fun the_scrim_is_solid_at_rest() {
        assertEquals(0f, dragFraction(0f, height), 0.001f)
    }

    @Test fun the_scrim_is_gone_once_the_sheet_is() {
        assertEquals(1f, dragFraction(height, height), 0.001f)
    }

    @Test fun the_fade_tracks_the_drag_in_between() {
        assertEquals(0.5f, dragFraction(200f, height), 0.001f)
    }

    /** Dragged past its own height, it cannot fade further than gone. */
    @Test fun the_fade_never_overshoots() {
        assertEquals(1f, dragFraction(4000f, height), 0.001f)
    }

    /** No height yet means no NaN — the value it feeds is an alpha. */
    @Test fun an_unmeasured_sheet_has_no_fade() {
        assertEquals(0f, dragFraction(100f, 0f), 0.001f)
    }

    // ---- what the drag must not have broken ---------------------------------

    @Test fun the_grabber_is_there_to_be_grabbed() {
        showSheet()
        compose.onNodeWithTag("sheet-drag-handle").assertIsDisplayed()
    }

    /**
     * The close button lives *inside* the draggable area, and the drag handle is
     * a `Box` parenting its own tap-swallow layer rather than a sibling of one.
     * Both of those could silently stop the button resolving as its own node.
     */
    @Test fun the_close_button_survives_inside_the_drag_area() {
        showSheet()
        compose.onNodeWithContentDescription("Close")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(dismissed)
    }

    /**
     * The swallow layer added inside the drag handle must not merge the chrome
     * into one semantics node — the trap a `clickable` ancestor would spring.
     */
    @Test fun the_title_is_still_its_own_node() {
        showSheet()
        compose.onNodeWithTag("sheet-title").assertIsDisplayed()
    }

    @Test fun the_scrim_still_dismisses() {
        showSheet()
        compose.onNodeWithTag("sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
    }
}
