package com.momo.swift.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.momo.swift.data.CustomButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomButtonsListModal(
    customButtons: List<CustomButton>,
    isIdle: Boolean,
    onDismiss: () -> Unit,
    onAddNew: () -> Unit,
    onExecute: (CustomButton) -> Unit,
    onEdit: (CustomButton) -> Unit,
    onDelete: (CustomButton) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Custom Shortcuts",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    if (customButtons.isNotEmpty()) {
                        Text(
                            "Tap card to run",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                BounceIconButton(onClick = onAddNew, enabled = isIdle) {
                    Icon(Icons.Default.Add, contentDescription = "Add New")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            if (customButtons.isEmpty()) {
                // ── Empty state ───────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.DashboardCustomize,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No custom shortcuts yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BounceTextButton(onClick = onAddNew, enabled = isIdle) {
                            Text("Create your first one")
                        }
                    }
                }
            } else {
                // ── Shortcut list ────────────────────────────────────────────
                ShortcutList(
                    buttons   = customButtons,
                    isIdle    = isIdle,
                    onExecute = onExecute,
                    onEdit    = onEdit,
                    onDelete  = onDelete
                )
            }
        }
    }
}

// ── Shortcut list ────────────────────────────────────────────────────────────

@Composable
private fun ShortcutList(
    buttons:   List<CustomButton>,
    isIdle:    Boolean,
    onExecute: (CustomButton) -> Unit,
    onEdit:    (CustomButton) -> Unit,
    onDelete:  (CustomButton) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        buttons.forEach { button ->
            val accessibilityText = remember(button.title, button.network) {
                val networkLabel = button.network?.let { net ->
                    when {
                        net.startsWith("MTN|fixed:") -> "MTN. Fixed GHS ${net.removePrefix("MTN|fixed:")}"
                        else                          -> net
                    }
                } ?: "Default network"
                "Shortcut for ${button.title}. Network details: $networkLabel."
            }
            
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = accessibilityText
                    },
                onClick   = { if (isIdle) onExecute(button) },
                enabled   = isIdle,
                shape     = MaterialTheme.shapes.medium,
                colors    = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                ),
                elevation = CardDefaults.outlinedCardElevation(
                    defaultElevation = 0.dp
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── Brand Accent Dot ──────────────────────────────────
                    val brandColor = if (button.network?.startsWith("MTN") == true) {
                        Color(0xFFFFCC00)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(brandColor, shape = CircleShape)
                    )

                    Spacer(Modifier.width(14.dp))

                    // ── Title & network badge ─────────────────────────────
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = button.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                        val networkLabel = button.network?.let { net ->
                            when {
                                net.startsWith("MTN|fixed:") -> "MTN · Fixed GHS ${net.removePrefix("MTN|fixed:")}"
                                else                          -> net
                            }
                        }
                        if (networkLabel != null) {
                            Text(
                                text  = networkLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // ── Edit / Delete ─────────────────────────────────────
                    BounceIconButton(
                        onClick  = { onEdit(button) },
                        enabled  = isIdle
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    BounceIconButton(
                        onClick  = { onDelete(button) },
                        enabled  = isIdle,
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
