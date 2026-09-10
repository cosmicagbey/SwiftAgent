package com.momo.swift.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun DashboardHeader(
    isAccessibilityEnabled: Boolean,
    isRestrictedSettings: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onCheckBalance: () -> Unit,
    onBroadcastScammer: () -> Unit = {},
    onOpenFraudRegistry: () -> Unit = {},
    onReportFraud: () -> Unit = {}
) {
    // Show the disclosure automatically when accessibility is off.
    // hasDeclinedThisSession persists across recompositions within the same session;
    // once the user explicitly taps "I Agree" or "No Thanks" it won't show again.
    val hasDeclinedThisSession = remember { mutableStateOf(false) }
    val showDisclosure = !isAccessibilityEnabled && !hasDeclinedThisSession.value
    var menuExpanded by remember { mutableStateOf(false) }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { /* Google Play: Consent dialog cannot be dismissed by tapping outside */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            icon = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    "Accessibility Permission Required",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Swift Agent uses the Accessibility Service API to facilitate and automate USSD-based mobile money transactions.\n\n" +
                    "Specifically, the service will:\n" +
                    "• Detect and read the content of mobile money (USSD) dialogs on your screen.\n" +
                    "• Type required inputs (such as phone numbers, amounts, and menu options) into the USSD input fields.\n" +
                    "• Click the Send/Reply/OK button to progress through the payment screens.\n\n" +
                    "Important Notice:\n" +
                    "• This service is used strictly to automate your mobile money transaction workflow.\n" +
                    "• We DO NOT collect, store, or share any personal, sensitive, or financial user data.\n" +
                    "• All transaction results are kept only in a local database on your device.\n\n" +
                    "By clicking 'I Agree', you will be taken to your device settings to enable this permission.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hasDeclinedThisSession.value = true
                        onOpenAccessibilitySettings()
                    }
                ) {
                    Text("I Agree", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { hasDeclinedThisSession.value = true }) {
                    Text("No Thanks")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isAccessibilityEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isAccessibilityEnabled) "Active" else "Inactive",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onCheckBalance,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Balance", style = MaterialTheme.typography.labelMedium)
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Security & Fraud Options",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFD32F2F),
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = {
                                    Text(
                                        "Broadcast Scammer",
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFD32F2F)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onBroadcastScammer()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = { Text("Fraud Registry & Alerts") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenFraudRegistry()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFFE65100),
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                text = { Text("Report Fraud to MTN") },
                                onClick = {
                                    menuExpanded = false
                                    onReportFraud()
                                }
                            )
                        }
                    }
                }
            }

            if (!isAccessibilityEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(6.dp))
                
                if (isRestrictedSettings) {
                    TextButton(
                        onClick = onOpenAppInfo,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enable Restricted Settings", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    TextButton(
                        onClick = { hasDeclinedThisSession.value = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Enable Accessibility", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
