# Swiping a reminder away snoozes it — design

**Date:** 2026-08-12
**Status:** approved, implementing
**Follows:** `2026-07-24-pesky-reminders-poc-design.md`,
`2026-08-02-notification-alert-levels-design.md`,
`2026-08-05-notification-quick-snooze-design.md`

## The problem

Swiping the notification away puts it straight back. That is the app's founding
trick — `setOngoing(true)` plus a `deleteIntent` that re-posts — and it is what
the POC was built to prove.

It is also, in daily use, the one interaction with no give in it. There is a real
and common state the app has no answer for: *I have seen it, I am in the middle
of something, ask me again shortly.* Both sanctioned exits are wrong for it —
Done is a lie, and Snooze 15 min is four times longer than needed. What is left
is the swipe, which does nothing, twice, until you stop trying.

## The change

**A swipe snoozes the reminder by a configurable number of minutes — 5 by
default — and a toast says where it landed.**

```
        swipe  ──▶  notification clears
                    dueMillis = now + 5 min          alarm re-armed
                    nag chain cancelled

        toast  ──▶  "Nice try — snoozed until 3:45 PM."
```

The swipe stops being a no-op and becomes the shortest snooze in the app,
reachable without opening anything. It is still pesky: the reminder comes back,
at full volume, with nothing marked off.

### What does *not* change

`setOngoing(true)` stays. It still blocks **clear-all** and **swipe-while-locked**
— those are the sweeping gestures, and a reminder that vanishes with forty
others is a reminder you never read. Only the deliberate, individual swipe now
buys five minutes.

Every other route is untouched: the notification's **Done** and **Snooze 15 min**
actions, the body tap that opens `ReminderSheet`, and the in-app check circle all
behave exactly as they do today.

## The wiring

`Reminders.snooze` is already precisely this operation:

- cancels the notification — a no-op after a swipe, which has already removed it;
- cancels the nag chain;
- sets `dueMillis = now + minutes`;
- **preserves a repeater's `anchorMillis`**, so a swiped daily 09:00 does not drag
  its cycle five minutes later every day;
- arms the alarm, which by construction lands in the future.

**So no new logic goes into `Reminders`.** The feature is a settings value, a
receiver branch, and one toast string. This mirrors the quick-snooze design
exactly, and for the same reason: the operation was already correct, only the
route to it is new.

### `ReminderContract`

- `ACTION_REPOST` → **`ACTION_SWIPED`** (`"com.wgorski.peskyreminders.SWIPED"`),
  `SLOT_REPOST` → **`SLOT_SWIPED`**, value **2 unchanged** so no request code
  moves.
- `Reminders.repost` **stays.** Two callers remain — an edit that leaves a task
  overdue, and a `snoozeUntil` onto a time already gone. Only the *broadcast*
  route disappears; nothing but the delete-intent ever sent it.

The rename is deliberate rather than cosmetic: a broadcast named `REPOST` whose
branch snoozes is the kind of lie that survives for years. The cost is one narrow
window — a notification posted **before** the update holds a PendingIntent
carrying the old action string, so a swipe in that window clears the notification
and hits no branch, losing the snooze. `BootReceiver` handles `MY_PACKAGE_REPLACED`
and `Reminders.restoreAll` re-posts every overdue task immediately, replacing the
notification and its delete-intent, so the window is the seconds between install
and that broadcast. Accepted, documented in the KDoc, and no compatibility branch
is kept for it.

### `ReminderReceiver`

The `ACTION_SWIPED` branch mirrors `ACTION_SNOOZE` exactly:

```kotlin
ReminderContract.ACTION_SWIPED -> {
    Settings.hydrate(context)
    val outcome = Reminders.snooze(context, taskId, Settings.swipeSnoozeMinutes)
    ActionToast.swiped(context, outcome, taskId, System.currentTimeMillis(),
                       DateFormat.is24HourFormat(context))
}
```

Two things it has to do for itself, both because a receiver is not the UI:
`hydrate` before reading the setting — the process may have been started by this
very broadcast, with nothing having touched `Settings` yet, exactly as
`Reminders.nag` does it — and ask for the device clock format, having no
composition to inherit it from.

The class KDoc currently explains that `FIRE`, `REPOST` and `NAG` stay silent
because they are "the app talking to itself". That stops being true of the swipe —
it is now a user action with a consequence to report — and the comment moves with
the branch.

## The setting

`Settings` gains a third value beside `nagEnabled` and `nagMinutes`, following
the same lazy-hydrate shape because it is read from the receiver as well as the UI:

| | |
|---|---|
| key | `swipe_snooze_minutes` |
| state | `var swipeSnoozeMinutes by mutableIntStateOf(…)`, private setter |
| writer | `setSwipeSnoozeMinutes(context, minutes)` |
| read from the receiver | `Settings.hydrate(context)`, then the property |
| clamp | `coerceSwipeSnoozeMinutes(minutes)` |
| `DEFAULT_SWIPE_SNOOZE_MINUTES` | 5 |
| `MIN_SWIPE_SNOOZE_MINUTES` | 1 |
| `MAX_SWIPE_SNOOZE_MINUTES` | 180 |

**Its own constants, not the nag's**, even though all three numbers coincide
today. They answer different questions — how long to hide a reminder you just
pushed away, versus how often to buzz one you are ignoring — and they are free to
diverge. This is the same call `SnoozeOptions.UNTIL_HOURS` makes in not borrowing
`TaskTime`'s morning hour.

The bounds: **1 minute** is the closest thing left to the old behaviour and is
harmless — the reminder is back before you have finished the thought. **180
minutes** is where a swipe stops meaning "not now" and starts meaning
"not today"; longer answers are what the sheet's ladder out to 72 hours is for,
and a gesture that can hide a reminder for a working day is a dismissal wearing a
disguise.

`Settings.clear` resets it with the rest.

## The sheet

A second block below `NAGGING`, structurally identical:

```
SETTINGS

NAGGING
  Keep buzzing                          [ on ]
  Buzz again while a reminder is still
  sitting there, until you snooze it
  or tick it off.

  Every [ 5 ] minutes
  Anything from 1 to 180 minutes.

SWIPING
  Snooze for [ 5 ] minutes
  Swiping a reminder away pushes it back
  by this long instead of bringing it
  straight back.
  Anything from 1 to 180 minutes.
```

No switch — the behaviour is unconditional, so there is nothing to toggle.

`MinutesRow` is **generalised, not copied**: it takes the leading word, the
min/max, a test tag, and keeps `enabled = true` as a default so the swipe row
does not have to think about it. It already carries the *never lose a typed value
on focus alone* fix — commit per keystroke once it parses in range, clamp on Done
and on dismiss — and a second copy would drift from it at the first change to
either. The sheet holds both fields' text so dismissing still commits a
half-typed value in each.

Two `BasicTextField`s in one sheet is exactly where a shared-state bug would
live, so a test pins that typing in one leaves the other alone.

Height: `PeskySheet` caps at 95% of the screen and the body scrolls past that.
Settings is currently the shortest sheet in the app, so there is room, but the
new block is checked at 440dpi and font scale 1.3 like everything else added to a
sheet body.

## The toast

`ActionToast` gains the pair every other action has — a pure `forSwipe` and a
`swiped` that re-reads the task and shows it:

```kotlin
fun forSwipe(outcome: SnoozeOutcome, task: Task?, nowMillis: Long, use24h: Boolean): String? =
    when (outcome) {
        SnoozeOutcome.MOVED -> task?.let {
            "Nice try — snoozed until " + TaskTime.formatCompact(it.dueMillis, nowMillis, use24h) + "."
        }
        // Unreachable from Reminders.snooze, which always lands in the future.
        // Delegated rather than duplicated so that if it ever becomes reachable it
        // says the truthful thing instead of claiming a move that did not happen.
        else -> forSnooze(outcome, task, nowMillis, use24h)
    }
```

Three things about the wording:

- **"snoozed until" is kept verbatim** from `forSnooze`, so a swipe reads as the
  same class of event as every other snooze rather than a private dialect.
- **"Nice try" is the only new part**, and it is the part that acknowledges you
  asked for something else and got this. Two words, then it gets out of the way
  with the fact.
- **The em dash matches the house style** — `"Done — next 9:00 AM."`,
  `"8:00 PM has passed — still due."`

It uses `formatCompact`, which drops the day when it is today: a swipe snooze
lands minutes away, and "until Today, 3:45 PM" is a word too many. `MISSING`
stays silent, as everywhere.

This is a toast fired by a swipe, which the alert-levels design flagged as the
one gesture where answering back "reads as the app arguing with you". That
judgement stands for **sound and vibration** and both remain off — the reminder
does not re-post at all now, so there is nothing to alert. A silent line of text
stating where the thing went is the opposite problem: without it the notification
simply vanishes and the app has given no account of itself.

## Tests

**JVM** (`app/src/test/`, the suite that runs on every edit):

- `SettingsTest` — the swipe default is 5; `0` clamps to 1 and `999` to 180;
  writing it leaves `nagMinutes` alone and vice versa.
- `ActionToastTest` — `forSwipe(MOVED)` in both clock formats; `MISSING` is null;
  `ALREADY_PAST` returns exactly what `forSnooze` returns.
- `SettingsSheetTest` — the new field is shown, accepts digits, clamps on
  dismiss, and typing in it does not disturb the nag field.

**Instrumented** (`app/src/androidTest/ReminderModelTest`). Two tests assert the
*old* promise and have to invert — they are the real proof either way, because
they fire the notification's own `deleteIntent`, which is exactly what the OS
sends on a swipe:

- `dismissing_notification_triggers_repost` → **`dismissing_notification_snoozes_it`**:
  the notification does **not** come back, and the armed alarm is approximately
  the configured minutes out.
- `a_swipe_puts_it_back_without_a_sound` is **removed** — there is no re-post left
  for it to inspect, and the test above already covers what the swipe does now.
  Its quiet-channel claim moves onto
  `an_edit_that_leaves_a_task_overdue_keeps_its_notification`, a surviving
  `repost` caller, so the `Alert.SILENT` routing keeps a test rather than quietly
  losing its only one.
- **New:** swiping a repeater keeps `anchorMillis`, so its cycle does not drag.
- **New:** the swipe honours a non-default interval set in `Settings`.

## Documentation

The headline claim is now wrong in three places and correcting it is part of the
work, not a follow-up:

- **`README`** — the tour and the "cannot swipe away" framing.
- **`CLAUDE.md`** — "the delete-intent re-post is what actually defeats a swipe",
  and the `ACTION_REPOST` mentions in the receiver's description.
- **`docs/play/release-notes.md`** — a user-visible behaviour change, in the app's
  own voice, both languages.

## Version

**Minor** bump — `0.25.0` → `0.26.0` — a behaviour change, not a fix.

## Out of scope

- **A switch to restore the instant re-post.** Weighed and dropped: it doubles the
  behaviours to reason about and test for a mode nobody has asked to keep, and
  1 minute is close enough to it for anyone who wants it back.
- **A per-task swipe duration.** The setting is global, like nagging.
- **Changing what Snooze 15 min or the sheet's ladder do.** Untouched.
