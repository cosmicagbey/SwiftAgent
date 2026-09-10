package com.momo.swift.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Shape

/**
 * Attaches a press-feedback visual effect to any composable.
 * When pressed: the element shrinks to 95% and dims to 75% opacity,
 * and a short haptic pulse fires.
 *
 * This modifier works by reading the press state from the provided
 * [interactionSource] and animating [graphicsLayer] properties.
 *
 * Usage — attach AFTER the element's own modifiers:
 * ```
 * Box(modifier = Modifier.pressFeedback(interactionSource))
 * ```
 */
@Composable
fun Modifier.pressFeedback(interactionSource: MutableInteractionSource): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // Trigger haptic once when the press begins
    if (isPressed) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "press_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.75f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "press_alpha"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/**
 * A [Modifier.clickable] that automatically includes [pressFeedback].
 * No ripple — the scale/alpha animation gives sufficient visual response.
 */
@Composable
fun Modifier.bounceClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressFeedback(interactionSource)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * A [Modifier.combinedClickable] with built-in [pressFeedback].
 * Supports both click and long-click. No ripple.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bounceCombinedClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .pressFeedback(interactionSource)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick
        )
}

// ─── Drop-in replacements ─────────────────────────────────────────────────────

/**
 * Drop-in replacement for [Button] that plays a press-feedback animation
 * (scale + alpha + haptic) when tapped.
 */
@Composable
fun BounceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.pressFeedback(interactionSource),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Drop-in replacement for [TextButton] with press-feedback animation.
 */
@Composable
fun BounceTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.pressFeedback(interactionSource),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content
    )
}

/**
 * Drop-in replacement for [IconButton] with press-feedback animation.
 */
@Composable
fun BounceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.pressFeedback(interactionSource),
        enabled = enabled,
        interactionSource = interactionSource,
        content = content
    )
}
