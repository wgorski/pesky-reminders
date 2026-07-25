package com.peskyreminders.poc.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peskyreminders.poc.Task
import com.peskyreminders.poc.TaskTime

private val CardShape = RoundedCornerShape(18.dp)

@Composable
fun TaskListScreen(
    tasks: List<Task>,
    nowMillis: Long,
    use24h: Boolean,
    doneExpanded: Boolean,
    onToggleDoneSection: () -> Unit,
    onToggleTask: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val active = tasks.filterNot { it.done }.sortedBy { it.dueMillis }
    val overdue = active.filter { it.dueMillis < nowMillis }
    val upNext = active.filter { it.dueMillis >= nowMillis }
    val done = tasks.filter { it.done }.sortedByDescending { it.dueMillis }

    Box(Modifier.fillMaxSize().background(PeskyColors.Screen)) {
        Column(Modifier.fillMaxSize()) {
            Header(nowMillis)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (tasks.isEmpty()) item("empty") { EmptyState() }

                if (overdue.isNotEmpty()) item("overdue") {
                    Section(header = { OverdueHeader() }) {
                        overdue.forEach { TaskRow(it, nowMillis, use24h, overdue = true, onToggleTask) }
                    }
                }

                if (upNext.isNotEmpty()) item("upnext") {
                    Section(header = { SectionLabel("UP NEXT", PeskyColors.TextMuted) }) {
                        upNext.forEach { TaskRow(it, nowMillis, use24h, overdue = false, onToggleTask) }
                    }
                }

                if (done.isNotEmpty()) item("done") {
                    DoneSection(done, doneExpanded, onToggleDoneSection, onToggleTask)
                }
            }
        }

        AddButtonBar(onAdd, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Header(nowMillis: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 6.dp),
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
        Text(TaskTime.todayLabel(nowMillis), style = PeskyType.Stamp)
    }
}

@Composable
private fun Section(header: @Composable () -> Unit, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows() }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        style = PeskyType.SectionLabel,
        color = color,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun OverdueHeader() {
    Row(
        modifier = Modifier.padding(horizontal = 6.dp),
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
) {
    val due = TaskTime.formatFull(task.dueMillis, nowMillis, use24h)
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
private fun DoneSection(
    done: List<Task>,
    expanded: Boolean,
    onToggleSection: () -> Unit,
    onToggleTask: (Int) -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(150),
        label = "chevron",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .pressable(scale = 0.98f, onClick = onToggleSection)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DONE (${done.size})",
                style = PeskyType.SectionLabel,
                color = PeskyColors.TextMuted,
            )
            Icon(
                imageVector = PeskyIcons.ChevronDown,
                contentDescription = null,
                tint = PeskyColors.Text.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp).rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                done.forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CardShape)
                            .background(PeskyColors.DoneCard)
                            .border(1.dp, PeskyColors.DoneCardBorder, CardShape)
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckCircle(checked = true, tag = "check-${task.id}") {
                            onToggleTask(task.id)
                        }
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
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp),
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
                tint = PeskyColors.Screen,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
