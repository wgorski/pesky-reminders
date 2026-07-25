package com.peskyreminders.poc.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Drives the settings sheet on the JVM.
 *
 * Same caveat as [AddTaskSheetTest]: Compose's pointer injection does not reach
 * into a sheet body under Robolectric, so controls are asserted displayed and
 * then their click action is fired directly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsSheetTest {

    @get:Rule val compose = createComposeRule()

    // Real state, so a toggle actually re-renders the sheet.
    private val nagEnabled = mutableStateOf(true)
    private val nagMinutes = mutableIntStateOf(5)
    private var dismissed = false

    private fun show(enabled: Boolean = true, minutes: Int = 5) {
        nagEnabled.value = enabled
        nagMinutes.intValue = minutes
        compose.setContent {
            SettingsSheet(
                nagEnabled = nagEnabled.value,
                nagMinutes = nagMinutes.intValue,
                onNagEnabled = { nagEnabled.value = it },
                onNagMinutes = { nagMinutes.intValue = it },
                onDismiss = { dismissed = true },
            )
        }
    }

    private fun switchState(): ToggleableState =
        compose.onNodeWithTag("nag-switch")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]

    private fun tapSwitch() {
        compose.onNodeWithTag("nag-switch")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    // ---- rendering ----------------------------------------------------------

    @Test fun the_sheet_shows_the_current_settings() {
        show(enabled = true, minutes = 5)
        compose.onNodeWithText("Keep buzzing").assertIsDisplayed()
        compose.onNodeWithTag("nag-minutes").assertTextEquals("5")
        assertEquals(ToggleableState.On, switchState())
    }

    @Test fun a_stored_interval_is_shown_rather_than_the_default() {
        show(minutes = 45)
        compose.onNodeWithTag("nag-minutes").assertTextEquals("45")
    }

    @Test fun the_range_is_spelled_out() {
        show()
        compose.onNodeWithTag("nag-minutes-hint")
            .assertTextEquals("Anything from 1 to 180 minutes.")
    }

    @Test fun the_unit_label_is_singular_for_one_minute() {
        show(minutes = 1)
        compose.onNodeWithText("minute").assertIsDisplayed()
    }

    @Test fun the_unit_label_is_plural_otherwise() {
        show(minutes = 5)
        compose.onNodeWithText("minutes").assertIsDisplayed()
    }

    // ---- the switch ---------------------------------------------------------

    @Test fun the_switch_turns_nagging_off_and_back_on() {
        show(enabled = true)
        tapSwitch()
        assertEquals(ToggleableState.Off, switchState())
        assertEquals(false, nagEnabled.value)

        tapSwitch()
        assertEquals(ToggleableState.On, switchState())
        assertEquals(true, nagEnabled.value)
    }

    @Test fun the_switch_announces_itself_as_a_switch() {
        show()
        compose.onNodeWithTag("nag-switch").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)
        )
    }

    @Test fun the_interval_field_is_disabled_while_nagging_is_off() {
        show(enabled = false)
        compose.onNodeWithTag("nag-minutes")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
    }

    // ---- typing an interval -------------------------------------------------

    private fun type(value: String) {
        compose.onNodeWithTag("nag-minutes").performTextClearance()
        compose.onNodeWithTag("nag-minutes").performTextInput(value)
        compose.onNodeWithTag("nag-minutes")
            .performSemanticsAction(SemanticsActions.OnImeAction)
        compose.waitForIdle()
    }

    /**
     * Regression: hiding the keyboard does not clear Compose focus, so relying
     * on focus loss alone silently dropped a typed value.
     */
    @Test fun a_typed_interval_commits_without_any_focus_change() {
        show(minutes = 5)
        compose.onNodeWithTag("nag-minutes").performTextClearance()
        compose.onNodeWithTag("nag-minutes").performTextInput("12")
        compose.waitForIdle()
        assertEquals("must be stored on the keystroke, not on blur", 12, nagMinutes.intValue)
    }

    @Test fun dismissing_commits_a_value_that_still_needs_clamping() {
        show(minutes = 5)
        compose.onNodeWithTag("nag-minutes").performTextClearance()
        compose.onNodeWithTag("nag-minutes").performTextInput("999")
        // Out of range, so nothing committed yet.
        assertEquals(5, nagMinutes.intValue)

        compose.onNodeWithContentDescription("Close")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("clamped on the way out", 180, nagMinutes.intValue)
    }

    @Test fun a_typed_interval_is_kept() {
        show(minutes = 5)
        type("20")
        assertEquals(20, nagMinutes.intValue)
        compose.onNodeWithTag("nag-minutes").assertTextEquals("20")
    }

    @Test fun a_zero_is_clamped_up_rather_than_busy_looping_the_alarm() {
        show(minutes = 5)
        type("0")
        assertEquals(1, nagMinutes.intValue)
        compose.onNodeWithTag("nag-minutes").assertTextEquals("1")
    }

    @Test fun an_absurd_interval_is_clamped_down() {
        show(minutes = 5)
        type("999")
        assertEquals(180, nagMinutes.intValue)
        compose.onNodeWithTag("nag-minutes").assertTextEquals("180")
    }

    @Test fun clearing_the_field_falls_back_to_the_stored_value() {
        show(minutes = 12)
        type("")
        assertEquals(12, nagMinutes.intValue)
        compose.onNodeWithTag("nag-minutes").assertTextEquals("12")
    }

    @Test fun non_digits_never_reach_the_field() {
        show(minutes = 5)
        compose.onNodeWithTag("nag-minutes").performTextClearance()
        compose.onNodeWithTag("nag-minutes").performTextInput("1a2b")
        compose.onNodeWithTag("nag-minutes").assertTextEquals("12")
    }

    // ---- dismissal ----------------------------------------------------------

    @Test fun closing_dismisses() {
        show()
        compose.onNodeWithContentDescription("Close")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
    }

    @Test fun tapping_the_scrim_dismisses() {
        show()
        compose.onNodeWithTag("sheet-scrim")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
    }
}
