# Tap a task to edit it

2026-07-25

## The problem

Tapping a task row does nothing. Everything you can do to an existing task hides
behind a long-press, which nobody discovers, and even once found the menu cannot
change the task: a typo means delete and re-add, and the time can only be pushed
by a relative offset. `CLAUDE.md` has carried "**No edit**" under Known gaps since
the v2 design landed.

## What we are building

A tap on a task row opens an **"Edit pester"** bottom sheet, seeded with that task,
that can change everything about it. The long-press menu is retired: one way in,
nothing hidden.

The sheet *is* the add sheet. Same name field, same Quick pick / Calendar tabs, same
repeat chips — seeded from the task, titled "Edit pester", with the button reading
**Save changes**. Below the pickers, a divider and two action rows:

| Row | Label | Shown for |
| --- | --- | --- |
| toggle | "Mark as done" | a one-off that is not done |
| toggle | "Done for now" + next-occurrence caption | a repeating task |
| toggle | "Mark as not done" | a done task |
| delete | "Delete" (+ "Stops it repeating") | everything |

The check circle on the row keeps its job — it ticks the task off without opening
anything.

### Commit model

Name, time and repeat are a **draft**. `Save changes` commits all three at once;
the X, the scrim and the back gesture discard them. Save is greyed only while the
name is blank — unlike the add sheet, the time is always already set.

The two action rows are not part of the draft: they commit immediately and close
the sheet, discarding unsaved edits. This is what the long-press menu does today,
and the alternative (disabling actions while dirty, or silently saving first) is
more surprising than the wart. Ticking something off is rarely the second half of
renaming it.

Delete closes the editor and raises the existing `DeleteTaskSheet` confirmation
rather than stacking a sheet on a sheet, matching how the long-press menu handed
over.

### Done tasks

A done row opens the same sheet, with everything editable and the toggle reading
"Mark as not done". No conditional layout: fixing a typo on a finished task is
legitimate, and `Reminders.update` already refuses to arm an alarm for a done task,
so an edited due time is inert until you un-tick it.

### Two changes made while building it

**The "When?" preset chips are gone.** Six shortcut chips ("Later today", "Tonight",
"Tomorrow morning", …) sat above the pickers in both paths. They came out, leaving the
wheels and the calendar as the only two ways to name a time, and "When?" moved up to
label the tabs. That takes `TaskTime.quickPicks`, the `QuickPick` model and
`QuickPickGrid` with it — nothing else referenced them — along with the ten unit tests
that guarded the chip labels, including the roll-over fix that was `0.8.1`'s whole
content. Git keeps it if the chips ever come back.

**The repeat row fits on one line.** "Monthly" wrapped onto a second row, which grew
the pinned footer by a step. The "Repeat" label moved off the row and onto its own
line as a field label, which frees the full sheet width for all four rules, and the
row now scrolls sideways so a large font scale pushes a chip off the edge rather than
wrapping. Inline label plus scrolling would have hidden "Monthly" by default, which is
worse than the wrap it fixes.

### Reschedule is gone

The old menu's "Reschedule" row pushed the due time by a *relative* offset counted
from the clock. The editor picks absolute times, which is what "reschedule" leads
you to expect, so the row goes and the "counts from now" caption goes with it. That
rule was subtle enough to have produced a real bug once (the readout promising a
time the button did not deliver), and it now survives in exactly one place — the
notification's own Snooze action, where "+15 minutes from now" is unambiguous.

`SnoozeSheet` stays for that. Its `title` / `readoutPrefix` / `confirmLabel`
parameters existed solely to re-word it as "Reschedule", so they go too;
`SnoozeActivity` always used the defaults.

## Structure

`AddTaskSheet.kt` is 672 lines and is about to hold two public entry points, so it
splits:

- **`ui/TaskSheet.kt`** — a private `TaskSheet(existing: Task?, …)` holding the
  shared body and footer, plus two thin public wrappers:
  - `AddTaskSheet(nowMillis, use24h, onDismiss, onSave)` → `existing = null`
  - `EditTaskSheet(task, nowMillis, use24h, onDismiss, onSave, onToggle, onDelete)`

  `NameField`, the footer, and `ActionRow` (moved out of the deleted
  `TaskActionsSheet.kt`) live here.
- **`ui/TimePickers.kt`** — `ModeTabs`, `Wheels`, `CalendarPicker`, `MonthArrow`,
  `DayCell`, `StepButton`, `RoundChip`, `EntryMode`, `MINUTE_STEPS`, moved verbatim.

Keeping `AddTaskSheet` a real public composable means its call site and its tests
do not move, and become the regression net proving the generalisation left adding
alone. The file's dead `Grabber()` / `SheetHeader()` — leftovers from before
`PeskySheet` was extracted — and its stale imports go.

### Draft state

`rememberSaveable(existing?.id) { … }`, keyed on the id so the state resets if the
sheet is ever reused for a different task. Seeded from the task: `name`,
`dueMillis` (non-null for an edit), `repeat`.

The calendar opens on the task's own month via one new pure helper,
`TaskTime.monthOffsetOf(millis, nowMillis)`.

Two accepted rough edges: the DAY wheel spans 14 days from today, so an **overdue**
or **far-future** task has no day cell to point at and shows nothing selected until
you touch it. The footer readout carries the truth — "Was due Today, 9:00 AM" in the
overdue red, matching the row — so the sheet never misstates when the task is due;
it just cannot point at it.

## `Reminders.update`

One new function on the facade, alongside `create` / `toggle` / `delete` / `snooze`.
It replaces the task (same id, so notification ids and PendingIntent request codes
stay stable) and then:

```
next due is in the future  → cancel the notification and the nag, arm the alarm
next due is in the past    → cancel the alarm (never arm in the past);
                             if a notification is on screen, re-post it so it
                             carries the new name, and keep nagging
task is done               → cancel notification, alarm and nag
```

The middle branch is the point. The naive version cancels the notification on every
save, which means opening an overdue task and pressing Save with no changes
**silently clears a pester you are not allowed to dismiss** — the app's whole
premise, defeated by a no-op.

Nothing is ever *posted* from `update`. Saving a time in the past leaves an Overdue
row with no notification, exactly what un-ticking a task produces today.
`restoreAll` remains the only place a past-due task is surfaced unasked.

## What is deleted

- `ui/TaskActionsSheet.kt` and `ui/TaskActionsSheetTest.kt`
- `Modifier.longPressable()` in `ui/Common.kt` — no callers left
- `SnoozeSheet`'s three label parameters, and the tests covering the "Reschedule"
  wording
- `actionsTaskId` and `snoozeTaskId` in `PeskyApp`, collapsed to one `editTaskId`

## Testing

**JVM (Robolectric).** New `ui/EditTaskSheetTest.kt`: seeded name / due / repeat
render; a blank name disables Save; Save reports the edited triple; the three
toggle labels; a repeater's next-occurrence caption; an overdue readout reads "Was
due". `ui/TaskListScreenTest.kt` gains: a row tap reports the task id; a
check-circle tap toggles *without* opening the editor; a done row opens it too.
`TaskTimeTest` gains `monthOffsetOf`.

**Device.** `Reminders.update` in `androidTest`: the id survives an edit; the alarm
is re-armed for a future time; the alarm is cancelled for a past time; and the pair
that matters — a live notification is **cleared** when an edit moves the task into
the future, and **survives** when the task stays overdue.

Then the required emulator pass: install, drive the new sheet with `adb input`, and
screenshot every step.

## Out of scope

- **Undo.** Still none, which is why delete still confirms.
- **Editing from the notification.** Snooze and Done stay as they are.
- **The 48dp gap on section headers.** Untouched.
