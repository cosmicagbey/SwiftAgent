package com.momo.swift.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import com.momo.swift.data.AppSettings

/**
 * Engine responsible for processing USSD templates and initiating phone calls.
 * It handles placeholder replacement for legacy custom shortcuts and provides
 * [dialUssd] for the new accessibility-service-driven flow.
 */
object UssdEngine {

    /**
     * Dials a base USSD code (e.g., "*171#") using ACTION_CALL.
     * The "#" is properly encoded with [Uri.encode] to prevent dialer errors.
     *
     * This is used by the accessibility-service flow: the app dials *171# and then
     * the [com.momo.swift.service.USSDService] handles the subsequent menu interactions.
     *
     * @param context Android context.
     * @param ussdCode The raw USSD code to dial (e.g., "*171#").
     * @param appSettings Current app settings for SIM selection.
     */
    fun dialUssd(context: Context, ussdCode: String, appSettings: AppSettings): Boolean {
        val encodedHash = Uri.encode("#")
        val encoded = ussdCode.replace("#", encodedHash)

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encoded"))

        // Handle SIM Selection
        appSettings.selectedSimId?.let { subId ->
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccountHandle = getPhoneAccountHandleForSubscriptionId(telecomManager, subId)
            if (phoneAccountHandle != null) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            }
        }

        try {
            context.startActivity(intent)
            return true
        } catch (e: SecurityException) {
            Toast.makeText(context, "Call Permission Required", Toast.LENGTH_LONG).show()
            return false
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch USSD call", Toast.LENGTH_LONG).show()
            return false
        }
    }

    /**
     * Executes a USSD command by directly dialing a full USSD string.
     * Used for custom shortcuts that encode the entire command (e.g., "*171*1*2*1#").
     *
     * @param context Android context to start the activity.
     * @param template The USSD template containing <number>, <id>, or <amount> placeholders.
     * @param number The phone number or ID to replace in the template.
     * @param amount The transaction amount to replace in the template.
     * @param appSettings Current application settings used for limit verification and SIM selection.
     * @param isCash Boolean indicating if the transaction is cash-based.
     */
    fun executeUssd(
        context: Context,
        template: String,
        number: String,
        amount: String,
        appSettings: AppSettings,
        isCash: Boolean = true
    ): Boolean {
        if (template.contains("<number>") || template.contains("<id>")) {
            if (number.length != 10 || !number.all { it.isDigit() }) {
                Toast.makeText(context, "Number must be exactly 10 digits.", Toast.LENGTH_LONG).show()
                return false
            }
        }

        if (template.contains("<amount>")) {
            val amountValue = amount.toDoubleOrNull()
            if (amountValue == null || amountValue <= 0) {
                Toast.makeText(context, "Invalid amount.", Toast.LENGTH_LONG).show()
                return false
            }
            val limit = if (isCash) appSettings.maxCashLimit else appSettings.maxAirtimeLimit
            if (amountValue > limit) {
                Toast.makeText(context, "Amount exceeds the limit of $limit.", Toast.LENGTH_LONG).show()
                return false
            }
        }

        var processedUssd = template
            .replace("<number>", number)
            .replace("<id>", number)
            .replace("<amount>", amount)

        if (!processedUssd.endsWith("#")) {
            processedUssd += "#"
        }

        val encodedHash = Uri.encode("#")
        processedUssd = processedUssd.replace("#", encodedHash)

        // Create the dial intent
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$processedUssd"))

        // Handle SIM Selection
        appSettings.selectedSimId?.let { subId ->
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccountHandle = getPhoneAccountHandleForSubscriptionId(telecomManager, subId)
            if (phoneAccountHandle != null) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            }
        }

        try {
            context.startActivity(intent)
            return true
        } catch (e: SecurityException) {
            Toast.makeText(context, "Call Permission Required", Toast.LENGTH_LONG).show()
            return false
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to launch Call", Toast.LENGTH_LONG).show()
            return false
        }
    }

    /**
     * Helper to find the PhoneAccountHandle for a given subscription ID.
     */
    private fun getPhoneAccountHandleForSubscriptionId(
        telecomManager: TelecomManager,
        subscriptionId: Int
    ): PhoneAccountHandle? {
        val accounts = try {
            telecomManager.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            emptyList<PhoneAccountHandle>()
        }

        for (handle in accounts) {
            val account = try {
                telecomManager.getPhoneAccount(handle)
            } catch (e: SecurityException) {
                null
            }

            val extras = account?.extras
            val accountSubId = when {
                extras?.containsKey(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX) == true ->
                    extras.getInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                extras?.containsKey("android.telephony.extra.SUBSCRIPTION_ID") == true ->
                    extras.getInt("android.telephony.extra.SUBSCRIPTION_ID", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                else -> SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }

            if (accountSubId == subscriptionId) {
                return handle
            }
        }

        // OEM fallback: some devices expose the sub id in handle.id only.
        return accounts.firstOrNull { it.id.contains(subscriptionId.toString()) }
    }

    // Overload for simple execution (e.g. Balance check via direct dial)
    fun executeUssd(
        context: Context,
        template: String,
        appSettings: AppSettings,
        isCash: Boolean = true
    ): Boolean {
        return executeUssd(context, template, "", "", appSettings, isCash)
    }
}
