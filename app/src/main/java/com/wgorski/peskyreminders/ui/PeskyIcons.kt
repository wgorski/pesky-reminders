package com.wgorski.peskyreminders.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The design's `assets/icons` SVG set, redrawn as stroked vectors.
 *
 * The exported SVGs are outline-expanded (strokes baked into fill paths); these
 * are the same shapes kept as strokes so they stay crisp at any size and can be
 * tinted by [androidx.compose.material3.Icon].
 */
object PeskyIcons {

    val Bell: ImageVector = stroked("bell") {
        moveTo(6f, 8f)
        arcToRelative(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 0f)
        curveToRelative(0f, 7f, 3f, 9f, 3f, 9f)
        horizontalLineTo(3f)
        reflectiveCurveToRelative(3f, -2f, 3f, -9f)
        moveTo(10.3f, 21f)
        arcToRelative(1.94f, 1.94f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3.4f, 0f)
    }

    val Check: ImageVector = stroked("check") {
        moveTo(20f, 6f)
        lineTo(9f, 17f)
        lineToRelative(-5f, -5f)
    }

    val ChevronDown: ImageVector = stroked("chevron-down") {
        moveTo(6f, 9f)
        lineTo(12f, 15f)
        lineTo(18f, 9f)
    }

    val ChevronRight: ImageVector = stroked("chevron-right") {
        moveTo(9f, 18f)
        lineTo(15f, 12f)
        lineTo(9f, 6f)
    }

    val Clock: ImageVector = stroked("clock") {
        moveTo(12f, 2f)
        arcToRelative(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = false, 0f, 20f)
        arcToRelative(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = false, 0f, -20f)
        moveTo(12f, 6f)
        verticalLineToRelative(6f)
        lineToRelative(4f, 2f)
    }

    val Repeat: ImageVector = stroked("repeat") {
        moveTo(17f, 2f)
        lineTo(21f, 6f)
        lineTo(17f, 10f)
        moveTo(3f, 11f)
        verticalLineToRelative(-1f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, -4f)
        horizontalLineToRelative(14f)
        moveTo(7f, 22f)
        lineTo(3f, 18f)
        lineTo(7f, 14f)
        moveTo(21f, 13f)
        verticalLineToRelative(1f)
        arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4f, 4f)
        horizontalLineTo(3f)
    }

    /** Settings — sliders rather than a gear: this screen tunes one dial. */
    val Sliders: ImageVector = stroked("sliders") {
        moveTo(21f, 4f); horizontalLineTo(14f)
        moveTo(10f, 4f); horizontalLineTo(3f)
        moveTo(21f, 12f); horizontalLineTo(12f)
        moveTo(8f, 12f); horizontalLineTo(3f)
        moveTo(21f, 20f); horizontalLineTo(16f)
        moveTo(12f, 20f); horizontalLineTo(3f)
        moveTo(14f, 2f); verticalLineTo(6f)
        moveTo(8f, 10f); verticalLineTo(14f)
        moveTo(16f, 18f); verticalLineTo(22f)
    }

    /** The FAB glyph — heavier stroke than the icon set, per the design. */
    val Plus: ImageVector = stroked("plus", width = 3f) {
        moveTo(12f, 5f)
        verticalLineTo(19f)
        moveTo(5f, 12f)
        horizontalLineTo(19f)
    }

    val Close: ImageVector = stroked("close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }

    /**
     * Not in the design's set — the design had no delete. Drawn to match it:
     * 24dp canvas, 2dp stroke, straight lines over curves.
     */
    val Trash: ImageVector = stroked("trash") {
        moveTo(3f, 6f); horizontalLineTo(21f)          // lid
        moveTo(8f, 6f)                                  // handle
        verticalLineTo(4f)
        horizontalLineTo(16f)
        verticalLineTo(6f)
        moveTo(6f, 6f)                                  // tapered body
        lineTo(7f, 21f)
        horizontalLineTo(17f)
        lineTo(18f, 6f)
        moveTo(10f, 10f); verticalLineTo(17f)          // ribs
        moveTo(14f, 10f); verticalLineTo(17f)
    }

    private fun stroked(
        name: String,
        width: Float = 2f,
        build: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
    }.build()
}
