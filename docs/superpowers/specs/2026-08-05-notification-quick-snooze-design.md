# The notification's Snooze becomes one tap — design

**Date:** 2026-08-05
**Status:** approved, implementing
**Follows:** `2026-07-27-notification-action-sheet-design.md`,
`2026-07-27-overdue-tap-and-standalone-panel-design.md`

## The problem

The notification's two quick actions are **Snooze** and **Done**, and they are
not the same kind of thing. Done acts: one tap, the reminder is finished, a toast
says so. Snooze does not act — it opens `ReminderSheet` and asks how long.

For the overwhelmingly common answer — *not now, a few minutes* — that is a tap
to open a sheet, a tap to pick a chip, and a sheet animating over whatever you
were doing. A quick action that opens a picker is not a quick action.

## The change

**The Snooze action snoozes 15 minutes and says so on its face.**

```
[ Snooze 15 min ]  [ Done ]
```

One tap: the notification clears, the alarm re-arms 15 minutes out, and a toast
reads "Snoozed until 3:45 PM." — structurally identical to Done, which is the
point. Two buttons that look alike now behave alike.

### The amount, and the words

`SnoozeOptions.QUICK_MINUTES = 15`. It is already `PRESETS.first()`, the shortest
rung the sheet offers on a chip, and short enough that "not now" does not become
"not today".

The action title is built as `"Snooze " + SnoozeOptions.label(QUICK_MINUTES)`,
not written out. Every other place the app names a duration goes through
`SnoozeOptions.label` or `TaskTime`, and the reason is always the same: a literal
can drift from the sheet it sits above. That also fixes the spelling as
**"Snooze 15 min"**, with the space, because that is what `label(15)` returns and
what the chips have always said.

Not configurable in Settings. Nothing has asked for it, and the sheet is still
there for every other duration.

### The wiring

`Reminders.snooze` already does the whole job — cancels the notification, cancels
the nag chain, moves `dueMillis`, preserves a repeater's `anchorMillis` so the
cycle does not drag, and re-arms the alarm. **No new logic goes into
`Reminders`.** By construction it can only return `MOVED` or `MISSING`, so the
`ALREADY_PAST` wording never applies to this path.

- **`ReminderContract`** — add `ACTION_SNOOZE`, and `SLOT_OPEN = 6` for the body
  tap. `SLOT_SNOOZE = 3` now carries the broadcast. The body tap and the Snooze
  action have shared one PendingIntent since the panel was built; they are
  different intents now and need different request codes, or they collapse onto
  one another.
- **`ReminderReceiver`** — an `ACTION_SNOOZE` branch that mirrors the Done branch
  exactly: `Reminders.snooze(context, taskId, SnoozeOptions.QUICK_MINUTES)`, then
  `ActionToast.snoozed(...)` with the device's own clock format.
- **`ReminderNotifier`** — the action carries the broadcast; `setContentIntent`
  keeps `openSheetIntent`, now on `SLOT_OPEN`. Two actions, same order.

**A broadcast is now correct where it was previously forbidden.** The rule is
unchanged — Android 12+ blocks a notification action from bouncing through a
receiver to *show UI* — but this action shows none. It performs a state change and
posts a toast, which is exactly what Done has always done from the same receiver.
The rule's example moves from Snooze to the notification body.

## What this costs

**The sheet loses one of its two doors from the notification.** A tap on the body
still opens it, and that is the less discoverable of the two. Accepted: the label
now states what the button does, and the full ladder — 30 min, 1 hr, 3 hr, the
absolute-time chips, the wheel out to 72 hours — is one tap away rather than
gone. Trading a hidden second door for a button that does not lie is worth it.

This does not touch `ReminderSheet`, `ReminderActivity`, or the panel raised from
an overdue row in the list. All three are unchanged.

## Verification

**JVM** — a `SnoozeOptionsTest` case pinning `QUICK_MINUTES` at 15, that it is a
preset chip, and that its label is "15 min". The label is the button's text, so
this is what stops a change to `label` silently rewording the notification.

**Instrumented** — `ReminderModelTest` currently has two cases asserting the old
shape: that Snooze is an activity PendingIntent, and that it is *the same*
PendingIntent as the body tap. Both are rewritten rather than deleted, because
the facts they pin still matter and have only moved:

- the body tap is the activity intent, and it targets `ReminderActivity`;
- the Snooze action is a broadcast carrying `ACTION_SNOOZE`;
- firing that action's intent moves the task ~15 minutes out and leaves no
  notification behind.

Then the standing workflow: emulator screenshot of the real notification, the
version bump, and the release APK staged in `dist/`.
