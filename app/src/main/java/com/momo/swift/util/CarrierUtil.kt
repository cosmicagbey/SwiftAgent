package com.momo.swift.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * Detects the carrier/network name from the selected SIM in settings.
 * Matches against the SIM display name for MTN, Telecel, or AT.
 * Returns "MTN", "Telecel", "AT", or null if unrecognised / no permission.
 */
@SuppressLint("MissingPermission")
fun detectCarrier(context: Context, selectedSimId: Int?): String? {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) return null

    val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
    val sims = try {
        manager.activeSubscriptionInfoList ?: return null
    } catch (e: SecurityException) {
        return null
    }

    val sim = if (selectedSimId != null) {
        sims.firstOrNull { it.subscriptionId == selectedSimId }
    } else {
        sims.firstOrNull() // default: first active SIM
    } ?: return null

    val name = (sim.displayName ?: sim.carrierName ?: "").toString().uppercase()

    return when {
        name.contains("MTN")          -> "MTN"
        name.contains("TELECEL")      -> "Telecel"
        name.contains("VODAFONE")     -> "Telecel"     // legacy branding
        name.contains("AIRTELTIGO")   -> "AT"          // merged brand
        name.contains("AIRTEL")       -> "AT"          // legacy branding
        name == "AT" || name.startsWith("AT ") || name.endsWith(" AT") -> "AT"
        else -> null
    }
}
