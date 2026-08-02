package com.wgorski.peskyreminders.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

/**
 * Dragged past this share of its own height, letting go dismisses the sheet
 * instead of springing it back.
 */
private const val DismissFraction = 0.35f

/** …or flung downwards at least this fast (dp/s), however short the drag was. */
private const val DismissVelocity = 1000f

/**
 * The shared bottom-sheet chrome: scrim, entrance, grabber, title row, a
 * scrolling body and an optional pinned footer.
 *
 * Every sheet goes through here so the tap handling and the drag are defined
 * once. Getting the taps right is subtle — see the swallow layer below.
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

    // How far the sheet has been dragged below its resting place, in px. Only
    // ever ≥ 0: there is nowhere above home for it to go.
    val dragY = remember { Animatable(0f) }
    // Measured rather than assumed, because every sheet is a different height
    // and the dismiss threshold is a fraction of it.
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val flingPx = with(LocalDensity.current) { DismissVelocity.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        // The scrim is split in two on purpose: this half is only paint, and the
        // tappable half below is laid out *beside* the sheet rather than under it.
        //
        // A full-screen dismiss-on-tap behind the sheet is the shape this started
        // as, and it leaks: whether a tap on the sheet reaches the scrim depends on
        // whether something in the sheet's own subtree consumed it first, and any
        // pointer-input node — `verticalScroll` on the body, `draggable` on the
        // grabber — changes that answer by shadowing the swallow layer meant to
        // catch it. Tapping a field label closed the whole sheet. Overlapping
        // regions make that a dispatch question; not overlapping makes it a
        // geometry one, and geometry cannot be argued with.
        AnimatedVisibility(visibleState = appear, enter = fadeIn(tween(200)), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Fades as the sheet is pushed away, so the two read as one
                    // movement. Read inside the lambda to stay in the draw phase.
                    .graphicsLayer { alpha = 1f - dragFraction(dragY.value, sheetHeight) }
                    .background(PeskyColors.Scrim)
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
            Column(Modifier.fillMaxSize()) {
                // Everything above the sheet, and nothing else, dismisses on a tap.
                // Weighted, so it is measured after the sheet and takes exactly the
                // space the sheet left — which is what keeps the two from
                // overlapping at any sheet height.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("sheet-scrim")
                        .tap(onDismiss)
                )

                AnimatedVisibility(
                    visibleState = appear,
                    enter = slideInVertically(tween(220)) { slideFrom } +
                        fadeIn(tween(220), initialAlpha = 0.4f),
                    exit = fadeOut(),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = sheetMax)
                            .onSizeChanged { sheetHeight = it.height.toFloat() }
                            .offset { IntOffset(0, dragY.value.roundToInt()) }
                            .clip(SheetShape)
                            .background(PeskyColors.Sheet)
                    ) {
                        // Sits *behind* the sheet's content and releases keyboard
                        // focus for taps that land on chrome nothing owns — around
                        // the header, around the footer. It is a sibling rather
                        // than a modifier on the Column below: a clickable
                        // ancestor would merge the whole sheet into one semantics
                        // node — a single giant "button" to a screen reader, and
                        // unreachable to a UI test.
                        //
                        // It used to also be what stopped taps reaching the scrim.
                        // That job is now geometry's — the scrim's tappable half is
                        // laid out beside the sheet, not under it — because this
                        // layer is shadowed by any pointer-input node above it and
                        // so could never be relied on for that.
                        Box(Modifier.matchParentSize().tap {})

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(
                                    WindowInsets.navigationBars.union(WindowInsets.ime)
                                ),
                        ) {
                            // The grabber and the title row drag as one unit. The
                            // grabber alone is 38×4dp — findable by eye, but a poor
                            // target for a thumb; together they give the whole top
                            // chrome. It stops short of the body on purpose: that
                            // scrolls, and one gesture cannot mean both without
                            // nested-scroll arbitration. The close button keeps its
                            // own tap — a drag only starts once slop is exceeded.
                            //
                            // The draggable goes on a Box that *parents* its own
                            // swallow layer, not on the Column beside one. Hit
                            // testing stops at the topmost sibling that registers:
                            // as a sibling, this would shadow the sheet-wide swallow
                            // below it, and since `draggable` does not consume a tap
                            // that never becomes a drag, the tap carried on to the
                            // scrim — so a tap on the title bar closed the sheet. As
                            // a parent, children are still hit and the swallow works.
                            Box(
                                modifier = Modifier
                                    .testTag("sheet-drag-handle")
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        state = rememberDraggableState { delta ->
                                            scope.launch {
                                                dragY.snapTo((dragY.value + delta).coerceAtLeast(0f))
                                            }
                                        },
                                        // Otherwise the sheet races the ime inset on
                                        // the way down and the drag stutters.
                                        onDragStarted = { focus.clearFocus() },
                                        onDragStopped = { velocity ->
                                            if (shouldDismiss(
                                                    dragY.value,
                                                    sheetHeight,
                                                    velocity,
                                                    flingPx,
                                                )
                                            ) {
                                                // Off the bottom edge first, so the
                                                // sheet leaves rather than blinks out.
                                                dragY.animateTo(
                                                    maxOf(sheetHeight, dragY.value),
                                                    tween(160),
                                                )
                                                onDismiss()
                                            } else {
                                                dragY.animateTo(
                                                    0f,
                                                    spring(stiffness = Spring.StiffnessMediumLow),
                                                )
                                            }
                                        },
                                    ),
                            ) {
                                Box(Modifier.matchParentSize().tap {})

                                Column {
                                    Grabber()
                                    SheetHeader(title, onDismiss)
                                }
                            }

                            // The body needs its *own* swallow layer, for the same
                            // reason the drag handle does: `verticalScroll` is a
                            // pointer-input node, so it shadows the sheet-wide
                            // swallow behind it and taps on the body's dead space —
                            // a field label, the gaps between rows — reached nothing
                            // and left the keyboard up. This one is a *child* of the
                            // scroller rather than a sibling, so the scroll cannot
                            // shadow it, and it sits behind the body's own content so
                            // the real controls still win the tap.
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Box(Modifier.matchParentSize().testTag("sheet-body-swallow").tap {})

                                Column(
                                    modifier = Modifier.padding(bodyPadding),
                                    verticalArrangement = Arrangement.spacedBy(bodySpacing),
                                    content = body,
                                )
                            }

                            footer?.invoke()
                    }
                }
                }
            }
        }
    }
}

/**
 * How far along its own exit the sheet has been dragged, 0 (home) to 1 (gone).
 *
 * Guards the unmeasured first frame, where [height] is still 0 and the ratio
 * would be a NaN that silently blanks whatever it is fed to.
 */
internal fun dragFraction(dragged: Float, height: Float): Float =
    if (height > 0f) (dragged / height).coerceIn(0f, 1f) else 0f

/**
 * The whole letting-go rule, as a pure function: far enough down, or thrown
 * hard enough, and the sheet goes; otherwise it springs home.
 *
 * Pulled out of the gesture so it can be tested without one. Compose's
 * synthetic pointer injection misroutes drags inside these sheets under
 * Robolectric — see [com.wgorski.peskyreminders.ui.PeskySheetTest] — so a test
 * that swiped would be measuring the test harness. This is the part with the
 * actual judgement in it, and it is exact.
 *
 * An unmeasured sheet ([height] 0) never dismisses: with no height there is no
 * threshold to be past, and dismissing on a stray touch during the first frame
 * would be the worst possible reading of the gesture.
 */
internal fun shouldDismiss(
    dragged: Float,
    height: Float,
    velocity: Float,
    flingVelocity: Float,
): Boolean {
    if (height <= 0f) return false
    return dragged > height * DismissFraction || velocity > flingVelocity
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
