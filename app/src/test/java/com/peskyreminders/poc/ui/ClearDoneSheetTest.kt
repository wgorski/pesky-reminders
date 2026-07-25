package com.peskyreminders.poc.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The confirmation guarding the app's only delete. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ClearDoneSheetTest {

    @get:Rule val compose = createComposeRule()

    private var confirmed = false
    private var dismissed = false

    private fun show(count: Int) {
        compose.setContent {
            ClearDoneSheet(
                count = count,
                onDismiss = { dismissed = true },
                onConfirm = { confirmed = true },
            )
        }
    }

    // ---- what it says -------------------------------------------------------

    @Test fun it_asks_before_deleting_anything() {
        show(3)
        compose.onNodeWithText("Clear done?").assertIsDisplayed()
        assertFalse("showing the sheet must not delete anything", confirmed)
    }

    @Test fun it_says_how_many_are_going() {
        show(3)
        compose.onNodeWithTag("clear-done-button").assertTextEquals("Clear 3 tasks")
    }

    @Test fun one_task_is_not_pluralised() {
        show(1)
        compose.onNodeWithTag("clear-done-button").assertTextEquals("Clear 1 task")
    }

    @Test fun it_warns_that_there_is_no_way_back() {
        show(2)
        compose.onNodeWithTag("clear-done-summary")
            .assertTextEquals(
                "Tasks you have ticked off are removed for good. There is no undo."
            )
    }

    // ---- what it does -------------------------------------------------------

    @Test fun confirming_reports_it() {
        show(2)
        compose.onNodeWithTag("clear-done-button")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(confirmed)
    }

    @Test fun closing_deletes_nothing() {
        show(2)
        compose.onNodeWithContentDescription("Close")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
        assertFalse(confirmed)
    }

    @Test fun tapping_the_scrim_deletes_nothing() {
        show(2)
        compose.onNodeWithTag("sheet-scrim")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
        assertFalse(confirmed)
    }
}
