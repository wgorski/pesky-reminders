package com.peskyreminders.poc.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

/**
 * Drives the edit sheet — the same sheet as the add one, seeded from a task —
 * against a frozen clock, on the JVM.
 *
 * The seeding assertions are deliberately behavioural: rather than inspecting
 * which chip looks selected, they save without touching anything and check the
 * task comes back out unchanged. That covers the whole seed → save path, and it
 * is the case a real edit of one field depends on.
 *
 * Same caveat as [AddTaskSheetTest]: Compose's pointer injection does not reach
 * into this sheet's scrolling body under Robolectric, so [act] asserts the node
 * is displayed and then fires its click action.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EditTaskSheetTest {

    @get:Rule val compose = createComposeRule()

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Saturday 25 July 2026, 14:20 UTC. */
    private val now = at(2026, Calendar.JULY, 25, 14, 20)

    private var savedName: String? = null
    private var savedDue: Long? = null
    private var savedRepeat: Repeat? = null
    private var deleted = false
    private var dismissed = false

    // Wednesday 29 July, 09:00 — four days out, so up next rather than overdue.
    private val weekly =
        Task(7, "Feed the sourdough", at(2026, Calendar.JULY, 29, 9), Repeat.WEEKLY)
    private val overdue =
        Task(8, "Call the vet", at(2026, Calendar.JULY, 25, 9), Repeat.ONCE)
    private val finished =
        Task(9, "Book dentist", at(2026, Calendar.JULY, 24, 9), Repeat.ONCE, done = true)
    private val oneOff =
        Task(10, "Renew the passport", at(2026, Calendar.NOVEMBER, 9, 14), Repeat.ONCE)

    private fun showSheet(task: Task) {
        compose.setContent {
            EditTaskSheet(
                task = task,
                nowMillis = now,
                use24h = false,
                onDismiss = { dismissed = true },
                onSave = { name, due, repeat ->
                    savedName = name; savedDue = due; savedRepeat = repeat
                },
                onDelete = { deleted = true },
            )
        }
    }

    private fun dueLabel() = compose.onNodeWithTag("due-label")

    private fun act(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun tap(text: String) = act(compose.onNodeWithText(text))

    private fun tapTag(tag: String) = act(compose.onNodeWithTag(tag))

    private fun tapCd(description: String) = act(compose.onNodeWithContentDescription(description))

    private fun tapWheel(wheel: String, index: Int) {
        compose.onNodeWithTag("wheel-$wheel").performScrollToNode(hasTestTag("$wheel-$index"))
        tapTag("$wheel-$index")
    }

    private fun save() = tapTag("save-button")

    // ---- it is the same sheet, worded for an edit ----------------------------

    @Test fun the_sheet_says_it_is_editing() {
        showSheet(weekly)
        compose.onNodeWithText("Edit pester").assertExists()
        compose.onNodeWithText("New pester").assertDoesNotExist()
        compose.onNodeWithText("Save changes").assertExists()
        compose.onNodeWithText("Pester me").assertDoesNotExist()
    }

    @Test fun both_time_pickers_are_offered_just_as_they_are_when_adding() {
        showSheet(weekly)
        compose.onNodeWithText("DAY").assertExists()
        tap("Calendar")
        compose.onNodeWithText("DAY").assertDoesNotExist()
        tap("Quick pick")
        compose.onNodeWithText("DAY").assertExists()
    }

    // ---- seeding ------------------------------------------------------------

    @Test fun the_name_starts_on_the_task_name() {
        showSheet(weekly)
        compose.onNodeWithTag("name-field").assertTextEquals("Feed the sourdough")
    }

    @Test fun the_readout_starts_on_the_time_the_task_is_due() {
        showSheet(weekly)
        dueLabel().assertTextEquals("Wed, 9:00 AM")
    }

    /** Saving without touching a thing has to hand back exactly what came in. */
    @Test fun saving_an_untouched_sheet_changes_nothing() {
        showSheet(weekly)
        save()
        assertEquals("Feed the sourdough", savedName)
        assertEquals(weekly.dueMillis, savedDue)
        assertEquals(Repeat.WEEKLY, savedRepeat)
    }

    @Test fun a_task_whose_moment_has_gone_says_so() {
        showSheet(overdue)
        dueLabel().assertTextEquals("Was due Today, 9:00 AM")
    }

    /**
     * The DAY wheel only spans a fortnight, so a task due in November has no rung
     * to sit on — but the calendar must still open on its own month rather than on
     * this one, or paging there by hand is the only way to see the date.
     */
    @Test fun the_calendar_opens_on_the_month_the_task_is_due_in() {
        showSheet(oneOff)
        tap("Calendar")
        compose.onNodeWithText("November 2026").assertExists()
        compose.onNodeWithText("July 2026").assertDoesNotExist()
    }

    // ---- editing one field leaves the others alone --------------------------

    @Test fun renaming_keeps_the_time_and_the_repeat() {
        showSheet(weekly)
        compose.onNodeWithTag("name-field").performTextClearance()
        compose.onNodeWithTag("name-field").performTextInput("Feed the starter")
        save()
        assertEquals("Feed the starter", savedName)
        assertEquals(weekly.dueMillis, savedDue)
        assertEquals(Repeat.WEEKLY, savedRepeat)
    }

    @Test fun retiming_keeps_the_name_and_the_repeat() {
        showSheet(weekly)
        // The wheels work from the task's own due time, so only the hour moves.
        tapWheel("HOUR", 21)
        dueLabel().assertTextEquals("Wed, 9:00 PM")
        save()
        assertEquals("Feed the sourdough", savedName)
        assertEquals(at(2026, Calendar.JULY, 29, 21), savedDue)
        assertEquals(Repeat.WEEKLY, savedRepeat)
    }

    @Test fun the_repeat_rule_can_be_changed_and_dropped() {
        showSheet(weekly)
        tap("Once")
        save()
        assertEquals(Repeat.ONCE, savedRepeat)
        assertEquals(weekly.dueMillis, savedDue)
    }

    @Test fun a_one_off_can_be_made_to_repeat() {
        showSheet(oneOff)
        tap("Monthly")
        save()
        assertEquals(Repeat.MONTHLY, savedRepeat)
    }

    @Test fun the_edited_name_is_trimmed() {
        showSheet(weekly)
        compose.onNodeWithTag("name-field").performTextClearance()
        compose.onNodeWithTag("name-field").performTextInput("   Feed the starter   ")
        save()
        assertEquals("Feed the starter", savedName)
    }

    // ---- gating -------------------------------------------------------------

    @Test fun emptying_the_name_blocks_the_save() {
        showSheet(weekly)
        compose.onNodeWithTag("save-button").assertHasClickAction()
        compose.onNodeWithTag("name-field").performTextClearance()
        compose.onNodeWithTag("save-button").assertHasNoClickAction()
    }

    @Test fun a_blanked_name_can_be_typed_back() {
        showSheet(weekly)
        compose.onNodeWithTag("name-field").performTextClearance()
        compose.onNodeWithTag("name-field").performTextInput("Anything")
        compose.onNodeWithTag("save-button").assertHasClickAction()
    }

    // ---- the action rows ----------------------------------------------------

    /**
     * Ticking off lives on the list's check circle and nowhere else. Offering it
     * here as well raised the question of whether it saved the draft on the way.
     */
    private fun assertNoToggleRow() {
        compose.onNodeWithTag("action-toggle").assertDoesNotExist()
        compose.onNodeWithText("Mark as done").assertDoesNotExist()
        compose.onNodeWithText("Mark as not done").assertDoesNotExist()
        compose.onNodeWithText("Done for now").assertDoesNotExist()
    }

    @Test fun a_one_off_has_no_mark_as_done_row() {
        showSheet(oneOff)
        assertNoToggleRow()
    }

    @Test fun a_repeater_has_no_done_for_now_row() {
        showSheet(weekly)
        assertNoToggleRow()
    }

    @Test fun a_finished_task_has_no_mark_as_not_done_row() {
        showSheet(finished)
        assertNoToggleRow()
    }

    /**
     * Delete is a repeater's *only* way out: ticking one off rolls it forward, so
     * it never reaches the done list that CLEAR empties.
     */
    @Test fun a_repeater_can_be_deleted() {
        showSheet(weekly)
        compose.onNodeWithTag("action-delete").assertExists()
        compose.onNodeWithText("Stops it repeating").assertExists()
    }

    /** A one-off has another exit — tick it off, then CLEAR — so it is not offered. */
    @Test fun a_one_off_has_no_delete_row() {
        showSheet(oneOff)
        compose.onNodeWithTag("action-delete").assertDoesNotExist()
    }

    @Test fun a_finished_task_has_no_delete_row_either() {
        showSheet(finished)
        compose.onNodeWithTag("action-delete").assertDoesNotExist()
    }

    /** A done task is still editable — a typo outlives the task being finished. */
    @Test fun a_done_task_can_still_be_renamed_and_retimed() {
        showSheet(finished)
        compose.onNodeWithTag("name-field").assertTextEquals("Book dentist")
        compose.onNodeWithText("DAY").assertExists()
        compose.onNodeWithTag("save-button").assertHasClickAction()
    }

    @Test fun the_delete_row_reports() {
        showSheet(weekly)
        tapTag("action-delete")
        assertTrue(deleted)
        assertFalse("it must not save the draft as well", savedName != null)
    }

    /** Delete acts now, so unsaved edits above it are dropped, not applied. */
    @Test fun deleting_does_not_commit_the_draft() {
        showSheet(weekly)
        compose.onNodeWithTag("name-field").performTextClearance()
        compose.onNodeWithTag("name-field").performTextInput("Half-typed name")
        tapTag("action-delete")
        assertTrue(deleted)
        assertEquals(null, savedName)
    }

    // ---- dismissal ----------------------------------------------------------

    @Test fun the_close_button_dismisses_without_saving() {
        showSheet(weekly)
        tapCd("Close")
        assertTrue(dismissed)
        assertEquals(null, savedName)
    }

    @Test fun tapping_the_scrim_dismisses_without_saving() {
        showSheet(weekly)
        tapTag("sheet-scrim")
        assertTrue(dismissed)
        assertEquals(null, savedName)
    }
}
