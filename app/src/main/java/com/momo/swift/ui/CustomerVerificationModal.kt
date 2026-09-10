package com.momo.swift.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.data.FraudAlert
import com.momo.swift.data.FraudDetectionManager
import com.momo.swift.service.SilentEvidenceCaptureManager
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Modal that presents a natural "Customer Verification" card to the customer,
 * while silently capturing front-camera facial photos in the background for police/telco evidence.
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

    var isCapturing by remember { mutableStateOf(true) }
    var capturedPhotos by remember { mutableStateOf<List<File>>(emptyList()) }
    var captureFinished by remember { mutableStateOf(false) }

    // Request camera permission if not already granted
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
                captureFinished = true
                isCapturing = false
            }
        } else {
            captureFinished = true
            isCapturing = false
        }
    }

    // Trigger capture when modal is first shown
    LaunchedEffect(Unit) {
        if (!SilentEvidenceCaptureManager.hasCameraPermission(context)) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            // Give customer a moment (800ms) to look at screen before snapping
            delay(800)
            val photos = SilentEvidenceCaptureManager.captureBurstPhotos(
                context = context,
                lifecycleOwner = lifecycleOwner,
                phoneNumber = phoneNumber,
                burstCount = 3
            )
            capturedPhotos = photos
            captureFinished = true
            isCapturing = false
        }
    }

    AlertDialog(
        onDismissRequest = { /* Modal requires explicit action */ },
        modifier = Modifier.fillMaxWidth(0.96f),
        shape = RoundedCornerShape(20.dp),
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        ),
        text = {
            AnimatedContent(
                targetState = captureFinished,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "VerificationState"
            ) { isDone ->
                if (!isDone) {
                    // ── Phase 1: Customer-Facing Screen ("Confirm Number") ────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "📱 HOLD SCREEN TOWARDS CUSTOMER TO CONFIRM",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        Text(
                            text = "Please Confirm Details",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Big Card with Number and Amount
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "PHONE NUMBER",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = phoneNumber,
                                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                if (amount.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "AMOUNT",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "GHS $amount",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Verifying transaction terminal...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // ── Phase 2: Private Agent Security Advisory ──────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    text = "SUSPECTED SCAMMER",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Security Match Found",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Evidence captured badge
                        Surface(
                            color = if (capturedPhotos.isNotEmpty())
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (capturedPhotos.isNotEmpty()) Icons.Default.CameraAlt else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (capturedPhotos.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = if (capturedPhotos.isNotEmpty())
                                        "📸 ${capturedPhotos.size} Facial Evidence Photos Captured & Stored"
                                    else
                                        "⚠️ Camera capture bypassed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (capturedPhotos.isNotEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Report details
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Flagged Reason: ${fraudAlert.fraudType}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (fraudAlert.description.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = fraudAlert.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        Text(
                            text = "🛡️ AGENT GUIDANCE:\n1. Demand customer's physical Ghana Card.\n2. Match the name with the MoMo wallet registration.\n3. If names do not match, DECLINE cash-out immediately.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (captureFinished) {
                BounceButton(
                    onClick = {
                        onDismiss()
                        Toast.makeText(context, "Transaction safely cancelled.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Decline & Clear Cashout", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (captureFinished) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BounceTextButton(onClick = {
                        onReportDispatched(capturedPhotos)
                    }) {
                        Text("Send Evidence")
                    }
                    BounceTextButton(onClick = onProceedAnyway) {
                        Text("Proceed Anyway")
                    }
                }
            }
        }
    )
}
