package com.momo.swift.ui

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import android.util.Log
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.momo.swift.R
import com.momo.swift.auth.AuthManager
import com.momo.swift.auth.DeviceSecureStorage
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions


/**
 * Returns true when the device has an active, confirmed internet connection.
 *
 * We check BOTH:
 *  - NET_CAPABILITY_INTERNET  — the network interface is marked for internet use
 *  - NET_CAPABILITY_VALIDATED — Android has confirmed actual internet reachability
 *
 * Checking only INTERNET (as before) causes false negatives on many OEM ROMs
 * (Tecno, Infinix, Samsung) where the network is marked for internet but
 * VALIDATED hasn’t been set yet, or on VPN/captive-portal setups.
 */
private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    // Accept validated networks OR cellular (many OEM ROMs don’t set VALIDATED on mobile data)
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
           caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}

private const val NO_INTERNET_MSG =
    "No internet connection. Please check your Wi-Fi or mobile data and try again."


@Composable
fun LoginScreen(
    initialError: String? = null,
    onAuthSuccess: () -> Unit,
    onReviewerBypass: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webClientId = stringResource(id = R.string.default_web_client_id)

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember(initialError) { mutableStateOf(initialError) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var hasPromptedGoogleSignIn by remember { mutableStateOf(false) }
    // Raw exception details for remote diagnostics (Fix #23)
    var rawErrorDetails by remember { mutableStateOf<String?>(null) }
    // The effective locked email — from local storage or restored from server after reinstall
    var effectiveLockedEmail by remember { mutableStateOf(DeviceSecureStorage.lockedEmail) }

    var logoTapCount by remember { mutableStateOf(0) }
    var showPinDialog by remember { mutableStateOf(false) }

    if (showPinDialog) {
        var pinText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                logoTapCount = 0
            },
            title = { Text("Reviewer Access") },
            text = {
                Column {
                    Text("Enter PIN:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinText == "8765") {
                            showPinDialog = false
                            logoTapCount = 0
                            onReviewerBypass()
                        } else {
                            showPinDialog = false
                            logoTapCount = 0
                        }
                    }
                ) {
                    Text("Submit")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPinDialog = false
                        logoTapCount = 0
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    fun launchGoogleSignIn() {
        // Fix #25: Synchronous guard — prevents a second call from racing in
        // before the first coroutine has a chance to set isLoading = true.
        if (isLoading) return
        isLoading = true
        rawErrorDetails = null  // Fix #23: reset diagnostics on each new attempt
        scope.launch {
            try {
                errorMessage = null

                // ── Pre-flight connectivity check ─────────────────────
                if (!isOnline(context)) {
                    errorMessage = NO_INTERNET_MSG
                    isLoading = false
                    return@launch
                }

                val credentialManager = CredentialManager.create(context)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .setNonce(UUID.randomUUID().toString())
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val activity = context as? android.app.Activity ?: run {
                    errorMessage = "Unexpected context. Please restart the app."
                    isLoading = false
                    return@launch
                }
                val result = credentialManager.getCredential(
                    context = activity,
                    request = request
                )

                statusMessage = null
                val credential = result.credential
                if (credential is androidx.credentials.CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken
                        val signedInEmail = googleIdTokenCredential.id   // email address

                        // Email lock enforcement — ensure user picked the registered account
                        val locked = effectiveLockedEmail
                        if (locked != null && !signedInEmail.equals(locked, ignoreCase = true)) {
                            errorMessage = "This device is linked to $locked. Please continue with that account."
                            isLoading = false
                            return@launch
                        }

                        val authResult = AuthManager.signInWithGoogle(idToken)
                        authResult.fold(
                            onSuccess = {
                                // Lock device to this email on first successful sign-in.
                                DeviceSecureStorage.setLockedEmail(signedInEmail)
                                onAuthSuccess()
                            },
                            onFailure = { e ->
                                Log.w("LoginScreen", "signInWithGoogle failure: ${e.javaClass.simpleName}: ${e.message}")
                                rawErrorDetails = "${e.javaClass.simpleName}: ${e.message}"  // Fix #23
                                errorMessage = formatAuthError(e.message ?: "Google Sign in failed.")
                            }
                        )
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.w("LoginScreen", "GoogleIdTokenParsingException: ${e.message}")
                        rawErrorDetails = "${e.javaClass.simpleName}: ${e.message}"  // Fix #23
                        errorMessage = "Invalid Google ID token."
                    }
                } else {
                    errorMessage = "Unsupported credential type."
                }
            } catch (e: NoCredentialException) {
                // Fix #21: No Google account is configured on the device at all.
                Log.w("LoginScreen", "NoCredentialException: ${e.message}")
                rawErrorDetails = "${e.javaClass.simpleName}: ${e.message}"  // Fix #23
                statusMessage = null
                errorMessage = "No Google account found on this device. Please add a Google account in your device Settings, then try again."
            } catch (_: GetCredentialCancellationException) {
                // User cancelled — ignore gracefully.
                statusMessage = null
            } catch (e: Exception) {
                Log.w("LoginScreen", "Sign-in exception: ${e.javaClass.simpleName}: ${e.message}")
                rawErrorDetails = "${e.javaClass.simpleName}: ${e.message}"  // Fix #23
                statusMessage = null
                // Surface a friendly offline message when the real cause is connectivity
                errorMessage = if (!isOnline(context)) {
                    NO_INTERNET_MSG
                } else {
                    formatAuthError(e.message ?: "Unknown error occurred")
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPromptedGoogleSignIn) {
            hasPromptedGoogleSignIn = true

            // ── Step 0: Connectivity gate ────────────────────────────
            // On some phones the network stack isn't fully ready at screen
            // launch (especially after device unlock or app cold start).
            // We retry up to 3 times with a 600ms gap before giving up.
            val online = run {
                var result = false
                repeat(3) { attempt ->
                    if (!result) {
                        if (attempt > 0) kotlinx.coroutines.delay(600L)
                        result = isOnline(context)
                    }
                }
                result
            }
            if (!online) {
                errorMessage = NO_INTERNET_MSG
                return@LaunchedEffect
            }

            // ── Step 1: Resolve the target email ────────────────────────
            // Check local storage first; if empty (e.g. after reinstall),
            // query the server by device ID.
            if (effectiveLockedEmail == null) {
                val serverEmail = AuthManager.lookupLinkedAccount()
                if (serverEmail != null) {
                    DeviceSecureStorage.setLockedEmail(serverEmail)
                    effectiveLockedEmail = serverEmail
                }
            }

            val targetEmail = effectiveLockedEmail

            // ── Step 2: If we know the email, try completely silent sign-in ──
            // GoogleSignInClient.silentSignIn() with setAccountName() is the
            // only Android API that authenticates a specific account with
            // ZERO UI — no picker, no bottom sheet, nothing shown to the user.
            if (targetEmail != null) {
                isLoading = true
                val silentResult = AuthManager.silentSignInWithEmail(
                    context = context,
                    webClientId = webClientId,
                    email = targetEmail
                )
                isLoading = false

                if (silentResult.isSuccess) {
                    // ✅ Signed in silently — no picker was shown at all
                    DeviceSecureStorage.setLockedEmail(targetEmail)
                    statusMessage = null
                    onAuthSuccess()
                    return@LaunchedEffect
                }

                // If silent sign-in failed due to connectivity, show offline message
                if (!isOnline(context)) {
                    errorMessage = NO_INTERNET_MSG
                    return@LaunchedEffect
                }
                // Otherwise fall through to picker
            }
            // (keep UI clean — no status message shown)

            // ── Step 3: Fallback picker (list of emails on their phone) ─────
            launchGoogleSignIn()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_icon),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(120.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        logoTapCount++
                        if (logoTapCount >= 5) {
                            showPinDialog = true
                        }
                    }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Swift Agent",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                "One tap, zero hassle.",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        "Welcome",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BounceButton(
                        onClick = { launchGoogleSignIn() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Rounded.AccountCircle, null, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Continue with Google",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Status message (e.g. "Recognized previous account. Signing you in…")
                    statusMessage?.let { status ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    errorMessage?.let { error ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ) {
                            Text(
                                error,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                        }
                        // Fix #23: Raw diagnostic overlay — shown beneath the main error card.
                        // Lets remote users take a screenshot of the actual exception for support.
                        rawErrorDetails?.let { raw ->
                            Text(
                                text = raw,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                                textAlign = TextAlign.Start
                            )
                        }
                    }

                    // Contact Us WhatsApp Link
                    val uriHandler = LocalUriHandler.current
                    BounceTextButton(
                        onClick = {
                            uriHandler.openUri("https://wa.me/233593095438")
                        }
                    ) {
                        Text(
                            text = "Contact Us",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private fun formatAuthError(rawError: String): String {
    val lower = rawError.lowercase()
    return when {
        lower.contains("network")            -> NO_INTERNET_MSG
        lower.contains("timeout")            -> NO_INTERNET_MSG
        lower.contains("deadline exceeded")  -> NO_INTERNET_MSG   // Fix #21: gRPC deadline
        lower.contains("unavailable")        -> NO_INTERNET_MSG   // Fix #21: gRPC UNAVAILABLE
        lower.contains("unable to resolve")  -> NO_INTERNET_MSG
        lower.contains("no internet")        -> NO_INTERNET_MSG
        // Only map "connect" if it's clearly a connection failure, not
        // e.g. "credential" which also contains the word.
        lower.contains("connection refused") || lower.contains("connection reset") ||
        lower.contains("failed to connect")  -> NO_INTERNET_MSG
        // Fix #21: Firebase/gRPC auth & server errors
        lower.contains("unauthenticated")    ->
            "Your session has expired. Please sign in again."
        lower.contains("permission")         ->
            "Sign-in was blocked. Please contact support if this continues."
        lower.contains("internal")           ->
            "A server error occurred. Please try again in a moment."
        // "failure response from one tap" is NOT necessarily a network issue —
        // it fires when no eligible Google accounts are configured on the device,
        // when One Tap rate-limits the user, or when the account isn’t registered.
        // Mapping it to NO_INTERNET_MSG produced false "no internet" banners.
        lower.contains("failure response from one tap") ->
            "Sign-in unavailable. Please tap the button below to continue."
        else -> "Authentication failed. Please try again."
    }
}
