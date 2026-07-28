# A second preset row: snooze until a time of day — design

Date: 2026-07-28
Status: approved, not yet implemented

## The gap

`ReminderSheet` offers only *durations*: four chips (15 min, 30 min, 1 hr, 3 hr) and a
wheel out to 72 hours. Every one answers "how long from now", which is the wrong shape
for the commonest intent — *this evening*, *tomorrow morning*. Getting to 08:00 tomorrow
today means doing the arithmetic yourself and then finding it on a wheel whose steps
coarsen to six hours out there, so the nearest rung may not even be 08:00.

A second row of chips naming absolute times of day closes that gap without touching
what already works.

## The ladder

Three rungs a day, on each of today and the next three days:

| Rung      | Time  |
|-----------|-------|
| morning   | 08:00 |
| afternoon | 13:00 |
| evening   | 20:00 |

Twelve candidates, filtered to those **strictly after** now, first **four** taken.
`untilPresets` always returns exactly four.

Today is not special-cased — it contributes all three rungs like any other day. At 06:00
the row therefore opens with Today 08:00.

Three days would strictly suffice: the worst case is a tap just after 20:00, where today
drops out entirely and the four chips become tomorrow's three plus the day after's 08:00
— so the furthest rung ever *displayed* is the day after tomorrow. The fourth day is
margin, never shown in practice, and costs nothing to generate.

The part-of-day names are how the ladder is *generated*. They are not shown — see below.

## The chips

Same `PresetChip` geometry as the duration row: two lines, 15sp semibold over 10sp dim,
`PeskyColors.Field` on a 12dp corner with a `FieldBorder` hairline, `pressable(0.96f)`.

Both lines come from functions that already exist, so this adds no formatting code:

- big line — `TaskTime.formatTime(target, use24h)` → `20:00`
- small line — `TaskTime.formatDay(target, nowMillis)` → `Today` / `Tomorrow` / `Thu`

This mirrors the duration row's hierarchy: the big line is the value, the small line
qualifies it. Once a chip reads `13:00`, the word "afternoon" adds nothing, and dropping
it is also what makes the row fit — four chips across the sheet body is ~81dp each, and
"Afternoon" at 15sp needs ~76dp of that, clipping at any font scale above ~1.1. `20:00`
needs ~45dp. The small line has room for the full word "Tomorrow" (~44dp at 10sp).

`use24h` already reaches this sheet from `DateFormat.is24HourFormat`. The chips follow it
rather than forcing 24h, so they cannot disagree with the wheel rows immediately below
them.

### Section labels

The two chip rows are **one section under a single "Snooze" label**. The existing
`"Snooze for"` string becomes `"Snooze"`.

```
Snooze          ┌─────┐  ┌─────┐  ┌─────┐  ┌─────┐
                │ 15  │  │ 30  │  │  1  │  │  3  │
                │ min │  │ min │  │ hr  │  │ hr  │
                └─────┘  └─────┘  └─────┘  └─────┘

                ┌───────┐ ┌────────┐ ┌────────┐ ┌────────┐
                │ 20:00 │ │ 08:00  │ │ 13:00  │ │ 20:00  │
                │ Today │ │Tomorrow│ │Tomorrow│ │Tomorrow│
                └───────┘ └────────┘ └────────┘ └────────┘

…or dial it in  [ wheel, 148dp ]
```

One label, because the two rows are the same choice offered two ways — how long from
now, or what time to land on. Giving each its own heading would split one decision into
two, and "Snooze for" cannot cover an absolute time anyway: *snooze for 20:00* is wrong,
which is what forces the label to be the neutral "Snooze" rather than a matched pair.

The rows stay visually distinct without a heading each: the duration chips are narrow and
uniform, the time chips wider and two-word. `Arrangement.spacedBy(8.dp)` between them
keeps them a single block against the 10dp gap before the wheel's label.

## Committing the tap

The target commits as **absolute epoch millis**, not as a duration:

- `SnoozeOptions.untilPresets(nowMillis: Long): List<Long>` — pure, returns the four
  timestamps.
- `ReminderSheet` gains `onSnoozeUntil(atMillis: Long)` beside `onSnooze(minutes)`.
- `Reminders.snoozeUntil(context, taskId, atMillis)` beside `Reminders.snooze`.

Converting the target to minutes-from-now and reusing `onSnooze` would be a smaller
diff and is **rejected**. The conversion would happen at composition time while
`Reminders.snooze` reads the clock again at tap time, so the landing time drifts by
however long the user takes to tap. The two hosts make this concrete and unequal:
`PeskyApp` re-reads the clock every 30 seconds, `ReminderActivity` captures
`System.currentTimeMillis()` once at `setContent` and never refreshes it — so a sheet
left open five minutes would land "Tomorrow 08:00" at 08:05. That is the failure
CLAUDE.md already records as having happened once: a readout promising a time the button
does not deliver.

This does not weaken the snooze-from-clock rule. That rule exists so a *duration* is
anchored to the clock rather than to the task's due time. An absolute target is anchored
to nothing — it *is* the time — so commit and readout cannot disagree by construction.

### The anchor

`snoozeUntil` preserves the snooze anchor exactly as `snooze` does:

```kotlin
anchorMillis = if (task.repeats) task.slotMillis else null
```

So pushing a daily 09:00 to tomorrow morning still leaves the day after at 09:00, rather
than dragging the whole cycle forward.

### A target that has already passed

If the sheet sits open across a rung boundary — opened at 12:59, "13:00" tapped at
13:00:30 — the target is in the past, and arming an alarm there makes `setAlarmClock`
fire immediately.

`snoozeUntil` treats it the way `Reminders.create` already treats a past due time:
**pester me now**. It writes the target as `dueMillis`, cancels the alarm rather than
arming it, and leaves the notification live. The reminder stays overdue and keeps
nagging, which is the honest outcome.

It deliberately does not refuse. CLAUDE.md's `NOT_DUE_YET` episode is the precedent: a
refusal indistinguishable from a broken control was worse than having no control.

## Two hosts, one implementation

`ReminderActivity` and `PeskyApp` both pass the new callback through to
`Reminders.snoozeUntil`. Neither gets a variant and the sheet takes no host parameter —
the same reasoning that keeps add and edit in one `TaskSheet`.

`PeskyApp` raises this sheet only from an **overdue** row, and that stays true. The
existing restriction is there because durations count from the clock and would drag a
task due tomorrow *earlier*. Absolute presets do not have that hazard, but nothing here
is a reason to widen the entry point, and the duration row it sits beside still is.

## No confirm step

Every chip commits on the tap, like everything else in this sheet. The new row holds no
selection and shows no chosen state, for the same reason the existing chips don't: there
is no confirm button for a highlight to promise.

## Height

The sheet comes to roughly 350dp against `PeskySheet`'s 95% cap (~766dp on a 440dpi
2340px phone). One more chip row plus its label is ~75dp. This is not `TaskSheet`, which
genuinely only just fits — there is ample headroom.

## Testing

**JVM (`SnoozeOptionsTest`)** — `untilPresets` at pinned clocks:

- 05:00 → Today 08:00, Today 13:00, Today 20:00, Tomorrow 08:00
- 14:00 → Today 20:00, Tomorrow 08:00, Tomorrow 13:00, Tomorrow 20:00
- 20:00:01 → today absent; the row starts at Tomorrow 08:00
- exactly 13:00:00 → "strictly after" excludes the current rung
- across a DST boundary the rungs stay at wall-clock 08:00/13:00/20:00, which is what
  `Calendar.add` gives and fixed millisecond offsets would not

**JVM (`ReminderSheetTest`)** — the four chips render with the expected
time-over-day labels, and a tap commits the matching absolute millis in one go.

One existing assertion changes rather than being added to:
`ReminderSheetTest.kt:113` asserts `onNodeWithText("Snooze for")`, which becomes
`"Snooze"`. It is the only test coupled to that string.

**Instrumented** — `snoozeUntil` arms an alarm for a future target; for a past target it
arms nothing and leaves the notification posted.

## Out of scope

- Changing the duration chips, the wheel, or `Reminders.snooze`.
- Making the rung hours configurable. Three fixed times cover the intent; a setting for
  them is a preferences screen's worth of work for a POC.
- Widening the overdue-only restriction on `PeskyApp`'s entry point.
- Any "next week" or day-of-week rung. Four days of ladder is all four chips can reach.

## Version

Minor bump — new user-facing behaviour.
