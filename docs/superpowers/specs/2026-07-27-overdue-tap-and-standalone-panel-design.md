# Overdue tap, and a panel that isn't the app — design

**Date:** 2026-07-27
**Status:** approved, implementing
**Follows:** `2026-07-27-notification-action-sheet-design.md`

## Two problems

**Acting on an overdue task takes too many steps.** Tapping a row opens the
editor, which is the wrong tool for the common case. When something is already
late the intent is almost always "done" or "not now" — exactly what
`ReminderSheet` offers — not "change its name".

**The notification drags the whole app up with it.** Confirmed on the emulator:
with the app alive in the background, tapping the notification put
`ReminderActivity` into MainActivity's task (`t45`, `sz=2`), the task went
opaque, and the Pesky task list was pulled up behind the sheet. Pressing Done
left `MainActivity` resumed — the user is now sitting in the app instead of back
where they were. With the app *not* running the behaviour is already right: the
sheet floats over the launcher in a task of its own.

Root cause: `FLAG_ACTIVITY_NEW_TASK` with the default `taskAffinity` (the package
name) makes Android reuse the app's existing task and bring it to the front.

## The notification's panel stands alone

- **`android:taskAffinity=""`** on `ReminderActivity`. An empty affinity gives it
  its own task unconditionally, so launching it can never haul MainActivity
  forward. With the `excludeFromRecents` and `singleTop` it already carries, the
  sheet becomes a free-floating panel over whatever is on screen.
- **`finishAndRemoveTask()`** replaces `finish()` on every exit — Done, snooze,
  close, scrim, back, and the missing-task guard. It tears the standalone task
  down so the user lands back where they were.

`ReminderSheet` itself does not change. The `onNewIntent` handling added earlier
still applies, within the activity's own task.

## Overdue tap opens the panel; hold to edit

| Gesture | Overdue row | Other active row | Done row |
|---|---|---|---|
| Tap | `ReminderSheet` | Edit sheet | Edit sheet |
| Long-press | Edit sheet | Edit sheet | — |

The sheet raised from the list is the **same composable** the notification
raises, with no Edit row and no parameters distinguishing the two hosts. Two
variants would drift; that is why the previous design collapsed the add and edit
sheets into one, and the same reasoning applies.

**Routing lives in `PeskyApp`.** It already holds `now`, so its tap handler asks
`TaskTime.groupOf(task.dueMillis, now) == DueGroup.OVERDUE` and sets either
`remindTaskId` or `editTaskId`. `TaskListScreen` forwards two callbacks and stays
ignorant of the rule — it renders bands, it does not decide behaviour.

**Long-press works on every active row, not only overdue ones.** One rule — hold
to edit — is easier to hold in your head than a rule that depends on which band
a row is in, and a gesture that silently does nothing reads as a broken control.
On a non-overdue row it is redundant with tap, which is harmless.

`Modifier.pressable` gains an optional `onLongClick: (() -> Unit)? = null` and
switches to `combinedClickable` when one is supplied, keeping the scale-not-ripple
feedback. A long-press fires `HapticFeedbackType.LongPress` — without it the
gesture feels dead until the sheet animates in.

### Why this is safe

**Delete survives.** Long-press an overdue repeater → edit sheet → Delete.
`Reminders.delete` is a repeater's only exit and the edit sheet is the only place
it is offered, so keeping a path to the editor from an overdue row was the thing
worth protecting.

**Snoozing from the list cannot surprise.** `Reminders.snooze` counts from the
clock, so on a task due *tomorrow* a 30-minute snooze would drag it **earlier** —
the trap CLAUDE.md documents. On an overdue task every duration moves it later.
That is the reason this is overdue-only, and it belongs in the code as a comment.

**`ToggleOutcome.NOT_DUE_YET` is unreachable** from this sheet: the row is
overdue, so its slot has passed. Same argument as the notification path.

After a snooze or a Done from the list, `PeskyApp` refreshes `now` so the row
re-bands immediately, matching what `onToggleTask` already does.

## Testing

- `TaskListScreenTest` — a tap on an overdue row reports "remind", a tap on a
  future row reports "edit", and a long-press on either reports "edit". Fire
  `SemanticsActions.OnLongClick` directly, the same way the sheet tests fire
  `OnClick`.
- `ReminderModelTest` — nothing new. The task-affinity change is manifest-level
  and its effect is a property of the activity manager, which no test tier
  reaches; it is verified by `dumpsys activity activities` on the emulator,
  asserting `ReminderActivity` sits in a task whose `sz=1` and whose root it is,
  with MainActivity's task untouched and not brought forward.

## Costs

**Long-press is undiscoverable.** Nothing on screen advertises it, and CLAUDE.md
currently states flatly that there is no long-press gesture. That bullet is
rewritten, and the gesture is documented in the README tour and the known-gaps
list.

**Tap now means two different things** depending on the band. Defensible — an
overdue task's likely intent differs from a future one's — but it is a real
inconsistency and is recorded as such.

## Out of scope

- An Edit row inside `ReminderSheet`. Rejected in favour of long-press.
- Edit reachable from the notification-hosted sheet. That would need new intent
  plumbing from `ReminderActivity` to `MainActivity`.
- Any change to `ReminderSheet`, `Reminders`, or the alarm model.

## Version

`0.12.0` → `0.13.0`, `versionCode` 18 → 19. This session already bumped once, but
0.12.0 has been built and served; a second artifact needs a new `versionCode` or
Android refuses to install it over the first.
