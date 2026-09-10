package com.momo.swift.ui

import android.Manifest
import android.annotation.SuppressLint
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.momo.swift.data.AppDatabase
import com.momo.swift.data.AppSettings
import com.momo.swift.data.TrialStatus
import com.momo.swift.data.UssdStep
import com.momo.swift.data.UssdStepType
import com.momo.swift.data.UssdTransaction
import com.momo.swift.service.UssdStateManager
import com.momo.swift.ui.components.AnimatedDropdownMenu
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.util.detectCarrier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    trialStatus: TrialStatus?,
    onSettingsChanged: (AppSettings) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasReadPhoneStatePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    // Re-check permission each time the screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasReadPhoneStatePermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedSimId by remember(settings.selectedSimId) { mutableStateOf(settings.selectedSimId) }
    var simExpanded by remember { mutableStateOf(false) }
    var showSavedStatus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val availableSims = remember { mutableStateListOf<SubscriptionInfo>() }

    LaunchedEffect(hasReadPhoneStatePermission) {
        val sims = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getAvailableSims(context)
        }
        availableSims.clear()
        availableSims.addAll(sims)
    }

    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connectivity Section
            SettingsSection(
                title = "Connectivity",
                icon = Icons.Rounded.SimCard
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        OutlinedTextField(
                            value = availableSims.firstOrNull { it.subscriptionId == selectedSimId }?.displayName?.toString()
                                ?: "Default SIM",
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Default SIM") },
                            supportingText = {
                                if (!hasReadPhoneStatePermission) {
                                    Text("Permission required to list SIMs", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            trailingIcon = { Icon(if (simExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(enabled = hasReadPhoneStatePermission) {
                                    simExpanded = !simExpanded
                                }
                        )
                        AnimatedDropdownMenu(
                            expanded = simExpanded && hasReadPhoneStatePermission,
                            onDismissRequest = { simExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Default (System Selected)") },
                                onClick = {
                                    selectedSimId = null
                                    simExpanded = false
                                }
                            )
                            availableSims.forEach { sim ->
                                DropdownMenuItem(
                                    text = { Text("${sim.displayName} (${sim.subscriptionId})") },
                                    onClick = {
                                        selectedSimId = sim.subscriptionId
                                        simExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Save Action & back buttons just below Connectivity
            BounceButton(
                onClick = {
                    val newSettings = settings.copy(selectedSimId = selectedSimId)
                    onSettingsChanged(newSettings)
                    showSavedStatus = true
                    Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        delay(1500)
                        showSavedStatus = false
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Save & Go Back")
            }

            AnimatedVisibility(visible = showSavedStatus) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Settings saved",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Account Details Section
            SettingsSection(title = "Account Details", icon = Icons.Rounded.CreditCard) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Account email — fall back to currently signed-in Firebase user
                    val displayEmail = settings.userEmail.takeIf { it.isNotEmpty() }
                        ?: com.momo.swift.auth.AuthManager.currentUser?.email
                        ?: "Not signed in"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Account: $displayEmail",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Subscription status
                    val isPremium = trialStatus?.isPremium ?: settings.isPremium
                    val expiresAt = if (isPremium) {
                        trialStatus?.premiumExpiresAt ?: settings.premiumExpiresAt
                    } else {
                        val start = trialStatus?.trialStartedAt?.takeIf { it > 0 }
                            ?: settings.trialStartedAt.takeIf { it > 0 }
                            ?: settings.firstLaunchTimestamp
                        if (start > 0) start + (60L * 24L * 60L * 60L * 1000L) else 0L
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isPremium) Icons.Rounded.Verified else Icons.Rounded.Timer,
                            contentDescription = null,
                            tint = if (isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isPremium) "Plan: Premium" else "Plan: Free Trial",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (expiresAt > 0) {
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        val label = if (isPremium) "Renewal Date" else "Trial Expires"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Event,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "$label: ${dateFormat.format(Date(expiresAt))}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Floating WhatsApp icon
        FloatingActionButton(
            onClick = { uriHandler.openUri("https://chat.whatsapp.com/FoEQzekuKnRKFIvqYVfrBo") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = androidx.compose.ui.graphics.Color(0xFF25D366),
            contentColor = androidx.compose.ui.graphics.Color.White
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Chat,
                contentDescription = "Join WhatsApp Group"
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (action != null) {
                    action()
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@SuppressLint("MissingPermission")
private fun getAvailableSims(context: android.content.Context): List<SubscriptionInfo> {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_PHONE_STATE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return emptyList()
    val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
    return try {
        manager.activeSubscriptionInfoList ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }
}
