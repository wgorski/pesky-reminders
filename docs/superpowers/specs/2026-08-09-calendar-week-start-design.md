# The month grid follows the locale's first day of week

**Date:** 2026-08-09
**Status:** designed

## The problem

Two places in the app decide where a week begins, and they disagree.

`TaskTime.startOfWeek` asks `Calendar.getFirstDayOfWeek()` — Monday across most of
Europe, Sunday in the US — and that drives `weekDiff`, and through it the THIS WEEK
and NEXT WEEK bands on the task list.

The month grid in the task sheet is hardcoded Sunday-first, in two independent spots:

- `TimePickers.kt:178` — the header row is the literal `listOf("S","M","T","W","T","F","S")`
- `TaskTime.kt:249` — `leadingBlanks` is `get(Calendar.DAY_OF_WEEK) - 1`, i.e. Sunday = 0

So on a Monday-first device the list cuts its weeks on Monday while the calendar the
user picks a date on draws them starting Sunday. This was already recorded in
CLAUDE.md as a known disagreement to unify "if it ever shows". It shows.

## What this is not

This started as a request for two settings — a 12/24h time format and a Monday/Sunday
week start. Both were dropped in brainstorming:

- **No week-start setting.** The locale already knows the answer and the bands already
  trust it. A setting would add a third opinion where the fix is to remove the second.
- **No time-format setting.** The app keeps following the device's "Use 24-hour format"
  system setting via `DateFormat.is24HourFormat`, as it does today.

Nothing is added to `Settings.kt`.

## The change

### 1. `leadingBlanks` uses the locale's first day

```kotlin
fun leadingBlanks(monthStartMillis: Long): Int = cal(monthStartMillis).run {
    (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
}
```

The same expression `startOfWeek` already uses, so the two cannot drift. It is pure
`Calendar` field arithmetic rather than a subtraction of millis: a week containing a
DST change is 167 or 169 hours long, and dividing that by `DAY_MILLIS` could put the
1st of the month in the wrong column.

### 2. A single source for the header letters

A new pure function on `TaskTime`:

```kotlin
/** The seven column headers, rotated to start on the locale's first day. */
fun weekdayInitials(): List<String>
```

derived from the existing `WEEKDAYS` array, and read by `CalendarPicker` in place of
the literal. Both halves of the grid — which letter leads and how many cells precede
the 1st — then come from one place, so the header cannot disagree with the blanks.
This is the same reasoning that has the snooze chip labels come from `TaskTime` rather
than being written out at the call site.

The letters stay English, merely rotated. The app is English-only and `WEEKDAYS` is
already hardcoded English; localising day names is a separate job.

### 3. Documentation

Three notes stop being true and come out:

- the `startOfWeek` KDoc's "Note the month grid in the task sheet is still hardcoded
  Sunday-first; the two can disagree"
- the `leadingBlanks` KDoc's "with Sunday as the first column"
- the CLAUDE.md week-boundaries gotcha's "The month grid in the task sheet is still
  hardcoded Sunday-first, so **the two can disagree**; unify them if it ever shows."

CLAUDE.md keeps the first half of that gotcha — week boundaries follow the locale, and
tests that touch banding must pin `Locale` as well as `TimeZone` — and gains the fact
that the grid now follows it too.

## Purity

`TaskTime` stays `Context`-free. `firstDayOfWeek` comes off the default `Locale`
through `Calendar`, exactly as `startOfWeek` reads it today, so the object remains
exercisable from plain JVM unit tests by setting `Locale.setDefault`.

## Testing

**Deterministic (`TaskTimeTest`).** The suite already pins `TimeZone` to UTC and
`Locale` to US in `@Before`, and already has a precedent for varying the locale
mid-test in `grouping_follows_the_locales_first_day_of_the_week`.

- The existing `calendar_grid_lines_up_with_the_month` assertion is unchanged and must
  stay green: 1 August 2026 is a Saturday, so under `Locale.US` it takes 6 blanks.
- A new case adds the `Locale.UK` counterpart: the same Saturday takes 5.
- A new case pins the header against the blanks — under either locale,
  `weekdayInitials().first()` is the initial of the day for which `leadingBlanks`
  returns 0. This is what stops a future edit rotating one and not the other.

**Emulator.** Verified twice, because the AVD ships en-US and would otherwise show
Sunday-first either way and prove nothing:

1. As shipped (en-US) — the grid still starts Sunday, unchanged from today.
2. With the device locale forced to en-GB — the grid redraws Monday-first, its header
   reads `M T W T F S S`, and the 1st of the month sits one column left of where it
   was.

A screenshot of each. No instrumented tests: nothing here touches the notification
model.

## Scope of risk

`leadingBlanks` has exactly one caller (`CalendarPicker`) and `weekdayInitials` will
have exactly one. Nothing in the notification path, the scheduler, the store or the
banding changes. The worst failure mode is a calendar grid off by one column, which a
screenshot catches immediately.
