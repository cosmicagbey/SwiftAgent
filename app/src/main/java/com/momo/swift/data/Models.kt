package com.momo.swift.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Represents the type of a single step in a USSD interaction sequence.
 */
enum class UssdStepType {
    /** Select a numeric menu option (e.g., "1" for Cash In). */
    OPTION,
    /** Input a phone number from the global field. */
    PHONE,
    /** Input an amount from the global field. */
    AMOUNT,
    /** Input a reference/note from the global field. */
    REFERENCE,
    /** Pause and let the user type their PIN manually. */
    PIN
}

/**
 * A single step in a USSD interaction sequence.
 *
 * @property type The kind of action to perform at this step.
 * @property value For [UssdStepType.OPTION], the menu number to input. Ignored for other types.
 */
data class UssdStep(
    val type: UssdStepType,
    val value: String = ""
)

/**
 * Describes a complete USSD transaction as an ordered list of steps
 * to be executed after dialing *171#.
 *
 * @property name Human-readable label (e.g., "Cash In").
 * @property steps Ordered list of [UssdStep] to perform.
 */
data class UssdTransaction(
    val name: String,
    val steps: List<UssdStep>
)

/**
 * Data class representing a user-defined USSD action button.
 *
 * @property id Unique identifier for the button.
 * @property title The label displayed on the button.
 * @property ussdTemplate The USSD string template (e.g., "*171*1*2*1#") dialed directly.
 * @property network Optional network carrier name (MTN, Telecel, AT) this button is bound to.
 */
@Serializable
data class CustomButton(
    val id: String,
    val title: String,
    val ussdTemplate: String,
    val network: String? = null
)

/**
 * Data class holding the application's configuration and user settings.
 */
@Serializable
data class AppSettings(
    val maxAirtimeLimit: Double = 1000.00,
    val maxCashLimit: Double = 10000.00,
    val customButtons: List<CustomButton> = emptyList(),
    val selectedSimId: Int? = null,
    val recentPhoneNumbers: List<String> = emptyList(),
    val isPremium: Boolean = false,
    val premiumExpiresAt: Long = 0L,
    val firstLaunchTimestamp: Long = 0L,
    val trialStartedAt: Long = 0L,
    val userEmail: String = "",
    val darkMode: Boolean = false,
    val trialExpired: Boolean = false,
    val hasSeenOnboarding: Boolean = false,
    val secretCodeAttemptsUsed: Int = 0,
    val isTestingMode: Boolean = false,
    val currentPaymentReference: String? = null
)

/**
 * Server-provided trial and subscription status.
 */
@Serializable
data class TrialStatus(
    val isPremium: Boolean = false,
    val premiumExpiresAt: Long = 0L,
    val trialStartedAt: Long = 0L,
    val trialExpired: Boolean = true,
    val daysLeft: Int = 0,
    val deviceAlreadyUsed: Boolean = false,
    val subscriptionDaysLeft: Int = 0,
    val isRegistered: Boolean = true
)

/**
 * Subscription plan definitions.
 */
enum class SubscriptionPlan(
    val id: String,
    val displayName: String,
    val price: String,
    val amountInPesewas: Int,
    val description: String,
    val badge: String? = null
) {
    MONTHLY("1month", "1 Month", "GHS 7.00", 700, "30 days of access"),
    THREE_MONTHS("3months", "3 Months", "GHS 15.00", 1500, "90 days of access — Save 28%", badge = "Save 28%")
}

/**
 * Status of a transaction log entry.
 */
enum class TransactionStatus {
    PENDING, SUCCESS, FAILED, TIMED_OUT
}

/**
 * Room entity representing a single transaction log entry.
 */
@Entity(tableName = "transaction_log")
data class TransactionLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val amount: String,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val responseText: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val network: String? = null
)
