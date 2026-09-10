package com.momo.swift.service

import android.util.Log
import com.momo.swift.data.TransactionLogDao
import com.momo.swift.data.TransactionLogEntry
import com.momo.swift.data.TransactionStatus
import com.momo.swift.data.UssdStep
import com.momo.swift.data.UssdStepType
import com.momo.swift.data.UssdTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the state of the current USSD interaction.
 */
enum class UssdState {
    IDLE,
    PROCESSING,
    DONE,
    TIMED_OUT
}

/**
 * Singleton that manages the state of an in-progress USSD transaction.
 *
 * The UI writes the global phone number, amount, and the transaction definition here
 * before dialing *171#. The [USSDService] then reads the current step and advances
 * through the sequence as each USSD dialog appears.
 *
 * State is exposed as a [StateFlow] so Compose can reactively observe it
 * (e.g., to disable buttons while processing).
 */
object UssdStateManager {

    private const val TAG = "UssdStateManager"
    private const val TIMEOUT_MS = 15_000L
    private const val NETWORK_CONFIRMATION_WINDOW_MS = 120_000L
    private data class PendingStatusUpdate(
        val token: Long,
        val status: TransactionStatus,
        val responseText: String
    )
    private data class PendingNetworkConfirmation(
        val token: Long,
        val entryId: Long,
        val phone: String,
        val amountValue: Double?,
        val expiresAt: Long
    )

    private val _stateFlow = MutableStateFlow(UssdState.IDLE)
    val stateFlow: StateFlow<UssdState> = _stateFlow.asStateFlow()

    /** Convenience accessor for non-Flow usage. */
    val state: UssdState get() = _stateFlow.value

    /** Name of the currently active transaction, or null if idle. */
    val currentTransactionName: String?
        @Synchronized get() = transaction?.name

    @Volatile
    var globalPhoneNumber: String = ""

    @Volatile
    var globalAmount: String = ""

    @Volatile
    var globalReference: String = ""

    private var transaction: UssdTransaction? = null
    private var currentStepIndex: Int = 0
    private val pendingStatusUpdates = mutableMapOf<Long, PendingStatusUpdate>()
    private var activeTransactionToken: Long = 0
    private var pendingNetworkConfirmation: PendingNetworkConfirmation? = null

    /** ID of the current transaction log entry in Room DB, or -1 if none. */
    @Volatile
    var currentLogEntryId: Long = -1L
        private set

    /** DAO injected from the app layer so the manager can update log entries. */
    @Volatile
    var dao: TransactionLogDao? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeoutJob: Job? = null

    /**
     * Begin a new transaction. Called from the UI right before dialing *171#.
     * Also inserts a PENDING log entry into Room when logging is enabled.
     */
    @Synchronized
    fun startTransaction(tx: UssdTransaction, phone: String, amount: String, reference: String = "", shouldLog: Boolean = true) {
        activeTransactionToken++
        val transactionToken = activeTransactionToken
        transaction = tx
        currentStepIndex = 0
        globalPhoneNumber = phone
        globalAmount = amount
        globalReference = reference
        _stateFlow.value = UssdState.PROCESSING

        currentLogEntryId = -1L
        if (shouldLog) {
            // Insert a PENDING log entry
            scope.launch {
                val entry = TransactionLogEntry(
                    name = tx.name,
                    phone = phone,
                    amount = amount,
                    status = TransactionStatus.PENDING
                )
                val id = dao?.insert(entry) ?: -1L
                dao?.pruneOldLogs()
                var pendingForThisToken: PendingStatusUpdate? = null
                synchronized(this@UssdStateManager) {
                    if (transactionToken == activeTransactionToken) {
                        currentLogEntryId = id
                        if (id > 0) {
                            pendingForThisToken = pendingStatusUpdates.remove(transactionToken)
                        }
                    } else if (id > 0) {
                        // Transaction already rotated, but we still have a deferred status for this token.
                        pendingForThisToken = pendingStatusUpdates.remove(transactionToken)
                    }

                    val pendingNetwork = pendingNetworkConfirmation
                    if (id > 0 && pendingNetwork != null && pendingNetwork.token == transactionToken && pendingNetwork.entryId <= 0L) {
                        pendingNetworkConfirmation = pendingNetwork.copy(entryId = id)
                    }
                }
                pendingForThisToken?.let { pending ->
                    dao?.updateStatus(id, pending.status, pending.responseText)
                }
                Log.d(TAG, "Created log entry id=$id")
            }
        }

        // Start the 5s timeout
        startTimeout()
    }

    @Synchronized
    fun startTransaction(tx: UssdTransaction, phone: String, amount: String) {
        startTransaction(tx, phone, amount, "", true)
    }

    /**
     * Returns the current [UssdStep] in the sequence, or `null` if the sequence is finished.
     */
    @Synchronized
    fun getCurrentStep(): UssdStep? {
        val tx = transaction ?: return null
        if (currentStepIndex >= tx.steps.size) return null
        return tx.steps[currentStepIndex]
    }

    /**
     * Returns the text value that should be typed into the USSD input field
     * for the current step.
     */
    @Synchronized
    fun getValueForCurrentStep(): String? {
        val step = getCurrentStep() ?: return null
        return when (step.type) {
            UssdStepType.OPTION -> step.value
            UssdStepType.PHONE -> globalPhoneNumber
            UssdStepType.AMOUNT -> globalAmount
            UssdStepType.REFERENCE -> globalReference.ifBlank { "Momo" }
            UssdStepType.PIN -> null // Unused — PIN is not a managed step
        }
    }

    /**
     * Move to the next step. If there are no more steps, transitions to [UssdState.DONE].
     * Resets the timeout timer on each advance.
     */
    @Synchronized
    fun advanceStep() {
        currentStepIndex++
        val nextStep = getCurrentStep()
        if (nextStep == null) {
            _stateFlow.value = UssdState.DONE
            cancelTimeout()
        } else {
            // Reset timeout — the USSD flow is still progressing
            startTimeout()
        }
    }

    /**
     * Rolls back the transaction by one step.
     * Used if an action (like clicking 'Send') fails after the step was advanced.
     */
    @Synchronized
    fun rollBackStep() {
        if (currentStepIndex > 0) {
            currentStepIndex--
            _stateFlow.value = UssdState.PROCESSING
        }
    }

    /**
     * Mark the current transaction as completed with a final result.
     * Called by [USSDService] after parsing the final USSD response.
     */
    fun completeTransaction(status: TransactionStatus, responseText: String = "") {
        cancelTimeout()
        _stateFlow.value = UssdState.IDLE
        clearPendingNetworkConfirmationForActiveToken()
        updateStatusWhenLogIsReady(status, responseText)
        clearInternal()
    }

    /**
     * Keeps the transaction in PENDING status while waiting for a later
     * network confirmation message that can close it as SUCCESS.
     */
    @Synchronized
    fun completeTransactionAwaitingNetwork(responseText: String = "Awaiting network confirmation") {
        cancelTimeout()
        _stateFlow.value = UssdState.IDLE

        pendingNetworkConfirmation = PendingNetworkConfirmation(
            token = activeTransactionToken,
            entryId = currentLogEntryId,
            phone = globalPhoneNumber,
            amountValue = globalAmount.toDoubleOrNull(),
            expiresAt = System.currentTimeMillis() + NETWORK_CONFIRMATION_WINDOW_MS
        )
        updateStatusWhenLogIsReady(TransactionStatus.PENDING, responseText)
        clearInternal()
    }

    /**
     * Tries to mark the pending transaction as SUCCESS when a later network message arrives.
     */
    fun tryApplyNetworkConfirmation(messageText: String): Boolean {
        val candidate = synchronized(this) {
            val pending = pendingNetworkConfirmation ?: return false
            if (System.currentTimeMillis() > pending.expiresAt) {
                pendingNetworkConfirmation = null
                return false
            }
            pending
        }

        if (!matchesPendingTransaction(candidate, messageText)) return false
        if (candidate.entryId <= 0L) return false

        scope.launch {
            dao?.updateStatus(candidate.entryId, TransactionStatus.SUCCESS, messageText)
            Log.d(TAG, "Network confirmation matched log entry id=${candidate.entryId}")
        }
        synchronized(this) {
            if (pendingNetworkConfirmation?.token == candidate.token) {
                pendingNetworkConfirmation = null
            }
        }
        return true
    }

    /**
     * Reset the state machine to [UssdState.IDLE].
     */
    @Synchronized
    fun reset() {
        cancelTimeout()
        _stateFlow.value = UssdState.IDLE
        clearInternal()
    }

    private fun clearInternal() {
        transaction = null
        currentStepIndex = 0
        globalPhoneNumber = ""
        globalAmount = ""
        globalReference = ""
        currentLogEntryId = -1L
    }

    // ── Timeout management ──

    private fun startTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            Log.w(TAG, "USSD timeout reached (${TIMEOUT_MS}ms)")
            _stateFlow.value = UssdState.TIMED_OUT
            // User requested that cancelled / timed out USSD sessions default to SUCCESS
            updateStatusWhenLogIsReady(
                TransactionStatus.SUCCESS,
                "Completed"
            )
            // Move back to IDLE so the UI can recover and allow new actions.
            _stateFlow.value = UssdState.IDLE
            clearInternal()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    @Synchronized
    private fun updateStatusWhenLogIsReady(status: TransactionStatus, responseText: String) {
        val entryId = currentLogEntryId
        if (entryId > 0) {
            scope.launch {
                dao?.updateStatus(entryId, status, responseText)
                Log.d(TAG, "Updated log entry id=$entryId → $status")
            }
            return
        }

        // The PENDING row insert may still be in flight.
        pendingStatusUpdates[activeTransactionToken] = PendingStatusUpdate(
            token = activeTransactionToken,
            status = status,
            responseText = responseText
        )
    }

    @Synchronized
    private fun clearPendingNetworkConfirmationForActiveToken() {
        if (pendingNetworkConfirmation?.token == activeTransactionToken) {
            pendingNetworkConfirmation = null
        }
    }

    private fun matchesPendingTransaction(
        pending: PendingNetworkConfirmation,
        messageText: String
    ): Boolean {
        val digitsInMessage = messageText.filter { it.isDigit() }
        val expectedPhone = pending.phone.filter { it.isDigit() }
        val phoneMatch = expectedPhone.isBlank() ||
            digitsInMessage.contains(expectedPhone) ||
            (expectedPhone.length >= 7 && digitsInMessage.contains(expectedPhone.takeLast(7)))

        val amountMatch = pending.amountValue == null || extractNumericValues(messageText).any {
            kotlin.math.abs(it - pending.amountValue) <= 0.01
        }

        return phoneMatch && amountMatch
    }

    private fun extractNumericValues(text: String): List<Double> {
        val regex = Regex("""\d+(?:[.,]\d{1,2})?""")
        return regex.findAll(text).mapNotNull { match ->
            match.value.replace(",", ".").toDoubleOrNull()
        }.toList()
    }
}
