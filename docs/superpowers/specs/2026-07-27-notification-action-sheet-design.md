# Notification action sheet — design

**Date:** 2026-07-27
**Status:** approved, ready to plan

## The problem

Two things, one shape.

The snooze picker makes you choose a duration and then press a button to confirm
it. The choice is the whole interaction; the button is a second tap that adds
nothing. Its presets are also wrong for how the app is actually used — 5, 15, 30
and 60 minutes, when the durations reached for are 15 min, 30 min, 1 hr, 3 hr.

Separately, the notification body does nothing at all. There is no
`contentIntent`, so a tap on the reminder itself is swallowed. The only ways to
act on it are the two small action buttons, which are the last place a thumb
lands.

## What we are building

Tapping the notification body opens **one sheet that offers every outcome**:
finish the task, or push it by any duration. The sheet is the existing snooze
picker with a Done action added at the top and its confirm button removed, so
every row in it is a one-tap commit.

The notification's Snooze action opens the same sheet. Nothing about the
reminder model changes.

## The sheet

`ui/SnoozeSheet.kt` becomes `ui/ReminderSheet.kt`, composable `SnoozeSheet` →
`ReminderSheet`. It is no longer only about snoozing, and a file called
`SnoozeSheet.kt` containing a Done button would read as a mistake.

`SnoozeActivity` **keeps its class name**. It is registered in the manifest and
live `PendingIntent`s point at it; renaming it risks a dead notification action
across an app update and buys nothing.

Top to bottom:

| Element | Behaviour |
|---|---|
| Title | The task name. The separate name line in the body is dropped — the header already truncates to one line with an ellipsis. |
| **Done** | Filled accent pill with the Check icon. Calls `Reminders.toggle`, then finishes. |
| **"Snooze for"** | Four chips: 15 min / 30 min / 1 hr / 3 hr. A tap commits that duration immediately. |
| **"…or dial it in"** | The wheel. A tap on any row commits that duration immediately. |
| Footer | Gone. |

### Consequences of instant commit

Nothing is ever *held*, so selection state disappears:

- `PresetChip` loses its `selected` parameter and its two-tone styling.
- The wheel is passed `selectedIndex = -1`. `PeskyWheel`'s scroll-into-view
  `LaunchedEffect` already returns early on a negative index, so no change is
  needed there.
- The `"Back at 2:35 PM"` readout has nothing to preview and is removed with the
  footer.

What replaces the readout: **every wheel row now shows the clock time it lands
on**, not just those over three hours — `15 min (2:35 PM)`. You see where a tap
will land before you take it, which is what the footer used to be for.

That makes `SnoozeOptions.landsAtAClockTime` and `CLOCK_TIME_ABOVE_MINUTES`
dead. Both are deleted, along with the three `SnoozeOptionsTest` cases that
cover them; the sheet's own clock-time tests are rewritten rather than dropped
(see Testing).

### Why Done is a filled pill, and at the top

It is the only filled element left in the sheet. Without it the sheet is flat
chips and a wheel with no hierarchy at all. It inherits the slot the Snooze
button vacated.

`TaskSheet` puts its immediate action (Delete) at the *foot*, behind a hairline,
because everything above it is a draft waiting for Save and the hairline marks
that change of register. **That rationale does not apply here** — in this sheet
every control acts immediately, so there are no two registers to separate. Done
goes at the top as the primary outcome.

Text on the accent is `PeskyColors.Text` (cream), per the theme rule; near-black
on crimson reads muddy.

### Done and repeating tasks

The sheet calls `Reminders.toggle`, the same call the notification's Done action
makes. A repeater rolls forward to its next occurrence rather than completing —
unchanged, and correct.

`ToggleOutcome.NOT_DUE_YET` is unreachable from a notification: the notification
only exists once the slot has passed, and every snooze cancels it. The sheet
therefore ignores the outcome and finishes. No toast — this is an activity that
is closing, and `PeskyApp`'s toast has no reach here. The reasoning goes in a
comment so the next reader does not "fix" it by adding one.

## The notification

`ReminderNotifier.post` gains:

```kotlin
.setContentIntent(snoozePickerIntent(context, task.id))
```

The **same** `PendingIntent` the Snooze action already uses — identical intent,
identical request code, so no new slot in `ReminderContract` and no risk of
collision.

`setOngoing(true)` and `setAutoCancel(false)` are already set, so tapping the
body opens the sheet and leaves the reminder in the shade. Backing out of the
sheet changes nothing.

**Both action buttons stay.** Done from the shade remains one tap, and the shade
keeps advertising that the reminder is actionable. The body tap is a third,
much larger way in.

## `SnoozeOptions`

```kotlin
val PRESETS = listOf(15, 30, 60, 180)
val WHEEL   = listOf(5) + <the banded list>   // 5, 15, 30 … 72h
```

5 minutes moves from preset to the wheel's first rung so it stays reachable.
This is a deliberate break in the "every entry is a multiple of `STEP_MINUTES`"
rule: the rule becomes **aligned to a quarter-hour above the first rung**. The
KDoc and the alignment test both change to say so.

`DEFAULT_MINUTES = 5` **stays**. The sheet no longer pre-selects anything, but it
is still the default argument on `Reminders.snooze` and
`ReminderContract.snoozeTriggerAtMillis`, which the instrumented tests rely on.

`chipLabel(180)` already yields `"3"` and `chipUnit(180)` `"hr"`, so the chips
need no code change.

## Testing

**`SnoozeSheetTest` → `ReminderSheetTest`.** Removed: the `back-at` readout
tests, and the three commit tests that went through `snooze-button`. Added:

- tapping each of the four presets commits that duration and nothing else;
- tapping a wheel row commits that duration;
- tapping Done reports done and snoozes nothing;
- Close and the scrim still commit nothing, neither snooze nor done;
- the title carries the task name;
- a short wheel row now shows its clock time (the case that used to assert the
  opposite).

**`SnoozeOptionsTest`.** Update `the_wheel_starts_at_a_quarter_hour_and_reaches_three_days`,
`every_entry_stays_aligned_to_a_quarter_hour` and `five_minutes_is_a_preset_only`.
Delete the three `landsAtAClockTime` tests and the boundary test.

**`ReminderModelTest`.** One new case: the posted notification's `contentIntent`
is set, is an activity `PendingIntent`, and resolves to `SnoozeActivity`.

The usual Robolectric caveat applies — pointer injection does not reach into a
sheet body, so controls are asserted displayed and their click action fired
directly. Hit-test geometry is proved on the emulator instead.

## Accepted costs

**A stray tap on the wheel snoozes with no undo.** Compose's tap detector will
not fire during a drag, so it takes a genuine tap on a row — but a one-way door
replaces what was a confirm step. This is the price of removing the button and
is accepted.

**5 min loses its chip.** It was plausibly the most common snooze and now costs a
tap on the wheel's first rung instead of a chip. Reachable, one tap further away.

## Out of scope

- The task list's own reschedule path. It picks absolute times and is untouched.
- Undo for a mis-tapped snooze. The app has no undo anywhere; adding it here
  alone would be inconsistent (see the "No undo" known gap).
- Live-updating the clock times while the sheet sits open. `nowMillis` is
  captured once, as it already is today.

## Version

Minor bump — new behaviour. `0.11.2` → `0.12.0`, `versionCode` 17 → 18.
