package com.momo.swift

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.AppOpsManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.momo.swift.auth.AuthManager
import com.momo.swift.auth.DeviceSecureStorage
import com.momo.swift.data.AppSettings
import com.momo.swift.data.DataStoreManager
import com.momo.swift.service.USSDService
import com.momo.swift.ui.MomoApp
import com.momo.swift.ui.theme.MomoSwiftTheme
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.compose.runtime.mutableFloatStateOf

/**
 * The main entry point for the Swift Agent application.
 */
class MainActivity : ComponentActivity() {

    /** Observable state for whether the AccessibilityService is enabled. */
    val isAccessibilityEnabled = mutableStateOf(false)

    /**
     * True when the app is sideloaded on Android 13+ and the user has NOT yet
     * tapped "Allow restricted settings" in App Info. Until they do, the
     * AccessibilityService cannot be enabled.
     */
    val isRestrictedSettings = mutableStateOf(false)

    // In-App Update States
    private lateinit var appUpdateManager: AppUpdateManager
    val isUpdateAvailable = mutableStateOf(false)
    val isUpdateDownloading = mutableStateOf(false)
    val updateProgress = mutableFloatStateOf(0f)
    val isUpdateDownloaded = mutableStateOf(false)
    private var pendingUpdateInfo: com.google.android.play.core.appupdate.AppUpdateInfo? = null
    private val UPDATE_REQUEST_CODE = 999

    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                isUpdateDownloading.value = true
                isUpdateDownloaded.value = false
                val bytes = state.bytesDownloaded()
                val total = state.totalBytesToDownload()
                if (total > 0) {
                    updateProgress.floatValue = bytes.toFloat() / total.toFloat()
                }
            }
            InstallStatus.DOWNLOADED -> {
                isUpdateDownloading.value = false
                isUpdateDownloaded.value = true
                updateProgress.floatValue = 1f
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> {
                isUpdateDownloading.value = false
                isUpdateDownloaded.value = false
                updateProgress.floatValue = 0f
            }
            else -> {}
        }
    }

    // Permission requests are now handled inside HomeScreen on first composition.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* unused — kept for compatibility */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize App Check before any other Firebase SDK call.
        // Fix #22: DebugAppCheckProviderFactory is a debugImplementation dependency — it is NOT
        // available on the release classpath. We load it via reflection so the release compiler
        // never sees a static reference to it. R8 prunes this branch automatically because
        // BuildConfig.DEBUG is a compile-time constant false in release builds.
        val appCheck = FirebaseAppCheck.getInstance()
        val appCheckProvider: AppCheckProviderFactory = if (BuildConfig.DEBUG) {
            @Suppress("UNCHECKED_CAST")
            val factoryClass = Class.forName(
                "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
            )
            factoryClass.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        appCheck.installAppCheckProviderFactory(appCheckProvider)

        AuthManager.init(this)
        DeviceSecureStorage.init(this)
        val settingsManager = DataStoreManager(this)

        // Note: CALL_PHONE / READ_PHONE_STATE permissions are requested in HomeScreen.

        // Initialize AppUpdateManager and register listener
        appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.registerListener(installStateUpdatedListener)
        checkForUpdates()

        setContent {
            val settings by settingsManager.appSettingsFlow.collectAsState(initial = AppSettings())
            MomoSwiftTheme(darkTheme = settings.darkMode) {
                MomoApp(
                    isAccessibilityEnabled = isAccessibilityEnabled.value,
                    isRestrictedSettings = isRestrictedSettings.value,
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenAppInfo = { openAppInfo() },
                    isUpdateAvailable = isUpdateAvailable.value,
                    isUpdateDownloading = isUpdateDownloading.value,
                    updateProgress = updateProgress.floatValue,
                    isUpdateDownloaded = isUpdateDownloaded.value,
                    onTriggerUpdate = { triggerFlexibleUpdate() },
                    onCompleteUpdate = { completeFlexibleUpdate() },
                    onDismissUpdatePrompt = { isUpdateAvailable.value = false }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check each time the activity resumes (e.g., after returning from Settings)
        isAccessibilityEnabled.value = isAccessibilityServiceEnabled(this, USSDService::class.java)
        // Skip restricted-settings gating in debug builds for faster local testing.
        val isDebuggableBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        isRestrictedSettings.value = !isDebuggableBuild && isRestrictedByAndroid13(this)

        // Check if an update has been downloaded but not installed while app was in background
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    isUpdateDownloaded.value = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        }
    }

    /**
     * Checks Google Play for available flexible updates.
     */
    private fun checkForUpdates() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
            ) {
                pendingUpdateInfo = appUpdateInfo
                isUpdateAvailable.value = true
            } else if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                isUpdateDownloaded.value = true
            }
        }
    }

    /**
     * Starts the flexible update download flow using the Play Store standard intent.
     */
    fun triggerFlexibleUpdate() {
        val info = pendingUpdateInfo
        if (info != null) {
            try {
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    AppUpdateType.FLEXIBLE,
                    this,
                    UPDATE_REQUEST_CODE
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Completes the update process, restarting the app.
     */
    fun completeFlexibleUpdate() {
        try {
            appUpdateManager.completeUpdate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // Permissions are now requested in HomeScreen; this method is kept as a no-op
    // to avoid breaking any future references but is no longer called from onCreate.
    @Suppress("unused")
    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing.toTypedArray())
    }

    /** Opens Accessibility Settings. Only call this AFTER restricted settings have been allowed. */
    private fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Opens the App Info screen so the user can tap "Allow restricted settings".
     * Required on Android 13+ for sideloaded/ADB-installed apps before accessibility works.
     */
    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    companion object {
        /**
         * Checks if [serviceClass] is currently enabled as an accessibility service.
         *
         * @param context The application context.
         * @param serviceClass The AccessibilityService class to check for.
         * @return `true` if the service is enabled in the system settings.
         */
        fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
            val am = context.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            val expectedId = "${context.packageName}/${serviceClass.canonicalName}"
            return enabled.any { it.id == expectedId }
        }

        /**
         * On Android 13+ (API 33), apps not installed from the Play Store are blocked from
         * enabling accessibility services until the user explicitly allows "Restricted Settings"
         * from: Settings → Apps → [App] → ⋮ menu → "Allow restricted settings".
         */
        fun isRestrictedByAndroid13(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            return try {
                val mode = appOps.checkOpNoThrow(
                    "android:access_restricted_settings",
                    android.os.Process.myUid(),
                    context.packageName
                )
                // MODE_ALLOWED (0) means it's permitted (either allowed by user or not restricted at all)
                mode != AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                // Fallback to installer check if appops check fails
                val installer = context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
                installer != "com.android.vending"
            }
        }
    }
}
