package com.wgorski.peskyreminders

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * The task list, persisted as JSON in SharedPreferences.
 *
 * A process-wide singleton on purpose: [ReminderReceiver] handles the Snooze and
 * Done actions in the same process as the UI, so both sides must see the same
 * list. Reads lazily hydrate from disk in case the process was started by the
 * broadcast rather than by the launcher.
 */
object TaskStore {

    private const val PREFS = "pesky_tasks"
    private const val KEY_TASKS = "tasks"
    private const val KEY_NEXT_ID = "next_id"

    /** Observed by Compose; written from the UI and from the broadcast receiver. */
    var tasks by mutableStateOf(emptyList<Task>())
        private set

    private var hydrated = false

    @Synchronized
    fun hydrate(context: Context) {
        if (hydrated) return
        hydrated = true
        tasks = parse(prefs(context).getString(KEY_TASKS, null))
    }

    fun find(context: Context, id: Int): Task? {
        hydrate(context)
        return tasks.firstOrNull { it.id == id }
    }

    fun add(context: Context, name: String, dueMillis: Long, repeat: Repeat): Task {
        hydrate(context)
        val id = prefs(context).getInt(KEY_NEXT_ID, 1)
        prefs(context).edit().putInt(KEY_NEXT_ID, id + 1).apply()
        // Bound here, at the store's door, so a preset remembers the weekday or
        // day of the month it was set on — see [Repeat].
        val task = Task(id, name, dueMillis, Recurrence.bindTo(repeat, dueMillis))
        commit(context, tasks + task)
        return task
    }

    fun replace(context: Context, task: Task) {
        hydrate(context)
        commit(context, tasks.map { if (it.id == task.id) task else it })
    }

    fun remove(context: Context, id: Int) {
        hydrate(context)
        commit(context, tasks.filterNot { it.id == id })
    }

    /** Drop several at once — one write and one recomposition, not one per task. */
    fun removeAll(context: Context, ids: Set<Int>) {
        hydrate(context)
        if (ids.isEmpty()) return
        commit(context, tasks.filterNot { it.id in ids })
    }

    /** Test seam — drops everything, on disk and in memory. */
    fun clear(context: Context) {
        hydrated = true
        prefs(context).edit().clear().apply()
        tasks = emptyList()
    }

    /** Test seam — drops the in-memory copy so the next read comes off disk. */
    fun forgetForTest() {
        hydrated = false
        tasks = emptyList()
    }

    private fun commit(context: Context, next: List<Task>) {
        tasks = next
        prefs(context).edit().putString(KEY_TASKS, serialise(next)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun serialise(tasks: List<Task>): String {
        val array = JSONArray()
        tasks.forEach {
            val o = JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("due", it.dueMillis)
                // The preset's name, or "Custom" — readable, and all an older
                // build understands. The rule itself rides beside it.
                .put("repeat", Recurrence.normalise(it.repeat, it.slotMillis).label)
                .put("done", it.done)
            if (it.repeat.repeats) o.put("rule", ruleJson(it.repeat))
            // Left out entirely when unset, so a task that has never been snoozed
            // serialises exactly as it did before the field existed.
            it.anchorMillis?.let { anchor -> o.put("anchor", anchor) }
            array.put(o)
        }
        return array.toString()
    }

    private fun parse(json: String?): List<Task> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val due = o.optLong("due")
            // has() rather than optLong's 0 default: a missing anchor means
            // "never snoozed", not "the epoch".
            val anchor = if (o.has("anchor")) o.optLong("anchor") else null
            // No "rule" is a task saved before rules existed: take the preset
            // from its label and bind it to its slot, so a weekly task keeps the
            // weekday it has always fired on.
            val repeat = o.optJSONObject("rule")?.let(::parseRule)
                ?: Recurrence.bindTo(Repeat.fromLabel(o.optString("repeat")), anchor ?: due)
            Task(
                id = o.optInt("id"),
                name = o.optString("name"),
                dueMillis = due,
                repeat = repeat,
                done = o.optBoolean("done"),
                anchorMillis = anchor,
            )
        }
    }

    private fun ruleJson(r: Repeat): JSONObject = JSONObject()
        .put("unit", r.unit?.name)
        .put("every", r.every)
        .put("days", JSONArray(r.weekdays.sorted()))
        .apply {
            r.monthDay?.let { put("monthDay", it) }
            r.ordinal?.let { put("ordinal", it) }
            r.weekday?.let { put("weekday", it) }
        }

    private fun parseRule(o: JSONObject): Repeat? {
        val unit = RepeatUnit.entries.firstOrNull { it.name == o.optString("unit") } ?: return null
        val days = o.optJSONArray("days")
        return Repeat(
            unit = unit,
            every = o.optInt("every", 1).coerceAtLeast(1),
            weekdays = if (days == null) emptySet() else (0 until days.length()).map { days.optInt(it) }.toSet(),
            monthDay = if (o.has("monthDay")) o.optInt("monthDay") else null,
            ordinal = if (o.has("ordinal")) o.optInt("ordinal") else null,
            weekday = if (o.has("weekday")) o.optInt("weekday") else null,
        )
    }
}
