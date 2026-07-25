package com.peskyreminders.poc.ui

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.peskyreminders.poc.DueGroup
import com.peskyreminders.poc.Task
import com.peskyreminders.poc.TaskTime

private val CardShape = RoundedCornerShape(18.dp)

/**
 * Movement between sections should read as the row travelling, not blinking out
 * and back. Kept deliberately quiet: a short ease with no overshoot.
 *
 * An arriving item fades in *at its final slot*, so without the delay it paints
 * on top of whatever is still sliding out of that slot — un-ticking the last
 * active task drew its band heading straight over the "DONE" header mid-flight.
 * Holding the fade until the move is most of the way done keeps the slot clear.
 */
private val MOVE: FiniteAnimationSpec<IntOffset> = tween(280, easing = FastOutSlowInEasing)
private val FADE_IN: FiniteAnimationSpec<Float> =
    tween(180, delayMillis = 160, easing = LinearOutSlowInEasing)
private val FADE_OUT: FiniteAnimationSpec<Float> = tween(140, easing = FastOutLinearInEasing)

/** The extra space that turns an 8dp row gap into a 20dp gap before a heading. */
private fun Modifier.sectionGap() = this.padding(top = 12.dp)

/**
 * Cards ride above the section labels.
 *
 * Un-ticking the last active task sends the row up past the "DONE" heading coming
 * the other way, and they have to cross. Lazy items otherwise draw in index order,
 * which puts the heading's text on top of the card it is passing. Cards are opaque,
 * so lifting them one layer turns that collision into a plain occlusion.
 */
private fun Modifier.cardLayer() = this.zIndex(1f)

@Composable
fun TaskListScreen(
    tasks: List<Task>,
    nowMillis: Long,
    use24h: Boolean,
    doneExpanded: Boolean,
    onToggleDoneSection: () -> Unit,
    onToggleTask: (Int) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTask: (Int) -> Unit = {},
    onClearDone: () -> Unit = {},
) {
    val active = tasks.filterNot { it.done }.sortedBy { it.dueMillis }
    // Every active task lands in exactly one band, and the bands are declared in
    // chronological order, so walking the enum lays the list out for us.
    val bands = active.groupBy { TaskTime.groupOf(it.dueMillis, nowMillis) }
    val done = tasks.filter { it.done }.sortedByDescending { it.dueMillis }

    Box(Modifier.fillMaxSize().background(PeskyColors.Screen)) {
        Column(Modifier.fillMaxSize()) {
            Header(onOpenSettings)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                // Rows are 8dp apart; section headers add their own 12dp on top
                // to make the 20dp gap between sections. Spacing has to live on
                // the items rather than on the arrangement now that every row is
                // its own lazy item — that is what lets them animate individually.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tasks.isEmpty()) {
                    item("empty") { EmptyState(Modifier.animateItem(FADE_IN, MOVE, FADE_OUT)) }
                }

                DueGroup.entries.forEach { band ->
                    val rows = bands[band].orEmpty()
                    if (rows.isEmpty()) return@forEach

                    item("h-${band.name}") {
                        val headerModifier = Modifier
                            .sectionGap()
                            .animateItem(FADE_IN, MOVE, FADE_OUT)
                        if (band == DueGroup.OVERDUE) {
                            OverdueHeader(headerModifier)
                        } else {
                            SectionLabel(band.label, PeskyColors.TextMuted, headerModifier)
                        }
                    }
                    items(rows, key = { "t-${it.id}" }) { task ->
                        TaskRow(
                            task, nowMillis, use24h, band == DueGroup.OVERDUE,
                            onToggleTask, onOpenTask,
                            Modifier.cardLayer().animateItem(FADE_IN, MOVE, FADE_OUT),
                        )
                    }
                }

                if (done.isNotEmpty()) {
                    item("h-done") {
                        DoneHeader(
                            count = done.size,
                            expanded = doneExpanded,
                            onClick = onToggleDoneSection,
                            onClear = onClearDone,
                            modifier = Modifier.sectionGap().animateItem(FADE_IN, MOVE, FADE_OUT),
                        )
                    }
                    if (doneExpanded) {
                        items(done, key = { "t-${it.id}" }) { task ->
                            DoneRow(
                                task, onToggleTask, onOpenTask,
                                Modifier.cardLayer().animateItem(FADE_IN, MOVE, FADE_OUT),
                            )
                        }
                    }
                }
            }
        }

        AddButtonBar(onAdd, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Wordmark on the left, settings on the right.
 *
 * The design's date stamp used to sit between them. It was dropped: every row
 * already says when it is due in words you can read at a glance ("Today, 8:00 PM",
 * "Was due Today, 9:00 AM"), so today's date told you nothing the list had not.
 */
@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = PeskyColors.Text)) { append("Pesky") }
                withStyle(SpanStyle(color = PeskyColors.Accent)) { append(".") }
            },
            style = PeskyType.Logo,
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .pressable(scale = 0.9f, onClick = onOpenSettings)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PeskyIcons.Sliders,
                contentDescription = "Settings",
                tint = PeskyColors.TextMuted,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PeskyType.SectionLabel,
        color = color,
        modifier = modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun OverdueHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(PeskyColors.Overdue))
        Text("OVERDUE", style = PeskyType.SectionLabel, color = PeskyColors.Overdue)
    }
}

@Composable
private fun TaskRow(
    task: Task,
    nowMillis: Long,
    use24h: Boolean,
    overdue: Boolean,
    onToggle: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val due = TaskTime.formatFull(task.dueMillis, nowMillis, use24h)
    Row(
        // The whole row opens the task. The check circle is a nested target, so a
        // tap that lands on it ticks the task off and never reaches this.
        modifier = modifier
            .fillMaxWidth()
            .testTag("row-${task.id}")
            .pressable(scale = 0.99f) { onOpen(task.id) }
            .clip(CardShape)
            .background(PeskyColors.Card)
            .border(
                1.dp,
                if (overdue) PeskyColors.OverdueBorder else PeskyColors.CardBorder,
                CardShape,
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = false, tag = "check-${task.id}") { onToggle(task.id) }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(task.name, style = PeskyType.TaskName, overflow = TextOverflow.Ellipsis)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (overdue) "Was due $due" else due,
                    style = PeskyType.TaskWhen,
                    color = if (overdue) PeskyColors.Overdue else PeskyColors.TextDim,
                )
                if (task.repeats) RepeatPill(task.repeat.label)
            }
        }
    }
}

@Composable
private fun RepeatPill(label: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(PeskyColors.CardBorder)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = PeskyIcons.Repeat,
            contentDescription = null,
            tint = PeskyColors.TextDim.copy(alpha = 0.7f),
            modifier = Modifier.size(10.dp),
        )
        Text(label, style = PeskyType.Pill, color = PeskyColors.TextDim)
    }
}

/** The 28dp tap target that ticks a task off — hollow ring, or a filled mint disc. */
@Composable
private fun CheckCircle(checked: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .testTag(tag)
            .pressable(scale = 0.88f, onClick = onClick)
            .clip(CircleShape)
            .then(
                if (checked) Modifier.background(PeskyColors.Check)
                else Modifier.border(2.dp, PeskyColors.CheckRing, CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = PeskyIcons.Check,
                contentDescription = "Done",
                tint = PeskyColors.Screen,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun DoneHeader(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(150),
        label = "chevron",
    )
    // Two independent targets sharing a row, so neither may wrap the other and
    // the row itself must stay inert: a clickable ancestor sets mergeDescendants
    // and would fold "CLEAR" into the expand toggle — one button to a screen
    // reader, and unreachable to a test.
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .pressable(scale = 0.98f, onClick = onClick)
                .clip(RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DONE ($count)", style = PeskyType.SectionLabel, color = PeskyColors.TextMuted)
            Icon(
                imageVector = PeskyIcons.ChevronDown,
                contentDescription = null,
                tint = PeskyColors.Text.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp).rotate(chevron),
            )
        }

        // Only offered while the section is open — clearing a list you cannot
        // see is how you delete something you meant to keep. Brighter than the
        // "DONE (n)" it sits beside, because it is the one thing here that acts;
        // the accent stays reserved for overdue in this screen.
        if (expanded) {
            Text(
                text = "CLEAR",
                style = PeskyType.SectionLabel,
                color = PeskyColors.TextDim,
                modifier = Modifier
                    .testTag("done-clear")
                    .pressable(scale = 0.94f, onClick = onClear)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun DoneRow(
    task: Task,
    onToggle: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("row-${task.id}")
            .pressable(scale = 0.99f) { onOpen(task.id) }
            .clip(CardShape)
            .background(PeskyColors.DoneCard)
            .border(1.dp, PeskyColors.DoneCardBorder, CardShape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = true, tag = "check-${task.id}") { onToggle(task.id) }
        Text(
            text = task.name,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontFamily = DmSans,
            color = PeskyColors.TextMuted,
            textDecoration = TextDecoration.LineThrough,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = PeskyIcons.Bell,
            contentDescription = null,
            tint = PeskyColors.Text.copy(alpha = 0.35f),
            modifier = Modifier.size(30.dp),
        )
        Text(
            "Nothing to pester you about",
            fontSize = 15.sp,
            fontFamily = DmSans,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = PeskyColors.TextDim,
        )
        Text(
            "Add something before you forget it. You know you will.",
            style = PeskyType.Body,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Gradient scrim so the list fades out behind the floating add button. */
@Composable
private fun AddButtonBar(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to PeskyColors.Screen,
                )
            )
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .pressable(onClick = onAdd)
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = PeskyColors.Accent,
                    spotColor = PeskyColors.Accent,
                )
                .clip(CircleShape)
                .background(PeskyColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PeskyIcons.Plus,
                contentDescription = "Add a task",
                tint = PeskyColors.Text,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
