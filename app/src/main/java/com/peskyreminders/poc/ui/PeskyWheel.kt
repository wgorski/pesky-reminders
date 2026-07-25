package com.peskyreminders.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val R12 = RoundedCornerShape(12.dp)
private val R8 = RoundedCornerShape(8.dp)

/**
 * A short scrolling column of values with one selected — the "…or dial it in"
 * control. Shared by the add sheet's day/hour/minute columns and the snooze
 * sheet, so the scroll-into-view behaviour is written once.
 *
 * [title] doubles as the test-tag prefix: the list is `wheel-$title` and each
 * row is `$title-$index`.
 *
 * [aside] is an optional dimmer note set beside the label — the snooze wheel uses
 * it to spell out the clock time a long duration lands on. Return null to leave a
 * row with just its label.
 */
@Composable
fun PeskyWheel(
    title: String,
    count: Int,
    selectedIndex: Int,
    label: (Int) -> String,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    showTitle: Boolean = true,
    aside: ((Int) -> String?)? = null,
) {
    val state = rememberLazyListState()
    // Bring the selection into view when it moves off-screen (e.g. a chip was
    // tapped) without yanking the list when it is already visible.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        if (state.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            state.animateScrollToItem(maxOf(0, selectedIndex - 1))
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showTitle) {
            Text(
                title,
                style = PeskyType.ColumnLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(
            state = state,
            modifier = Modifier
                .height(height)
                .testTag("wheel-$title")
                .clip(R12)
                .background(PeskyColors.DoneCard)
                .border(1.dp, PeskyColors.CardBorder, R12)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(count) { index ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$title-$index")
                        .clip(R8)
                        .background(
                            if (selected) PeskyColors.AccentWashStrong else Color.Transparent
                        )
                        .tap { onPick(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label(index),
                            fontFamily = DmSans,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = PeskyColors.Text,
                        )
                        aside?.invoke(index)?.let {
                            Text(
                                it,
                                fontFamily = DmSans,
                                fontSize = 12.sp,
                                color = PeskyColors.TextMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}
