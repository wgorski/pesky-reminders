package com.peskyreminders.poc.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peskyreminders.poc.Repeat
import com.peskyreminders.poc.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

/** Drives the list screen against a frozen clock, on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TaskListScreenTest {

    @get:Rule val compose = createComposeRule()

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Saturday 25 July 2026, 14:20 UTC. */
    private val now: Long
        get() = at(2026, Calendar.JULY, 25, 14, 20)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val toggled = mutableListOf<Int>()
    private val actioned = mutableListOf<Int>()
    private var addTapped = false
    private var sectionToggled = 0
    private var clearTapped = 0

    private fun show(tasks: List<Task>, doneExpanded: Boolean = false) {
        compose.setContent {
            TaskListScreen(
                tasks = tasks,
                nowMillis = now,
                use24h = false,
                doneExpanded = doneExpanded,
                onToggleDoneSection = { sectionToggled++ },
                onToggleTask = { toggled += it },
                onAdd = { addTapped = true },
                onTaskActions = { actioned += it },
                onClearDone = { clearTapped++ },
            )
        }
    }

    private val overdueTask =
        Task(1, "Call the vet", at(2026, Calendar.JULY, 25, 9), Repeat.ONCE)
    private val upNextTask =
        Task(2, "Feed the sourdough", at(2026, Calendar.JULY, 29, 9), Repeat.WEEKLY)
    private val doneTask =
        Task(3, "Book dentist", at(2026, Calendar.JULY, 24, 9), Repeat.ONCE, done = true)

    // ---- rendering ----------------------------------------------------------

    @Test fun the_header_carries_no_date_stamp() {
        show(emptyList())
        compose.onNodeWithText("SAT 25 JUL").assertDoesNotExist()
    }

    @Test fun the_header_is_the_wordmark_and_the_settings_button() {
        show(emptyList())
        compose.onNodeWithText("Pesky.").assertExists()
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test fun an_empty_list_shows_the_empty_state() {
        show(emptyList())
        compose.onNodeWithText("Nothing to pester you about").assertExists()
        compose.onNodeWithText("OVERDUE").assertDoesNotExist()
        compose.onNodeWithText("UP NEXT").assertDoesNotExist()
    }

    @Test fun tasks_split_by_whether_they_are_late() {
        show(listOf(overdueTask, upNextTask))

        compose.onNodeWithText("OVERDUE").assertExists()
        compose.onNodeWithText("Call the vet").assertExists()
        compose.onNodeWithText("Was due Today, 9:00 AM").assertExists()

        compose.onNodeWithText("UP NEXT").assertExists()
        compose.onNodeWithText("Feed the sourdough").assertExists()
        compose.onNodeWithText("Wed, 9:00 AM").assertExists()

        compose.onNodeWithText("Nothing to pester you about").assertDoesNotExist()
    }

    @Test fun only_repeating_tasks_carry_a_repeat_pill() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithText("Weekly").assertExists()
        compose.onNodeWithText("Once").assertDoesNotExist()
    }

    @Test fun the_done_section_counts_but_hides_its_rows_until_expanded() {
        show(listOf(upNextTask, doneTask))
        compose.onNodeWithText("DONE (1)").assertExists()
        compose.onNodeWithText("Book dentist").assertDoesNotExist()
    }

    @Test fun an_expanded_done_section_lists_its_rows() {
        show(listOf(upNextTask, doneTask), doneExpanded = true)
        compose.onNodeWithText("Book dentist").assertExists()
    }

    // ---- interaction --------------------------------------------------------

    @Test fun the_done_header_toggles_the_section() {
        show(listOf(doneTask))
        compose.onNodeWithText("DONE (1)").performClick()
        assertEquals(1, sectionToggled)
    }

    @Test fun ticking_an_active_task_reports_its_id() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("check-1").performClick()
        compose.onNodeWithTag("check-2").performClick()
        assertEquals(listOf(1, 2), toggled)
    }

    @Test fun un_ticking_a_done_task_reports_its_id_too() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("check-3").performClick()
        assertEquals(listOf(3), toggled)
    }

    @Test fun the_add_button_reports() {
        show(emptyList())
        compose.onNodeWithContentDescription("Add a task").performClick()
        assertTrue(addTapped)
    }

    // ---- clearing the done list ---------------------------------------------

    @Test fun there_is_nothing_to_clear_while_the_done_section_is_shut() {
        show(listOf(upNextTask, doneTask))
        compose.onNodeWithTag("done-clear").assertDoesNotExist()
    }

    @Test fun opening_the_done_section_offers_to_clear_it() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("done-clear").assertIsDisplayed()
    }

    @Test fun clearing_reports_it() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("done-clear").performClick()
        assertEquals(1, clearTapped)
    }

    /**
     * The regression that a clickable ancestor would cause: wrapping the header
     * row in one merges "CLEAR" into the expand toggle, and every tap on it
     * would collapse the section instead of clearing — or do both.
     */
    @Test fun clearing_does_not_also_collapse_the_section() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("done-clear").performClick()
        assertEquals("CLEAR must not reach the expand toggle", 0, sectionToggled)
    }

    @Test fun the_header_still_toggles_while_the_clear_label_shares_its_row() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithText("DONE (1)").performClick()
        assertEquals(1, sectionToggled)
        assertEquals("collapsing must not clear anything", 0, clearTapped)
    }

    // ---- long press ---------------------------------------------------------

    @Test fun long_pressing_a_row_asks_for_its_actions() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("row-1").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithTag("row-2").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(1, 2), actioned)
    }

    @Test fun a_done_row_can_be_long_pressed_too() {
        show(listOf(doneTask), doneExpanded = true)
        compose.onNodeWithTag("row-3").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf(3), actioned)
    }

    @Test fun a_plain_tap_on_a_row_does_nothing() {
        show(listOf(overdueTask))
        compose.onNodeWithTag("row-1").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("tap is reserved; only long-press opens the menu", actioned.isEmpty())
        assertTrue("and it must not tick the task off either", toggled.isEmpty())
    }

    @Test fun the_long_press_is_announced_to_screen_readers() {
        show(listOf(overdueTask))
        val label = compose.onNodeWithTag("row-1").fetchSemanticsNode()
            .config[SemanticsActions.OnLongClick].label
        assertEquals("Task actions", label)
    }

    // ---- ordering -----------------------------------------------------------

    @Test fun active_tasks_are_ordered_by_when_they_are_due() {
        val later = Task(4, "Later", at(2026, Calendar.JULY, 30, 9), Repeat.ONCE)
        val sooner = Task(5, "Sooner", at(2026, Calendar.JULY, 26, 9), Repeat.ONCE)
        show(listOf(later, sooner))

        val soonerTop = compose.onNodeWithText("Sooner").fetchSemanticsNode().positionInRoot.y
        val laterTop = compose.onNodeWithText("Later").fetchSemanticsNode().positionInRoot.y
        assertTrue("the sooner task must sit above the later one", soonerTop < laterTop)
    }
}
