# Custom repeat rules, and a five-minute wheel — design

**Date:** 2026-09-30
**Status:** implemented (1.2.0)
**Mockup:** `2026-09-30-custom-repeat-mockup.png` (HTML mock-up in the app's tokens
and fonts, chip widths calibrated against `docs/play/screenshots/03-new-pester.png`)

## The problem

Two limits in the task sheet:

1. **The MIN wheel only offers quarter-hours.** `:00 :15 :30 :45` is too coarse for
   "the bus is at 8:40".
2. **Repeat is four fixed choices.** Once / Daily / Weekly / Monthly cannot say
   "every two weeks on Monday and Wednesday", "the 15th of every month" or "the last
   Friday of the month" — the rules Google Calendar's *Custom recurrence* covers.

## The change

### 1. Five-minute wheel

`MINUTE_STEPS` becomes `0, 5, … 55` (12 rungs). Nothing else moves: the wheel
already scrolls, and the `−15m / +15m` steppers on the calendar tab stay — they are
quick nudges, not the minute grid. The Known-gap note about a snoozed task at `:07`
showing nothing selected still holds, just more rarely.

### 2. Custom repeat

A fifth chip, **Custom**, opens a *Custom repeat* sheet over the task sheet:

- **Repeat every** — a `− N +` stepper (1–99) and a `day / week / month / year`
  segmented switch whose labels pluralise with N. No text field, so nothing can be
  left half-typed.
- **Repeat on** — depends on the unit:
  - **week:** seven round toggles in locale order (`TaskTime.weekdayInitials`),
    multi-select; the last ticked day cannot be unticked.
  - **month:** an `A date / A weekday` switch.
    - *A date:* a 7-column grid `1 … 31` plus **Last day**. Days 29–31 fall back to
      the month's last day in shorter months, so no month is ever skipped.
    - *A weekday:* ordinal chips `1st 2nd 3rd 4th Last` plus the seven weekday
      toggles, single-select. No "5th": most months lack one, and a reminder that
      silently skips months is worse than none. "Last" covers the intent.
  - **day / year:** no "Repeat on" section.
- **Footer** — a readout of the rule ("Every 2 weeks on Mon, Wed") above a **Done**
  button. Done writes the rule into the task sheet's draft; dismissing (scrim, ✕,
  drag, back) discards it. Save on the task sheet still commits everything, as now.

The editor **seeds from the draft**: an existing custom rule as-is; otherwise the
current preset expressed as a rule and bound to the draft's date (Weekly on a
Wednesday → every 1 week on W; Monthly on the 30th → every 1 month on day 30;
Daily → every 1 day; Once → every 1 week on the due weekday, the most common reason
to open Custom).

On the task sheet (mockup 1b), the chip row stays `Once Daily Weekly Monthly
Custom` — all five fit on one line at the default font scale — and when Custom is
active the rule is printed on the right of the existing **Repeat** label line, in
accent. It costs no height. A summary *inside* the chip was mocked (1a) and
rejected: it cannot fit after Monthly, so the row would scroll Once and Daily off
the left edge every time such a task is opened.

## Rules

### Presets follow the date; a custom rule constrains it

- **Preset** (Daily / Weekly / Monthly): the rule is bound to whatever date is
  picked. Move a weekly task from Wednesday to Thursday and it becomes weekly on
  Thursday — today's behaviour.
- **Custom**: the rule names its days, so the date must be one of them. **The
  draft's date snaps to the first matching date on or after it, keeping the time**,
  whenever the rule is applied (Done) or the date is changed while a custom rule is
  active. Wed 30 Sep 17:05 with "monthly on the 15th" becomes **Thu 15 Oct 17:05**,
  and the footer's due readout shows it before anything is saved. The consequence
  is that the day wheel can jump when a date that does not match is picked; the
  readout says where it landed.

Snapping only ever moves the date forward, never earlier, and never changes the
time of day.

### Normalisation

A custom rule equal to a preset bound to the draft date *is* that preset: every 1
week on only the due weekday → **Weekly**; every 1 month on the due day-of-month →
**Monthly**; every 1 day → **Daily**. The preset chip lights and the label line
stays plain. This keeps "custom" meaning "something the presets cannot say".

### Next occurrence

`TaskTime.nextOccurrence(slotMillis, repeat, nowMillis)` keeps its name and contract
(first occurrence strictly after now, stepping from the slot, `Calendar` arithmetic
only) and hands off to a new pure object, `Recurrence`, which holds the rule maths
and gains the cases:

| unit | step from the slot |
|---|---|
| day | `+N days` |
| week | the next ticked weekday later in the slot's week; if none, jump to the start of the week `N` weeks on and take its first ticked day. Weeks come from the existing `startOfWeek`, so they agree with the list's bands and the month grid. |
| month, date | first of the month `N` months on, then day `min(d, lastDay)`; `Last day` = `lastDay` |
| month, weekday | first of the month `N` months on, then the k-th (or last) chosen weekday |
| year | `+N years` (Feb 29 falls back to Feb 28 via `Calendar`) |

Because every slot is a match (snapping guarantees the first one; stepping
guarantees the rest), stepping from the slot is enough — no separate series start
is stored. Snooze and `anchorMillis` are untouched: they operate on the slot, which
is still a single `Long`.

Storing the chosen day-of-month also fixes the old drift where a monthly task on
the 31st became the 28th after February and stayed there: the day is now read from
the rule, not from the previous slot. The **Monthly** preset gets the same fix by
being bound (day = the date's day) on save.

## Model

`Repeat` changes from an enum to a data class, keeping `ONCE / DAILY / WEEKLY /
MONTHLY` as named values so existing call sites still compile:

```kotlin
data class Repeat(
    val unit: RepeatUnit? = null,    // DAY / WEEK / MONTH / YEAR; null = once
    val every: Int = 1,
    val weekdays: Set<Int> = emptySet(),   // Calendar.SUNDAY..SATURDAY; week only
    val monthDay: Int? = null,       // 1..31, or LAST_DAY; month-by-date only
    val ordinal: Int? = null,        // 1..4, or LAST; month-by-weekday only
    val weekday: Int? = null,        // month-by-weekday only
)
```

- `bindTo(dueMillis)` fills the date-derived fields for a preset (Weekly → its
  weekday, Monthly → its day), and is what persistence and normalisation go through.
- `matches(millis)` / `TaskTime.firstMatchOnOrAfter(millis, repeat)` drive snapping.
- `repeats` stays `unit != null`, so the Delete row, `toggle` and the "not due yet"
  rule are unaffected.
- The summary sentence is a pure function beside the maths (`Recurrence.describe`),
  pluralising and ordering weekdays by locale, e.g. "Every 3 days", "Weekly on Mon,
  Wed", "Every 2 weeks on Mon, Wed", "Monthly on the 15th", "Monthly on the last
  day", "Every 2 months on the last Friday", "Every 2 years". Strings are English,
  like every other string in the app.

### Persistence

`TaskStore` keeps writing `"repeat"` (`Once / Daily / Weekly / Monthly / Custom`)
and adds a `"rule"` object only for repeating tasks. On read, a missing `"rule"`
means a pre-update task: the preset is taken from the label and bound to its
slot, so lists already on phones load unchanged and keep their weekday / day. A
downgrade reading `"Custom"` falls back to Once — acceptable for a sideload.

`rememberSaveable` in `TaskSheet` needs a `Saver` for the data class; it reuses the
same JSON encoding so there is one format, not two.

### The list row

The row's repeat pill reads the preset's name, or a custom rule cut down to fit
beside a due time (`Recurrence.shortLabel`): "Mon–Fri", "Monthly · 15th",
"Monthly · last Fri", "Every 2 weeks". Runs of three or more weekdays collapse to
"Mon–Fri" in both the pill and the full sentence.

## Height

The task sheet gains nothing: the label line already exists and the fifth chip
fits in the row. The custom sheet is its own `PeskySheet`, hosted inside
`TaskSheet`'s composition (so the draft survives), and at its tallest — month by
date — comes to roughly 600dp. Both are checked at 440dpi × font scale 1.3. At that
scale Custom is the chip pushed off the row's edge, so the row scrolls to it when a
custom rule is active.

## Out of scope

- **Ends** (never / on a date / after N times).
- A "5th weekday" option.
- Localising weekday names (the app is English throughout).

## Testing

JVM (`app/src/test`), pinning `TimeZone` and `Locale` as the grid tests do:

- `TaskTimeTest`: `nextOccurrence` for every unit and N > 1; weekly multi-day
  across the week boundary in Sunday- and Monday-first locales; month-by-date on
  29–31 and Last day through February and a leap year; month-by-weekday 1st–4th and
  Last; a DST transition; `firstMatchOnOrAfter` including "already matches"; the
  summary sentences.
- `TaskStoreTest` (new) or an existing store test: round-trip of every rule shape;
  a legacy JSON array with `"repeat": "Monthly"` and no `"rule"` loads bound to its
  slot.
- `AddTaskSheetTest` / `EditTaskSheetTest`: the MIN wheel's 12 rungs; Custom opens
  the sheet seeded from the draft; stepper, unit switch, weekday toggles (and the
  last one refusing to untick), both monthly modes; Done snaps the draft date and
  shows the label line; dismiss discards; normalisation lights the preset chip;
  changing the date with Custom active snaps it.
- Emulator: the nested sheet's touches land (the known Robolectric hit-test gap),
  and both sheets at 440dpi × 1.3.
