@file:OptIn(ExperimentalMaterial3Api::class)
package com.momo.swift.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import com.momo.swift.service.SilentEvidenceCaptureManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.momo.swift.data.AppDatabase
import com.momo.swift.data.AppSettings
import com.momo.swift.data.CustomButton
import com.momo.swift.data.FraudAlert
import com.momo.swift.data.FraudDetectionManager
import com.momo.swift.data.TransactionStatus
import com.momo.swift.data.TrialStatus
import com.momo.swift.data.UssdStep
import com.momo.swift.data.UssdStepType
import com.momo.swift.data.UssdTransaction
import com.momo.swift.service.UssdState
import com.momo.swift.service.UssdStateManager
import com.momo.swift.ui.components.*
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The main user interface screen where users can enter transaction details and execute USSD commands.
 * Displays an accessibility service status banner, global input fields, standard action buttons,
 * and user-defined custom buttons.
 *
 * @param appSettings Current application settings.
 * @param trialStatus Current server-side trial status.
 * @param isAccessibilityEnabled Whether the USSD AccessibilityService is currently enabled.
 * @param onOpenAccessibilitySettings Callback to open system accessibility settings.
 * @param onSettingsChanged Callback invoked when settings need to be updated (e.g., adding a custom button).
 */
@Composable
fun HomeScreen(
    appSettings: AppSettings,
    trialStatus: TrialStatus? = null,
    isAccessibilityEnabled: Boolean = false,
    isRestrictedSettings: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenAppInfo: () -> Unit = {},
    onSettingsChanged: (AppSettings) -> Unit,
    onOpenFraudRegistry: () -> Unit = {},
    onReportFraud: () -> Unit = {}
) {
    var phoneTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var amountTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var referenceTextFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val amount = amountTextFieldValue.text
    val reference = referenceTextFieldValue.text
    var phoneError by rememberSaveable { mutableStateOf(false) }
    var amountError by rememberSaveable { mutableStateOf(false) }
    var showCustomButtonDialog by remember { mutableStateOf(false) }
    var showCustomButtonsListModal by remember { mutableStateOf(false) }
    var commissionExpanded by remember { mutableStateOf(false) }
    var payToType by rememberSaveable { mutableStateOf("Subscriber") }
    var payToDropdownExpanded by remember { mutableStateOf(false) }
    var editingCustomButton by remember { mutableStateOf<CustomButton?>(null) }
    var pendingDeleteCustomButton by remember { mutableStateOf<CustomButton?>(null) }

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    
    val dao = remember(context) { AppDatabase.getInstance(context).transactionLogDao() }
    val contactDao = remember(context) { AppDatabase.getInstance(context).savedContactDao() }
    val coroutineScope = rememberCoroutineScope()

    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(contactDao))
    val matchingSuggestions by homeViewModel.matchingSuggestions.collectAsState()

    // Pass the typed phone number to the ViewModel so it can do the matching asynchronously
    LaunchedEffect(phoneTextFieldValue.text) {
        homeViewModel.onSearchQueryChanged(phoneTextFieldValue.text)
    }

    // ── Request telephony permissions once on first composition ───────────────
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled via checkSelfPermission elsewhere */ }

    LaunchedEffect(Unit) {
        val needed = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) phonePermissionLauncher.launch(needed.toTypedArray())
    }

    val ussdState by UssdStateManager.stateFlow.collectAsState()
    val isIdle = ussdState == UssdState.IDLE

    // Server-side trial status fallback to appSettings local check
    val isPremium = trialStatus?.isPremium ?: appSettings.isPremium
    val isTrialActive = trialStatus?.let { !it.trialExpired } ?: true
    val isPremiumOrTrial = isPremium || isTrialActive
    val canExecute = isIdle && isPremiumOrTrial

    var numberDropdownExpanded by remember { mutableStateOf(false) }

    val fraudAlerts by FraudDetectionManager.alertsFlow.collectAsState()
    val matchingFraudAlert = remember(phoneTextFieldValue.text, fraudAlerts) {
        FraudDetectionManager.checkNumber(phoneTextFieldValue.text)
    }
    var showFraudWarningDialog by remember { mutableStateOf(false) }
    var pendingFraudAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showBroadcastDialog by remember { mutableStateOf(false) }
    var isFaceTrapActive by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* camera permission handled */ }

    LaunchedEffect(Unit) {
        FraudDetectionManager.refreshAlerts()
    }

    fun executeTransaction(
        tx: UssdTransaction,
        baseCode: String = "*171#",
        phone: String = phoneTextFieldValue.text,
        amountValue: String = amount,
        refValue: String = reference,
        shouldLog: Boolean = true
    ) {
        UssdStateManager.dao = dao
        UssdStateManager.startTransaction(tx, phone, amountValue, refValue, shouldLog)
        // Persist phone number permanently for long-term suggestions
        if (phone.isNotBlank() && shouldLog) {
            coroutineScope.launch { contactDao.upsert(phone) }
        }
        val launched = UssdEngine.dialUssd(context, baseCode, appSettings)
        if (!launched) {
            UssdStateManager.completeTransaction(
                status = TransactionStatus.FAILED,
                responseText = "Unable to start USSD call. Check call permission and phone settings."
            )
        }
    }

    fun launchTransaction(
        tx: UssdTransaction,
        baseCode: String = "*171#",
        phone: String = phoneTextFieldValue.text,
        amountValue: String = amount,
        refValue: String = reference,
        shouldLog: Boolean = true
    ) {
        val fraud = matchingFraudAlert
        if (fraud != null) {
            pendingFraudAction = {
                executeTransaction(tx, baseCode, phone, amountValue, refValue, shouldLog)
            }
            showFraudWarningDialog = true
            return
        }
        executeTransaction(tx, baseCode, phone, amountValue, refValue, shouldLog)
    }

    // ── One-time onboarding tip ────────────────────────────────────────────────
    if (!appSettings.hasSeenOnboarding) {
        AlertDialog(
            onDismissRequest = { /* require explicit tap on Got it */ },
            icon = {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    "New here?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Tap \"How to use\" at the top right corner of the screen to watch a quick tutorial on YouTube and learn how to use the app.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        onSettingsChanged(appSettings.copy(hasSeenOnboarding = true))
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Got it!", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    pendingDeleteCustomButton?.let { buttonToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCustomButton = null },
            title = { Text("Delete Custom Shortcut") },
            text = {
                Text(
                    "Delete \"${buttonToDelete.title}\"? This shortcut will be removed from your custom buttons."
                )
            },
            confirmButton = {
                BounceTextButton(
                    onClick = {
                        val newSettings = appSettings.copy(
                            customButtons = appSettings.customButtons.filter { it.id != buttonToDelete.id }
                        )
                        onSettingsChanged(newSettings)
                        pendingDeleteCustomButton = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { pendingDeleteCustomButton = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            androidx.compose.animation.AnimatedVisibility(visible = !isIdle) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Transaction in progress...", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        BounceTextButton(onClick = { UssdStateManager.reset() }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onTertiaryContainer, textDecoration = TextDecoration.Underline)
                        }
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(8.dp))

            // Dashboard Header
            DashboardHeader(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isRestrictedSettings = isRestrictedSettings,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenAppInfo = onOpenAppInfo,
                onCheckBalance = {
                    val tx = UssdTransaction(
                        name = "Check Balance",
                        steps = listOf(UssdStep(UssdStepType.OPTION, "7"), UssdStep(UssdStepType.OPTION, "1"))
                    )
                    launchTransaction(tx, phone = "", amountValue = "", shouldLog = false)
                },
                onBroadcastScammer = { showBroadcastDialog = true },
                onOpenFraudRegistry = onOpenFraudRegistry,
                onReportFraud = onReportFraud
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
        
        item {
            // Transaction Details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Transaction Details", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Box {
                            Row(
                                modifier = Modifier.clickable { payToDropdownExpanded = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(payToType, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            AnimatedDropdownMenu(
                                expanded = payToDropdownExpanded,
                                onDismissRequest = { payToDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Agent") },
                                    onClick = { 
                                        payToType = "Agent"
                                        payToDropdownExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Merchant") },
                                    onClick = { 
                                        payToType = "Merchant"
                                        payToDropdownExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Subscriber") },
                                    onClick = { 
                                        payToType = "Subscriber"
                                        payToDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ExposedDropdownMenuBox(
                        expanded = numberDropdownExpanded,
                        onExpandedChange = { 
                            if (canExecute) numberDropdownExpanded = it 
                        }
                    ) {
                        OutlinedTextField(
                            value = phoneTextFieldValue,
                            onValueChange = { newValue ->
                                var cleanText = newValue.text.replace("\\s+".toRegex(), "")
                                if (cleanText.startsWith("+233")) {
                                    cleanText = "0" + cleanText.removePrefix("+233")
                                } else if (cleanText.startsWith("233")) {
                                    cleanText = "0" + cleanText.removePrefix("233")
                                }
                                val maxLength = if (payToType == "Merchant") 9 else 10
                                if (cleanText.length <= maxLength && cleanText.all { it.isDigit() }) {
                                    val diff = newValue.text.length - cleanText.length
                                    val newSelection = if (diff == 0) newValue.selection else TextRange(cleanText.length)
                                    phoneTextFieldValue = newValue.copy(text = cleanText, selection = newSelection)
                                    phoneError = false
                                    // Only expand if there are suggestions and we're not already done typing a full number
                                    numberDropdownExpanded = cleanText.length >= 5 && matchingSuggestions.isNotEmpty()
                                }
                            },
                            label = { Text(if (payToType == "Merchant") "Merchant ID" else "Phone Number") },
                            placeholder = { Text(if (payToType == "Merchant") "Enter Merchant ID" else "024XXXXXXX") },
                            isError = phoneError,
                            supportingText = if (phoneError) { { Text("Enter a valid number") } } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            singleLine = true,
                            enabled = canExecute,
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                            trailingIcon = if (phoneTextFieldValue.text.isNotEmpty()) {
                                { BounceIconButton(onClick = { phoneTextFieldValue = TextFieldValue("") }) { Icon(Icons.Default.Clear, contentDescription = null) } }
                            } else null,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
                        )

                        if (matchingSuggestions.isNotEmpty()) {
                            ExposedDropdownMenu(
                                expanded = numberDropdownExpanded,
                                onDismissRequest = { numberDropdownExpanded = false }
                            ) {
                                matchingSuggestions.forEach { suggestion ->
                                    DropdownMenuItem(
                                        text = { Text(suggestion.displayLabel) },
                                        onClick = {
                                            phoneTextFieldValue = TextFieldValue(
                                                text = suggestion.phone,
                                                selection = TextRange(suggestion.phone.length)
                                            )
                                            phoneError = false
                                            numberDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    // ── Real-time Fraud / Blacklist Warning Notice & Trap Button ─────
                    AnimatedVisibility(visible = matchingFraudAlert != null && !isFaceTrapActive) {
                        matchingFraudAlert?.let { alert ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "🚨 BLACKLISTED SCAMMER NUMBER",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = "${alert.fraudType}: Reported by SwiftAgent community.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }

                                    // Single-tap button to trap scammer & capture face
                                    BounceButton(
                                        onClick = {
                                            if (!SilentEvidenceCaptureManager.hasCameraPermission(context)) {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                            coroutineScope.launch {
                                                // 1. Immediately hide warning badge so the screen is 100% normal
                                                isFaceTrapActive = true

                                                // 2. First vibration: Heads-up to agent to prepare
                                                SilentEvidenceCaptureManager.triggerPreparationTick(context)

                                                // 3. 1.5s natural pause
                                                kotlinx.coroutines.delay(1500L)

                                                // 4. Second vibration: Double buzz = "Show screen to customer now"
                                                SilentEvidenceCaptureManager.triggerFlipScreenBuzz(context)

                                                // 5. Customer inspects screen for 3-4s; camera takes 3 photos silently
                                                SilentEvidenceCaptureManager.captureBurstPhotos(
                                                    context = context,
                                                    lifecycleOwner = lifecycleOwner,
                                                    phoneNumber = phoneTextFieldValue.text,
                                                    burstCount = 3,
                                                    delayBetweenMs = 800L
                                                )

                                                // 6. Silent completion tick felt only by agent
                                                SilentEvidenceCaptureManager.triggerCompletionTick(context)
                                                isFaceTrapActive = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("📸 Trap & Capture Face", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = amountTextFieldValue,
                        onValueChange = { newValue ->
                            if (newValue.text.isEmpty() || newValue.text.toDoubleOrNull() != null) {
                                amountTextFieldValue = newValue
                                amountError = false
                            }
                        },
                        label = { Text("Amount") },
                        prefix = { Text("GHS ") },
                        isError = amountError,
                        supportingText = if (amountError) { { Text("Enter a valid amount") } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = canExecute,
                        leadingIcon = { Icon(Icons.Default.Payments, contentDescription = null) },
                        trailingIcon = if (amountTextFieldValue.text.isNotEmpty()) {
                            { BounceIconButton(onClick = { amountTextFieldValue = TextFieldValue("") }) { Icon(Icons.Default.Clear, contentDescription = null) } }
                        } else null,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = referenceTextFieldValue,
                        onValueChange = { referenceTextFieldValue = it },
                        label = { Text("Reference") },
                        placeholder = { Text("Optional description") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = canExecute,
                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                        trailingIcon = if (referenceTextFieldValue.text.isNotEmpty()) {
                            { BounceIconButton(onClick = { referenceTextFieldValue = TextFieldValue("") }) { Icon(Icons.Default.Clear, contentDescription = null) } }
                        } else null,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        text = "Cash In",
                        icon = Icons.Default.AddCircle,
                        modifier = Modifier.weight(1f),
                        enabled = canExecute
                    ) {
                        if (!validateInputs(phoneTextFieldValue.text, amount, { phoneError = true }, { amountError = true })) return@ActionButton
                        val tx = UssdTransaction("Cash In", listOf(
                            UssdStep(UssdStepType.OPTION, "3"), 
                            UssdStep(UssdStepType.OPTION, "1"), 
                            UssdStep(UssdStepType.PHONE),
                            UssdStep(UssdStepType.PHONE),
                            UssdStep(UssdStepType.AMOUNT)
                        ))
                        launchTransaction(tx)
                    }
                    ActionButton(
                        text = "Cash Out",
                        icon = Icons.Default.Payments,
                        modifier = Modifier.weight(1f),
                        enabled = canExecute
                    ) {
                        if (!validateInputs(phoneTextFieldValue.text, amount, { phoneError = true }, { amountError = true })) return@ActionButton
                        val tx = UssdTransaction("Cash Out", listOf(
                            UssdStep(UssdStepType.OPTION, "2"),
                            UssdStep(UssdStepType.OPTION, "1"),
                            UssdStep(UssdStepType.PHONE),
                            UssdStep(UssdStepType.PHONE),
                            UssdStep(UssdStepType.AMOUNT)
                        ))
                        launchTransaction(tx)
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        text = "Airtime",
                        icon = Icons.Default.PhoneAndroid,
                        modifier = Modifier.weight(1f),
                        enabled = canExecute
                    ) {
                        if (!validateInputs(phoneTextFieldValue.text, amount, { phoneError = true }, { amountError = true })) return@ActionButton
                        val tx = UssdTransaction("Airtime", listOf(
                            UssdStep(UssdStepType.OPTION, "5"),
                            UssdStep(UssdStepType.OPTION, "1"),
                            UssdStep(UssdStepType.OPTION, "5"),
                            UssdStep(UssdStepType.AMOUNT),
                            UssdStep(UssdStepType.PHONE),
                            UssdStep(UssdStepType.PHONE)
                        ))
                        launchTransaction(tx)
                    }
                    ActionButton(
                        text = "Pay To",
                        icon = Icons.Default.Send,
                        modifier = Modifier.weight(1f),
                        enabled = canExecute
                    ) {
                        val isMerchant = payToType == "Merchant"
                        if (!validateInputs(phoneTextFieldValue.text, amount, { phoneError = true }, { amountError = true }, isMerchant = isMerchant)) return@ActionButton
                        if (isMerchant) {
                            val tx = UssdTransaction("Pay Merchant", listOf(
                                UssdStep(UssdStepType.OPTION, "1"),
                                UssdStep(UssdStepType.OPTION, "2"),
                                UssdStep(UssdStepType.PHONE), // This maps to Merchant ID from the phoneTextFieldValue
                                UssdStep(UssdStepType.AMOUNT),
                                UssdStep(UssdStepType.REFERENCE)
                            ))
                            launchTransaction(tx)
                        } else {
                            val tx = UssdTransaction("Pay Agent", listOf(
                                UssdStep(UssdStepType.OPTION, "1"),
                                UssdStep(UssdStepType.OPTION, "1"),
                                UssdStep(UssdStepType.PHONE),
                                UssdStep(UssdStepType.PHONE),
                                UssdStep(UssdStepType.AMOUNT),
                                UssdStep(UssdStepType.REFERENCE)
                            ))
                            launchTransaction(tx)
                        }
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        ActionButton(
                            text = "Commission",
                            icon = Icons.Default.Percent,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canExecute
                        ) {
                            commissionExpanded = true
                        }
                        AnimatedDropdownMenu(
                            expanded = commissionExpanded,
                            onDismissRequest = { commissionExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Cashout comm. Bal") },
                                onClick = {
                                    commissionExpanded = false
                                    val tx = UssdTransaction("Cashout comm. Bal", listOf(
                                        UssdStep(UssdStepType.OPTION, "7"),
                                        UssdStep(UssdStepType.OPTION, "2"),
                                        UssdStep(UssdStepType.OPTION, "1")
                                    ))
                                    launchTransaction(tx, phone = "", amountValue = "", shouldLog = false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Transfer Commission to Wallet") },
                                onClick = {
                                    commissionExpanded = false
                                    if (!validateInputs("0000000000", amount, { }, { amountError = true })) return@DropdownMenuItem
                                    val tx = UssdTransaction("Transfer Commission to Wallet", listOf(
                                        UssdStep(UssdStepType.OPTION, "7"),
                                        UssdStep(UssdStepType.OPTION, "2"),
                                        UssdStep(UssdStepType.OPTION, "2"),
                                        UssdStep(UssdStepType.AMOUNT)
                                    ))
                                    launchTransaction(tx, phone = "", shouldLog = false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("View Cash In commission") },
                                onClick = {
                                    commissionExpanded = false
                                    val tx = UssdTransaction("View Cash In commission", listOf(
                                        UssdStep(UssdStepType.OPTION, "7"),
                                        UssdStep(UssdStepType.OPTION, "2"),
                                        UssdStep(UssdStepType.OPTION, "3")
                                    ))
                                    launchTransaction(tx, phone = "", amountValue = "", shouldLog = false)
                                }
                            )
                        }
                    }

                    BounceButton(
                        onClick = { showCustomButtonsListModal = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(12.dp),
                        enabled = canExecute
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Shortcuts")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "Get Affordable MTN Data",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClickable { uriHandler.openUri("https://www.cheapdata.shop/shop/emqay-supplies-1772722953362/products") }
                    .padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Dialog for creating or editing a custom shortcut
    if (showCustomButtonDialog) {
        CustomButtonDialog(
            initialButton = editingCustomButton,
            onDismiss = { 
                showCustomButtonDialog = false 
                editingCustomButton = null
            },
            onSave = { title, template ->
                if (editingCustomButton != null) {
                    val updatedBtns = appSettings.customButtons.map { 
                        if (it.id == editingCustomButton!!.id) it.copy(title = title, ussdTemplate = template) else it 
                    }
                    val newSettings = appSettings.copy(customButtons = updatedBtns)
                    onSettingsChanged(newSettings)
                } else {
                    val newBtn = CustomButton(UUID.randomUUID().toString(), title, template)
                    val newSettings = appSettings.copy(customButtons = appSettings.customButtons + newBtn)
                    onSettingsChanged(newSettings)
                }
            }
        )
    }

    // Modal listing all custom buttons
    if (showCustomButtonsListModal) {
        CustomButtonsListModal(
            customButtons = appSettings.customButtons,
            isIdle = canExecute,
            onDismiss = { showCustomButtonsListModal = false },
            onAddNew = { 
                editingCustomButton = null
                showCustomButtonDialog = true 
            },
            onExecute = { customBtn ->
                // For fixed-amount MTN shortcuts (e.g. "Mashup 5"), the amount is
                // encoded in the network tag as "MTN|fixed:<value>". Extract it so
                // the transaction log shows the correct amount regardless of what
                // the user typed in the amount field.
                val fixedAmount = customBtn.network
                    ?.takeIf { it.startsWith("MTN|fixed:") }
                    ?.removePrefix("MTN|fixed:")

                // Validate phone; for fixed-amount shortcuts skip amount validation
                // since the amount is baked into the USSD code.
                val amountForValidation = fixedAmount ?: amount
                if (!validateInputs(
                        phoneTextFieldValue.text,
                        amountForValidation,
                        { phoneError = true },
                        { amountError = true }
                    )
                ) {
                    showCustomButtonsListModal = false
                    return@CustomButtonsListModal
                }

                val parsed = parseUssdTemplate(customBtn.ussdTemplate)
                if (parsed != null) {
                    val (baseCode, steps) = parsed
                    showCustomButtonsListModal = false
                    val tx = UssdTransaction(customBtn.title, steps)
                    // Use the fixed amount (if any) as the log amount, otherwise
                    // fall through to the user-typed amount via launchTransaction's default.
                    if (fixedAmount != null) {
                        launchTransaction(tx, baseCode, amountValue = fixedAmount)
                    } else {
                        launchTransaction(tx, baseCode)
                    }
                }
            },
            onEdit = { customBtn ->
                editingCustomButton = customBtn
                showCustomButtonDialog = true
            },
            onDelete = { customBtn ->
                pendingDeleteCustomButton = customBtn
            }
        )
    }

    // ── Emergency Fraud Customer Verification & Silent Evidence Capture Modal ──
    if (showFraudWarningDialog) {
        matchingFraudAlert?.let { alert ->
            CustomerVerificationModal(
                phoneNumber = phoneTextFieldValue.text,
                amount = amount,
                transactionType = payToType,
                fraudAlert = alert,
                onDismiss = {
                    showFraudWarningDialog = false
                    phoneTextFieldValue = TextFieldValue("")
                    pendingFraudAction = null
                },
                onProceedAnyway = {
                    showFraudWarningDialog = false
                    val action = pendingFraudAction
                    pendingFraudAction = null
                    action?.invoke()
                },
                onReportDispatched = { photos ->
                    showFraudWarningDialog = false
                    phoneTextFieldValue = TextFieldValue("")
                    pendingFraudAction = null
                    Toast.makeText(
                        context,
                        "Evidence report with ${photos.size} photo(s) submitted for dispatch!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    // ── Broadcast Fraud Modal ──────────────────────────────────────────────
    if (showBroadcastDialog) {
        BroadcastFraudDialog(
            initialPhoneNumber = phoneTextFieldValue.text,
            onDismiss = { showBroadcastDialog = false },
            onBroadcastSuccess = { phone ->
                showBroadcastDialog = false
                Toast.makeText(context, "Scammer alert for $phone broadcasted to all agents!", Toast.LENGTH_LONG).show()
            }
        )
    }
}
