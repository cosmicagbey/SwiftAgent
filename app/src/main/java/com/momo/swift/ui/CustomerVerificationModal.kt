package com.momo.swift.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.data.FraudAlert
import com.momo.swift.service.SilentEvidenceCaptureManager
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Four-phase fraud interception modal.
 *
 * Phase 0 — AGENT ALERT (private):
 *   Agent sees scammer warning. Chooses: Capture Evidence | Proceed Anyway | Decline & Clear.
 *
 * Phase 1 — INVISIBLE COUNTDOWN (private):
 *   3-second haptic countdown. Escalating vibrations let agent feel when to flip phone.
 *   Camera fires silently only if agent chose "Capture Evidence".
 *   Ends with a strong double-buzz = "Flip now!"
 *
 * Phase 2 — CUSTOMER FACING (public):
 *   "Please Confirm Details" screen shown to customer.
 *
 * Phase 3 — AGENT SUMMARY (private):
 *   Agent sees evidence badge + security guidance + action buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerVerificationModal(
    phoneNumber: String,
    amount: String,
    transactionType: String,
    fraudAlert: FraudAlert,
    onDismiss: () -> Unit,
    onProceedAnyway: () -> Unit,
    onReportDispatched: (List<File>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(0) }
    var captureRequested by remember { mutableStateOf(false) }
    var capturedPhotos by remember { mutableStateOf<List<File>>(emptyList()) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            coroutineScope.launch {
                val photos = SilentEvidenceCaptureManager.captureBurstPhotos(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    phoneNumber = phoneNumber,
                    burstCount = 3
                )
                capturedPhotos = photos
            }
        }
    }

    // Phase 1: start haptic countdown + optionally capture in parallel
    LaunchedEffect(phase) {
        if (phase != 1) return@LaunchedEffect

        if (captureRequested) {
            launch {
                if (!SilentEvidenceCaptureManager.hasCameraPermission(context)) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    val photos = SilentEvidenceCaptureManager.captureBurstPhotos(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        phoneNumber = phoneNumber,
                        burstCount = 3
                    )
                    capturedPhotos = photos
                }
            }
        }

        // 3-second escalating haptic countdown
        for (i in 3 downTo 1) {
            fireCountdownHaptic(context, i)
            delay(1000L)
        }
        // Double-buzz = "Flip now!"
        fireFlipNowHaptic(context)
        delay(200L)
        phase = 2
    }

    AlertDialog(
        onDismissRequest = { },
        modifier = Modifier.fillMaxWidth(0.96f),
        shape = RoundedCornerShape(20.dp),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        text = {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "PhaseTransition"
            ) { currentPhase ->
                when (currentPhase) {

                    // ── Phase 0: Private Agent Alert ──────────────────────────
                    0 -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                            Column {
                                Text("⚠️ SCAMMER DETECTED",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error)
                                Text("This number is on the community blacklist",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Type: ${fraudAlert.fraudType}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                if (fraudAlert.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(fraudAlert.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }

                        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("🛡️ Ask customer for Ghana Card. Match name with wallet. If names differ — DECLINE immediately.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp))
                        }

                        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.Vibration, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text("Your phone will vibrate a 3-second countdown. Flip screen to customer when you feel the double buzz.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }

                    // ── Phase 1: Invisible Countdown ──────────────────────────
                    1 -> Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp),
                            strokeWidth = 4.dp, color = MaterialTheme.colorScheme.primary)
                        Text("Get ready...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Feel for the double vibration — then flip the screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    // ── Phase 2: Customer-Facing Confirm Screen ───────────────
                    2 -> Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)) {
                            Text("📱 HOLD SCREEN TOWARDS CUSTOMER TO CONFIRM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center)
                        }
                        Text("Please Confirm Details",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("PHONE NUMBER", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(phoneNumber,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary)
                                if (amount.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("AMOUNT", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("GHS $amount",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }

                    // ── Phase 3: Agent Private Summary ────────────────────────
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                            Column {
                                Text("SUSPECTED SCAMMER",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error)
                                Text("Security Match Found",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Surface(
                            color = if (capturedPhotos.isNotEmpty())
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (capturedPhotos.isNotEmpty()) Icons.Default.CameraAlt
                                    else Icons.Default.Warning, contentDescription = null,
                                    tint = if (capturedPhotos.isNotEmpty())
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (capturedPhotos.isNotEmpty())
                                        "📸 ${capturedPhotos.size} Facial Evidence Photos Captured & Stored"
                                    else if (captureRequested)
                                        "⚠️ Camera capture was attempted — no photos saved"
                                    else
                                        "ℹ️ No evidence capture requested",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (capturedPhotos.isNotEmpty())
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Flagged Reason: ${fraudAlert.fraudType}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                if (fraudAlert.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(fraudAlert.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }

                        Text("🛡️ AGENT GUIDANCE:\n1. Demand customer's physical Ghana Card.\n2. Match the name with the MoMo wallet registration.\n3. If names do not match, DECLINE cash-out immediately.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                0 -> BounceButton(
                    onClick = { captureRequested = true; phase = 1 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Capture Evidence", fontWeight = FontWeight.Bold)
                }
                2 -> BounceButton(
                    onClick = { phase = 3 },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Customer Confirmed", fontWeight = FontWeight.Bold) }
                3 -> BounceButton(
                    onClick = {
                        onDismiss()
                        Toast.makeText(context, "Transaction safely cancelled.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("Decline & Clear Cashout", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            when (phase) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BounceTextButton(onClick = { captureRequested = false; phase = 1 }) {
                        Text("Proceed Anyway")
                    }
                    BounceTextButton(
                        onClick = {
                            onDismiss()
                            Toast.makeText(context, "Transaction safely cancelled.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Decline & Clear") }
                }
                3 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (capturedPhotos.isNotEmpty()) {
                        BounceTextButton(onClick = { onReportDispatched(capturedPhotos) }) {
                            Text("Send Evidence")
                        }
                    }
                    BounceTextButton(onClick = onProceedAnyway) { Text("Proceed Anyway") }
                }
            }
        }
    )
}

private fun getVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

private fun fireCountdownHaptic(context: Context, secondsRemaining: Int) {
    val vibrator = getVibrator(context) ?: return
    if (!vibrator.hasVibrator()) return
    val amplitude = when (secondsRemaining) { 3 -> 40; 2 -> 80; else -> 140 }
    val duration = when (secondsRemaining) { 3 -> 30L; 2 -> 50L; else -> 80L }
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        else { @Suppress("DEPRECATION") vibrator.vibrate(duration) }
    } catch (_: Exception) {}
}

private fun fireFlipNowHaptic(context: Context) {
    val vibrator = getVibrator(context) ?: return
    if (!vibrator.hasVibrator()) return
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 180), intArrayOf(0, 200, 0, 255), -1))
        else { @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0, 100, 80, 180), -1) }
    } catch (_: Exception) {}
}
