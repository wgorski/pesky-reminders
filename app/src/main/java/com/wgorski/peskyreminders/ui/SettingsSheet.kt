package com.wgorski.peskyreminders.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.wgorski.peskyreminders.Settings

private val Card = RoundedCornerShape(18.dp)

/**
 * Settings. Two things to tune, one card each: whether an ignored reminder keeps
 * buzzing and how often, and how long swiping one away hides it for. A sentence
 * at the foot states what the two of them add up to.
 *
 * Both cards commit on the touch, like the reminder sheet's chips do — there is no
 * Save here and nothing is held back. That is why the sheet no longer has to
 * rescue anything on the way out: the typed field this replaced could be left
 * holding an out-of-range number with no reliable moment to clamp it, and a chip
 * or a slider cannot produce one at all.
 */
@Composable
fun SettingsSheet(
    nagEnabled: Boolean,
    nagMinutes: Int,
    swipeSnoozeMinutes: Int,
    onNagEnabled: (Boolean) -> Unit,
    onNagMinutes: (Int) -> Unit,
    onSwipeSnoozeMinutes: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PeskySheet(
        title = "Settings",
        onDismiss = onDismiss,
        bodyPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 26.dp),
        bodySpacing = 12.dp,
    ) {
        // One label over both cards: swiping a reminder away is something you do
        // to one that is already due, so the heading covers it too.
        Text(
            "WHEN A REMINDER IS DUE",
            style = PeskyType.SectionLabel,
            color = PeskyColors.TextMuted,
            modifier = Modifier.padding(horizontal = 2.dp),
        )

        SettingCard(
            title = "Keep buzzing",
            description = "Buzz again while a reminder is still sitting there, " +
                "until you snooze it or tick it off.",
            trailing = { PeskySwitch(checked = nagEnabled, onCheckedChange = onNagEnabled) },
            // Gone rather than greyed out. A disabled row still costs its full
            // height to say nothing, and the switch immediately above it has
            // already said why it is absent.
            body = if (!nagEnabled) null else {
                {
                    MinutesPicker(
                        label = "Buzz every",
                        tag = "nag",
                        minutes = nagMinutes,
                        presets = Settings.NAG_PRESET_MINUTES,
                        min = Settings.MIN_NAG_MINUTES,
                        max = Settings.MAX_NAG_MINUTES,
                        onMinutes = onNagMinutes,
                    )
                }
            },
        )

        SettingCard(
            title = "Swipe to snooze",
            description = "Swiping a reminder away pushes it back instead of " +
                "bringing it straight back.",
            // No switch beside it: the behaviour is unconditional, so there is
            // nothing to turn off — and therefore nothing that could take the
            // block below away.
            body = {
                MinutesPicker(
                    label = "Snooze for",
                    tag = "swipe",
                    minutes = swipeSnoozeMinutes,
                    presets = Settings.SWIPE_SNOOZE_PRESET_MINUTES,
                    min = Settings.MIN_SWIPE_SNOOZE_MINUTES,
                    max = Settings.MAX_SWIPE_SNOOZE_MINUTES,
                    onMinutes = onSwipeSnoozeMinutes,
                )
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = PeskyIcons.Bell,
                contentDescription = null,
                tint = PeskyColors.TextDisabled,
                modifier = Modifier.padding(top = 3.dp).size(14.dp),
            )
            Text(
                Settings.summarise(nagEnabled, nagMinutes, swipeSnoozeMinutes),
                style = PeskyType.Body,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("settings-summary"),
            )
        }
    }
}

/**
 * One setting: a name, a sentence explaining it, optionally something on the right
 * that switches it, and optionally a block hanging below a hairline.
 *
 * The hairline only exists when there is a [body] to separate, so the card with
 * its block hidden closes up to just the title and the switch rather than leaving
 * a rule under nothing.
 */
@Composable
private fun SettingCard(
    title: String,
    description: String,
    trailing: (@Composable () -> Unit)? = null,
    body: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Card)
            .background(PeskyColors.Card)
            .border(1.dp, PeskyColors.CardBorder, Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    fontFamily = DmSans,
                    fontSize = 15.5.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PeskyColors.Text,
                )
                Text(
                    description,
                    style = PeskyType.Body,
                    color = PeskyColors.TextDim,
                    lineHeight = 19.sp,
                )
            }
            trailing?.invoke()
        }
        if (body != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(PeskyColors.CardBorder))
            body()
        }
    }
}

/**
 * A count of minutes, chosen either off three chips or — behind a fourth — on a
 * slider covering the whole range.
 *
 * Shared by both cards rather than copied, for the reason the typed row it
 * replaced was shared: two copies would drift apart at the first fix to either.
 * Every label in it comes from [Settings.minutesLabel], so the chips, the readout
 * and the marks at the ends of the track cannot write the same duration three
 * ways.
 */
@Composable
private fun MinutesPicker(
    label: String,
    tag: String,
    minutes: Int,
    presets: List<Int>,
    min: Int,
    max: Int,
    onMinutes: (Int) -> Unit,
) {
    // Sticky once tapped, and NOT keyed on `minutes`: the slider passes straight
    // over 5, 15 and 30 on its way anywhere, and re-deriving this would snap the
    // sheet back to the chip row mid-drag. It only guesses on the way in, so a
    // stored 47 opens on the slider.
    var custom by rememberSaveable(key = "$tag-custom-open") {
        mutableStateOf(minutes !in presets)
    }
    // What the slider is showing right now, which is ahead of what is stored for
    // as long as a drag lasts. Keyed on the stored value so a chip tap, or
    // anything else that moves it, is picked straight up.
    var draft by remember(minutes) { mutableIntStateOf(minutes) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, style = PeskyType.FieldLabel)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { preset ->
                PresetChip(
                    label = Settings.minutesLabel(preset),
                    selected = !custom && minutes == preset,
                    tag = "$tag-preset-$preset",
                    modifier = Modifier.weight(1f),
                    onClick = { custom = false; onMinutes(preset) },
                )
            }
            // Opens the slider and commits nothing: whatever is already stored is
            // the value it opens on, which is the only one the user has chosen.
            //
            // Wider than the three beside it, and that is not a whim. "Custom" is
            // the same six characters as "30 min" but in far wider glyphs — no
            // space, no `i` — so four exactly-equal columns clipped it to "Custo"
            // at font scale 1.3 while the presets still had room to spare. Four
            // equal columns was also a mistranslation of the design's `flex:1`,
            // which in CSS will not crush a chip below its own content width.
            // Weights divide the same total, so this holds at every scale rather
            // than at the one that was measured. 1.5 is the ceiling: past there the
            // presets are the ones that run out.
            PresetChip(
                label = "Custom",
                selected = custom,
                tag = "$tag-custom",
                modifier = Modifier.weight(1.3f),
                onClick = { custom = true },
            )
        }

        if (custom) {
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // One Text, not a number beside a unit, so the readout is a single
                // node a test can read whole — and so the baseline cannot drift.
                Text(
                    buildAnnotatedString {
                        append(draft.toString())
                        withStyle(
                            SpanStyle(
                                fontFamily = DmSans,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PeskyColors.TextDim,
                            )
                        ) {
                            append(" min")
                        }
                    },
                    fontFamily = Bricolage,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    lineHeight = 30.sp,
                    letterSpacing = (-0.02).em,
                    color = PeskyColors.AccentBright,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("$tag-readout"),
                )
                PeskySlider(
                    value = draft,
                    min = min,
                    max = max,
                    onValueChange = { draft = it },
                    onCommit = onMinutes,
                    modifier = Modifier.testTag("$tag-slider"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        Settings.minutesLabel(min),
                        style = PeskyType.Body,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("$tag-floor"),
                    )
                    Text(
                        Settings.minutesLabel(max),
                        style = PeskyType.Body,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("$tag-ceiling"),
                    )
                }
            }
        }
    }
}

/**
 * One of the four chips.
 *
 * Both cards offer the same three durations, so a test cannot go by text —
 * `onNodeWithText("5 min")` matches two nodes. Hence the tag, and hence
 * `selected`: which chip is chosen is then a fact about the node rather than a
 * colour someone has to sample.
 *
 * This is the one place in the app that merges its descendants deliberately. The
 * warning against it is about a *sheet-wide* `clickable`, which collapses every
 * control inside into one node; a chip is exactly the granularity a screen reader
 * wants — one node that says "15 min, selected" — and it is what lets a test read
 * the label off the same node it fires.
 */
@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .testTag(tag)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .pressable(scale = 0.96f, onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) PeskyColors.AccentWash else PeskyColors.Field)
            .border(
                1.dp,
                if (selected) PeskyColors.Accent else PeskyColors.FieldBorder,
                CircleShape,
            )
            .padding(horizontal = 5.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = DmSans,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) PeskyColors.AccentBright else PeskyColors.TextChip,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A switch in the app's own language — no Material chrome.
 *
 * The knob stays cream in both states, as the design has it: where it sits is the
 * signal, and dimming it as well made the off state read as broken rather than as
 * off. The track carries the colour.
 */
@Composable
private fun PeskySwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track by animateColorAsState(
        targetValue = if (checked) PeskyColors.Accent else PeskyColors.FieldBorder,
        animationSpec = tween(140),
        label = "track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 3.dp,
        animationSpec = tween(140),
        label = "thumb",
    )
    Box(
        modifier = Modifier
            .testTag("nag-switch")
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .width(48.dp)
            .height(29.dp)
            .clip(CircleShape)
            .background(track),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(23.dp)
                .clip(CircleShape)
                .background(PeskyColors.Text)
        )
    }
}
