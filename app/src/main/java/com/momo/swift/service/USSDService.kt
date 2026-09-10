package com.momo.swift.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.momo.swift.data.AppDatabase
import com.momo.swift.data.TransactionStatus

/**
 * AccessibilityService that monitors USSD dialogs across all OEM phone packages.
 *
 * It uses a high-speed, event-driven, signature-based state machine ported from UserMomo.
 * Instead of timing assumptions, it advances step indices strictly when visual transitions
 * are confirmed by comparing non-editable dialog signatures.
 */
class USSDService : AccessibilityService() {

    companion object {
        private const val TAG = "USSDService"

        /** Known packages that host USSD dialogs on various OEMs. */
        private val USSD_PACKAGES = setOf(
            "com.android.phone",
            "com.samsung.android.dialer",
            "com.samsung.android.phone",
            "com.mediatek.phone",
            "com.google.android.dialer",
            "com.android.server.telecom"
        )

        /** Delay between ACTION_SET_TEXT and ACTION_CLICK to let the UI settle. */
        private const val SEND_CLICK_DELAY_MS = 50L

        /** Keywords indicating a successful USSD transaction. */
        private val SUCCESS_KEYWORDS = listOf(
            "successful", "has been completed", "confirmed", "payment received", "payment made", "cash in made", "cash out made", "transfer made",
            "reussie", "réussie", "effectuée" // French variants for MTN regions
        )

        /** Keywords indicating a failed USSD transaction. */
        private val FAILURE_KEYWORDS = listOf(
            "failed", "error", "insufficient", "invalid", "denied", "rejected",
            "echec", "échoué", "insuffisant" // French variants
        )
    }

    private enum class SubmissionPhase { IDLE, READY, AWAITING_CHANGE }

    private val handler = Handler(Looper.getMainLooper())

    private var submissionPhase = SubmissionPhase.IDLE
    private var submittedOnSignature = ""
    private var lastSeenSignature = ""
    private var lastKnownUssdState = UssdState.IDLE

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Inject DAO into state manager
        UssdStateManager.dao = AppDatabase.getInstance(applicationContext).transactionLogDao()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 40
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        Log.d(TAG, "USSDService connected — listening for USSD dialogs")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        val rootNode = rootInActiveWindow

        // Fix #29 — Do not inspect our own app's windows; only system/dialer packages.
        if (rootNode?.packageName?.startsWith("com.momo.swift") == true) return

        // Try to reconcile delayed network confirmation messages while IDLE.
        val eventText = buildEventText(event, rootNode)
        if (eventText.isNotBlank()) {
            val lower = eventText.lowercase()
            val looksSuccessful = SUCCESS_KEYWORDS.any { lower.contains(it) }
            if (looksSuccessful && UssdStateManager.tryApplyNetworkConfirmation(eventText)) {
                Log.d(TAG, "Applied delayed network confirmation from package: $pkg")
                return
            }
        }

        // Only react to known USSD / phone packages
        if (pkg !in USSD_PACKAGES) return

        // Sync local state when state manager transitions
        val ussdState = UssdStateManager.state
        if (ussdState != lastKnownUssdState) {
            when (ussdState) {
                UssdState.PROCESSING -> resetSubmissionState()   // new transaction started
                UssdState.IDLE,
                UssdState.TIMED_OUT -> submissionPhase = SubmissionPhase.IDLE
                else -> Unit
            }
            lastKnownUssdState = ussdState
        }

        // If state is DONE, try to capture the final response before resetting
        if (ussdState == UssdState.DONE) {
            val activeRootNode = rootNode ?: return
            try {
                captureAndParseFinalResponse(activeRootNode)
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing final USSD response", e)
                UssdStateManager.completeTransaction(TransactionStatus.PENDING, "Failed to parse USSD response")
            }
            return
        }

        // Only act when the state machine is actively processing
        if (ussdState != UssdState.PROCESSING) return

        val ussdRootNode = rootNode ?: return

        try {
            processUssdDialog(ussdRootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing USSD dialog", e)
        }
    }

    /**
     * Main per-event handler. Decides whether to ignore the event, advance
     * the step, or submit the current step value — based entirely on observed
     * dialog content changes rather than timing assumptions.
     */
    private fun processUssdDialog(rootNode: AccessibilityNodeInfo) {
        val signature = extractDialogSignature(rootNode)

        when (submissionPhase) {
            SubmissionPhase.AWAITING_CHANGE -> {
                // We are waiting for the dialog to change after our last Send.
                if (signature == submittedOnSignature) return  // same screen — ignore

                // ✓ A real screen/dialog transition was detected.
                // Advance the step index NOW, driven by the observed UI change.
                UssdStateManager.advanceStep()

                if (UssdStateManager.state == UssdState.DONE) {
                    // All steps consumed — this new screen is the final USSD response.
                    captureAndParseFinalResponse(rootNode)
                    submissionPhase = SubmissionPhase.IDLE
                    return
                }

                // More steps remain. Treat the new dialog as the next input screen.
                submissionPhase = SubmissionPhase.READY
                lastSeenSignature = signature
                // Fall through to submitCurrentStep().
            }

            SubmissionPhase.IDLE,
            SubmissionPhase.READY -> {
                // Deduplicate rapid TYPE_WINDOW_CONTENT_CHANGED events for the same dialog.
                if (signature == lastSeenSignature) return
                lastSeenSignature = signature
                submissionPhase = SubmissionPhase.READY
                // Fall through to submitCurrentStep().
            }
        }

        submitCurrentStep(rootNode, signature)
    }

    /**
     * Given a confirmed-new dialog, fill the input field and schedule a click.
     * On success, transitions phase to AWAITING_CHANGE.
     * On any failure, leaves phase as READY so the next event can retry.
     */
    private fun submitCurrentStep(rootNode: AccessibilityNodeInfo, signature: String) {
        val allText = extractAllText(rootNode)

        // If the screen already shows a terminal response, parse and finish.
        if (isFinalResponse(allText)) {
            captureAndParseFinalResponse(rootNode)
            submissionPhase = SubmissionPhase.IDLE
            return
        }

        val currentStep = UssdStateManager.getCurrentStep() ?: return

        val value = UssdStateManager.getValueForCurrentStep()
        val inputField = findEditableNode(rootNode)
        val sendButton = findSendButton(rootNode)

        if (inputField == null || sendButton == null || value.isNullOrBlank()) {
            // Screen has no input field — may be an intermediate info screen or an
            // early error. If it looks terminal, parse it; otherwise wait.
            if (isFinalResponse(allText)) captureAndParseFinalResponse(rootNode)
            return
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }

        val textSet = inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) {
            // setText failed — leave phase as READY to allow a retry on the next event.
            return
        }

        // Dismiss the soft keyboard immediately after typing.
        inputField.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)

        // Lock the phase immediately so stale content-changed events (which fire
        // while the send button is being clicked) don't re-enter this block.
        submissionPhase = SubmissionPhase.AWAITING_CHANGE
        submittedOnSignature = signature

        handler.postDelayed({
            val clicked = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                // Click failed after text was set — roll back so we can retry.
                submissionPhase = SubmissionPhase.READY
                submittedOnSignature = ""
            }
        }, SEND_CLICK_DELAY_MS)
    }

    private fun resetSubmissionState() {
        submissionPhase = SubmissionPhase.IDLE
        submittedOnSignature = ""
        lastSeenSignature = ""
    }

    private fun isFinalResponse(text: String): Boolean {
        val lower = text.lowercase()
        return SUCCESS_KEYWORDS.any { lower.contains(it) } ||
                FAILURE_KEYWORDS.any { lower.contains(it) }
    }

    private fun captureAndParseFinalResponse(rootNode: AccessibilityNodeInfo) {
        val responseText = extractAllText(rootNode)

        val lower = responseText.lowercase()
        when {
            FAILURE_KEYWORDS.any { lower.contains(it) } -> {
                UssdStateManager.completeTransaction(TransactionStatus.FAILED, responseText)
            }
            else -> {
                // Per user request: show failed only if a failed keyword is found.
                // Otherwise register as successful.
                UssdStateManager.completeTransaction(
                    TransactionStatus.SUCCESS,
                    responseText.ifBlank { "Completed" }
                )
            }
        }
    }

    private fun buildEventText(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        event.text?.forEach { value ->
            val part = value?.toString()?.trim().orEmpty()
            if (part.isNotBlank()) {
                sb.append(part).append(" ")
            }
        }
        val desc = event.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotBlank()) {
            sb.append(desc).append(" ")
        }
        if (rootNode != null) {
            val rootText = extractAllText(rootNode)
            if (rootText.isNotBlank()) {
                sb.append(rootText)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Produces a stable dialog identity string from non-editable node text only.
     * Excluding editable fields ensures that typing a value into an input does
     * not change the signature and trigger a false "new screen" detection.
     */
    private fun extractDialogSignature(rootNode: AccessibilityNodeInfo): String {
        val pieces = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.isEditable) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { pieces += it }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(rootNode)
        return pieces.distinct().joinToString("|")
    }

    /**
     * Recursively extracts all visible text from the accessibility node tree.
     */
    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            sb.append(text).append(" ")
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) {
            sb.append(desc).append(" ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(extractAllText(child))
        }
        return sb.toString().trim()
    }

    /**
     * Recursively search the node tree for an editable EditText.
     */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if this node is an editable text field
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText") && node.isEditable) {
            return node
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Recursively search the node tree for the Send / OK / Reply button.
     */
    private fun findSendButton(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Strategy 1: Try known AOSP IDs
        val knownIds = listOf(
            "com.android.phone:id/button1",
            "android:id/button1"
        )
        for (id in knownIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }

        // Strategy 2: Try by text content — multi-language dictionary (Fix #24)
        // Carrier payloads are in English but dialog buttons are localised by the Android OS.
        val sendTexts = listOf(
            // English
            "Send", "OK", "Reply", "SEND", "Ok",
            // French
            "Envoyer", "Envoie", "Répondre", "Envoyer",
            // Spanish
            "Enviar", "Aceptar", "Responder",
            // German
            "Senden", "OK", "Antworten",
            // Portuguese
            "Enviar", "Aceitar", "OK",
            // Swahili
            "Tuma", "Sawa",
            // Arabic
            "إرسال", "موافق",
            // Chinese (Simplified)
            "发送", "确定", "回复",
            // Russian
            "Отправить", "ОК",
            // Italian
            "Invia", "OK"
        )
        for (text in sendTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (n.isClickable) return n
                }
            }
        }

        // Strategy 3: Find a clickable Button that's not Cancel
        return findClickableButton(rootNode)
    }

    /**
     * Recursively find the first clickable Button that is NOT a cancel/dismiss action.
     * Cancel keywords cover 10 languages to match the expanded send-button dictionary (Fix #24).
     */
    private fun findClickableButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Fix #29 — Never interact with nodes that belong to our own package.
        if (node.packageName?.startsWith("com.momo.swift") == true) return null

        val className = node.className?.toString() ?: ""
        if (className.contains("Button") && node.isClickable) {
            val text = node.text?.toString()?.lowercase() ?: ""
            // Skip cancel/dismiss buttons (multi-language)
            val cancelKeywords = listOf(
                "cancel", "dismiss", "no",       // English
                "annuler", "non",                 // French
                "cancelar", "no",                 // Spanish
                "abbrechen", "nein",              // German
                "cancelar", "não",                // Portuguese
                "ghairi", "hapana",               // Swahili
                "إلغاء", "لا",                    // Arabic
                "取消", "否",                      // Chinese
                "отмена", "нет",                  // Russian
                "annulla", "no"                   // Italian
            )
            if (cancelKeywords.none { text == it }) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableButton(child)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "USSDService interrupted")
        UssdStateManager.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        UssdStateManager.reset()
        Log.d(TAG, "USSDService destroyed")
    }
}
