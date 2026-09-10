package com.momo.swift.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.data.CustomButton
import java.util.UUID

/**
 * Describes a single entry in the shortcut store.
 *
 * @property title            Display name shown on the card.
 * @property ussdTemplate     The USSD string template dialled when the shortcut is executed.
 * @property description      Short description shown below the title.
 * @property brandColor       Accent dot colour for the card.
 * @property fixedLogAmount   If non-null, this value is used as the transaction-log amount
 *                            instead of whatever the user typed in the amount field. Use for
 *                            fixed-price products (e.g. "Mashup 5", "Mashup 10") where the
 *                            price is baked into the USSD code itself.
 */
data class StoreEntry(
    val title: String,
    val ussdTemplate: String,
    val description: String,
    val brandColor: Color,
    val fixedLogAmount: String? = null
)

/** MTN yellow brand colour. */
private val MtnYellow = Color(0xFFFFCC00)

/**
 * The five MTN shortcut entries requested by the user.
 *
 * Templates follow the parseUssdTemplate convention:
 *   - <Number>  → phone-number step (PHONE)
 *   - <Amount>  → user-typed amount step (AMOUNT)
 *   - plain digits → menu-option step (OPTION)
 *
 * For fixed-price entries (Mashup 5, Mashup 10) there is no <Amount> placeholder
 * because MTN does not ask for an amount; the price is encoded in the menu path.
 * The fixedLogAmount field ensures the correct value is recorded in the log.
 */
private val MTN_ENTRIES = listOf(
    StoreEntry(
        title       = "Data Any Amount",
        ussdTemplate = "*138*1*1*2*<Number>*<Number>*1*<Amount>*1#",
        description  = "Buy any amount of MTN data for a subscriber.",
        brandColor   = MtnYellow
    ),
    StoreEntry(
        title       = "Mashup 0.10-4.99",
        ussdTemplate = "*567*1*2*<Number>*<Number>*1*2*<Amount>*1#",
        description  = "MTN Mashup bundle — enter any amount between 0.10 and 4.99.",
        brandColor   = MtnYellow
    ),
    StoreEntry(
        title        = "Mashup 5",
        ussdTemplate = "*567*1*2*<Number>*<Number>*2*1*2#",
        description  = "MTN Mashup GHS 5 fixed bundle.",
        brandColor   = MtnYellow,
        fixedLogAmount = "5"
    ),
    StoreEntry(
        title       = "Mashup 6-9.99",
        ussdTemplate = "*567*1*2*<Number>*<Number>*2*2*<Amount>*1#",
        description  = "MTN Mashup bundle — enter any amount between 6 and 9.99.",
        brandColor   = MtnYellow
    ),
    StoreEntry(
        title        = "Mashup 10",
        ussdTemplate = "*567*1*2*<Number>*<Number>*3*1*2#",
        description  = "MTN Mashup GHS 10 fixed bundle.",
        brandColor   = MtnYellow,
        fixedLogAmount = "10"
    )
)

/**
 * Builds the network tag that is stored on the [CustomButton].
 * For variable-amount shortcuts the tag is simply "MTN".
 * For fixed-amount shortcuts the tag is "MTN|fixed:<amount>" so that
 * HomeScreen can read the correct log amount at execution time.
 */
private fun networkTag(entry: StoreEntry): String =
    if (entry.fixedLogAmount != null) "MTN|fixed:${entry.fixedLogAmount}" else "MTN"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreSheet(
    existingShortcuts: List<CustomButton>,
    onDismiss: () -> Unit,
    onImport: (CustomButton) -> Unit,
    onRemove: (CustomButton) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // ── Header ───────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shortcut Store",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                BounceIconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            Text(
                text = "Tap a shortcut to add or remove it from your home screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── MTN Shortcuts Section ─────────────────────────────────────────────
            MtnSectionHeader()

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(MTN_ENTRIES) { entry ->
                    val existing = existingShortcuts.find { btn ->
                        btn.ussdTemplate.filterNot(Char::isWhitespace) ==
                            entry.ussdTemplate.filterNot(Char::isWhitespace)
                    }
                    val isAdded = existing != null

                    ShortcutCard(
                        entry    = entry,
                        isAdded  = isAdded,
                        onClick  = {
                            if (isAdded && existing != null) {
                                onRemove(existing)
                            } else {
                                onImport(
                                    CustomButton(
                                        id           = UUID.randomUUID().toString(),
                                        title        = entry.title,
                                        ussdTemplate = entry.ussdTemplate,
                                        network      = networkTag(entry)
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

@Composable
private fun MtnSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFFFFCC00).copy(alpha = 0.20f), Color(0xFFFFCC00).copy(alpha = 0.05f))
                )
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector      = Icons.Rounded.SignalCellularAlt,
            contentDescription = null,
            tint             = Color(0xFFFFCC00),
            modifier         = Modifier.size(20.dp)
        )
        Column {
            Text(
                text       = "MTN Shortcuts",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = Color(0xFFBB9000)
            )
            Text(
                text  = "USSD shortcuts for MTN data & Mashup bundles",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ShortcutCard(
    entry:   StoreEntry,
    isAdded: Boolean,
    onClick: () -> Unit
) {
    val surfaceColor by animateColorAsState(
        targetValue = if (isAdded)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        label = "cardColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isAdded)
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        label = "borderColor"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClickable(onClick = onClick),
        shape  = MaterialTheme.shapes.medium,
        color  = surfaceColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand accent dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(entry.brandColor, shape = CircleShape)
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = entry.title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text  = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Show fixed-amount badge if applicable, hide the USSD code template completely
                if (entry.fixedLogAmount != null) {
                    Text(
                        text       = "Fixed: GHS ${entry.fixedLogAmount}",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.primary,
                        modifier   = Modifier.padding(top = 3.dp),
                        fontSize   = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Checkbox(
                checked         = isAdded,
                onCheckedChange = null,
                colors          = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
