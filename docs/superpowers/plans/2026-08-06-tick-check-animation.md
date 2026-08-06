# The tick's check animation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ticking a task off fills its check circle and holds it for a beat before the row leaves, instead of the row vanishing with no feedback at all.

**Architecture:** `CheckCircle` stops being a two-state look and becomes one animated float — hollow ring at `0f`, mint disc with a popped-in tick at `1f`. `TaskRow` holds a `ticking` flag, and the tap sets it rather than committing: a `LaunchedEffect` waits out the fill plus a hold, *then* calls the toggle. Because the commit is what removes the row, the animation is drawn before anyone knows whether the tick will be honoured — so `onToggleTask` changes from `(Int) -> Unit` to `(Int) -> ToggleOutcome`, and only `COMPLETED` keeps the check.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.10.01), `animateFloatAsState`/`tween`, `kotlinx.coroutines.delay`, Robolectric-hosted Compose tests, Gradle wrapper 8.11.1.

## Global Constraints

- Every command block that uses `adb`, `emulator`, `sdkmanager` — and every `./gradlew` — must be prefixed with:
  ```bash
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  ```
- Use the Gradle **wrapper** (`./gradlew`). Never the system `gradle`.
- The emulator AVD is `pesky`. If it dies mid-task, restart it without asking:
  `emulator -avd pesky -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`, then `adb wait-for-device`, poll `getprop sys.boot_completed` until `1`, then `adb shell wm dismiss-keyguard`.
- Keep `adb` calls in straight-line scripts. A shell function called in a loop has exhausted the process table and killed the emulator before.
- `screencap` is full device resolution — **1080×2400** on the `pesky` AVD. Scale tap coordinates accordingly.
- **Version bump once for the whole session**, in `app/build.gradle.kts`: this is a behaviour change, so the **minor** component. `versionName "0.20.0"` → `"0.21.0"`, `versionCode 26` → `27`. Task 3 owns this; no other task touches it.
- Every APK staged in `dist/` is built with **`-PuseDebugSigning`**. Without it the release build is upload-signed and cannot install over a sideloaded copy.
- Press feedback is a scale, never a ripple, and `pressable(...)` must come **before** `.clip()`/`.background()` in a modifier chain.
- The refusal rule — a repeater whose slot has not come declines the tick — lives in `Reminders.toggle` and must not be re-derived in the UI.
- Do not start a tunnel or expose anything publicly. Staging in `dist/` is the whole job.

## File Structure

| File | Change | Responsibility |
| --- | --- | --- |
| `app/src/main/java/com/wgorski/peskyreminders/ui/TaskListScreen.kt` | Modify | All of it: the timing constants, the animated `CheckCircle`, `TaskRow`'s `ticking` state and delayed commit, and the callback's new return type. |
| `app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt:74-82` | Modify | Return the `ToggleOutcome` it already computes for `ActionToast`. |
| `app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt` | Modify | Both `TaskListScreen(...)` call sites gain the outcome; three ticking tests gain a wait; seven new tests cover the beat. |
| `CLAUDE.md` | Modify | One bullet in *Conventions & gotchas* recording why the commit waits and why the outcome comes back. |
| `app/build.gradle.kts:42,45` | Modify | The version bump. |

`Reminders.kt` is **not** touched. `ToggleOutcome` already exists with exactly the cases needed, and its KDoc already says it exists so the UI does not re-derive the rule.

## Testing Notes (read before Task 2)

The beat is `delay`-driven, so the tests have to move the clock:

- `compose.waitUntil { … }` advances the main clock a frame at a time until the condition holds — use it to wait *through* the beat.
- `compose.mainClock.autoAdvance = false` plus `compose.mainClock.advanceTimeBy(n)` stops *inside* the beat — use it to prove the check is drawn before the commit.
- If `waitUntil` ever fails to converge (the virtual clock not driving the `LaunchedEffect`'s `delay`), the fallback is `autoAdvance = false` and an explicit `advanceTimeBy(300)` before asserting. Don't lengthen the timeout instead — a real hang would then take a second per test.

`Modifier.clickable` merges descendants in this Compose version, so the tick's `contentDescription = "Done"` surfaces on the circle's own node. `compose.onNodeWithContentDescription("Done")` is therefore the test hook for "is it checked", and it is unambiguous in a list of active-only tasks — the only other content descriptions on this screen are "Settings" and "Add a task".

---

### Task 1: The outcome gets back to the row

Behaviour-neutral plumbing. `TaskListScreen`'s toggle callback starts reporting what the toggle did, because Task 2 needs it to decide whether the check stays. Nothing animates yet, and the whole existing suite must stay green — that is this task's test.

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/TaskListScreen.kt` (imports, `TaskListScreen` signature, the `DoneRow` call site, `TaskRow` signature)
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt:74-82`
- Test: `app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt` (both call sites)

**Interfaces:**
- Consumes: `com.wgorski.peskyreminders.ToggleOutcome` — existing enum, cases `COMPLETED`, `REOPENED`, `ADVANCED`, `NOT_DUE_YET`, `MISSING`. `Reminders.toggle(context, taskId): ToggleOutcome` — existing.
- Produces: `TaskListScreen(..., onToggleTask: (Int) -> ToggleOutcome, ...)` and `private fun TaskRow(task, nowMillis, use24h, overdue, onToggle: (Int) -> ToggleOutcome, onOpen, onRemind, modifier)`. `DoneRow`'s `onToggle` stays `(Int) -> Unit`. The test class gains `private var outcome = ToggleOutcome.COMPLETED`, which its `show`/`showFromMonday` helpers return.

- [ ] **Step 1: Add the import to `TaskListScreen.kt`**

Alongside the existing `com.wgorski.peskyreminders.*` imports (they sit at lines 50-52, alphabetical):

```kotlin
import com.wgorski.peskyreminders.Task
import com.wgorski.peskyreminders.TaskTime
import com.wgorski.peskyreminders.ToggleOutcome
```

- [ ] **Step 2: Change the screen's callback type**

In `TaskListScreen`'s parameter list, replace:

```kotlin
    onToggleTask: (Int) -> Unit,
```

with:

```kotlin
    // Returns what the toggle actually did: the row draws its check *before* this
    // runs, and only a completion has earned the right to keep it. Asking rather
    // than guessing is what keeps the not-due-yet rule in [Reminders.toggle].
    onToggleTask: (Int) -> ToggleOutcome,
```

- [ ] **Step 3: Change `TaskRow`'s parameter to match**

In `private fun TaskRow(...)`, replace `onToggle: (Int) -> Unit,` with:

```kotlin
    onToggle: (Int) -> ToggleOutcome,
```

Leave the body alone in this task — `CheckCircle(checked = false, tag = "check-${task.id}") { onToggle(task.id) }` still compiles, because Kotlin coerces the lambda's result to `Unit` for `CheckCircle`'s `onClick`.

- [ ] **Step 4: Keep `DoneRow` on `(Int) -> Unit`**

`DoneRow`'s signature does not change. Adapt at its call site instead — inside `if (doneExpanded) { items(done, …) }`, replace the `DoneRow(` call with:

```kotlin
                            DoneRow(
                                // A done row has no beat in front of it, so the
                                // outcome tells it nothing — the un-tick commits
                                // on the tap and the lambda's result is dropped.
                                task, { onToggleTask(it) }, onOpenTask,
                                Modifier.cardLayer().animateItem(FADE_IN, MOVE, FADE_OUT),
                            )
```

- [ ] **Step 5: Return the outcome from `PeskyApp`**

In `app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt`, replace the `onToggleTask` lambda (lines 74-82) with:

```kotlin
                onToggleTask = { id ->
                    // Every outcome has something to say, the refusal included: a
                    // repeater that is not due yet declines the tick on purpose,
                    // and unreported that makes the circle a control which visibly
                    // does nothing. Which sentence is [ActionToast]'s call.
                    val outcome = Reminders.toggle(context, id)
                    now = System.currentTimeMillis()
                    ActionToast.toggled(context, outcome, id, now, use24h)
                    // And back to the row, which has already drawn its check and
                    // needs to know whether to keep it.
                    outcome
                },
```

- [ ] **Step 6: Teach the test helpers to report an outcome**

In `app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt`, add the import beside the existing domain imports:

```kotlin
import com.wgorski.peskyreminders.Repeat
import com.wgorski.peskyreminders.Task
import com.wgorski.peskyreminders.ToggleOutcome
```

Add the field just after `private var clearTapped = 0`:

```kotlin
    /**
     * What the screen's toggle callback reports back. A completion by default —
     * the common case — and set per-test where the outcome is the thing under
     * test. JUnit builds a fresh instance per test, so this resets on its own.
     */
    private var outcome = ToggleOutcome.COMPLETED
```

Then in **both** `show(...)` and `showFromMonday(...)`, replace `onToggleTask = { toggled += it },` with:

```kotlin
                onToggleTask = { toggled += it; outcome },
```

- [ ] **Step 7: Run the suite — it must be green, unchanged**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 227 tests, no failures. This task changes no behaviour, so a single red test means the plumbing is wrong — most likely the `DoneRow` adapter lambda.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/ui/TaskListScreen.kt \
        app/src/main/java/com/wgorski/peskyreminders/ui/PeskyApp.kt \
        app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt
git commit -m "$(cat <<'EOF'
refactor: the toggle reports its outcome back to the row

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LNcpmwN7c2S4udHMfBMWK4
EOF
)"
```

---

### Task 2: The check fills, holds, and then the row leaves

**Files:**
- Modify: `app/src/main/java/com/wgorski/peskyreminders/ui/TaskListScreen.kt` (imports, timing constants, `TaskRow` body, `CheckCircle`)
- Test: `app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt`

**Interfaces:**
- Consumes: `onToggleTask: (Int) -> ToggleOutcome` and the test's `outcome` field, both from Task 1.
- Produces: no new public surface. Private to `TaskListScreen.kt`: `TICK_FILL = 170`, `TICK_HOLD = 110`, `TICK_FROM = 0.6f`, `TICK_POP`. `CheckCircle(checked: Boolean, tag: String, onClick: () -> Unit)` keeps its signature — `checked` simply animates now.

- [ ] **Step 1: Write the failing tests**

Add to `TaskListScreenTest.kt`, after the `ticking_an_overdue_task_does_not_also_raise_the_panel` test (which ends around line 336) and before the `// ---- ordering ----` divider:

```kotlin
    // ---- the tick's beat -----------------------------------------------------

    /** How many circles are currently wearing a tick. */
    private fun checkedCircles() =
        compose.onAllNodesWithContentDescription("Done").fetchSemanticsNodes().size

    /**
     * The whole feature: the check is drawn *before* the store changes. Committing
     * first would remove the row, so the circle that was tapped would never spend
     * a single frame checked — which is exactly the old behaviour.
     */
    @Test fun the_check_fills_before_the_task_is_committed() {
        show(listOf(overdueTask))
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.advanceTimeBy(100) // well inside the 280ms beat

        assertEquals("the circle should be wearing its tick", 1, checkedCircles())
        assertTrue("the commit must wait for the beat", toggled.isEmpty())
    }

    @Test fun the_tick_commits_once_the_beat_is_over() {
        show(listOf(overdueTask))

        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.isNotEmpty() }

        assertEquals(listOf(1), toggled)
    }

    /** A completion keeps its check, and rides out with the row's exit fade. */
    @Test fun a_completed_tick_keeps_its_check() {
        outcome = ToggleOutcome.COMPLETED
        show(listOf(overdueTask))

        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.isNotEmpty() }

        assertEquals(1, checkedCircles())
    }

    /**
     * A repeater whose slot has not come refuses the tick and says so. The circle
     * has to come back with it — a check left behind would claim a completion the
     * app declined to make.
     */
    @Test fun a_refused_tick_gives_the_hollow_ring_back() {
        outcome = ToggleOutcome.NOT_DUE_YET
        show(listOf(upNextTask))

        compose.onNodeWithTag("check-2").performClick()
        compose.waitUntil { toggled.isNotEmpty() }
        compose.waitUntil { checkedCircles() == 0 }
    }

    /**
     * A repeater rolling forward is not done either — the next occurrence has not
     * been finished, so the check drains as the row moves to its new band.
     */
    @Test fun a_rolled_forward_repeater_gives_the_hollow_ring_back_too() {
        outcome = ToggleOutcome.ADVANCED
        show(listOf(upNextTask))

        compose.onNodeWithTag("check-2").performClick()
        compose.waitUntil { toggled.isNotEmpty() }
        compose.waitUntil { checkedCircles() == 0 }
    }

    /**
     * A second tap inside the beat has to be swallowed by the circle, not fall
     * through to the row — which is why the circle stays enabled and no-ops
     * rather than disabling itself. A disabled `clickable` installs no pointer
     * input at all, so the tap would reach the row and raise the action panel.
     */
    @Test fun a_second_tap_inside_the_beat_commits_once_and_opens_nothing() {
        show(listOf(overdueTask))
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("check-1").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitUntil { toggled.isNotEmpty() }

        assertEquals(listOf(1), toggled)
        assertTrue("the circle must not reach the row beneath it", reminded.isEmpty())
        assertTrue(opened.isEmpty())
    }

    /**
     * Un-ticking is an undo, not an achievement, so the done row's circle commits
     * on the tap with nothing in front of it. The asymmetry is deliberate and is
     * pinned here, or a later tidy-up "restoring" the symmetry would put a quarter
     * second in front of every undo.
     */
    @Test fun un_ticking_a_done_row_has_no_beat() {
        show(listOf(doneTask), doneExpanded = true)
        compose.mainClock.autoAdvance = false

        compose.onNodeWithTag("check-3").performClick()

        assertEquals(listOf(3), toggled)
    }
```

Add the one missing import:

```kotlin
import androidx.compose.ui.test.onAllNodesWithContentDescription
```

Then update the three existing tests that tick a circle, since the commit is no longer synchronous. Replace `ticking_an_active_task_reports_its_id`:

```kotlin
    @Test fun ticking_an_active_task_reports_its_id() {
        show(listOf(overdueTask, upNextTask))
        compose.onNodeWithTag("check-1").performClick()
        compose.waitUntil { toggled.size == 1 }
        compose.onNodeWithTag("check-2").performClick()
        compose.waitUntil { toggled.size == 2 }
        assertEquals(listOf(1, 2), toggled)
    }
```

and add a `compose.waitUntil { toggled.isNotEmpty() }` immediately after the `performClick()` in both `ticking_a_task_does_not_also_open_it` and `ticking_an_overdue_task_does_not_also_raise_the_panel`, leaving their assertions as they are.

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest --tests 'com.wgorski.peskyreminders.ui.TaskListScreenTest'
```

Expected: FAIL. `the_check_fills_before_the_task_is_committed` fails on `toggled.isEmpty()` (the tap still commits immediately) and on `checkedCircles()` being 0 (an active row never draws a tick). `a_refused_tick_gives_the_hollow_ring_back` and the `ADVANCED` twin fail on the `toggled` wait or trivially pass with 0 checks — either way they are not yet meaningful. `un_ticking_a_done_row_has_no_beat` should already pass; that is fine, it is a guard.

- [ ] **Step 3: Add the timing constants**

In `TaskListScreen.kt`, after the `FADE_OUT` declaration (line 68) and before the `sectionGap()` helper:

```kotlin
/**
 * The beat between ticking a task off and its row leaving.
 *
 * [TICK_FILL] is the crossfade from hollow ring to filled disc and the tick's pop;
 * [TICK_HOLD] is how long the finished check sits there before the store changes.
 * Without the hold the check is a flicker inside the exit fade, and the point is
 * that the completion is legible.
 *
 * [TICK_POP] overshoots on purpose. The kit expresses arrival as scale and nothing
 * else — see `pressable` — and a tick that swells straight to size reads slower
 * than one that lands.
 */
private const val TICK_FILL = 170
private const val TICK_HOLD = 110
private const val TICK_FROM = 0.6f
private val TICK_POP = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
```

- [ ] **Step 4: Add the imports**

Into the existing alphabetical blocks in `TaskListScreen.kt`:

```kotlin
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
```

`getValue` is already imported. `androidx.compose.animation.core.FastOutSlowInEasing` and `tween` are already there too.

- [ ] **Step 5: Give `TaskRow` the pending state and the delayed commit**

In `TaskRow`, immediately after `val haptics = LocalHapticFeedback.current`:

```kotlin
    // The tick gets a beat: the circle fills and holds, and only then does the
    // store change and the row go. The commit reports back because three of the
    // four outcomes leave this row on screen, and a row that stays must not keep
    // a check it did not earn. Which outcome is which is [Reminders.toggle]'s
    // business — re-deriving the not-due-yet rule here would be a second copy of
    // it, and it would drift.
    var ticking by remember { mutableStateOf(false) }
    LaunchedEffect(ticking) {
        if (!ticking) return@LaunchedEffect
        delay((TICK_FILL + TICK_HOLD).toLong())
        if (onToggle(task.id) != ToggleOutcome.COMPLETED) ticking = false
    }
```

Then replace the `CheckCircle(...)` call in its body with:

```kotlin
        // A second tap inside the beat is swallowed here rather than by disabling
        // the circle: a disabled `clickable` installs no pointer input, so the tap
        // would fall through to the row and open the editor or the action panel.
        CheckCircle(checked = ticking, tag = "check-${task.id}") { if (!ticking) ticking = true }
```

- [ ] **Step 6: Make `CheckCircle` animate**

Replace the whole of `CheckCircle` (its KDoc included) with:

```kotlin
/**
 * The 28dp tap target that ticks a task off — hollow ring, or a filled mint disc.
 *
 * [checked] drives a crossfade rather than picking between two looks: the ring
 * fades out as the disc fades in, and the tick pops from [TICK_FROM] to full size.
 * `animateFloatAsState` initialises *at* its target, so anything that composes
 * already checked plays nothing — which is what keeps a done row instant, and what
 * stops a row arriving in the Done section from replaying the beat it just
 * performed at the other end of the move.
 */
@Composable
private fun CheckCircle(checked: Boolean, tag: String, onClick: () -> Unit) {
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(TICK_FILL, easing = FastOutSlowInEasing),
        label = "check-fill",
    )
    val pop by animateFloatAsState(
        targetValue = if (checked) 1f else TICK_FROM,
        animationSpec = tween(TICK_FILL, easing = TICK_POP),
        label = "check-pop",
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .testTag(tag)
            .pressable(scale = 0.88f, onClick = onClick)
            .clip(CircleShape)
            .background(PeskyColors.Check.copy(alpha = fill))
            .border(2.dp, PeskyColors.CheckRing.copy(alpha = 1f - fill), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Composed only once there is something to see, so an untouched circle
        // carries no "Done" — for a screen reader or for a test.
        if (fill > 0f) {
            Icon(
                imageVector = PeskyIcons.Check,
                contentDescription = "Done",
                tint = PeskyColors.Screen,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { alpha = fill; scaleX = pop; scaleY = pop },
            )
        }
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 234 tests. If `waitUntil` times out, apply the fallback from *Testing Notes* — do not extend the timeout.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/wgorski/peskyreminders/ui/TaskListScreen.kt \
        app/src/test/java/com/wgorski/peskyreminders/ui/TaskListScreenTest.kt
git commit -m "$(cat <<'EOF'
feat: the check fills and holds before a ticked row leaves

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LNcpmwN7c2S4udHMfBMWK4
EOF
)"
```

---

### Task 3: Prove it on the emulator, then ship it

A clean compile and a green suite are not evidence that this renders — the whole
class of bug this workflow exists for. The beat is shorter than the round trip of
`input tap` + `screencap`, so it is verified from a screen recording with the
frames pulled out.

**Files:**
- Modify: `app/build.gradle.kts:42,45` (version bump)
- Modify: `CLAUDE.md` (one bullet in *Conventions & gotchas*)
- Create: `dist/pesky-reminders-0.21.0.apk`, `dist/pesky-reminders.apk` (gitignored)

**Interfaces:**
- Consumes: the finished behaviour from Task 2.
- Produces: nothing other tasks depend on. This is the last task.

- [ ] **Step 1: Build, install and launch the debug build**

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb devices
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
```

If `adb devices` shows nothing, boot the AVD per *Global Constraints* first.

- [ ] **Step 2: Seed one active task, so the tap target is predictable**

Writing `shared_prefs` directly beats driving the add sheet. Force-stop first or
the running app will write its own list back over this one.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
SCRATCH=/private/tmp/claude-501/-Users-ket-dev-ai-pesky-reminders/5f9ad951-bcac-4094-95df-af8043178c36/scratchpad
DUE=$(( ($(date +%s) + 5400) * 1000 ))
cat > "$SCRATCH/pesky_tasks.xml" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="next_id" value="2" />
    <string name="tasks">[{"id":1,"name":"Water the ficus","due":$DUE,"repeat":"Once","done":false}]</string>
</map>
EOF
adb shell am force-stop com.wgorski.peskyreminders
adb push "$SCRATCH/pesky_tasks.xml" /data/local/tmp/pesky_tasks.xml
adb shell run-as com.wgorski.peskyreminders cp /data/local/tmp/pesky_tasks.xml /data/data/com.wgorski.peskyreminders/shared_prefs/pesky_tasks.xml
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
adb exec-out screencap -p > "$SCRATCH/seeded.png"
```

Then **look at `seeded.png`**. It must show one card, "Water the ficus", under a
TODAY heading, with a hollow ring on its left. A one-off due in 90 minutes is
deliberate: ticking it returns `COMPLETED`, so the check stays for the exit.

If `run-as` is refused, `adb root` then
`adb shell cp … && adb shell chown $(adb shell stat -c %u:%g /data/data/com.wgorski.peskyreminders) /data/data/com.wgorski.peskyreminders/shared_prefs/pesky_tasks.xml`
— the chown matters, or the app cannot rewrite its own prefs afterwards.

- [ ] **Step 3: Record the tap and pull the frames**

Read the check circle's centre off `seeded.png` — full 1080×2400 coordinates, so
no scaling if you measured on the raw file. It sits ~16dp + 14dp from the left
edge of the card, roughly `x≈118`; take `y` from the card's vertical centre.
Substitute both below.

The `sleep` runs **on the device**, inside `adb shell`, so nothing sleeps on the
host:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
SCRATCH=/private/tmp/claude-501/-Users-ket-dev-ai-pesky-reminders/5f9ad951-bcac-4094-95df-af8043178c36/scratchpad
adb shell 'screenrecord --time-limit 4 --bit-rate 8000000 /sdcard/tick.mp4 & sleep 1.5; input tap 118 YYY; wait'
adb pull /sdcard/tick.mp4 "$SCRATCH/tick.mp4"
mkdir -p "$SCRATCH/frames" && rm -f "$SCRATCH/frames"/*.png
ffmpeg -loglevel error -i "$SCRATCH/tick.mp4" -vf fps=30 "$SCRATCH/frames/%03d.png"
ls "$SCRATCH/frames" | head -80
```

- [ ] **Step 4: Look at the frames and confirm the beat**

Frames are 33ms apart, so the beat spans about nine of them starting a frame or
two after `1.5s` (frame ~46). Read four of them — one just before the tap, two
inside the fill, one after the row has gone:

```bash
SCRATCH=/private/tmp/claude-501/-Users-ket-dev-ai-pesky-reminders/5f9ad951-bcac-4094-95df-af8043178c36/scratchpad
ls "$SCRATCH/frames" | sed -n '44,62p'
```

Open them with the Read tool. What must be visible, in order: the hollow ring →
a partly-filled mint disc with a small tick → a full mint disc with a full-size
tick → the row gone and the empty state or remaining rows in its place. If the
disc never appears, the beat is not rendering and Task 2 is not done, whatever
the JVM suite says. Scan a couple of frames either side if the tap landed late.

- [ ] **Step 5: Bump the version**

In `app/build.gradle.kts`, `versionCode = 26` → `27` and `versionName = "0.20.0"`
→ `"0.21.0"`. A behaviour change, so the minor component, once for the session.

- [ ] **Step 6: Record the convention in `CLAUDE.md`**

Add this bullet to *Conventions & gotchas*, directly after the **Press feedback is
a scale, not a ripple** bullet — they are the same subject:

```markdown
- **Ticking a task off has a beat, and the commit waits for it.** The ring fills
  into the mint disc, the tick pops in, the check holds long enough to be read
  (`TICK_FILL` + `TICK_HOLD`, ~280ms, in `TaskListScreen.kt`), and only then does
  `Reminders.toggle` run and the row leave. Four things hold it together:
  - **The check is drawn before the store changes**, because the store change is
    what removes the row. So the tap animates before anyone knows whether the tick
    will be honoured — which is why `onToggleTask` returns a `ToggleOutcome`. Only
    `COMPLETED` keeps the check; `ADVANCED`, `NOT_DUE_YET`, `REOPENED` and
    `MISSING` all leave the row on screen, so it drains back. Don't predict this by
    re-deriving the not-due-yet rule in the UI — it lives in `Reminders.toggle`,
    same as for the toast.
  - **A second tap inside the beat is swallowed, not disabled.** A disabled
    `clickable` installs no pointer input at all, so the tap would fall through to
    the row and raise the editor or the action panel. The circle stays enabled and
    its lambda no-ops while `ticking`.
  - **`animateFloatAsState` initialises at its target**, which is the whole reason
    a done row is still instant and a row arriving in the Done section does not
    replay the beat it just performed.
  - **Un-ticking has no beat, deliberately.** Removing a check is an undo, and a
    quarter second in front of an undo is only latency. A test pins the asymmetry
    so it does not get "fixed".
```

Also update the two test counts in the *Testing* section: "227 tests" → "234
tests", and the JVM tier's "~9s" only if the run says otherwise.

- [ ] **Step 7: Build the release APK and stage it in `dist/`**

`-PuseDebugSigning` is required — without it the APK is upload-signed and Android
refuses to install it over a sideloaded copy.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
V=0.21.0
./gradlew :app:assembleRelease -PuseDebugSigning
mkdir -p dist
cp app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders-$V.apk
cp app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders.apk
shasum -a 256 app/build/outputs/apk/release/pesky-reminders-$V.apk dist/pesky-reminders-$V.apk dist/pesky-reminders.apk
```

All three hashes must match. Do not start a server or a tunnel.

- [ ] **Step 8: Install the release build and confirm the version**

The debug build passing says nothing about the artifact being staged.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb install -r dist/pesky-reminders-0.21.0.apk
adb shell dumpsys package com.wgorski.peskyreminders | grep -m1 versionName
adb shell am force-stop com.wgorski.peskyreminders
adb shell am start -n com.wgorski.peskyreminders/.MainActivity
adb exec-out screencap -p > /private/tmp/claude-501/-Users-ket-dev-ai-pesky-reminders/5f9ad951-bcac-4094-95df-af8043178c36/scratchpad/release.png
```

Expected: `versionName=0.21.0`, and `release.png` shows the task list. Read it.

- [ ] **Step 9: Commit**

```bash
git add app/build.gradle.kts CLAUDE.md
git commit -m "$(cat <<'EOF'
chore: release 0.21.0 — the tick's check animation

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01LNcpmwN7c2S4udHMfBMWK4
EOF
)"
```

---

## Out of scope

- **`docs/play/release-notes.md`** — that file is Play "What's new" copy, written
  when a Play release is actually cut. Nothing here goes to Play.
- **The instrumented suite** — nothing in this change touches the notification
  model, the alarms, or the store, which is what `connectedDebugAndroidTest`
  exists to prove. The emulator check in Task 3 is the device-side evidence.
- **`ReminderSheet`'s Done button** — it closes a sheet rather than leaving a
  circle on screen, so there is nothing to animate.
