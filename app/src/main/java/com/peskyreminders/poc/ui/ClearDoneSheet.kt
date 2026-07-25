package com.peskyreminders.poc.ui

import androidx.compose.runtime.Composable

/**
 * Confirms throwing the done list away.
 *
 * Reached from a label sitting in the same row as the expand toggle, so a mis-tap
 * is a few millimetres from wiping the list. One extra tap is a cheap price.
 */
@Composable
fun ClearDoneSheet(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmSheet(
        title = "Clear done?",
        message = "Tasks you have ticked off are removed for good. There is no undo.",
        confirmLabel = "Clear $count ${if (count == 1) "task" else "tasks"}",
        tag = "clear-done",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
