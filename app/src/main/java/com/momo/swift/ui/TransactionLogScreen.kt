package com.momo.swift.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.momo.swift.ui.components.bounceClickable
import com.momo.swift.ui.components.bounceCombinedClickable
import com.momo.swift.ui.components.BounceIconButton
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import com.momo.swift.ui.components.AnimatedDropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.data.AppDatabase
import com.momo.swift.data.AppSettings
import com.momo.swift.data.TransactionLogDao
import com.momo.swift.data.TransactionLogEntry
import com.momo.swift.data.TransactionStatus
import com.momo.swift.util.TransactionExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


private val transactionItemDateFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("EEE, MMM dd \u2022 HH:mm", Locale.getDefault())
}

// Not @Composable — safe to call inside remember { } lambdas.
// Pass highlightColor explicitly from the composable scope above.
private fun getHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var startIdx = 0
        val queryLen = query.length
        val lowerText = text.lowercase(Locale.getDefault())
        val lowerQuery = query.lowercase(Locale.getDefault())
        
        while (true) {
            val matchIdx = lowerText.indexOf(lowerQuery, startIdx)
            if (matchIdx == -1) {
                append(text.substring(startIdx))
                break
            }
            append(text.substring(startIdx, matchIdx))
            pushStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.Bold))
            append(text.substring(matchIdx, matchIdx + queryLen))
            pop()
            startIdx = matchIdx + queryLen
        }
    }
}

/**
 * Transaction log screen displayed inside the main app scaffold.
 * Uses LazyColumn for efficient rendering of large transaction histories.
 */

@Composable
fun TransactionLogScreen(
    settings: AppSettings,
    searchQuery: String = "",
    onReportFraud: () -> Unit = {}
) {
    val context = LocalContext.current
    val dao = remember(context) { AppDatabase.getInstance(context).transactionLogDao() }
    val viewModel: TransactionLogViewModel = viewModel(factory = TransactionLogViewModel.Factory(dao))

    // Sync search query from parent state to ViewModel
    androidx.compose.runtime.LaunchedEffect(searchQuery) {
        viewModel.setSearchQuery(searchQuery)
    }
    
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val selectedTypeFilter by viewModel.selectedTypeFilter.collectAsState()
    val filteredLogs by viewModel.filteredLogs.collectAsState()
    val summaryTotal by viewModel.summaryTotal.collectAsState()
    val summaryCount by viewModel.summaryCount.collectAsState()
    
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Scroll to the top (most recent) every time the screen first appears
    androidx.compose.runtime.LaunchedEffect(Unit) {
        listState.scrollToItem(0)
    }

    // Also scroll to top when a brand-new transaction is inserted
    val firstLogId = filteredLogs.firstOrNull()?.id
    androidx.compose.runtime.LaunchedEffect(firstLogId) {
        if (firstLogId != null) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Summary Bar ─────────────────────────────────────────────
        TransactionSummaryBar(
            total = summaryTotal,
            count = summaryCount,
            selectedPeriod = selectedPeriod,
            onPeriodChange = { viewModel.setPeriod(it) },
            selectedTypeFilter = selectedTypeFilter,
            onTypeFilterChange = { viewModel.setTypeFilter(it) },
            onExportCsv = {
                scope.launch(Dispatchers.IO) {
                    TransactionExporter.exportCsv(context, filteredLogs)
                }
            },
            onExportPdf = {
                scope.launch(Dispatchers.IO) {
                    TransactionExporter.exportPdf(context, filteredLogs)
                }
            },
            onClearAll = { showClearConfirm = true },
            hasLogs = filteredLogs.isNotEmpty(),
            onReportFraud = onReportFraud
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )



        // ── Transaction list ────────────────────────────────────────
        if (filteredLogs.isEmpty()) {
            EmptyLogsView()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredLogs,
                    key = { it.id },
                    contentType = { "transaction_item" }
                ) { entry ->
                    TransactionItem(
                        entry = entry,
                        dao = dao,
                        scope = scope,
                        searchQuery = searchQuery
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear History?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete all transaction records. This action cannot be undone.") },
            confirmButton = {
                BounceButton(
                    onClick = {
                        scope.launch {
                            dao.clearAll()
                            showClearConfirm = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ── Summary bar composable ──────────────────────────────────────────────────

@Composable
private fun TransactionSummaryBar(
    total: Double,
    count: Int,
    selectedPeriod: SummaryPeriod,
    onPeriodChange: (SummaryPeriod) -> Unit,
    selectedTypeFilter: TransactionTypeFilter,
    onTypeFilterChange: (TransactionTypeFilter) -> Unit,
    onExportCsv: () -> Unit,
    onExportPdf: () -> Unit,
    onClearAll: () -> Unit,
    hasLogs: Boolean,
    onReportFraud: () -> Unit
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    val formattedTotal = remember(total) {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(total)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top row: amount + overflow menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Total amount
            Column {
                AnimatedContent(
                    targetState = formattedTotal,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "total"
                ) { total ->
                    Text(
                        text = "GHS $total",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "$count completed transaction${if (count != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Overflow menu
            Box {
                BounceIconButton(onClick = { overflowExpanded = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedDropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Export as CSV") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.TableChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            overflowExpanded = false
                            onExportCsv()
                        },
                        enabled = hasLogs
                    )
                    DropdownMenuItem(
                        text = { Text("Export as PDF") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            overflowExpanded = false
                            onExportPdf()
                        },
                        enabled = hasLogs
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Clear All", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            overflowExpanded = false
                            onClearAll()
                        },
                        enabled = hasLogs
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Report Fraud", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            overflowExpanded = false
                            onReportFraud()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type filter chips with equal width
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransactionTypeFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedTypeFilter == filter,
                        onClick = { onTypeFilterChange(filter) },
                        label = {
                            Text(
                                text = filter.label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTypeFilter == filter) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Period filter icon button and dropdown
            Box {
                var periodDropdownExpanded by remember { mutableStateOf(false) }
                BounceIconButton(
                    onClick = { periodDropdownExpanded = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = "Filter by Period",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                AnimatedDropdownMenu(
                    expanded = periodDropdownExpanded,
                    onDismissRequest = { periodDropdownExpanded = false }
                ) {
                    SummaryPeriod.entries.forEach { period ->
                        DropdownMenuItem(
                            text = { Text(period.label) },
                            leadingIcon = {
                                if (selectedPeriod == period) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            onClick = {
                                onPeriodChange(period)
                                periodDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Empty state ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyLogsView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(100.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "No Activity Yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Transactions will appear here for your records.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ── Transaction card ────────────────────────────────────────────────────────

@Composable
private fun TransactionItem(
    entry: TransactionLogEntry,
    dao: TransactionLogDao,
    scope: CoroutineScope,
    searchQuery: String
) {
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    val (statusLabel, statusColor, statusIcon) = when (entry.status) {
        TransactionStatus.SUCCESS -> Triple("Completed", MaterialTheme.colorScheme.primary, Icons.Rounded.CheckCircle)
        else -> Triple("Failed", MaterialTheme.colorScheme.error, Icons.Rounded.Error)
    }

    // Hoist theme color so it can be captured inside remember lambdas (non-composable context).
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)

    val dateStr = remember(entry.timestamp) {
        transactionItemDateFormatter.get().format(Date(entry.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceCombinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    if (entry.phone.isNotBlank()) {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        clipboardManager.setText(AnnotatedString(entry.phone))
                        android.widget.Toast.makeText(
                            context,
                            "Copied: ${entry.phone}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$statusLabel transaction: ${entry.name}. Amount: GHS ${entry.amount}. Phone: ${entry.phone}. Date: $dateStr."
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (expanded) 6.dp else 2.dp),
        border = BorderStroke(
            width = if (expanded) 1.5.dp else 1.dp,
            color = if (expanded)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column {
            // ── Main content ───────────────────────────────────────────
            Column(modifier = Modifier.padding(12.dp)) {
                // Top row: status icon, name/date, amount
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.1f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(16.dp))
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        val highlightedName = remember(entry.name, searchQuery, highlightColor) {
                            getHighlightedText(
                                text = entry.name,
                                query = searchQuery,
                                highlightColor = highlightColor
                            )
                        }
                        Text(text = highlightedName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (entry.amount.isNotBlank()) {
                        val highlightedAmount = remember(entry.amount, searchQuery, highlightColor) {
                            getHighlightedText(
                                text = entry.amount,
                                query = searchQuery,
                                highlightColor = highlightColor
                            )
                        }
                        val fullAmountText = remember(highlightedAmount) {
                            buildAnnotatedString {
                                append("GHS ")
                                append(highlightedAmount)
                            }
                        }
                        Text(
                            text = fullAmountText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Bottom row: phone + status badge + share
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (entry.phone.isNotBlank()) {
                        val highlightedPhone = remember(entry.phone, searchQuery, highlightColor) {
                            getHighlightedText(
                                text = entry.phone,
                                query = searchQuery,
                                highlightColor = highlightColor
                            )
                        }
                        Text(
                            text = highlightedPhone,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = statusColor.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = statusLabel.uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Black,
                                color = statusColor
                            )
                        }
                        BounceIconButton(
                            onClick = { shareTransaction(context, entry) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Share,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Manual override dropdown (animated) ────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Mark as:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )

                        // ✓ Success button
                        Surface(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .bounceClickable {
                                    expanded = false
                                    scope.launch(Dispatchers.IO) {
                                        dao.updateStatus(
                                            entry.id,
                                            TransactionStatus.SUCCESS,
                                            entry.responseText
                                        )
                                    }
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = Color(0xFF1B8A5A).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFF1B8A5A).copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Mark successful",
                                    tint = Color(0xFF1B8A5A),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Successful",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B8A5A)
                                )
                            }
                        }

                        // ✗ Failed button
                        Surface(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.medium)
                                .bounceClickable {
                                    expanded = false
                                    scope.launch(Dispatchers.IO) {
                                        dao.updateStatus(
                                            entry.id,
                                            TransactionStatus.FAILED,
                                            entry.responseText
                                        )
                                    }
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Cancel,
                                    contentDescription = "Mark failed",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Failed",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


private fun shareTransaction(context: Context, entry: TransactionLogEntry) {
    val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
    val text = """
        Momo Transaction Receipt
        -----------------------
        Type: ${entry.name}
        Amount: GHS ${entry.amount}
        Phone: ${entry.phone}
        Status: ${entry.status}
        Date: $dateStr
        
        Generated by Swift Agent
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share Transaction Receipt"))
}

