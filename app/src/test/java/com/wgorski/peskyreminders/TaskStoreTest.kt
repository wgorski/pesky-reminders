package com.wgorski.peskyreminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** How repeat rules reach disk and come back — including lists saved before rules existed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        TaskStore.clear(context)
    }

    @After fun tearDown() {
        TaskStore.clear(context)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Drop the in-memory list, so the next read has to come off disk. */
    private fun restart() = TaskStore.forgetForTest()

    @Test fun every_shape_of_rule_survives_a_restart() {
        val due = at(2026, Calendar.JULY, 27)
        val rules = listOf(
            Repeat.ONCE,
            Repeat.DAILY,
            Repeat(RepeatUnit.DAY, 3),
            Repeat(RepeatUnit.WEEK, 2, weekdays = setOf(Calendar.MONDAY, Calendar.WEDNESDAY)),
            Repeat(RepeatUnit.MONTH, monthDay = Repeat.LAST),
            Repeat(RepeatUnit.MONTH, 3, monthDay = 15),
            Repeat(RepeatUnit.MONTH, ordinal = Repeat.LAST, weekday = Calendar.FRIDAY),
            Repeat(RepeatUnit.YEAR, 2),
        )
        val ids = rules.map { TaskStore.add(context, "t", due, it).id }
        restart()
        rules.zip(ids).forEach { (rule, id) -> assertEquals(rule, TaskStore.find(context, id)!!.repeat) }
    }

    @Test fun a_preset_is_stored_bound_to_the_date_it_was_set_on() {
        val jan31 = at(2026, Calendar.JANUARY, 31)
        val monthly = TaskStore.add(context, "Rent", jan31, Repeat.MONTHLY).id
        val weekly = TaskStore.add(context, "Bins", jan31, Repeat.WEEKLY).id
        restart()
        assertEquals(Repeat(RepeatUnit.MONTH, monthDay = 31), TaskStore.find(context, monthly)!!.repeat)
        assertEquals(
            Repeat(RepeatUnit.WEEK, weekdays = setOf(Calendar.SATURDAY)),
            TaskStore.find(context, weekly)!!.repeat,
        )
    }

    /**
     * A list written by the previous build has a `"repeat"` label and no `"rule"`.
     * It must load as the same preset, fixed to the day it has always fired on —
     * the slot, which for a snoozed task is its anchor rather than its due time.
     */
    @Test fun a_list_saved_before_rules_existed_loads_unchanged() {
        val monday = at(2026, Calendar.JULY, 27)
        val snoozedTo = at(2026, Calendar.JULY, 28, 10)
        val legacy = JSONArray()
            .put(JSONObject().put("id", 1).put("name", "Bins").put("due", monday).put("repeat", "Weekly").put("done", false))
            .put(
                JSONObject().put("id", 2).put("name", "Rent").put("due", snoozedTo).put("repeat", "Monthly")
                    .put("done", false).put("anchor", at(2026, Calendar.JULY, 15)),
            )
            .put(JSONObject().put("id", 3).put("name", "Milk").put("due", monday).put("repeat", "Once").put("done", true))
        context.getSharedPreferences("pesky_tasks", Context.MODE_PRIVATE).edit()
            .putString("tasks", legacy.toString()).putInt("next_id", 4).commit()
        restart()

        assertEquals(Repeat(RepeatUnit.WEEK, weekdays = setOf(Calendar.MONDAY)), TaskStore.find(context, 1)!!.repeat)
        assertEquals(Repeat(RepeatUnit.MONTH, monthDay = 15), TaskStore.find(context, 2)!!.repeat)
        assertEquals(Repeat.ONCE, TaskStore.find(context, 3)!!.repeat)
    }

    @Test fun the_label_on_disk_stays_readable_by_an_older_build() {
        val due = at(2026, Calendar.JULY, 27)
        TaskStore.add(context, "Bins", due, Repeat.WEEKLY)
        TaskStore.add(context, "Stand-up", due, Repeat(RepeatUnit.WEEK, weekdays = setOf(Calendar.MONDAY, Calendar.FRIDAY)))
        val onDisk = JSONArray(
            context.getSharedPreferences("pesky_tasks", Context.MODE_PRIVATE).getString("tasks", null),
        )
        assertEquals("Weekly", onDisk.getJSONObject(0).getString("repeat"))
        assertEquals("Custom", onDisk.getJSONObject(1).getString("repeat"))
    }
}
