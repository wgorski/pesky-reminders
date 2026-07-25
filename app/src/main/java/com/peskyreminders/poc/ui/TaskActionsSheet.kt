package com.peskyreminders.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peskyreminders.poc.Task
import com.peskyreminders.poc.TaskTime

private val R14 = RoundedCornerShape(14.dp)

/**
 * What you can do to a task, raised by long-pressing its row.
 *
 * Deliberately short: reschedule, and tick it off or back on. Editing and deleting
 * are not in the design, so they are not here either.
 */
@Composable
fun TaskActionsSheet(
    task: Task,
    nowMillis: Long,
    use24h: Boolean,
    onReschedule: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val due = TaskTime.formatFull(task.dueMillis, nowMillis, use24h)

    PeskySheet(
        title = task.name,
        onDismiss = onDismiss,
        bodySpacing = 6.dp,
        bodyPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 6.dp, bottom = 22.dp,
        ),
    ) {
        Text(
            text = if (task.dueMillis < nowMillis) "Was due $due" else due,
            modifier = Modifier
                .padding(start = 10.dp, bottom = 6.dp)
                .testTag("actions-due"),
            style = PeskyType.TaskWhen,
            color = if (task.dueMillis < nowMillis) PeskyColors.Overdue else PeskyColors.TextDim,
        )

        if (!task.done) {
            ActionRow(
                icon = PeskyIcons.Clock,
                label = "Reschedule",
                // Worth saying even though it never varies: on a task that is not
                // due yet, counting from now can pull it *earlier*, which is not
                // what "reschedule" leads you to expect.
                caption = "Counts from now",
                tag = "action-reschedule",
                onClick = onReschedule,
            )
        }

        ActionRow(
            icon = PeskyIcons.Check,
            label = when {
                task.done -> "Mark as not done"
                task.repeats -> "Done for now"
                else -> "Mark as done"
            },
            caption = if (!task.done && task.repeats) {
                "Moves to " + TaskTime.formatFull(
                    TaskTime.nextOccurrence(task.dueMillis, task.repeat, nowMillis),
                    nowMillis,
                    use24h,
                )
            } else {
                null
            },
            tag = "action-toggle",
            onClick = onToggle,
        )

        // Offered on everything, but it is the *only* way out of a repeating task:
        // ticking one off rolls it forward, so it never reaches the done list that
        // CLEAR empties.
        ActionRow(
            icon = PeskyIcons.Trash,
            label = "Delete",
            caption = if (task.repeats) "Stops it repeating" else null,
            tag = "action-delete",
            onClick = onDelete,
            destructive = true,
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    caption: String?,
    tag: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .pressable(scale = 0.985f, onClick = onClick)
            .clip(R14)
            .background(Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // The accent means "overdue" out in the list, so a destructive row
                // is marked by the icon alone rather than a full red row.
                tint = if (destructive) PeskyColors.Accent else PeskyColors.TextDim,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                fontFamily = DmSans,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = PeskyColors.Text,
            )
            if (caption != null) {
                Text(caption, style = PeskyType.Body, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}
