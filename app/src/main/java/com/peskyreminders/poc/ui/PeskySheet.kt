package com.peskyreminders.poc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

/**
 * The shared bottom-sheet chrome: scrim, entrance, grabber, title row, a
 * scrolling body and an optional pinned footer.
 *
 * Both sheets go through here so the tap handling is defined once. Getting it
 * wrong is subtle — see the swallow layer below.
 */
@Composable
fun PeskySheet(
    title: String,
    onDismiss: () -> Unit,
    bodyPadding: PaddingValues = PaddingValues(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 16.dp),
    bodySpacing: Dp = 18.dp,
    footer: @Composable (() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    // Runs the entrance the moment the sheet is composed.
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    val slideFrom = with(LocalDensity.current) { 48.dp.roundToPx() }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = appear, enter = fadeIn(tween(200)), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("sheet-scrim")
                    .background(PeskyColors.Scrim)
                    .tap(onDismiss)
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 95%, not 90%. The task sheet with a repeater's Delete row came to
            // roughly 723dp against a 766dp ceiling on a 440dpi 2340px phone — a
            // 5% margin, which any font scale above 1.2 ate. The body then became
            // scrollable by a few dp, which reads as broken rather than as a
            // scroller: the first field label slides under the header and a strip
            // of dead space opens above the footer. The scroll stays as the
            // fallback for genuinely small screens; it should just never be the
            // normal case.
            val sheetMax = maxHeight * 0.95f
            AnimatedVisibility(
                visibleState = appear,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(220)) { slideFrom } +
                    fadeIn(tween(220), initialAlpha = 0.4f),
                exit = fadeOut(),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetMax)
                        .clip(SheetShape)
                        .background(PeskyColors.Sheet)
                ) {
                    // Sits *behind* the sheet's content and eats taps that land
                    // on empty space, so they never reach the scrim and close
                    // the sheet. A sibling rather than a modifier on the Column
                    // below: a clickable ancestor would merge the whole sheet
                    // into one semantics node — a single giant "button" to a
                    // screen reader, and unreachable to a UI test.
                    Box(Modifier.matchParentSize().tap {})

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.navigationBars.union(WindowInsets.ime)
                            ),
                    ) {
                        Grabber()
                        SheetHeader(title, onDismiss)

                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState())
                                .padding(bodyPadding),
                            verticalArrangement = Arrangement.spacedBy(bodySpacing),
                            content = body,
                        )

                        footer?.invoke()
                    }
                }
            }
        }
    }
}

@Composable
private fun Grabber() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(PeskyColors.Grabber))
    }
}

@Composable
private fun SheetHeader(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = PeskyType.SheetTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("sheet-title"),
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .pressable(scale = 0.9f, onClick = onDismiss)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PeskyIcons.Close,
                contentDescription = "Close",
                tint = PeskyColors.TextDim,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
