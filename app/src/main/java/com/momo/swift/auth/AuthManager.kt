package com.momo.swift.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.installations.FirebaseInstallations
import com.momo.swift.data.TrialStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/**
 * Manages Firebase Authentication and server-side trial/subscription status.
 */
object AuthManager {

    private const val TAG = "AuthManager"

    private lateinit var auth: FirebaseAuth
    private lateinit var functions: FirebaseFunctions
    private var cachedDeviceId: String? = null
    private var cachedSignals: DeviceSignals? = null
    var currentPaymentReference: String? = null

    /**
     * Device signals used for cross-signal device recognition.
     * Hashed before leaving the device for privacy.
     */
    data class DeviceSignals(
        val deviceId: String,         // raw Android ID (primary key, backward-compat)
        val fidHash: String           // hashed Firebase Installation ID
    ) {
        fun toMap() = hashMapOf(
            "deviceId"         to deviceId,
            "fidHash"          to fidHash
        )
    }

    fun init(context: Context) {
        auth = FirebaseAuth.getInstance()
        functions = FirebaseFunctions.getInstance()
        cachedDeviceId = getDeviceId(context)
    }

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isLoggedIn: Boolean
        get() = auth.currentUser != null

    /**
     * Emits the current FirebaseUser whenever auth state changes.
     */
    val authStateFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    // ── Device Signal Helpers ─────────────────────────────────────────

    /** SHA-256 hex digest of [input]. */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Gets the Android ID — unique per device until factory reset.
     */
    @SuppressLint("HardwareIds")
    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
    }

    fun getDeviceIdCached(): String = cachedDeviceId ?: "unknown"

    /**
     * Collects and returns device signals, hashing where appropriate.
     * Result is cached for the lifetime of the process.
     *
     * Signals:
     *  - deviceId          : raw Android ID (primary, backward-compat Firestore key)
     *  - fidHash           : SHA-256 of the Firebase Installation ID
     */
    suspend fun getDeviceSignals(): DeviceSignals {
        cachedSignals?.let { return it }

        val androidId = cachedDeviceId ?: "unknown"

        val fid = try {
            FirebaseInstallations.getInstance().id.await()
        } catch (e: Exception) {
            Log.w(TAG, "FID retrieval failed: ${e.message}")
            "unknown-fid"
        }

        val signals = DeviceSignals(
            deviceId        = androidId,
            fidHash         = sha256(fid)
        )
        cachedSignals = signals
        Log.d(TAG, "DeviceSignals resolved: androidId=$androidId fidHash=${signals.fidHash.take(8)}…")
        return signals
    }

    // ── Auth Operations ──────────────────────────────────────────────

    suspend fun signUp(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw Exception("Sign up succeeded but user is null")
            Log.d(TAG, "Sign up success: ${user.uid}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: throw Exception("Sign in succeeded but user is null")
            Log.d(TAG, "Sign in success: ${user.uid}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user ?: throw Exception("Sign in succeeded but user is null")
            Log.d(TAG, "Google Sign in success: ${user.uid}")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(e)
        }
    }

    fun signOut() {
        auth.signOut()
    }

    // ── Cloud Function Calls ─────────────────────────────────────────

    private fun parseTrialStatus(map: Map<String, Any>): TrialStatus {
        val trialStartedAtVal = (map["trialStartedAt"] as? Number)?.toLong() ?: 0L
        val isPremiumVal = map["isPremium"] as? Boolean ?: false
        val trialDurationMs = 60L * 24 * 60 * 60 * 1000
        val isExpiredByTime = trialStartedAtVal > 0L &&
            (System.currentTimeMillis() > (trialStartedAtVal + trialDurationMs))
        val trialExpiredVal = map["trialExpired"] as? Boolean ?: isExpiredByTime

        return TrialStatus(
            isPremium = isPremiumVal,
            premiumExpiresAt = (map["premiumExpiresAt"] as? Number)?.toLong() ?: 0L,
            trialStartedAt = trialStartedAtVal,
            trialExpired = trialExpiredVal,
            daysLeft = if (trialExpiredVal) 0 else ((map["daysLeft"] as? Number)?.toInt() ?: 0),
            deviceAlreadyUsed = map["deviceAlreadyUsed"] as? Boolean ?: false,
            subscriptionDaysLeft = (map["subscriptionDaysLeft"] as? Number)?.toInt() ?: 0,
            isRegistered = map["isRegistered"] as? Boolean ?: true
        )
    }

    /**
     * Registers the user's trial on the server.
     * Called once after sign-up. Sends both device signals (Android ID + FID hash) so the
     * server can build the multi-signal index for future cross-signal lookups.
     */
    @Deprecated("Merged into checkTrialStatus", ReplaceWith("checkTrialStatus()"))
    suspend fun registerTrial(): Result<TrialStatus> {
        return try {
            val data = getDeviceSignals().toMap()
            val result = functions.getHttpsCallable("registerTrial").call(data).await()

            @Suppress("UNCHECKED_CAST")
            val map = result.getData() as? Map<String, Any> ?: emptyMap()
            val status = parseTrialStatus(map)

            Log.d(TAG, "registerTrial: $status")
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "registerTrial failed", e)
            Result.failure(e)
        }
    }

    /**
     * Checks the user's trial/subscription status from the server.
     * Called on every app launch. Sends device signals so the server
     * can match via any available signal if Android ID rotated.
     */
    suspend fun checkTrialStatus(): Result<TrialStatus> {
        return try {
            val data = getDeviceSignals().toMap()
            val result = functions.getHttpsCallable("checkTrialStatus").call(data).await()

            @Suppress("UNCHECKED_CAST")
            val map = result.getData() as? Map<String, Any> ?: emptyMap()
            val status = parseTrialStatus(map)

            Log.d(TAG, "checkTrialStatus: $status")
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "checkTrialStatus failed", e)
            Result.failure(e)
        }
    }

    /**
     * Initializes a Paystack transaction for a given plan.
     * Returns a Pair of (authorizationUrl, reference)
     */
    suspend fun initializeTransaction(plan: String): Result<Pair<String, String>> {
        return try {
            val data = hashMapOf("plan" to plan)
            val result = functions.getHttpsCallable("initializeTransaction").call(data).await()

            @Suppress("UNCHECKED_CAST")
            val map = result.getData() as? Map<String, Any> ?: emptyMap()

            val authUrl = map["authorizationUrl"] as? String
            val reference = map["reference"] as? String

            if (authUrl != null && reference != null) {
                currentPaymentReference = reference
                Result.success(Pair(authUrl, reference))
            } else {
                Result.failure(Exception("Invalid response from server"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "initializeTransaction failed", e)
            Result.failure(e)
        }
    }

    /**
     * Manually verifies a Paystack transaction.
     */
    suspend fun verifyTransactionManual(reference: String): Result<Boolean> {
        return try {
            val data = hashMapOf("reference" to reference)
            val result = functions.getHttpsCallable("verifyTransactionManual").call(data).await()

            @Suppress("UNCHECKED_CAST")
            val map = result.getData() as? Map<String, Any> ?: emptyMap()

            val success = map["success"] as? Boolean ?: false
            if (success) {
                Result.success(true)
            } else {
                Result.failure(Exception(map["message"] as? String ?: "Verification failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyTransactionManual failed", e)
            Result.failure(e)
        }
    }

    /**
     * Redeems a secret activation code to unlock the app manually.
     */
    suspend fun redeemSecretCode(code: String): Result<Boolean> {
        return try {
            val data = hashMapOf("code" to code)
            val result = functions.getHttpsCallable("redeemSecretCode").call(data).await()

            @Suppress("UNCHECKED_CAST")
            val map = result.getData() as? Map<String, Any> ?: emptyMap()

            val success = map["success"] as? Boolean ?: false
            if (success) {
                Result.success(true)
            } else {
                Result.failure(Exception(map["message"] as? String ?: "Failed to redeem code"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "redeemSecretCode failed", e)
            Result.failure(e)
        }
    }

    /**
     * Pre-auth lookup: queries the server for the email previously linked to this device.
     * Sends device signals so the server can match via Android ID (primary) or
     * Firebase Installation ID — whichever is available.
     * Fails silently (returns null) — never blocks the sign-in flow.
     */
    suspend fun lookupLinkedAccount(): String? {
        return try {
            val data = getDeviceSignals().toMap()
            val result = functions.getHttpsCallable("lookupLinkedAccount").call(data).await()

            @Suppress("UNCHECKED_CAST")
            val map = result.getData() as? Map<String, Any> ?: return null

            val hasAccount = map["hasAccount"] as? Boolean ?: false
            if (!hasAccount) return null

            (map["lockedEmail"] as? String)?.takeIf { it.isNotBlank() }
                .also { email -> Log.d(TAG, "lookupLinkedAccount: found=$email via ${map["matchedSignal"]}") }
        } catch (e: Exception) {
            Log.w(TAG, "lookupLinkedAccount failed (non-critical): ${e.message}")
            null
        }
    }

    /**
     * Attempts a completely silent, no-UI sign-in for a specific Google account.
     * Uses the Credential Manager API with filterByAuthorizedAccounts=true so only
     * pre-authorized accounts are considered — no account picker is shown.
     *
     * If the silently returned account doesn't match [email], the call fails so the
     * caller can fall back to the normal Credential Manager picker.
     *
     * Returns Result.success if the correct account was silently authenticated.
     * Returns Result.failure if not silently available or wrong account returned.
     */
    suspend fun silentSignInWithEmail(
        context: Context,
        webClientId: String,
        email: String
    ): Result<FirebaseUser> {
        return try {
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)  // only pre-authorized — no picker shown
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)            // auto-pick if exactly one account matches
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {

                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val returnedEmail = googleIdTokenCredential.id

                // Enforce that the silently returned account matches the device-locked email
                if (!returnedEmail.equals(email, ignoreCase = true)) {
                    Log.w(TAG, "silentSignInWithEmail: returned $returnedEmail but expected $email")
                    return Result.failure(Exception("Silent sign-in returned wrong account"))
                }

                Log.d(TAG, "silentSignInWithEmail: succeeded for $email via Credential Manager")
                signInWithGoogle(googleIdTokenCredential.idToken)
            } else {
                Result.failure(Exception("Unsupported credential type in silent sign-in"))
            }
        } catch (e: GetCredentialException) {
            Log.w(TAG, "silentSignInWithEmail: no silent credential for $email — ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "silentSignInWithEmail failed for $email: ${e.message}")
            Result.failure(e)
        }
    }
}
