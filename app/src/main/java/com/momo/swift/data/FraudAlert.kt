package com.momo.swift.data

import kotlinx.serialization.Serializable

/**
 * Represents a broadcasted fraud alert for a blacklisted scammer phone number.
 */
@Serializable
data class FraudAlert(
    val id: String = "",
    val phoneNumber: String = "",
    val normalizedNumber: String = "",
    val fraudType: String = "Scam / Fraud",
    val description: String = "",
    val reporterEmail: String = "",
    val reporterPhone: String = "",
    val reportedAt: Long = 0L,
    val broadcastLevel: String = "CRITICAL",
    val isVerified: Boolean = true
) {
    /**
     * Checks if a given raw or formatted search query matches this fraud alert.
     */
    fun matchesQuery(query: String): Boolean {
        if (query.isBlank()) return false
        val cleanQuery = query.replace(Regex("[^0-9]"), "")
        if (cleanQuery.isEmpty()) return false
        
        val cleanNormalized = normalizedNumber.replace(Regex("[^0-9]"), "")
        val cleanRaw = phoneNumber.replace(Regex("[^0-9]"), "")

        return cleanNormalized.contains(cleanQuery) || 
               cleanRaw.contains(cleanQuery) || 
               cleanQuery.contains(cleanNormalized) ||
               (cleanQuery.length >= 7 && (cleanNormalized.endsWith(cleanQuery) || cleanQuery.endsWith(cleanNormalized)))
    }
}
