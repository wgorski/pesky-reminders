package com.wgorski.peskyreminders.ui

import androidx.compose.runtime.Composable
import com.wgorski.peskyreminders.Task

/**
 * Confirms removing a single task.
 *
 * For a repeating task this is the only exit there is — ticking one off just rolls
 * it forward — so the wording says plainly that the repeat stops too, rather than
 * leaving the user to wonder whether it will be back next week.
 */
@Composable
fun DeleteTaskSheet(
    task: Task,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmSheet(
        title = "Delete this task?",
        message = if (task.repeats) {
            "“${task.name}” is removed for good and stops repeating. " +
                "There is no undo."
        } else {
            "“${task.name}” and its reminder are removed for good. " +
                "There is no undo."
        },
        confirmLabel = "Delete",
        tag = "delete-task",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
