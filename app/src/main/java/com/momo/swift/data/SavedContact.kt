package com.momo.swift.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Represents a permanently saved contact (phone number) that the user
 * has transacted with. Survives transaction log pruning.
 *
 * @property phone The phone number (primary key — guaranteed unique).
 * @property nickname Optional friendly name set by the user.
 * @property lastUsed Timestamp of the most recent transaction with this number.
 * @property useCount How many times this number has been used in a transaction.
 */
@Entity(tableName = "saved_contacts")
data class SavedContact(
    @PrimaryKey
    val phone: String,
    val nickname: String = "",
    val lastUsed: Long = System.currentTimeMillis(),
    val useCount: Int = 1
)

@Dao
interface SavedContactDao {

    /**
     * Insert a new contact or update lastUsed + useCount if already present.
     */
    @Query("""
        INSERT INTO saved_contacts (phone, nickname, lastUsed, useCount)
        VALUES (:phone, '', :lastUsed, 1)
        ON CONFLICT(phone) DO UPDATE SET
            lastUsed = excluded.lastUsed,
            useCount = saved_contacts.useCount + 1
    """)
    suspend fun upsert(phone: String, lastUsed: Long = System.currentTimeMillis())

    /**
     * Update the nickname of a contact.
     */
    @Query("UPDATE saved_contacts SET nickname = :nickname WHERE phone = :phone")
    suspend fun updateNickname(phone: String, nickname: String)

    /**
     * Delete a specific contact.
     */
    @Query("DELETE FROM saved_contacts WHERE phone = :phone")
    suspend fun delete(phone: String)

    /**
     * Clear all saved contacts.
     */
    @Query("DELETE FROM saved_contacts")
    suspend fun clearAll()

    /**
     * Get all contacts ordered by most recently used.
     */
    @Query("SELECT * FROM saved_contacts ORDER BY lastUsed DESC")
    fun getAllFlow(): Flow<List<SavedContact>>

    /**
     * Get phone numbers matching a prefix, ordered by most used then most recent.
     * Used for the auto-suggest dropdown.
     */
    @Query("""
        SELECT phone, nickname FROM saved_contacts
        WHERE phone LIKE :prefix || '%'
        ORDER BY useCount DESC, lastUsed DESC
        LIMIT 5
    """)
    fun searchFlow(prefix: String): Flow<List<SavedContactSuggestion>>
}

/**
 * Lightweight projection for the auto-suggest dropdown.
 */
data class SavedContactSuggestion(
    val phone: String,
    val nickname: String
) {
    /** Display label: "Kwame (0244...)" if nickname set, else just the number. */
    val displayLabel: String get() = if (nickname.isBlank()) phone else "$nickname ($phone)"
}
