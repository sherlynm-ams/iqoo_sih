package com.crosscheck.app.data

import kotlinx.coroutines.flow.Flow

/** Payment store. Interface so the ingestor/reconciler can be unit-tested with an in-memory fake. */
interface PaymentRepository {
    suspend fun insert(payment: PaymentEntity): Long
    fun observeBetween(start: Long, end: Long): Flow<List<PaymentEntity>>
    suspend fun between(start: Long, end: Long): List<PaymentEntity>
    suspend fun findByUtr(utr: String): PaymentEntity?
    suspend fun findByAmountBetween(amountPaise: Long, start: Long, end: Long): List<PaymentEntity>
    suspend fun all(): List<PaymentEntity>
}

interface ClaimRepository {
    suspend fun insert(claim: ClaimEntity): Long
    fun observeBetween(start: Long, end: Long): Flow<List<ClaimEntity>>
    fun observeNoMatchBetween(start: Long, end: Long): Flow<List<ClaimEntity>>
    suspend fun between(start: Long, end: Long): List<ClaimEntity>
    suspend fun byId(id: Long): ClaimEntity?
    suspend fun all(): List<ClaimEntity>
}

interface FlaggedVpaRepository {
    suspend fun get(payerKey: String): FlaggedVpaEntity?
    /** Returns the failure count recorded BEFORE this increment. [keyKind] is a [PayerKeyKind]. */
    suspend fun increment(payerKey: String, keyKind: String, failedAt: Long): Int
    suspend fun all(): List<FlaggedVpaEntity>
}

class RoomPaymentRepository(private val dao: PaymentDao) : PaymentRepository {
    override suspend fun insert(payment: PaymentEntity): Long = dao.insert(payment)
    override fun observeBetween(start: Long, end: Long): Flow<List<PaymentEntity>> = dao.observeBetween(start, end)
    override suspend fun between(start: Long, end: Long): List<PaymentEntity> = dao.between(start, end)
    override suspend fun findByUtr(utr: String): PaymentEntity? = dao.findByUtr(utr)
    override suspend fun findByAmountBetween(amountPaise: Long, start: Long, end: Long): List<PaymentEntity> =
        dao.findByAmountBetween(amountPaise, start, end)
    override suspend fun all(): List<PaymentEntity> = dao.all()
}

class RoomClaimRepository(private val dao: ClaimDao) : ClaimRepository {
    override suspend fun insert(claim: ClaimEntity): Long = dao.insert(claim)
    override fun observeBetween(start: Long, end: Long): Flow<List<ClaimEntity>> = dao.observeBetween(start, end)
    override fun observeNoMatchBetween(start: Long, end: Long): Flow<List<ClaimEntity>> =
        dao.observeByVerdictBetween(VerdictCode.NO_MATCH, start, end)
    override suspend fun between(start: Long, end: Long): List<ClaimEntity> = dao.between(start, end)
    override suspend fun byId(id: Long): ClaimEntity? = dao.byId(id)
    override suspend fun all(): List<ClaimEntity> = dao.all()
}

class RoomFlaggedVpaRepository(private val dao: FlaggedVpaDao) : FlaggedVpaRepository {
    override suspend fun get(payerKey: String): FlaggedVpaEntity? = dao.get(payerKey)
    override suspend fun increment(payerKey: String, keyKind: String, failedAt: Long): Int =
        dao.increment(payerKey, keyKind, failedAt)
    override suspend fun all(): List<FlaggedVpaEntity> = dao.all()
}

/** String codes persisted in [ClaimEntity.verdict]. */
object VerdictCode {
    const val MATCH = "MATCH"
    const val LIKELY_MATCH = "LIKELY_MATCH"
    const val NO_MATCH = "NO_MATCH"
}
