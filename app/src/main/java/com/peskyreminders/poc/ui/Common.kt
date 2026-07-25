package com.peskyreminders.poc.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Text styles as specified in the design. */
object PeskyType {
    val Logo = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp, letterSpacing = (-0.01).em,
    )
    val SheetTitle = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp, letterSpacing = (-0.01).em, color = PeskyColors.Text,
    )
    val Action = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 15.sp,
    )
    val Stamp = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, letterSpacing = 0.04.em, color = PeskyColors.TextMuted,
    )
    val SectionLabel = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, letterSpacing = 0.12.em,
    )
    val ColumnLabel = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, letterSpacing = 0.1.em, color = PeskyColors.TextMuted,
    )
    val TaskName = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp, lineHeight = 20.sp, color = PeskyColors.Text,
    )
    val TaskWhen = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    )
    val Pill = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
    )
    val FieldLabel = TextStyle(
        fontFamily = DmSans, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 18.sp, color = PeskyColors.TextDim,
    )
    val Body = TextStyle(fontFamily = DmSans, fontSize = 13.sp, color = PeskyColors.TextMuted)
    val Input = TextStyle(fontFamily = DmSans, fontSize = 14.sp, color = PeskyColors.Text)
}

/**
 * The design's `style-active="transform:scale(…)"` — the only press feedback in
 * the whole kit, so no ripples anywhere.
 */
@Composable
fun Modifier.pressable(
    scale: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val factor by animateFloatAsState(
        targetValue = if (pressed && enabled) scale else 1f,
        animationSpec = tween(90),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = factor; scaleY = factor }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/** A tap with no visual feedback at all — scrims, wheel rows, calendar cells. */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

/** A rounded, optionally outlined surface — the recurring card/chip/field shape. */
@Composable
fun PeskySurface(
    modifier: Modifier = Modifier,
    background: Color,
    borderColor: Color? = null,
    radius: Dp,
    padding: PaddingValues = PaddingValues(),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val shape: Shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .padding(padding),
        contentAlignment = contentAlignment,
    ) { content() }
}
