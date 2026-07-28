package com.wgorski.peskyreminders.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Are you sure?" for the things that cannot be taken back.
 *
 * The app keeps no tombstones and has no undo, so every delete goes through here.
 * Backing out — the close button, the scrim, or the back gesture — always means no.
 *
 * [tag] namespaces the test tags, giving `<tag>-button` and `<tag>-summary`.
 */
@Composable
fun ConfirmSheet(
    title: String,
    message: String,
    confirmLabel: String,
    tag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PeskySheet(
        title = title,
        onDismiss = onDismiss,
        bodyPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 10.dp),
        footer = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PeskyColors.Sheet)
                    .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("$tag-button")
                        .pressable(scale = 0.99f, onClick = onConfirm)
                        .clip(CircleShape)
                        .background(PeskyColors.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(confirmLabel, style = PeskyType.Action, color = PeskyColors.Text)
                }
            }
        },
    ) {
        Text(
            text = message,
            modifier = Modifier.testTag("$tag-summary"),
            style = PeskyType.Body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}
