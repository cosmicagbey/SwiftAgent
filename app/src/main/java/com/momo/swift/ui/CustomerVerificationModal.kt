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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
 *   Agent sees scammer warning + 3 choices.
 *
 * Phase 1 — INVISIBLE COUNTDOWN + CAPTURE:
 *   3-second escalating haptic countdown (invisible to customer).
 *   Front camera silently fires 3 photos if agent chose capture.
 *   Ends with strong double-buzz = "Flip now".
 *
 * Phase 2 — CLEAN TRANSACTION SCREEN (customer-facing):
 *   Just the number + amount. No banners, no hints, nothing suspicious.
 *   Agent taps anywhere on screen (invisible) to return to private view.
 *
 * Phase 3 — AGENT SUMMARY (private):
 *   Evidence saved silently. Agent sees badge, options, and can decline/proceed.
 *   Evidence is accessible later from Broadcast dialog.
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

    // 0=Agent alert, 1=Countdown+capture, 2=Customer-facing, 3=Agent summary
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

    // Phase 1: run countdown + optionally capture in parallel
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

        // Escalating haptic countdown: 3 ticks then double-buzz
        for (i in 3 downTo 1) {
            fireCountdownHaptic(context, i)
            delay(1000L)
        }
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
                                Text("SCAMMER DETECTED",
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
                            Text("Ask customer for Ghana Card. Match name with wallet. If names differ — DECLINE immediately.",
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
                                Text("Your phone vibrates a 3-second countdown. Flip screen to customer on the double buzz. Tap anywhere to return to this screen.",
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

                    // ── Phase 2: Clean Customer-Facing Transaction Screen ──────
                    // No banners, no hints. Looks like normal app screen.
                    // Invisible full-area tap returns agent to private view.
                    2 -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { phase = 3 }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Swift Agent branding strip (looks natural)
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("SWIFT AGENT  •  MOBILE MONEY",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    letterSpacing = 1.5.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                            }

                            Spacer(Modifier.height(8.dp))

                            // Number — big and clear
                            Text("PHONE NUMBER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                letterSpacing = 1.sp)
                            Text(phoneNumber,
                                style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp),
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary)

                            // Amount — if present
                            if (amount.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f),
                                    color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("AMOUNT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    letterSpacing = 1.sp)
                                Text("GHS $amount",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }

                            Spacer(Modifier.height(24.dp))
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

                        // Evidence badge — no count mentioned to avoid info leak
                        Surface(
                            color = if (capturedPhotos.isNotEmpty())
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    if (capturedPhotos.isNotEmpty()) Icons.Default.CameraAlt else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (capturedPhotos.isNotEmpty()) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column {
                                    Text(
                                        if (capturedPhotos.isNotEmpty()) "Evidence photos saved to secure gallery"
                                        else if (captureRequested) "Camera capture attempted — check permissions"
                                        else "No evidence capture was requested",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (capturedPhotos.isNotEmpty())
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface)
                                    if (capturedPhotos.isNotEmpty()) {
                                        Text("Attach photos from Evidence Gallery when broadcasting",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                    }
                                }
                            }
                        }

                        Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Flagged: ${fraudAlert.fraudType}",
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

                        Text("1. Demand customer's Ghana Card.\n2. Match name with MoMo wallet registration.\n3. If names differ — DECLINE immediately.",
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
                3 -> BounceButton(
                    onClick = {
                        onDismiss()
                        Toast.makeText(context, "Transaction cancelled.", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, "Transaction cancelled.", Toast.LENGTH_SHORT).show()
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
