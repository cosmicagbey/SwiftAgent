package com.momo.swift.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Stores device-level auth constraints using Android Keystore-backed encryption.
 *
 *  - [lockedEmail]          – the first Google account successfully used on this device.
 *                             Once written, ONLY this email is allowed to authenticate.
 *  - [stampVerifiedNow]     – records the timestamp of the last successful backend
 *                             trial/subscription check.
 */
object DeviceSecureStorage {

    private const val FILE_NAME          = "device_secure_prefs"
    private const val KEY_LOCKED_EMAIL   = "locked_email"
    private const val KEY_LAST_VERIFIED  = "last_verified_ms"

    // Clock tampering guard key
    private const val KEY_LAST_KNOWN_TIME = "last_known_time_ms"

    private lateinit var prefs: SharedPreferences

    /**
     * Must be called once during app startup (e.g., in [MainActivity.onCreate]).
     * Sets up the Android Keystore master key and opens the encrypted preferences file.
     */
    fun init(context: Context) {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                FILE_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore might be corrupted on Samsung devices, clear the shared prefs file and retry
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                prefs = EncryptedSharedPreferences.create(
                    FILE_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fallbackException: Exception) {
                // Fallback to plain SharedPreferences if Keystore is completely broken
                prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    // ── Email Lock ────────────────────────────────────────────────────

    /**
     * The email address this device is permanently locked to.
     * Returns null if no account has ever successfully signed in.
     */
    val lockedEmail: String?
        get() = prefs.getString(KEY_LOCKED_EMAIL, null)?.takeIf { it.isNotBlank() }

    /**
     * Locks this device to [email]. Silently ignores subsequent calls so that
     * the locked email can never be overwritten once set.
     */
    fun setLockedEmail(email: String) {
        if (lockedEmail == null && email.isNotBlank()) {
            prefs.edit().putString(KEY_LOCKED_EMAIL, email).apply()
            // Also stamp verification time so a fresh install doesn't immediately
            // look offline-expired after the first successful sign-in.
            stampVerifiedNow()
        }
    }

    // ── Offline Verification Stamp ─────────────────────────────────────

    /** Returns the timestamp (in milliseconds) of the last successful verification. */
    val lastVerifiedTime: Long
        get() = prefs.getLong(KEY_LAST_VERIFIED, 0L)

    /** Records the current system time as the last successful backend verification. */
    fun stampVerifiedNow() {
        prefs.edit().putLong(KEY_LAST_VERIFIED, System.currentTimeMillis()).apply()
    }

    // ── Clock Tampering Guard ──────────────────────────────────────────

    /**
     * Updates the last known time to [currentTime].
     * Returns true if clock tampering is detected (the current time is in the past
     * compared to a previously recorded time).
     */
    fun checkAndSetClock(currentTime: Long): Boolean {
        val lastKnown = prefs.getLong(KEY_LAST_KNOWN_TIME, 0L)
        if (currentTime < lastKnown) {
            return true // System clock was set back in time
        }
        // Save the new maximum time observed
        prefs.edit().putLong(KEY_LAST_KNOWN_TIME, currentTime).apply()
        return false
    }
}
