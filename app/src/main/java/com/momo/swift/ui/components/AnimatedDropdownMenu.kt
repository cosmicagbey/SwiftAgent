package com.momo.swift.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * A wrapper around Material 3 [DropdownMenu] that adds a smooth
 * scale + fade entrance/exit animation for a more premium feel.
 *
 * Drop-in replacement: just swap `DropdownMenu(...)` → `AnimatedDropdownMenu(...)`.
 */
@Composable
fun AnimatedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (expanded) 1f else 0.92f,
        animationSpec = tween(durationMillis = 200),
        label = "dropdownScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "dropdownAlpha"
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
            transformOrigin = TransformOrigin(0.5f, 0f) // scale from top-center
        },
        offset = offset,
        properties = properties,
        content = content
    )
}
