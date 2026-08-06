# The tick's check animation — design

**Date:** 2026-08-06
**Status:** approved, ready to plan

## The problem

Ticking a task off is the one thing the app is for, and it is the only
interaction in it with no feedback at all. The row simply stops existing.

The check circle is a boolean look — `CheckCircle(checked = …)` draws a hollow
ring or a filled mint disc, and an active `TaskRow` always passes `false`. So the
tap goes straight to `Reminders.toggle`, the store mutates, the row leaves its
band, and the circle it was tapped on never spends a single frame checked. The
completion the user just performed is reported only by a toast and by an absence.

## What we are building

A beat between the tap and the departure: the ring fills into the mint disc, the
tick snaps in, it holds long enough to be read, and *then* the row goes. About a
quarter second, and only on a tick that means something.

## The circle

`CheckCircle` stops taking a boolean look and becomes progress-driven: one
animated float where `0f` is the hollow ring and `1f` is the disc with its tick.

- The `2dp CheckRing` border fades out as the `Check` disc fades up over it,
  across **`TICK_FILL` = 170ms**.
- The tick scales `0.6f → 1f` on an overshoot easing, so it lands with a snap
  rather than swelling into place. The kit has no ripples and expresses press
  and arrival as scale — `pressable` already does exactly this — so the beat
  speaks the language already there.
- The tick's `Icon` is composed only while progress is above zero. An untouched
  row therefore still carries no `"Done"` in its semantics, which keeps the
  screen reader honest and gives the tests a crisp hook.

`animateFloatAsState` initialises its `Animatable` at the target value, so a
composable that *starts* checked plays nothing. That is load-bearing in two
places: a `DoneRow` renders instantly checked as it does today, and a row
**arriving** in the Done section after a tick appears already checked instead of
replaying the beat at the far end of the move.

## The delayed commit

`TaskRow` holds the pending state, because the row is what owns the outcome of
its own tap:

```
tap → ticking = true            circle fills (TICK_FILL); further taps on the
                                circle are ignored while it is true
      hold TICK_HOLD = 110ms    so the check is read, not glimpsed
      outcome = onToggle(id)    the real Reminders.toggle — store, alarm,
                                notification, toast
      outcome != COMPLETED  →   ticking = false; the check drains back over
                                the same 170ms
```

`TICK_FILL` and `TICK_HOLD` join the existing `MOVE` / `FADE_IN` / `FADE_OUT`
specs at the top of `TaskListScreen.kt`, so every duration on this screen is
declared in one place.

## Why the callback must report the outcome

The store mutation is what removes the row, so the check has to be drawn before
it — which means the tap animates *before* anyone knows whether the tick will be
honoured. Ticking has four endings and three of them leave the row on screen:

| Outcome | What the row does | The check |
| --- | --- | --- |
| `COMPLETED` | leaves the band | **stays**, and rides out with the exit fade |
| `ADVANCED` | moves to its next occurrence's band | drains back — the next occurrence is not done |
| `NOT_DUE_YET` | stays exactly where it is, refused | drains back, as the toast explains why |
| `REOPENED` | unreachable from an active row — it is not done | drains back |
| `MISSING` | the task is gone; nothing left to draw | drains back |

The UI cannot tell those apart without re-deriving the not-due-yet rule that
`Reminders.toggle` owns, and CLAUDE.md is explicit that a second copy of that
condition will drift. So `TaskListScreen`'s `onToggleTask` changes from
`(Int) -> Unit` to `(Int) -> ToggleOutcome`. `PeskyApp` already has the outcome
in hand for `ActionToast` and simply returns it. The rule stays in `Reminders`;
the animation, like the toast, only reports what happened.

The refusal reads better for the delay, incidentally. "Not due until Tomorrow,
8:00 AM." now lands *as* the check springs back, so it explains the spring-back
instead of racing it.

## Scope

**Only the tick, not the un-tick.** A done row's circle commits instantly, as it
does today. Removing a check is an undo, not an achievement, and a beat in front
of an undo is just latency. The asymmetry is deliberate and gets a test so it
cannot be closed by accident.

**Both completing endings animate**, `ADVANCED` included. Rolling a repeater
forward *is* finishing that occurrence, and having the same circle behave two
different ways depending on the task's repeat rule would be the odd choice.

**No haptic.** Considered and dropped: the app buzzes on long-press-to-edit
because that gesture has no other acknowledgement until the sheet arrives. The
check now acknowledges itself.

**The row's own tap stays live during the beat.** There is a ~280ms window in
which the row could be tapped and open the editor for a task about to complete.
The editor opens on done rows anyway, so nothing breaks, and guarding it would
add a second piece of state for a case nobody will hit.

**The reminder sheet's Done is untouched.** It closes the sheet rather than
leaving a circle on screen, so there is nothing to animate.

## Testing

Deterministic (JVM), in `TaskListScreenTest`:

- the check appears while the task is still active — the beat is drawn *before*
  the commit, which is the whole feature;
- the id is still reported, after the beat rather than on the tap (the three
  existing ticking tests gain the wait);
- a callback returning `NOT_DUE_YET` leaves the ring hollow again;
- a callback returning `COMPLETED` leaves the check in place;
- un-ticking a done row still reports on the tap, with no beat.

On the emulator the beat is shorter than the round trip of `input tap` +
`screencap`, so it is verified from a `screenrecord` of the tap with the frames
pulled out, not from a still.
