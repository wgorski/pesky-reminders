# A toast for every snooze and every done — design

Date: 2026-07-28
Status: implemented in 0.17.0. Three things came out differently than written —
see "What changed in the build" at the foot.

## The gap

Snoozing and completing are the app's two real verbs, and neither one says anything.

The reminder sheet deliberately has **no confirm step and no "back at …" footer** — every
chip and wheel row commits on the tap, so there is nothing held to preview. That was the
right call for the interaction, but it leaves a hole on the way out: the sheet vanishes
and the notification with it, and nothing ever states where the reminder actually landed.
The wheel row you tapped said `(3:45 PM)` a moment before it disappeared; whether the tap
registered on *that* row is left to faith.

Done has a milder version of the same gap. Ticking a one-off off the list is visible — the
row moves — but the notification's own Done button clears a notification and reports
nothing, and a **repeater** rolling forward to its next occurrence is the single most
opaque thing the app does: the row appears to stay put, moved from OVERDUE to some future
band, and the new time is never named.

A toast is the smallest thing that closes both. It fits the sheet's existing shape rather
than fighting it: commit on the tap, then *report*, which is exactly the pattern
`ToggleOutcome` + the not-due-yet toast already established.

## What each action says

| Action | Toast |
|---|---|
| Done, one-off | `Done.` |
| Done, repeater — rolls forward | `Done — next Tomorrow 9:00 AM.` |
| Un-tick a done row | `Back on the list.` |
| Snooze by duration | `Snoozed until 3:45 PM.` |
| Snooze by time chip | `Snoozed until 8:00 PM.` |
| Snooze to a rung that has since passed | `8:00 PM has passed — still due.` |
| Repeater ticked before its slot | `Not due until Tomorrow, 8:00 AM.` — unchanged |
| Task deleted from under the tap | *no toast* |

The register matches the toast that already exists: a stated fact, one sentence, full
stop. It says what happened and stops there — the earlier draft of the not-due-yet toast
explained the *consequence* too and read like an argument.

### The already-passed row is not hypothetical

`Reminders.snoozeUntil` has a live past-target branch: the sheet can sit open across the
very rung it is offering, and arming `setAlarmClock` in the past fires it at once. It
therefore cancels the alarm and leaves the notification live — "pester me now", the line
`create` takes for a past due time.

`Snoozed until 8:00 PM.` would be a straight lie there. The toast reports what actually
happened instead, which is also the only feedback distinguishing that case from a snooze
that worked.

### Which formatter, and why they differ

Landing times use `TaskTime.formatCompact`, which drops the day when it is today. That is
what makes it `Snoozed until 3:45 PM.` rather than `Snoozed until Today, 3:45 PM.` — and
it still says `Tomorrow 9:00 AM` when the day matters, which is nearly always the case for
a repeater's next slot.

The not-due-yet toast keeps `TaskTime.formatFull` and its comma, unchanged. It names a
*slot on a named day*, where the day is never redundant. Both formatters live in
`TaskTime`, so no toast can invent a time format or disagree with the sheet's chips about
whether the clock is 24-hour; they differ only in how much they say, which is the
distinction `formatCompact` exists to draw.

No test currently pins either string.

## Where the decision lives

`Reminders` already hands the UI a `ToggleOutcome` so a refused tick can explain itself
without the call site re-deriving the rule. This widens that same seam rather than adding
a parallel one.

### `ToggleOutcome.CHANGED` splits three ways

`COMPLETED`, `REOPENED`, `ADVANCED`. `NOT_DUE_YET` and `MISSING` are untouched.

The cheaper alternative — infer it afterwards from `task.done` and `task.repeats` — is
**not safe**. `DoneRow` is passed `onOpenTask`, so a done row opens the editor; the sheet
can set a repeat rule; and `Reminders.update` carries `done` through unchanged. A
done-*and*-repeating task is therefore reachable, and ticking it takes `toggle`'s reopen
branch while looking exactly like a roll-forward from the outside. Inferring would report
`Done — next …` for what was actually a reopen.

`toggle` already knows which branch it took. Let it say so.

### `snooze` and `snoozeUntil` return a `SnoozeOutcome`

`MOVED`, `ALREADY_PAST`, `MISSING`. `snooze` can only ever return `MOVED` or `MISSING`,
since its result is by construction in the future; `ALREADY_PAST` exists for
`snoozeUntil`'s past branch alone.

### The landing time is read from the store, not carried in the enum

Both `snoozeUntil` branches store the target verbatim in `dueMillis`, and `toggle`'s
advance branch stores the next occurrence there. So one `TaskStore` lookup *after* the
call serves every case, and the enums stay plain enums.

This is reading state, not re-deriving a rule — `PeskyApp`'s existing not-due-yet branch
already looks the task up the same way.

## `ActionToast`

One new file, `ActionToast.kt`, in the main package alongside `SnoozeOptions`.

```kotlin
object ActionToast {
    fun forToggle(outcome: ToggleOutcome, task: Task?, nowMillis: Long, use24h: Boolean): String?
    fun forSnooze(outcome: SnoozeOutcome, task: Task?, nowMillis: Long, use24h: Boolean): String?
    fun show(context: Context, message: String)
}
```

The two builders are **pure** — `nowMillis` is a parameter, not a clock read, the same
constraint that keeps `TaskTime` and `SnoozeOptions` unit-testable. Returning `String?`
puts the "say nothing" cases (`MISSING`) in one place rather than at four call sites.

`show` is the only impure part:

- It **cancels the previous toast** before posting the next, so ticking off five rows
  reports the last action instead of replaying a ten-second queue. The reference is held
  in the object.
- It builds with `context.applicationContext`. Two reasons, not one: a statically-held
  `Toast` must not retain an Activity, and the toast has to survive
  `ReminderActivity.finishAndRemoveTask()` firing immediately after.

`Toast` must be touched from the main thread. Every caller is already on it — Compose
callbacks by definition, and `BroadcastReceiver.onReceive` by default.

## Wiring

Four surfaces, one line each.

| Surface | Actions |
|---|---|
| `PeskyApp.onToggleTask` — the list's check circle | done / reopen / advance / refused |
| `PeskyApp`'s `ReminderSheet` — an overdue row tapped | done, snooze, snooze-until |
| `ReminderActivity` — raised from the notification | done, snooze, snooze-until |
| `ReminderReceiver`, `ACTION_DONE` — the notification's Done button | done |

`ReminderActivity` builds its message **before** `close()`; `show`'s use of the
application context is what lets it appear after the activity is gone.

`ReminderReceiver` reads `DateFormat.is24HourFormat(context)` for itself — it has no
composition to inherit it from. `ACTION_FIRE`, `ACTION_REPOST` and `ACTION_NAG` stay
silent: those are the app talking to itself, not a user action to confirm.

`PeskyApp`'s inline not-due-yet toast is **replaced** by the shared path, so that call
site gets shorter. This is a net reduction there, not an addition.

## Testing

**New JVM `ActionToastTest`** — the real coverage. Every branch of both builders, in 12-
and 24-hour form, with `TimeZone` pinned as the rest of the suite does. Because the
builders are pure this needs no device, no Robolectric, and no clock.

**`ReminderModelTest`** — four `assertEquals(ToggleOutcome.CHANGED, …)` assertions become
the specific outcome each one exercises. Worth more than the mechanical change suggests:
those four lines are what pin the split.

**Robolectric UI** — attempt a `ShadowToast.getTextOfLatestToast()` assertion in
`TaskListScreenTest` and `ReminderSheetTest`. Dropped if it fights the existing setup;
the pure tests already cover the strings, so this would only be adding proof of the
wiring, and contorting the UI tests for it is not worth it.

**Emulator** — a screenshot per surface, including the notification's Done button raising
a toast over the launcher rather than over the app, which is the one case no test can
reach.

## Version

`0.17.0`. New behaviour, so the minor component. `0.16.0` is already claimed by the
uncommitted Play-prep work in the tree and is not re-used.

## Rejected

- **A snackbar instead.** Pesky-styled and dismissible, but it needs a host in the
  composition — which `ReminderReceiver` does not have and `ReminderActivity` is actively
  tearing down. Two of the four surfaces could not use it, and the app already has one
  system-styled toast to be consistent with.
- **Naming the task in every toast.** Genuinely useful from the notification, where the
  list is not on screen. Rejected because a long name wraps the toast to three lines and
  pushes the time — the fact worth reporting — off the first.
- **A toast on nag re-posts.** The app buzzing itself is not a user action, and one every
  nag interval is the definition of pesky in the wrong sense.

## What changed in the build

Three deviations, all found by running it.

**`show` hops to the main looper.** This spec claimed "main thread only. Every caller is
already there — Compose callbacks by definition, `BroadcastReceiver.onReceive` by
default." That is true of production but not of the tests: `ReminderModelTest` delivers
intents to `ReminderReceiver` straight from the instrumentation thread, and four tests
died on `Can't toast on a thread that has not called Looper.prepare()`. The precondition
was the defect, not the tests — a toast helper that throws depending on the caller's
thread is a trap for whoever calls it next — so `show` now posts to the main looper when
it is not already on it. The already-there path stays synchronous, which is what keeps
the Robolectric assertions able to read the toast the moment they have acted.

**The wiring test moved.** The plan proposed a `ShadowToast` assertion inside
`TaskListScreenTest`. That would have proved nothing: `TaskListScreenTest` stubs
`onToggleTask` with `{ toggled += it }` and never reaches `Reminders` or `ActionToast` —
the wiring lives in `PeskyApp`, which no JVM test hosts. It became
`ActionToastShowTest` instead, six Robolectric cases against `ActionToast` directly:
that `show` reaches the screen, that the newest message is the one showing, that
`toggled`/`snoozed` read the task *after* the action wrote it (the reason the lookup is
inside `ActionToast` rather than at the call site), and that a missing task posts
nothing. Cancellation is checked on the emulator, since `ShadowToast` records what was
shown but not what was withdrawn.

**A pre-existing bug fixed on the way past.** `ReminderNotifier` passed
`!DateFormat.is24HourFormat(context)` — negated — so a 24-hour device read
"Is due Today, 5:17 PM" directly above a list row saying "Was due Today, 17:17". It was
out of this change's scope, but it is the same `use24h` plumbing, and shipping a toast
saying "Snoozed until 15:45." beside a notification saying "3:45 PM" would have made the
new feature look like the broken half. Nothing caught it because the instrumented tests
only asserted the "Is due …" prefix; there is now a test pinning the format itself.
