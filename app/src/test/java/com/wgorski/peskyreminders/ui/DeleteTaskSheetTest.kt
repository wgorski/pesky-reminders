package com.wgorski.peskyreminders.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wgorski.peskyreminders.Repeat
import com.wgorski.peskyreminders.Task
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The confirmation guarding a single-task delete. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DeleteTaskSheetTest {

    @get:Rule val compose = createComposeRule()

    private var confirmed = false
    private var dismissed = false

    private val oneOff = Task(1, "Book dentist", 0L, Repeat.ONCE)
    private val repeating = Task(2, "Feed the sourdough", 0L, Repeat.WEEKLY)

    private fun show(task: Task) {
        compose.setContent {
            DeleteTaskSheet(
                task = task,
                onDismiss = { dismissed = true },
                onConfirm = { confirmed = true },
            )
        }
    }

    @Test fun it_asks_before_deleting_anything() {
        show(oneOff)
        compose.onNodeWithText("Delete this task?").assertIsDisplayed()
        assertFalse("showing the sheet must not delete anything", confirmed)
    }

    @Test fun it_names_the_task_going() {
        show(oneOff)
        compose.onNodeWithTag("delete-task-summary").assertTextContains(
            "Book dentist", substring = true,
        )
    }

    @Test fun a_repeating_task_is_told_the_repeat_stops_too() {
        show(repeating)
        compose.onNodeWithTag("delete-task-summary").assertTextContains(
            "stops repeating", substring = true,
        )
    }

    @Test fun a_one_off_gets_no_repeat_wording() {
        show(oneOff)
        compose.onNodeWithTag("delete-task-summary").assertTextContains(
            "its reminder", substring = true,
        )
    }

    @Test fun it_warns_that_there_is_no_way_back() {
        show(repeating)
        compose.onNodeWithTag("delete-task-summary").assertTextContains(
            "no undo", substring = true,
        )
    }

    @Test fun the_button_says_what_it_does() {
        show(oneOff)
        compose.onNodeWithTag("delete-task-button").assertTextEquals("Delete")
    }

    @Test fun confirming_reports_it() {
        show(repeating)
        compose.onNodeWithTag("delete-task-button")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(confirmed)
    }

    @Test fun closing_deletes_nothing() {
        show(repeating)
        compose.onNodeWithContentDescription("Close")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(dismissed)
        assertFalse(confirmed)
    }

    @Test fun tapping_the_scrim_deletes_nothing() {
        show(repeating)
        compose.onNodeWithTag("sheet-scrim")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(dismissed)
        assertFalse(confirmed)
    }
}
