# Action Toasts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every snooze and every done raises a toast naming what happened, on all four surfaces that can perform one.

**Architecture:** `Reminders` already returns `ToggleOutcome` so a call site can explain a refused tick without re-deriving the rule. Widen that seam: split `CHANGED` into `COMPLETED`/`REOPENED`/`ADVANCED`, give `snooze`/`snoozeUntil` a `SnoozeOutcome`, and put every toast string in one pure object (`ActionToast`) that the four surfaces call. No decision about *what happened* moves out of `Reminders`; no decision about *how it reads* lands anywhere but `ActionToast`.

**Tech Stack:** Kotlin, Jetpack Compose, `android.widget.Toast`, JUnit4 (JVM, no Robolectric needed for the new tests).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-28-action-toasts-design.md`.
- `versionName` → `0.17.0`, `versionCode` → `23`. Once, at the end. `0.16.0` is already claimed by the uncommitted Play-prep work in the tree.
- Toast strings, verbatim: `Done.` / `Done — next <compact>.` / `Back on the list.` / `Snoozed until <compact>.` / `<compact> has passed — still due.` / `Not due until <full>.` The dashes are em dashes (`—`), not hyphens.
- `<compact>` is `TaskTime.formatCompact`; `<full>` is `TaskTime.formatFull`. No new formatting code — a toast must not be able to invent a time format.
- `ActionToast`'s two string builders take `nowMillis` as a parameter and never read the clock, the same constraint that keeps `TaskTime` and `SnoozeOptions` unit-testable.
- `Toast` is built with `context.applicationContext` — a statically-held `Toast` must not retain an Activity, and it has to outlive `ReminderActivity.finishAndRemoveTask()`.
- Every new toast cancels the previous one before showing.
- No toast on `ACTION_FIRE`, `ACTION_REPOST` or `ACTION_NAG`, and none for `MISSING`.
- JVM suite must stay green: `./gradlew :app:testDebugUnitTest`. It also runs automatically after every `.kt` edit via the `PostToolUse` hook.

---

### Task 1: The outcomes `Reminders` reports

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/Reminders.kt` — `ToggleOutcome` (lines 12-21), `toggle` (142-169), `snooze` (293-304), `snoozeUntil` (323-348)
- Modify: `app/src/androidTest/java/com/wgorski/peskyreminders/ReminderModelTest.kt:790-816`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class ToggleOutcome { COMPLETED, REOPENED, ADVANCED, NOT_DUE_YET, MISSING }`; `enum class SnoozeOutcome { MOVED, ALREADY_PAST, MISSING }`; `Reminders.snooze(Context, Int, Int): SnoozeOutcome`; `Reminders.snoozeUntil(Context, Int, Long): SnoozeOutcome`; `Reminders.toggle(Context, Int): ToggleOutcome` (unchanged signature, new members).

- [ ] **Step 1: Replace `ToggleOutcome.CHANGED` with the three branches it hid**

In `Reminders.kt`, `CHANGED` becomes:

```kotlin
    /** A one-off ticked off. It is in the done list now. */
    COMPLETED,

    /** A done task un-ticked, back among the active ones. */
    REOPENED,

    /**
     * A repeater rolled on to its next occurrence. Never "done" — see [Reminders.toggle].
     *
     * Distinct from [REOPENED] because the two are not distinguishable from the
     * outside: a done one-off can be edited into a repeater (the editor is
     * reachable from a done row and `update` carries `done` through), so
     * `done && repeats` is a real state, and un-ticking it takes the reopen
     * branch while looking exactly like a roll-forward.
     */
    ADVANCED,
```

- [ ] **Step 2: Return the specific outcome from `toggle`**

Name the branch, so the return cannot drift from the work:

```kotlin
        val advancing = !task.done && task.repeats
        val next = if (advancing) {
            task.copy(
                dueMillis = TaskTime.nextOccurrence(task.slotMillis, task.repeat, now),
                anchorMillis = null,
            )
        } else {
            task.copy(done = !task.done, anchorMillis = null)
        }
        TaskStore.replace(context, next)

        // Nothing to fire for a completed task, or for one that is already late:
        // an alarm in the past would go off the instant it is set.
        if (next.done || next.dueMillis <= now) ReminderScheduler.cancel(context, taskId)
        else ReminderScheduler.schedule(context, next)

        return when {
            advancing -> ToggleOutcome.ADVANCED
            next.done -> ToggleOutcome.COMPLETED
            else -> ToggleOutcome.REOPENED
        }
```

- [ ] **Step 3: Add `SnoozeOutcome` beside `ToggleOutcome`**

```kotlin
/**
 * What a snooze did.
 *
 * [ALREADY_PAST] is [Reminders.snoozeUntil]'s alone: the sheet can sit open across
 * the very rung it is offering, and the task is then left overdue and pestering
 * rather than pushed. [Reminders.snooze] always lands in the future by
 * construction, so it can only ever report [MOVED] or [MISSING].
 */
enum class SnoozeOutcome {
    /** Pushed. The task's `dueMillis` is where it landed. */
    MOVED,

    /** The target had gone by. Still overdue, notification still up. */
    ALREADY_PAST,

    /** No such task — deleted from under the tap. */
    MISSING,
}
```

- [ ] **Step 4: Return it from both snooze paths**

`snooze`: signature becomes `): SnoozeOutcome {`, the early return becomes
`?: return SnoozeOutcome.MISSING`, and the body ends with:

```kotlin
        ReminderScheduler.schedule(context, next)
        return SnoozeOutcome.MOVED
```

`snoozeUntil`: signature becomes `): SnoozeOutcome {`, the early return becomes
`?: return SnoozeOutcome.MISSING`, and the tail becomes:

```kotlin
        return if (atMillis > now) {
            ReminderNotifier.cancel(context, taskId)
            ReminderScheduler.schedule(context, next)
            SnoozeOutcome.MOVED
        } else {
            // The sheet sat open across the very rung it was offering. Never arm
            // setAlarmClock in the past — it fires at once. Take the line
            // `create` takes for a past due time instead: pester me now, so the
            // reminder stays overdue with its notification live.
            ReminderScheduler.cancel(context, taskId)
            if (showing) notify(context, taskId)
            SnoozeOutcome.ALREADY_PAST
        }
```

- [ ] **Step 5: Update the four instrumented assertions**

`ReminderModelTest.kt` around 790-816 has four `assertEquals(ToggleOutcome.CHANGED, …)`.
Read each test and replace `CHANGED` with the outcome that call actually produces —
a repeater tick is `ADVANCED`, a one-off tick is `COMPLETED`, an un-tick is `REOPENED`.
These four lines are what pin the split; do not blanket-replace them with one value.

- [ ] **Step 6: Compile and run the JVM suite**

Run: `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools && export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH" && ./gradlew :app:testDebugUnitTest`
Expected: PASS, 188 tests. `PeskyApp.kt:80` compares against `NOT_DUE_YET`, which survives the split untouched.

---

### Task 2: `ActionToast`'s strings

**Files:**
- Create: `app/src/main/java/com/wgorski/peskyreminders/ActionToast.kt`
- Test: `app/src/test/java/com/wgorski/peskyreminders/ActionToastTest.kt`

**Interfaces:**
- Consumes: `ToggleOutcome`, `SnoozeOutcome` from Task 1; `Task`; `TaskTime.formatCompact`, `TaskTime.formatFull`.
- Produces: `ActionToast.forToggle(ToggleOutcome, Task?, Long, Boolean): String?` and `ActionToast.forSnooze(SnoozeOutcome, Task?, Long, Boolean): String?`.

- [ ] **Step 1: Write the failing tests**

`ActionToastTest.kt`, in full:

```kotlin
package com.wgorski.peskyreminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * What the app says after a snooze or a done.
 *
 * Pure string building — no clock, no context. Every landing time goes through
 * [TaskTime], so these also pin that a toast cannot invent a format.
 */
class ActionToastTest {

    @Before fun fixTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JULY, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val now = at(28, 9, 5)

    private fun task(dueMillis: Long, repeat: Repeat = Repeat.ONCE, done: Boolean = false) =
        Task(id = 1, name = "Bins", dueMillis = dueMillis, repeat = repeat, done = done)

    // ---- done ---------------------------------------------------------------

    @Test fun a_completed_one_off_says_only_that() {
        assertEquals(
            "Done.",
            ActionToast.forToggle(ToggleOutcome.COMPLETED, task(at(28, 9), done = true), now, false),
        )
    }

    @Test fun reopening_says_where_it_went() {
        assertEquals(
            "Back on the list.",
            ActionToast.forToggle(ToggleOutcome.REOPENED, task(at(28, 9)), now, false),
        )
    }

    /** The most opaque thing the app does: the row moves and the new time is never named. */
    @Test fun an_advanced_repeater_names_its_next_occurrence() {
        assertEquals(
            "Done — next Tomorrow 9:00 AM.",
            ActionToast.forToggle(
                ToggleOutcome.ADVANCED, task(at(29, 9), Repeat.DAILY), now, false,
            ),
        )
    }

    @Test fun a_refused_tick_keeps_the_wording_it_already_had() {
        assertEquals(
            "Not due until Tomorrow, 8:00 AM.",
            ActionToast.forToggle(
                ToggleOutcome.NOT_DUE_YET, task(at(29, 8), Repeat.DAILY), now, false,
            ),
        )
    }

    @Test fun a_task_deleted_from_under_the_tap_says_nothing() {
        assertNull(ActionToast.forToggle(ToggleOutcome.MISSING, null, now, false))
        assertNull(ActionToast.forSnooze(SnoozeOutcome.MISSING, null, now, false))
    }

    // ---- snooze -------------------------------------------------------------

    /** Same day, so the day is redundant — this is why it is formatCompact. */
    @Test fun a_snooze_landing_today_names_only_the_time() {
        assertEquals(
            "Snoozed until 3:45 PM.",
            ActionToast.forSnooze(SnoozeOutcome.MOVED, task(at(28, 15, 45)), now, false),
        )
    }

    @Test fun a_snooze_landing_tomorrow_names_the_day_too() {
        assertEquals(
            "Snoozed until Tomorrow 8:00 AM.",
            ActionToast.forSnooze(SnoozeOutcome.MOVED, task(at(29, 8)), now, false),
        )
    }

    /**
     * The sheet sat open across the rung it was offering. Reporting "snoozed
     * until 8:00 AM" there would be a straight lie — the task is still overdue.
     */
    @Test fun a_target_that_has_gone_by_says_so_instead() {
        assertEquals(
            "8:00 AM has passed — still due.",
            ActionToast.forSnooze(SnoozeOutcome.ALREADY_PAST, task(at(28, 8)), now, false),
        )
    }

    // ---- the clock the user set ---------------------------------------------

    @Test fun a_24_hour_device_gets_24_hour_times() {
        assertEquals(
            "Snoozed until 15:45.",
            ActionToast.forSnooze(SnoozeOutcome.MOVED, task(at(28, 15, 45)), now, true),
        )
        assertEquals(
            "Done — next Tomorrow 09:00.",
            ActionToast.forToggle(
                ToggleOutcome.ADVANCED, task(at(29, 9), Repeat.DAILY), now, true,
            ),
        )
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools && export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH" && ./gradlew :app:testDebugUnitTest --tests '*ActionToastTest'`
Expected: FAIL to compile — `Unresolved reference: ActionToast`.

- [ ] **Step 3: Write the builders**

`ActionToast.kt`:

```kotlin
package com.wgorski.peskyreminders

import android.content.Context
import android.widget.Toast

/**
 * What the app says after a snooze or a done, and the only place it says it.
 *
 * Four surfaces can perform one of those actions — the list's check circle, the
 * action panel raised from an overdue row, the same panel raised from the
 * notification, and the notification's own Done button. Every one of them routes
 * through here, so none of them can word the same event differently.
 *
 * The two builders are pure: `nowMillis` is a parameter rather than a clock read,
 * which is what makes every string in the app assertable from a plain JVM test.
 * They return null for the cases that should stay silent, so "say nothing" is one
 * decision here instead of four at the call sites.
 *
 * Times come from [TaskTime] and nowhere else. Landing times use
 * [TaskTime.formatCompact], which drops the day when it is today — a snooze
 * usually lands within the hour, and "Snoozed until Today, 3:45 PM" is two words
 * too many. [ToggleOutcome.NOT_DUE_YET] keeps [TaskTime.formatFull] and its comma,
 * because it names a slot on a named day, where the day is never redundant.
 */
object ActionToast {

    /** The message for a tick, or null if there is nothing worth saying. */
    fun forToggle(
        outcome: ToggleOutcome,
        task: Task?,
        nowMillis: Long,
        use24h: Boolean,
    ): String? = when (outcome) {
        ToggleOutcome.COMPLETED -> "Done."
        ToggleOutcome.REOPENED -> "Back on the list."
        ToggleOutcome.ADVANCED -> task?.let {
            "Done — next " + TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + "."
        }
        ToggleOutcome.NOT_DUE_YET -> task?.let {
            "Not due until " + TaskTime.formatFull(it.dueMillis, nowMillis, use24h) + "."
        }
        ToggleOutcome.MISSING -> null
    }

    /** The message for a snooze, or null if there is nothing worth saying. */
    fun forSnooze(
        outcome: SnoozeOutcome,
        task: Task?,
        nowMillis: Long,
        use24h: Boolean,
    ): String? = when (outcome) {
        SnoozeOutcome.MOVED -> task?.let {
            "Snoozed until " + TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + "."
        }
        // Never "snoozed until": the task did not move, and saying it did would be
        // the one case where the toast contradicts what the app actually did.
        SnoozeOutcome.ALREADY_PAST -> task?.let {
            TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + " has passed — still due."
        }
        SnoozeOutcome.MISSING -> null
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ActionToastTest'` (with the env exports)
Expected: PASS, 10 tests.

---

### Task 3: Show it, on all four surfaces

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ActionToast.kt` — add the impure half
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt:76-91` and `117-149`
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ReminderActivity.kt:44-76`
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ReminderReceiver.kt:14-19`

**Interfaces:**
- Consumes: `forToggle` / `forSnooze` from Task 2; `TaskStore.find(Context, Int): Task?`.
- Produces: `ActionToast.toggled(Context, ToggleOutcome, Int, Long, Boolean)` and `ActionToast.snoozed(Context, SnoozeOutcome, Int, Long, Boolean)` — one call per surface, store lookup included.

- [ ] **Step 1: Add `show` and the two one-liners**

Append inside `object ActionToast`:

```kotlin
    /**
     * The last toast shown, so the next one can cancel it.
     *
     * Ticking five rows off in a row would otherwise queue five toasts and replay
     * them for ten seconds. Only the newest action is worth reporting.
     *
     * Held statically, which is safe only because [show] builds with the
     * application context — a `Toast` made from an Activity would leak it.
     */
    private var current: Toast? = null

    /**
     * Post [message], replacing anything already on screen.
     *
     * The application context is load-bearing twice over: it keeps [current] from
     * retaining an Activity, and it lets the toast outlive
     * `ReminderActivity.finishAndRemoveTask()`, which fires the instant the sheet
     * commits.
     *
     * Main thread only. Every caller is already there — Compose callbacks by
     * definition, `BroadcastReceiver.onReceive` by default.
     */
    fun show(context: Context, message: String) {
        current?.cancel()
        current = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
            .also { it.show() }
    }

    /**
     * Report what a tick did to task [taskId].
     *
     * The task is re-read rather than passed in, because the interesting field is
     * the one the action just wrote: `toggle` puts the next occurrence in
     * `dueMillis`, and both `snoozeUntil` branches store their target there. That
     * is reading state, not re-deriving the rule — the rule stayed in [Reminders].
     */
    fun toggled(
        context: Context,
        outcome: ToggleOutcome,
        taskId: Int,
        nowMillis: Long,
        use24h: Boolean,
    ) {
        forToggle(outcome, TaskStore.find(context, taskId), nowMillis, use24h)
            ?.let { show(context, it) }
    }

    /** Report what a snooze did to task [taskId]. See [toggled]. */
    fun snoozed(
        context: Context,
        outcome: SnoozeOutcome,
        taskId: Int,
        nowMillis: Long,
        use24h: Boolean,
    ) {
        forSnooze(outcome, TaskStore.find(context, taskId), nowMillis, use24h)
            ?.let { show(context, it) }
    }
```

- [ ] **Step 2: Route `PeskyApp`'s check circle through it**

Replace the whole `onToggleTask` lambda (`PeskyApp.kt:76-91`) with:

```kotlin
                onToggleTask = { id ->
                    // Every outcome has something to say, including the refusal: a
                    // repeater that is not due yet declines the tick on purpose,
                    // and without a word the circle is a control that visibly does
                    // nothing. ActionToast decides which.
                    val outcome = Reminders.toggle(context, id)
                    now = System.currentTimeMillis()
                    ActionToast.toggled(context, outcome, id, now, use24h)
                },
```

Then drop the now-unused imports `android.widget.Toast`, `com.wgorski.peskyreminders.TaskTime` and `com.wgorski.peskyreminders.ToggleOutcome`, and add
`com.wgorski.peskyreminders.ActionToast`. Let the compiler tell you which are genuinely
unused rather than guessing.

- [ ] **Step 3: Route the in-app action panel through it**

In the `remindTaskId?.let` block, the three callbacks become:

```kotlin
                        onDone = {
                            // toggle cannot refuse here: the row is overdue, so
                            // its slot has passed. Same argument as the
                            // notification's own Done.
                            val outcome = Reminders.toggle(context, id)
                            now = System.currentTimeMillis()
                            ActionToast.toggled(context, outcome, id, now, use24h)
                            remindTaskId = null
                        },
                        onSnooze = { minutes ->
                            val outcome = Reminders.snooze(context, id, minutes)
                            // Re-band the row straight away — it has just left
                            // OVERDUE for somewhere in the future. The toast reads
                            // the same refreshed clock, so it cannot promise a
                            // time the row disagrees with.
                            now = System.currentTimeMillis()
                            ActionToast.snoozed(context, outcome, id, now, use24h)
                            remindTaskId = null
                        },
                        onSnoozeUntil = { atMillis ->
                            val outcome = Reminders.snoozeUntil(context, id, atMillis)
                            // Same re-band as above. A target already past is the
                            // one case the row stays in OVERDUE, and re-reading
                            // the clock is what keeps it there correctly — and
                            // what makes the toast say so.
                            now = System.currentTimeMillis()
                            ActionToast.snoozed(context, outcome, id, now, use24h)
                            remindTaskId = null
                        },
```

- [ ] **Step 4: Route `ReminderActivity` through it**

Hoist `use24h` out of the `ReminderSheet` call, since three callbacks now need it, and
build each message before `close()`:

```kotlin
        setContent {
            val id = taskId.intValue
            val task = remember(id) { TaskStore.find(this, id) }
            val use24h = DateFormat.is24HourFormat(this)
            if (task == null) {
                // Nothing to show for this id — e.g. it was deleted out from
                // under us. Close rather than render a blank sheet.
                LaunchedEffect(id) { close() }
                return@setContent
            }
            ReminderSheet(
                taskName = task.name,
                nowMillis = System.currentTimeMillis(),
                use24h = use24h,
                onDismiss = { close() },
                onDone = {
                    // toggle can refuse a repeater whose slot has not come, but
                    // that cannot happen from here: a notification only exists
                    // once the slot has passed, and every snooze cancels it.
                    val outcome = Reminders.toggle(this, id)
                    // Built and posted before close(); the toast survives because
                    // ActionToast.show goes through the application context.
                    ActionToast.toggled(this, outcome, id, System.currentTimeMillis(), use24h)
                    close()
                },
                onSnooze = { minutes ->
                    val outcome = Reminders.snooze(this, id, minutes)
                    ActionToast.snoozed(this, outcome, id, System.currentTimeMillis(), use24h)
                    close()
                },
                onSnoozeUntil = { atMillis ->
                    val outcome = Reminders.snoozeUntil(this, id, atMillis)
                    ActionToast.snoozed(this, outcome, id, System.currentTimeMillis(), use24h)
                    close()
                },
            )
        }
```

Note the KDoc on the class says the sheet's actions report nothing and "there is no
PeskyApp to raise a toast on either" — that sentence is now wrong. Delete it from the
`onDone` comment as shown.

- [ ] **Step 5: Route the notification's Done button through it**

`ReminderReceiver.kt` — add `import android.text.format.DateFormat`, then:

```kotlin
            ReminderContract.ACTION_DONE -> {
                val outcome = Reminders.toggle(context, taskId)
                // The one surface with no composition to inherit the clock format
                // from. FIRE/REPOST/NAG stay silent: those are the app talking to
                // itself, not a user action to confirm.
                ActionToast.toggled(
                    context,
                    outcome,
                    taskId,
                    System.currentTimeMillis(),
                    DateFormat.is24HourFormat(context),
                )
            }
```

- [ ] **Step 6: Run the full JVM suite**

Run: `./gradlew :app:testDebugUnitTest` (with the env exports)
Expected: PASS. `TaskListScreenTest` drives `onToggleTask` under Robolectric, so a
crash in the new path shows up here.

- [ ] **Step 7: Try a wiring assertion, and drop it if it fights**

Add to `app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt` a case that
taps a row's check circle and asserts `org.robolectric.shadows.ShadowToast.getTextOfLatestToast()`.
If Robolectric's shadow does not see a toast posted from the composable's callback in this
setup, delete the test rather than contorting it — Task 2 already pins every string, and
Task 4's emulator pass is the real proof of the wiring.

---

### Task 4: Verify on the device, bump, publish

**Files:**
- Modify: `app/build.gradle.kts:42,45` — `versionCode = 23`, `versionName = "0.17.0"`
- Modify: `CLAUDE.md` — the "app's only toast" claim in Conventions & gotchas is now false
- Modify: `README.md` if it describes what feedback the actions give

**Interfaces:**
- Consumes: everything above.
- Produces: a `0.17.0` release APK staged under both names in `dist/`.

- [ ] **Step 1: Build, install, launch**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
```

If the emulator is not running, boot it headless first — do not ask:
`emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`,
then `adb wait-for-device`, poll `getprop sys.boot_completed` until `1`, then
`adb shell wm dismiss-keyguard`.

- [ ] **Step 2: Screenshot all four surfaces**

A toast lives ~2s, so screencap immediately after the tap in a straight-line script — no
shell function in a loop, which has exhausted the process table and killed the emulator
before. Coordinates are full device resolution, 1080×2400.

Seed the list by writing `shared_prefs` directly and `chown`-ing it, rather than driving
the add sheet. Needed: one overdue one-off, one overdue daily repeater, one future
repeater (for the refusal), one done task (for the reopen).

The four to capture:
1. list check circle on the overdue one-off → `Done.`
2. overdue row tapped → panel → a snooze chip → `Snoozed until …`
3. overdue daily repeater's check circle → `Done — next …`
4. notification's Done button, fired from the launcher rather than the app, so the toast
   is proven to appear over someone else's UI → `Done.`

Also confirm the future repeater's circle still says `Not due until …`, and that ticking
several rows fast leaves **one** toast on screen, not a queue.

- [ ] **Step 3: Run the instrumented suite**

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS. This is where Task 1 Step 5's four assertions are checked. Note it calls
`TaskStore.clear()` and so wipes the list seeded in Step 2 — do the screenshots first.

- [ ] **Step 4: Bump the version**

`app/build.gradle.kts`: `versionCode = 23`, `versionName = "0.17.0"`. Once for the whole
session, minor because this is new behaviour.

- [ ] **Step 5: Correct the docs the change falsifies**

`CLAUDE.md`'s Conventions & gotchas says of the not-due-yet toast: "This is the app's only
toast, and it is system-styled rather than Pesky-styled." Replace with the rule that now
holds — every snooze and every done reports through `ActionToast`, the strings live in one
pure object, and `ALREADY_PAST` must never read "snoozed until". Add the surface count
(four) and why the receiver reads `is24HourFormat` itself.

- [ ] **Step 6: Build and publish the release APK**

```bash
./gradlew :app:assembleRelease -PuseDebugSigning
cp app/build/outputs/apk/release/pesky-reminders-0.17.0.apk dist/pesky-reminders-0.17.0.apk
cp app/build/outputs/apk/release/pesky-reminders-0.17.0.apk dist/pesky-reminders.apk
```

`-PuseDebugSigning` is required — the upload key would refuse to install over the copy on
the phone. Reuse the running `cloudflared`; restarting it mints a new hostname and breaks
every link already shared. A 502 through the tunnel means the local server died — restart
`python3 -m http.server 9999` from `dist/`. Verify by downloading through the tunnel and
hash-matching the staged artifact before reporting the URL.

---

## Self-review

Spec coverage, section by section:

| Spec section | Task |
|---|---|
| The gap | — (motivation) |
| What each action says (all 8 rows) | 2, tested in 2.1 |
| Already-passed is not hypothetical | 1.4, 2.1, 2.3 |
| Which formatter, and why they differ | 2.3 + the 24-hour test in 2.1 |
| `CHANGED` splits three ways | 1.1, 1.2, 1.5 |
| `snooze`/`snoozeUntil` return `SnoozeOutcome` | 1.3, 1.4 |
| Landing time read from the store | 3.1 (`toggled`/`snoozed`) |
| `ActionToast` — pure builders | 2.3 |
| `ActionToast` — cancel previous, app context | 3.1 |
| Wiring, four surfaces | 3.2, 3.3, 3.4, 3.5 |
| `PeskyApp`'s inline toast replaced | 3.2 |
| Testing — JVM / instrumented / Robolectric / emulator | 2.1, 1.5, 3.7, 4.2, 4.3 |
| Version `0.17.0` | 4.4 |

No gaps. One thing the spec did not mention and this plan adds: **Task 4 Step 5**, correcting
`CLAUDE.md`'s "this is the app's only toast" claim, which the change makes false.

Placeholder scan: no TBDs; every code step carries the actual code; the one deliberately
conditional step (3.7) says exactly what to do if it fails, which is delete it.

Type consistency: `forToggle`/`forSnooze`/`toggled`/`snoozed` keep the same names and
`(context, outcome, taskId, nowMillis, use24h)` ordering everywhere they appear; `ADVANCED`
is never written `ROLLED_FORWARD`; `SnoozeOutcome.ALREADY_PAST` is never `PAST`.
