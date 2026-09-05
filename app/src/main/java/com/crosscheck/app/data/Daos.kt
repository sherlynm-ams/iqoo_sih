package com.crosscheck.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {
    @Insert
    suspend fun insert(payment: PaymentEntity): Long

    /** Payments with [start] <= timestamp < [end], newest first. Used for "today". */
    @Query("SELECT * FROM payments WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp DESC")
    fun observeBetween(start: Long, end: Long): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE timestamp >= :start AND timestamp < :end ORDER BY timestamp DESC")
    suspend fun between(start: Long, end: Long): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE utr = :utr LIMIT 1")
    suspend fun findByUtr(utr: String): PaymentEntity?

    /** Same amount inside a time window (dedup and reconciliation candidates). */
    @Query(
        "SELECT * FROM payments WHERE amountPaise = :amountPaise AND timestamp >= :start AND timestamp <= :end " +
            "ORDER BY timestamp DESC",
    )
    suspend fun findByAmountBetween(amountPaise: Long, start: Long, end: Long): List<PaymentEntity>

    @Query("SELECT * FROM payments ORDER BY timestamp DESC")
    suspend fun all(): List<PaymentEntity>
}

@Dao
interface ClaimDao {
    @Insert
    suspend fun insert(claim: ClaimEntity): Long

    @Query("SELECT * FROM claims WHERE createdAt >= :start AND createdAt < :end ORDER BY createdAt DESC")
    fun observeBetween(start: Long, end: Long): Flow<List<ClaimEntity>>

    @Query("SELECT * FROM claims WHERE createdAt >= :start AND createdAt < :end ORDER BY createdAt DESC")
    suspend fun between(start: Long, end: Long): List<ClaimEntity>

    @Query(
        "SELECT * FROM claims WHERE verdict = :verdict AND createdAt >= :start AND createdAt < :end " +
            "ORDER BY createdAt DESC",
    )
    fun observeByVerdictBetween(verdict: String, start: Long, end: Long): Flow<List<ClaimEntity>>

    @Query("SELECT * FROM claims WHERE id = :id")
    suspend fun byId(id: Long): ClaimEntity?

    @Query("SELECT * FROM claims ORDER BY createdAt DESC")
    suspend fun all(): List<ClaimEntity>
}

@Dao
interface FlaggedVpaDao {
    @Query("SELECT * FROM flagged_vpas WHERE payerKey = :payerKey")
    suspend fun get(payerKey: String): FlaggedVpaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FlaggedVpaEntity)

    /** Atomically bumps failCount for [payerKey]; returns the count BEFORE this failure. */
    @Transaction
    suspend fun increment(payerKey: String, keyKind: String, failedAt: Long): Int {
        val prior = get(payerKey)?.failCount ?: 0
        upsert(FlaggedVpaEntity(payerKey = payerKey, keyKind = keyKind, failCount = prior + 1, lastFailedAt = failedAt))
        return prior
    }

    @Query("SELECT * FROM flagged_vpas ORDER BY failCount DESC")
    suspend fun all(): List<FlaggedVpaEntity>
}
