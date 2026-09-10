package com.momo.swift.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [TransactionLogEntry].
 */
@Dao
interface TransactionLogDao {

    @Insert
    suspend fun insert(entry: TransactionLogEntry): Long

    @Query("UPDATE transaction_log SET status = :status, responseText = :responseText WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TransactionStatus, responseText: String = "")

    @Query("SELECT * FROM transaction_log ORDER BY timestamp DESC LIMIT 500")
    fun getAllFlow(): Flow<List<TransactionLogEntry>>

    @Query("SELECT DISTINCT phone FROM transaction_log WHERE phone != '' ORDER BY timestamp DESC LIMIT 100")
    fun getRecentPhoneNumbersFlow(): Flow<List<String>>

    @Query("SELECT * FROM transaction_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 2): Flow<List<TransactionLogEntry>>

    @Query("SELECT * FROM transaction_log ORDER BY timestamp DESC")
    suspend fun getAll(): List<TransactionLogEntry>

    @androidx.room.Update
    suspend fun update(entry: TransactionLogEntry)

    @Query("DELETE FROM transaction_log WHERE id NOT IN (SELECT id FROM (SELECT id FROM transaction_log ORDER BY timestamp DESC LIMIT 500))")
    suspend fun pruneOldLogs()

    @Query("DELETE FROM transaction_log")
    suspend fun clearAll()
}
