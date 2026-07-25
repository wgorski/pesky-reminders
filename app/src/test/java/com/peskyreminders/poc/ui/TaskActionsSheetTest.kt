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
import com.peskyreminders.poc.Repeat
import com.peskyreminders.poc.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/** The long-press menu on a task row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TaskActionsSheetTest {

    @get:Rule val compose = createComposeRule()

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Saturday 25 July 2026, 14:20 UTC. */
    private val now: Long get() = at(2026, Calendar.JULY, 25, 14, 20)

    private var snoozed = false
    private var toggled = false
    private var deleted = false
    private var dismissed = false

    private fun show(task: Task) {
        compose.setContent {
            TaskActionsSheet(
                task = task,
                nowMillis = now,
                use24h = false,
                onReschedule = { snoozed = true },
                onToggle = { toggled = true },
                onDelete = { deleted = true },
                onDismiss = { dismissed = true },
            )
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private val overdue =
        Task(1, "Water the monstera", at(2026, Calendar.JULY, 25, 9), Repeat.ONCE)
    private val upcoming =
        Task(2, "Call the vet", at(2026, Calendar.JULY, 25, 20), Repeat.ONCE)
    private val repeating =
        Task(3, "Take out the trash", at(2026, Calendar.JULY, 25, 9), Repeat.WEEKLY)
    private val finished =
        Task(4, "Book dentist", at(2026, Calendar.JULY, 24, 9), Repeat.ONCE, done = true)

    // ---- what it shows ------------------------------------------------------

    @Test fun it_is_titled_with_the_task() {
        show(overdue)
        compose.onNodeWithText("Water the monstera").assertIsDisplayed()
    }

    @Test fun an_overdue_task_says_so() {
        show(overdue)
        compose.onNodeWithTag("actions-due").assertTextEquals("Was due Today, 9:00 AM")
    }

    @Test fun an_upcoming_task_just_shows_its_time() {
        show(upcoming)
        compose.onNodeWithTag("actions-due").assertTextEquals("Today, 8:00 PM")
    }

    // ---- snooze -------------------------------------------------------------

    @Test fun reschedule_is_offered_on_a_task_that_is_still_live() {
        show(overdue)
        compose.onNodeWithTag("action-reschedule").assertIsDisplayed()
    }

    @Test fun reschedule_is_not_offered_on_something_already_done() {
        show(finished)
        compose.onNodeWithTag("action-reschedule").assertDoesNotExist()
    }

    @Test fun an_overdue_task_counts_from_now() {
        show(overdue)
        compose.onNodeWithText("Counts from now").assertIsDisplayed()
    }

    /**
     * The case the wording exists for: this task is not due until tonight, and
     * rescheduling it still counts from now — which can pull it earlier.
     */
    @Test fun something_not_yet_due_counts_from_now_as_well() {
        show(upcoming)
        compose.onNodeWithText("Counts from now").assertIsDisplayed()
        compose.onNodeWithText("Pushes it back from Today, 8:00 PM").assertDoesNotExist()
    }

    @Test fun choosing_reschedule_reports_it() {
        show(overdue)
        tap("action-reschedule")
        assertTrue(snoozed)
        assertFalse("rescheduling must not also tick it off", toggled)
    }

    // ---- done / not done ----------------------------------------------------

    @Test fun a_one_off_offers_to_be_marked_done() {
        show(overdue)
        compose.onNodeWithText("Mark as done").assertIsDisplayed()
    }

    @Test fun something_done_offers_to_be_put_back() {
        show(finished)
        compose.onNodeWithText("Mark as not done").assertIsDisplayed()
    }

    @Test fun a_repeating_task_says_it_rolls_forward_rather_than_completing() {
        show(repeating)
        compose.onNodeWithText("Done for now").assertIsDisplayed()
        // Weekly from Saturday the 25th lands on the following Saturday.
        compose.onNodeWithText("Moves to Sat 1 Aug, 9:00 AM").assertIsDisplayed()
    }

    @Test fun choosing_done_reports_it() {
        show(overdue)
        tap("action-toggle")
        assertTrue(toggled)
        assertFalse("ticking off must not also reschedule", snoozed)
    }

    // ---- delete -------------------------------------------------------------

    @Test fun a_live_task_can_be_deleted() {
        show(overdue)
        compose.onNodeWithTag("action-delete").assertIsDisplayed()
    }

    @Test fun a_repeating_task_can_be_deleted() {
        show(repeating)
        compose.onNodeWithTag("action-delete").assertIsDisplayed()
    }

    /** Unlike Reschedule, which is hidden once a task is done. */
    @Test fun a_finished_task_can_still_be_deleted() {
        show(finished)
        compose.onNodeWithTag("action-delete").assertIsDisplayed()
        compose.onNodeWithTag("action-reschedule").assertDoesNotExist()
    }

    /**
     * The gap this closes: a repeating task never completes, so it never reaches
     * the done list, so CLEAR can never take it. Delete is its only exit.
     */
    @Test fun a_repeating_task_says_deleting_stops_the_repeat() {
        show(repeating)
        compose.onNodeWithText("Stops it repeating").assertIsDisplayed()
    }

    @Test fun a_one_off_needs_no_such_warning() {
        show(overdue)
        compose.onNodeWithText("Stops it repeating").assertDoesNotExist()
    }

    @Test fun choosing_delete_reports_it_and_nothing_else() {
        show(repeating)
        tap("action-delete")
        assertTrue(deleted)
        assertFalse("deleting must not also tick it off", toggled)
        assertFalse("deleting must not also reschedule", snoozed)
    }

    // ---- backing out --------------------------------------------------------

    @Test fun closing_changes_nothing() {
        show(overdue)
        compose.onNodeWithContentDescription("Close")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, dismissed)
        assertFalse(snoozed)
        assertFalse(toggled)
        assertFalse(deleted)
    }
}
