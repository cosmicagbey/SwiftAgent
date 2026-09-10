package com.momo.swift.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.auth.AuthManager
import com.momo.swift.auth.DeviceSecureStorage
import com.momo.swift.data.AppSettings
import com.momo.swift.data.DataStoreManager
import com.momo.swift.data.FraudAlert
import com.momo.swift.data.FraudDetectionManager
import com.momo.swift.data.TrialStatus
import com.momo.swift.data.AppDatabase
import com.momo.swift.ui.components.StoreSheet
import com.momo.swift.ui.components.LegalDocumentDialog
import com.momo.swift.ui.components.WipeDataConfirmDialog
import com.momo.swift.ui.components.LegalDocs
import com.momo.swift.ui.components.AnimatedDropdownMenu
import com.momo.swift.ui.components.bounceClickable
import com.momo.swift.ui.components.BounceIconButton
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import com.momo.swift.ui.theme.MomoSwiftTheme
import androidx.compose.material.icons.filled.MoreVert
import android.widget.Toast
import com.momo.swift.service.UssdState
import com.momo.swift.service.UssdStateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Enum representing the different screens available in the application.
 */
enum class Screen(val title: String) { 
    Home("Swift Agent"), 
    Log("Transaction Log"), 
    Settings("Settings") 
}

private enum class AppScreen {
    LOADING,
    LOGIN,
    EXPIRED,
    CLOCK_ERROR,
    MAIN_APP
}

/**
 * The root composable of the application.
 * Uses [HorizontalPager] to enable swipe navigation between screens,
 * synced with a bottom [NavigationBar].
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MomoApp(
    isAccessibilityEnabled: Boolean = false,
    isRestrictedSettings: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenAppInfo: () -> Unit = {},
    isUpdateAvailable: Boolean = false,
    isUpdateDownloading: Boolean = false,
    updateProgress: Float = 0f,
    isUpdateDownloaded: Boolean = false,
    onTriggerUpdate: () -> Unit = {},
    onCompleteUpdate: () -> Unit = {},
    onDismissUpdatePrompt: () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val dataStoreManager = remember(context.applicationContext) {
        DataStoreManager(context.applicationContext)
    }
    val appSettings by dataStoreManager.appSettingsFlow.collectAsState(initial = AppSettings())
    val coroutineScope = rememberCoroutineScope()

    // Auth state
    val authUser by AuthManager.authStateFlow.collectAsState(initial = AuthManager.currentUser)
    var trialStatus by remember { mutableStateOf<TrialStatus?>(null) }
    var currentScreen by remember { mutableStateOf(AppScreen.LOADING) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var showReportFraudScreen by remember { mutableStateOf(false) }
    var showFraudRegistryScreen by remember { mutableStateOf(false) }
    var incomingFraudAlert by remember { mutableStateOf<FraudAlert?>(null) }
    var isAccessSyncing by remember { mutableStateOf(false) }

    var showRestartPopup by remember { mutableStateOf(true) }

    var showMenu by remember { mutableStateOf(false) }
    var selectedLegalDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        FraudDetectionManager.init(context)
        FraudDetectionManager.newBroadcastEvent.collect { alert ->
            incomingFraudAlert = alert
        }
    }

    LaunchedEffect(isUpdateDownloaded) {
        if (isUpdateDownloaded) {
            showRestartPopup = true
        }
    }

    // Check auth and trial status whenever auth state changes or testing mode changes
    LaunchedEffect(authUser, appSettings.isTestingMode) {
        if (appSettings.isTestingMode) {
            currentScreen = AppScreen.MAIN_APP
            trialStatus = TrialStatus(
                isPremium = true,
                premiumExpiresAt = 0L,
                trialStartedAt = 0L,
                trialExpired = false,
                daysLeft = 999,
                deviceAlreadyUsed = false,
                subscriptionDaysLeft = 999,
                isRegistered = true
            )
            return@LaunchedEffect
        }

        val user = authUser
        if (user == null) {
            currentScreen = AppScreen.LOGIN
            trialStatus = null
            return@LaunchedEffect
        }

        // 1. Clock tampering guard check
        if (DeviceSecureStorage.checkAndSetClock(System.currentTimeMillis())) {
            currentScreen = AppScreen.CLOCK_ERROR
            return@LaunchedEffect
        }

        // Read local settings and check local access BEFORE routing
        val currentLocalSettings = dataStoreManager.appSettingsFlow.first()
        val hasAccessLocally = currentLocalSettings.isPremium || !currentLocalSettings.trialExpired

        // 2. Check cache TTL: if verified within last 3 hours, skip network check
        val timeSinceLastCheck = System.currentTimeMillis() - DeviceSecureStorage.lastVerifiedTime
        val hasValidCache = timeSinceLastCheck in 0L..(3L * 60 * 60 * 1000) // 3 hours

        if (hasValidCache) {
            android.util.Log.d("MomoApp", "checkTrialStatus: Using cached status (last checked ${timeSinceLastCheck / 1000}s ago)")
            trialStatus = TrialStatus(
                isPremium = currentLocalSettings.isPremium,
                premiumExpiresAt = currentLocalSettings.premiumExpiresAt,
                trialStartedAt = currentLocalSettings.trialStartedAt,
                trialExpired = currentLocalSettings.trialExpired,
                isRegistered = true
            )
            currentScreen = when {
                currentLocalSettings.isPremium -> AppScreen.MAIN_APP
                !currentLocalSettings.trialExpired -> AppScreen.MAIN_APP
                else -> AppScreen.EXPIRED
            }
            return@LaunchedEffect
        }

        // OPTIMISTIC UI: Only show MAIN_APP instantly if the user has access locally.
        // Otherwise, show LOADING while we fetch the server response.
        currentScreen = if (hasAccessLocally) AppScreen.MAIN_APP else AppScreen.LOADING

        // Fetch the true status from the server in the background
        isAccessSyncing = true
        val result = AuthManager.checkTrialStatus()
        isAccessSyncing = false
        val status = result.getOrNull()
        
        if (status != null) {
            trialStatus = status

            // ✓ Backend reached — record the verification timestamp
            DeviceSecureStorage.stampVerifiedNow()

            // Sync premium status to local settings (read latest to avoid stale overwrite)
            dataStoreManager.updateSettings { latest ->
                latest.copy(
                    isPremium = status.isPremium,
                    premiumExpiresAt = status.premiumExpiresAt,
                    trialStartedAt = status.trialStartedAt,
                    trialExpired = status.trialExpired,
                    userEmail = user.email ?: ""
                )
            }

            val targetScreen = when {
                status.isPremium -> AppScreen.MAIN_APP
                !status.trialExpired -> AppScreen.MAIN_APP
                else -> AppScreen.EXPIRED
            }

            if (targetScreen == AppScreen.EXPIRED && currentScreen == AppScreen.MAIN_APP) {
                // If they are actively using the app, wait until the USSD engine is IDLE before kicking them out
                coroutineScope.launch {
                    UssdStateManager.stateFlow.first { it == UssdState.IDLE }
                    currentScreen = AppScreen.EXPIRED
                }
            } else {
                currentScreen = targetScreen
            }
        } else {
            val e = result.exceptionOrNull()
            if (e?.message?.contains("DEVICE_LOCKED") == true) {
                coroutineScope.launch { AuthManager.signOut() }
                loginError = "This device is locked to your first-time email. Please select that account."
                currentScreen = AppScreen.LOGIN
            } else {
                // Offline fallback — use local settings.
                trialStatus = null
                currentScreen = when {
                    hasAccessLocally -> AppScreen.MAIN_APP
                    else -> AppScreen.EXPIRED
                }
            }
        }
    }

    when (currentScreen) {
        AppScreen.LOADING -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        AppScreen.LOGIN -> {
            MomoSwiftTheme(darkTheme = false) {
                LoginScreen(
                    initialError = loginError,
                    onAuthSuccess = {
                        loginError = null
                        // LaunchedEffect(authUser) will handle the rest
                    },
                    onReviewerBypass = {
                        trialStatus = TrialStatus(
                            isPremium = true,
                            premiumExpiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L,
                            trialStartedAt = System.currentTimeMillis(),
                            trialExpired = false,
                            daysLeft = 365,
                            deviceAlreadyUsed = false,
                            subscriptionDaysLeft = 365,
                            isRegistered = true
                        )
                        coroutineScope.launch {
                            dataStoreManager.updateSettings { latest ->
                                latest.copy(
                                    isPremium = true,
                                    premiumExpiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L,
                                    trialExpired = false
                                )
                            }
                            currentScreen = AppScreen.MAIN_APP
                        }
                    }
                )
            }
        }

        AppScreen.CLOCK_ERROR -> {
            MomoSwiftTheme(darkTheme = false) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = "Clock Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "System Clock Error",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "A discrepancy was detected in your system time. Swift Agent requires the correct date and time to verify your trial and premium status. Please correct your system clock settings and restart the app.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(32.dp))
                            BounceButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(AndroidSettings.ACTION_DATE_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (e: Exception) { /* fallback: ignore */ }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open Date & Time Settings")
                            }
                        }
                    }
                }
            }
        }

        AppScreen.EXPIRED -> {
            // ── App Resume Auto-Unlock ───────────────────────────────────────
            // When the user returns from the Paystack browser page, ON_RESUME
            // fires a background status check. If the backend confirms payment,
            // the user is auto-admitted without tapping "I Have Paid".
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        coroutineScope.launch {
                            val res = AuthManager.checkTrialStatus()
                            res.onSuccess { s ->
                                if (s.isPremium || !s.trialExpired) {
                                    trialStatus = s
                                    DeviceSecureStorage.stampVerifiedNow()
                                    dataStoreManager.updateSettings { latest ->
                                        latest.copy(
                                            isPremium = s.isPremium,
                                            premiumExpiresAt = s.premiumExpiresAt,
                                            trialStartedAt = s.trialStartedAt,
                                            trialExpired = s.trialExpired
                                        )
                                    }
                                    currentScreen = AppScreen.MAIN_APP
                                }
                            }
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            MomoSwiftTheme(darkTheme = false) {
                ExpiredScreen(
                    userEmail = authUser?.email ?: "",
                    secretCodeAttemptsUsed = appSettings.secretCodeAttemptsUsed,
                    currentPaymentReference = appSettings.currentPaymentReference,
                    onPaymentInitialized = { ref ->
                        coroutineScope.launch {
                            dataStoreManager.updateSettings { latest ->
                                latest.copy(currentPaymentReference = ref)
                            }
                        }
                    },
                    onAttemptFailed = {
                        coroutineScope.launch {
                            dataStoreManager.updateSettings { latest ->
                                latest.copy(secretCodeAttemptsUsed = latest.secretCodeAttemptsUsed + 1)
                            }
                        }
                    },
                    onUnlock = {
                        coroutineScope.launch {
                            currentScreen = AppScreen.LOADING
                            val res = AuthManager.checkTrialStatus()
                            res.onSuccess { s ->
                                trialStatus = s
                                DeviceSecureStorage.stampVerifiedNow()
                                dataStoreManager.updateSettings { latest ->
                                    latest.copy(
                                        isPremium = s.isPremium,
                                        premiumExpiresAt = s.premiumExpiresAt,
                                        trialStartedAt = s.trialStartedAt,
                                        trialExpired = s.trialExpired
                                    )
                                }
                                currentScreen = if (s.isPremium || !s.trialExpired) AppScreen.MAIN_APP else AppScreen.EXPIRED
                            }
                        }
                    }
                )
            }
        }

        AppScreen.MAIN_APP -> {
            val screens = Screen.entries
            val pagerState = rememberPagerState(initialPage = 0) { screens.size }

            // Use derivedStateOf to stabilize state updates and prevent unnecessary recompositions
            val currentPage by remember { derivedStateOf { pagerState.currentPage } }
            val currentScreenTitle by remember { derivedStateOf { screens[currentPage] } }

            var showStore by remember { mutableStateOf(false) }
            var logSearchActive by remember { mutableStateOf(false) }
            var logSearchQuery by remember { mutableStateOf("") }
            val uriHandler = LocalUriHandler.current
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusRequester = remember { FocusRequester() }

            LaunchedEffect(logSearchActive) {
                if (logSearchActive) {
                    try {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Remembered stable callbacks for child screens
            val onSettingsChangedRemembered = remember(dataStoreManager) {
                { newSettings: AppSettings ->
                    coroutineScope.launch {
                        dataStoreManager.updateSettings { latest ->
                            latest.copy(
                                selectedSimId = newSettings.selectedSimId,
                                customButtons = newSettings.customButtons,
                                darkMode = newSettings.darkMode,
                                maxAirtimeLimit = newSettings.maxAirtimeLimit,
                                maxCashLimit = newSettings.maxCashLimit,
                                recentPhoneNumbers = newSettings.recentPhoneNumbers,
                                isTestingMode = newSettings.isTestingMode,
                                hasSeenOnboarding = newSettings.hasSeenOnboarding
                            )
                        }
                    }
                    Unit
                }
            }

            val onReportFraudRemembered = remember {
                { showReportFraudScreen = true }
            }

            val onBackRemembered = remember(pagerState) {
                {
                    coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    Unit
                }
            }

            val onDismissReportFraudRemembered = remember {
                { showReportFraudScreen = false }
            }

            // Reset search when navigating away from the Log tab
            LaunchedEffect(currentPage) {
                if (screens[currentPage] != Screen.Log) {
                    logSearchActive = false
                    logSearchQuery = ""
                }
            }

            if (showReportFraudScreen) {
                ReportFraudScreen(onBack = onDismissReportFraudRemembered)
            } else if (showFraudRegistryScreen) {
                FraudAlertsScreen(onBack = { showFraudRegistryScreen = false })
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                if (currentScreenTitle == Screen.Log && logSearchActive) {
                                    // ── Inline search field replaces title ──────────────
                                    BasicTextField(
                                        value = logSearchQuery,
                                        onValueChange = { logSearchQuery = it },
                                        singleLine = true,
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onPrimary),
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                        decorationBox = { inner ->
                                            Box {
                                                if (logSearchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Search transactions…",
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(focusRequester)
                                    )
                                } else {
                                    Text(currentScreenTitle.title)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                titleContentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            navigationIcon = {
                                if (currentScreenTitle == Screen.Log && logSearchActive) {
                                    // Back/close arrow when search is active
                                    BounceIconButton(onClick = {
                                        logSearchActive = false
                                        logSearchQuery = ""
                                        focusManager.clearFocus()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Close search",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                } else {
                                    BounceIconButton(onClick = {
                                        focusManager.clearFocus()
                                        showStore = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Store,
                                            contentDescription = "Shortcut Store",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            },
                            actions = {
                                // ── Home-only actions ───────────────────────────────
                                if (currentScreenTitle == Screen.Home) {
                                    Text(
                                        text = "How to use",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .bounceClickable {
                                                try {
                                                    uriHandler.openUri("https://youtu.be/qIyBuJ_C2-k")
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Unable to open tutorial link", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(end = 8.dp)
                                    )
                                    if (isUpdateDownloaded) {
                                        BounceIconButton(onClick = { showRestartPopup = true }) {
                                            Icon(
                                                imageVector = Icons.Rounded.SystemUpdate,
                                                contentDescription = "Update Ready to Install",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                    BounceIconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                dataStoreManager.updateSettings {
                                                    it.copy(darkMode = !appSettings.darkMode)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (appSettings.darkMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                            contentDescription = if (appSettings.darkMode) "Switch to light mode" else "Switch to dark mode",
                                            tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }

                                // ── Log screen: Search icon / clear icon ─────────────
                                if (currentScreenTitle == Screen.Log) {
                                    if (logSearchActive && logSearchQuery.isNotEmpty()) {
                                        BounceIconButton(onClick = { logSearchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Clear search",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    } else if (!logSearchActive) {
                                        BounceIconButton(onClick = { logSearchActive = true }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Search,
                                                contentDescription = "Search transactions",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }

                                // ── Settings overflow menu ───────────────────────────
                                if (currentScreenTitle == Screen.Settings) {
                                    Box {
                                        BounceIconButton(onClick = { showMenu = !showMenu }) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "More Options",
                                                tint = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                        AnimatedDropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("🚨 Fraud Registry & Alerts") },
                                                onClick = {
                                                    showMenu = false
                                                    showFraudRegistryScreen = true
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Disclaimer") },
                                                onClick = {
                                                    showMenu = false
                                                    selectedLegalDoc = "Disclaimer" to LegalDocs.DISCLAIMER
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Privacy Statement") },
                                                onClick = {
                                                    showMenu = false
                                                    selectedLegalDoc = "Privacy Statement" to LegalDocs.PRIVACY_STATEMENT
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("How it Works") },
                                                onClick = {
                                                    showMenu = false
                                                    selectedLegalDoc = "How it Works" to LegalDocs.HOW_IT_WORKS
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Terms of Service") },
                                                onClick = {
                                                    showMenu = false
                                                    selectedLegalDoc = "Terms of Service" to LegalDocs.TERMS_OF_SERVICE
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Contact Us") },
                                                onClick = {
                                                    showMenu = false
                                                    uriHandler.openUri("https://docs.google.com/forms/d/e/1FAIpQLSfUZStWno_HqGRMVBLftYFARfV06VNfzT7Qa1-C4YhxhYWAdQ/viewform?usp=dialog")
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Wipe Local Data & Account") },
                                                onClick = {
                                                    showMenu = false
                                                    showWipeConfirm = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    },
                bottomBar = {
                    NavigationBar {
                        screens.forEachIndexed { index, screen ->
                            val icon = when (screen) {
                                Screen.Home -> Icons.Default.Home
                                Screen.Log -> Icons.AutoMirrored.Filled.List
                                Screen.Settings -> Icons.Default.Settings
                            }
                            NavigationBarItem(
                                icon = { Icon(icon, contentDescription = screen.title) },
                                label = { Text(screen.name) },
                                selected = currentPage == index,
                                onClick = {
                                    coroutineScope.launch {
                                        if (isAccessibilityEnabled) {
                                            pagerState.scrollToPage(index)
                                        } else {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Surface(
                    modifier = Modifier.padding(paddingValues),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val preRenderCount = if (isAccessibilityEnabled) 0 else 2
                    HorizontalPager(
                        state = pagerState,
                        beyondBoundsPageCount = preRenderCount,
                        userScrollEnabled = false,
                        key = { page -> screens[page].name }
                    ) { page ->
                        when (screens[page]) {
                            Screen.Home -> {
                                HomeScreen(
                                    appSettings = appSettings,
                                    trialStatus = trialStatus,
                                    isAccessibilityEnabled = isAccessibilityEnabled,
                                    isRestrictedSettings = isRestrictedSettings,
                                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                    onOpenAppInfo = onOpenAppInfo,
                                    onSettingsChanged = onSettingsChangedRemembered,
                                    onOpenFraudRegistry = { showFraudRegistryScreen = true },
                                    onReportFraud = onReportFraudRemembered
                                )
                            }
                            Screen.Log -> {
                                TransactionLogScreen(
                                    settings = appSettings,
                                    searchQuery = logSearchQuery,
                                    onReportFraud = onReportFraudRemembered
                                )
                            }
                            Screen.Settings -> {
                                SettingsScreen(
                                    settings = appSettings,
                                    trialStatus = trialStatus,
                                    onSettingsChanged = onSettingsChangedRemembered,
                                    onBack = onBackRemembered
                                )
                            }
                        }
                    }
                }
            }

            if (showStore) {
                StoreSheet(
                    existingShortcuts = appSettings.customButtons,
                    onDismiss = { showStore = false },
                    onImport = { newButton ->
                        coroutineScope.launch {
                            var success = false
                            dataStoreManager.updateSettings { latest ->
                                val alreadyExists = latest.customButtons.any {
                                    it.ussdTemplate.filterNot(Char::isWhitespace) ==
                                            newButton.ussdTemplate.filterNot(Char::isWhitespace)
                                }
                                if (!alreadyExists) {
                                    success = true
                                    val nextButtons = latest.customButtons
                                        .plus(newButton)
                                        .sortedBy { it.title.lowercase() }
                                    latest.copy(customButtons = nextButtons)
                                } else {
                                    latest
                                }
                            }
                            if (success) {
                                Toast.makeText(context, "Shortcut imported", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Shortcut already exists", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRemove = { toRemove ->
                        coroutineScope.launch {
                            dataStoreManager.updateSettings { latest ->
                                latest.copy(customButtons = latest.customButtons.filterNot { it.id == toRemove.id })
                            }
                        }
                    }
                )
            }

            selectedLegalDoc?.let { doc ->
                LegalDocumentDialog(
                    title = doc.first,
                    content = doc.second,
                    onDismissRequest = { selectedLegalDoc = null }
                )
            }

            if (showWipeConfirm) {
                WipeDataConfirmDialog(
                    onConfirm = {
                        coroutineScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val db = AppDatabase.getInstance(context)
                                db.transactionLogDao().clearAll()
                                db.savedContactDao().clearAll()
                            }
                            dataStoreManager.updateSettings { AppSettings() }
                            AuthManager.signOut()
                        }
                    },
                    onDismissRequest = { showWipeConfirm = false }
                )
            }


            // ── Real-time Incoming Fraud Broadcast Banner ───────────────────
            AnimatedVisibility(
                visible = incomingFraudAlert != null,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                incomingFraudAlert?.let { alert ->
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                incomingFraudAlert = null
                                showFraudRegistryScreen = true
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🚨 NEW SCAMMER NUMBER BROADCAST",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${alert.phoneNumber} reported for ${alert.fraudType}. Tap to view registry.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { incomingFraudAlert = null }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onError
                                )
                            }
                        }
                    }
                }
            }

            // In-App Update background progress bar
            if (isUpdateDownloading) {
                LinearProgressIndicator(
                    progress = { updateProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                )
            }

            // In-App Update flexible download prompt
            AnimatedVisibility(
                visible = isUpdateAvailable && !isUpdateDownloading && !isUpdateDownloaded,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = "Download Update",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "New Version Available!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "An update is ready. Click below to download in the background while you continue using the app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            BounceTextButton(onClick = onDismissUpdatePrompt) {
                                Text("Later", color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            BounceButton(
                                onClick = {
                                    onTriggerUpdate()
                                    onDismissUpdatePrompt()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Update Now")
                            }
                        }
                    }
                }
            }

            // In-App Update installation restart popup
            if (isUpdateDownloaded && showRestartPopup) {
                AlertDialog(
                    onDismissRequest = { showRestartPopup = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Success",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Update Ready!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        Text(
                            text = "The update has been downloaded in the background. To apply the new update and continue, the app needs to restart.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    },
                    confirmButton = {
                        BounceButton(
                            onClick = onCompleteUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Restart & Install", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        BounceTextButton(
                            onClick = { showRestartPopup = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Later", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                )
            }
        } // Closes Box
        } // Closes else
    } // Closes MAIN_APP
} // Closes when
} // Closes MomoApp
