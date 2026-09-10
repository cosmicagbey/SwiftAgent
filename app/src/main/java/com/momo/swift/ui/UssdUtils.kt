package com.momo.swift.ui

import com.momo.swift.data.UssdStep
import com.momo.swift.data.UssdStepType

/**
 * Validates phone number (10 digits) and amount (non-empty, positive).
 * Calls the error setters if validation fails.
 *
 * @return `true` if inputs are valid.
 */
fun validateInputs(
    phone: String,
    amount: String,
    setPhoneError: () -> Unit,
    setAmountError: () -> Unit,
    isMerchant: Boolean = false
): Boolean {
    var valid = true
    val validLength = if (isMerchant) phone.length in 5..9 else phone.length == 10
    if (!validLength || !phone.all { it.isDigit() }) {
        setPhoneError()
        valid = false
    }
    val amountVal = amount.toDoubleOrNull()
    if (amountVal == null || amountVal <= 0) {
        setAmountError()
        valid = false
    }
    return valid
}

/**
 * Parses a USSD template string into a base dial code and a list of [UssdStep]s.
 *
 * Example: "*171*1*2*<number>*<amount>#"
 *   → baseCode = "*171#"
 *   → steps = [OPTION("1"), OPTION("2"), PHONE, AMOUNT]
 *
 * Placeholders: <number> or <id> → PHONE step, <amount> → AMOUNT step.
 * Plain numbers → OPTION step.
 *
 * @return Pair of (baseCode, steps), or null if the template is invalid.
 */
fun parseUssdTemplate(template: String): Pair<String, List<UssdStep>>? {
    val cleaned = template.trim().removeSuffix("#")
    // Split on '*', e.g. "*171*1*2*<number>" → ["", "171", "1", "2", "<number>"]
    val parts = cleaned.split("*").filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null

    val baseCode = "*${parts[0]}#"
    val steps = mutableListOf<UssdStep>()

    for (i in 1 until parts.size) {
        val part = parts[i].trim().lowercase()
        when {
            part == "<number>" || part == "<id>" -> steps.add(UssdStep(UssdStepType.PHONE))
            part == "<amount>" -> steps.add(UssdStep(UssdStepType.AMOUNT))
            part == "<reference>" || part == "<ref>" -> steps.add(UssdStep(UssdStepType.REFERENCE))
            else -> steps.add(UssdStep(UssdStepType.OPTION, parts[i].trim()))
        }
    }

    return Pair(baseCode, steps)
}
