package com.wgorski.peskyreminders.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The travel bar. Thin, because the thumb is the thing you aim at. */
private val TrackHeight = 6.dp

/** The thumb — and with it how far in from each edge the track's ends sit. */
private val ThumbSize = 22.dp

/** The row's own height: a thumb-sized target is not a thumb-sized control. */
private val RowHeight = 44.dp

/**
 * A whole-number slider in the app's own language — no Material chrome, so no
 * ripple and no tick marks.
 *
 * Reports twice, and the difference matters. [onValueChange] fires all the way
 * through the drag so the readout above it can follow the thumb; [onCommit] fires
 * once, on release, and is the only one that should reach storage. The settings
 * sheet's nag interval re-arms every showing reminder's alarm when it changes —
 * see `Reminders.applyNagSettings` — which must not happen once a frame. A drag
 * always ends, so unlike a text field there is nothing to rescue on the way out.
 *
 * Four things about it worth keeping:
 *
 * - **The gesture is [draggable], not a raw `pointerInput`.** The sheet body
 *   scrolls, and `draggable` with a horizontal orientation leaves a vertical drag
 *   to the scroller instead of swallowing it. The cost is that a bare **tap** on
 *   the track does nothing at all: the gesture only begins once horizontal touch
 *   slop is passed. That is the right trade here — a stray tap while scrolling
 *   past should not silently change a setting.
 * - **It follows the finger even so.** `onDragStarted` hands over where the drag
 *   began, so the thumb jumps there before the deltas start arriving, rather than
 *   creeping from wherever it happened to be.
 * - **All three mappings are pure** — [sliderFraction], [sliderValue],
 *   [sliderFractionAt] — and tested exactly. Compose's pointer injection
 *   misroutes drags inside these sheets under Robolectric (see [PeskySheetTest]),
 *   so the arithmetic is what gets unit-tested and the gesture is an emulator
 *   check. Same shape as `shouldDismiss`.
 * - **It publishes [ProgressBarRangeInfo] and a `setProgress` action**, in the
 *   real unit rather than a 0..1 fraction. That is what a screen reader adjusts,
 *   and it is also the seam a JVM test drives — `performSemanticsAction` needs no
 *   pointer. The action commits as well as moves, because an assistive adjustment
 *   is a finished act, not the middle of a drag.
 */
@Composable
fun PeskySlider(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbPx = with(density) { ThumbSize.toPx() }
    val trackPx = with(density) { TrackHeight.toPx() }

    var widthPx by remember { mutableFloatStateOf(0f) }
    // Where the finger is, in this row's own pixels. Held rather than derived,
    // because `draggable` reports movement as deltas and never as a position.
    var touchX by remember { mutableFloatStateOf(0f) }

    fun valueAt(x: Float) = sliderValue(sliderFractionAt(x, widthPx, thumbPx), min, max)

    val fraction = sliderFraction(value, min, max)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    touchX += delta
                    onValueChange(valueAt(touchX))
                },
                onDragStarted = { start ->
                    touchX = start.x
                    onValueChange(valueAt(touchX))
                },
                onDragStopped = { onCommit(valueAt(touchX)) },
            )
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.toFloat(),
                    range = min.toFloat()..max.toFloat(),
                    steps = (max - min - 1).coerceAtLeast(0),
                )
                setProgress { target ->
                    val next = target.roundToInt().coerceIn(min, max)
                    onValueChange(next)
                    onCommit(next)
                    true
                }
            },
    ) {
        val radius = thumbPx / 2f
        val centreY = size.height / 2f
        val left = radius
        val right = size.width - radius
        // The unfilled half is the switch's own off-track colour, so the two
        // controls in this sheet agree about what "not chosen" looks like.
        drawLine(
            color = PeskyColors.FieldBorder,
            start = Offset(left, centreY),
            end = Offset(right, centreY),
            strokeWidth = trackPx,
            cap = StrokeCap.Round,
        )
        val centreX = left + (right - left) * fraction
        if (centreX > left) {
            drawLine(
                color = PeskyColors.Accent,
                start = Offset(left, centreY),
                end = Offset(centreX, centreY),
                strokeWidth = trackPx,
                cap = StrokeCap.Round,
            )
        }
        // Cream, like the switch knob — and like every label that sits on the
        // accent, for the reason in PeskyColors.Accent.
        drawCircle(color = PeskyColors.Text, radius = radius, center = Offset(centreX, centreY))
    }
}

/**
 * Where the thumb sits for [value]: 0 at [min], 1 at [max].
 *
 * A degenerate range pins it to the left rather than dividing by zero.
 */
internal fun sliderFraction(value: Int, min: Int, max: Int): Float =
    if (max <= min) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)

/**
 * The whole number a thumb at [fraction] means, rounded to the nearest.
 *
 * Rounding rather than truncating is what makes [sliderFraction] and this each
 * other's inverse for every value in range — a truncating version loses the top
 * of every step and can never reach [max].
 */
internal fun sliderValue(fraction: Float, min: Int, max: Int): Int =
    (min + (fraction.coerceIn(0f, 1f) * (max - min)).roundToInt()).coerceIn(min, max)

/**
 * Turns a touch at [x] into a fraction of the track, allowing for the thumb.
 *
 * The thumb's centre can only travel between the two half-widths, or it would
 * hang off the ends, so the usable span is [width] less one whole [thumb] — and a
 * touch at the very left edge has to read as 0 rather than as a negative.
 *
 * Returns 0 for an unmeasured row, where [width] is still 0 and the span would be
 * negative.
 */
internal fun sliderFractionAt(x: Float, width: Float, thumb: Float): Float {
    val travel = width - thumb
    if (travel <= 0f) return 0f
    return ((x - thumb / 2f) / travel).coerceIn(0f, 1f)
}
