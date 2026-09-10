package com.momo.swift.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.auth.AuthManager
import com.momo.swift.data.SubscriptionPlan
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import com.momo.swift.ui.components.bounceClickable
import kotlinx.coroutines.launch

@Composable
fun ExpiredScreen(
    userEmail: String,
    secretCodeAttemptsUsed: Int = 0,
    currentPaymentReference: String? = null,
    onPaymentInitialized: (String) -> Unit = {},
    onAttemptFailed: () -> Unit = {},
    onUnlock: () -> Unit = {}
) {
    val scope       = rememberCoroutineScope()
    val uriHandler  = LocalUriHandler.current

    var isLoading       by remember { mutableStateOf(false) }
    var errorMessage    by remember { mutableStateOf<String?>(null) }
    var successMessage  by remember { mutableStateOf<String?>(null) }

    var selectedPlan        by remember { mutableStateOf<SubscriptionPlan?>(null) }
    var showSecretCodeDialog by remember { mutableStateOf(false) }
    var secretCode          by remember { mutableStateOf("") }
    var showPlanError       by remember { mutableStateOf(false) }

    // Lock the feature permanently once 3 attempts are used
    val secretCodeLocked = secretCodeAttemptsUsed >= 3
    val attemptsLeft     = (3 - secretCodeAttemptsUsed).coerceAtLeast(0)

    // ── Actions ──────────────────────────────────────────────────────
    fun initializePayment(plan: SubscriptionPlan) {
        scope.launch {
            isLoading = true
            errorMessage = null
            showPlanError = false
            val result = AuthManager.initializeTransaction(plan.id)
            result.fold(
                onSuccess = { (authUrl, ref) ->
                    isLoading = false
                    onPaymentInitialized(ref)
                    uriHandler.openUri(authUrl)
                },
                onFailure = { e ->
                    isLoading = false
                    errorMessage = e.message ?: "Failed to generate payment link."
                }
            )
        }
    }

    fun verifyPayment() {
        val ref = currentPaymentReference
        if (ref.isNullOrBlank()) {
            errorMessage = "No recent payment found. Please select a plan and complete payment first."
            return
        }
        scope.launch {
            isLoading = true
            errorMessage = null
            successMessage = null
            val result = AuthManager.verifyTransactionManual(ref)
            result.fold(
                onSuccess = {
                    isLoading = false
                    successMessage = "Payment verified! Unlocking your account…"
                    AuthManager.checkTrialStatus()
                    onUnlock()
                },
                onFailure = { e ->
                    isLoading = false
                    errorMessage = e.message ?: "Payment not confirmed yet. Please try again."
                }
            )
        }
    }

    fun redeemCode() {
        if (secretCode.isBlank()) return
        scope.launch {
            isLoading = true
            errorMessage = null
            successMessage = null
            val result = AuthManager.redeemSecretCode(secretCode.trim())
            result.fold(
                onSuccess = {
                    isLoading = false
                    successMessage = "Code redeemed successfully!"
                    showSecretCodeDialog = false
                    AuthManager.checkTrialStatus()
                    onUnlock()
                },
                onFailure = { e ->
                    isLoading = false
                    onAttemptFailed()   // persist the failed attempt
                    errorMessage = e.message ?: "Invalid or already-used code."
                }
            )
        }
    }

    // ── Secret Code Dialog ───────────────────────────────────────────
    if (showSecretCodeDialog) {
        AlertDialog(
            onDismissRequest = { if (!isLoading) showSecretCodeDialog = false },
            title = { Text("Enter Secret Code") },
            text = {
                Column {
                    Text(
                        "Contact support via WhatsApp, provide payment evidence, and we will send you a secret code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    // Attempts remaining badge
                    Text(
                        text = "⚠️ $attemptsLeft attempt${if (attemptsLeft == 1) "" else "s"} remaining",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (attemptsLeft <= 1)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = secretCode,
                        onValueChange = { secretCode = it },
                        label = { Text("Secret Code") },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                BounceButton(
                    onClick = { redeemCode() },
                    enabled = !isLoading && secretCode.isNotBlank()
                ) { Text("Redeem") }
            },
            dismissButton = {
                BounceTextButton(onClick = { showSecretCodeDialog = false }, enabled = !isLoading) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Main Screen ──────────────────────────────────────────────────
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ─ Illustration Placeholder (top 25% area) ──────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxSize()
                ) {}
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(28.dp))

            // ─ Title ────────────────────────────────────────────────
            Text(
                text = "Payment Due",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ─ Description ──────────────────────────────────────────
            Text(
                text = "A subscription is required to continue using this service. " +
                       "An active internet connection is needed to process payments.\n\n" +
                       "Select a plan below, complete payment, then tap \"I Have Paid\" to restore your access.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(20.dp))

            // ─ Account Info ─────────────────────────────────────────
            Text(
                text = buildAnnotatedString {
                    append("Choose a payment option for\nAccount: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(userEmail)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(24.dp))

            // ─ Plan Selection Cards ──────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SubscriptionPlan.entries.forEach { plan ->
                    PlanSelectionCard(
                        plan = plan,
                        isSelected = selectedPlan == plan,
                        isLoading = isLoading,
                        onClick = {
                            selectedPlan = plan
                            showPlanError = false
                            errorMessage = null
                            initializePayment(plan)
                        }
                    )
                }
            }

            // Plan validation error
            if (showPlanError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Please select a plan above to continue.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(16.dp))

            // ─ Secret Code Button ────────────────────────────────────
            if (!secretCodeLocked) {
                BounceTextButton(
                    onClick = { showSecretCodeDialog = true },
                    enabled = !isLoading
                ) {
                    Icon(
                        Icons.Rounded.VpnKey,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Have a secret code? Tap here to unlock.",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                // Locked state — show message instead of button
                Text(
                    text = "🔒 Secret code feature disabled after 3 failed attempts.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            // ─ Internet Required Note ────────────────────────────────
            Text(
                text = "⚡ Internet connection is required",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            // ─ Feedback Messages ─────────────────────────────────────
            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            successMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
            }

            // ─ I Have Paid Button ────────────────────────────────────
            BounceButton(
                onClick = { verifyPayment() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(56.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                )
            ) {
                if (!isLoading) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = if (isLoading) "Verifying…" else "I Have Paid",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(32.dp))

            // ─ Support Footer ────────────────────────────────────────
            Text(
                text = buildAnnotatedString {
                    append("If you are experiencing issues with your payment, ")
                    withStyle(
                        SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append("click here to contact support")
                    }
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .bounceClickable { uriHandler.openUri("https://wa.me/233540605230") }
                    .padding(8.dp)
            )

            Spacer(Modifier.height(8.dp))

            BounceTextButton(onClick = { AuthManager.signOut() }) {
                Text("Sign Out", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlanSelectionCard(
    plan: SubscriptionPlan,
    isSelected: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(200),
        label = "border"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        animationSpec = tween(200),
        label = "container"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .bounceClickable(enabled = !isLoading, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = plan.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plan.price,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                plan.badge?.let { badge ->
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (isSelected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
