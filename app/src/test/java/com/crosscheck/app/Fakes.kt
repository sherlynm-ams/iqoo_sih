package com.crosscheck.app

import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.ClaimRepository
import com.crosscheck.app.data.FlaggedVpaEntity
import com.crosscheck.app.data.FlaggedVpaRepository
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentRepository
import com.crosscheck.app.data.VerdictCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory repositories for pure-logic tests (no Room, no Android). */
class FakePaymentRepository : PaymentRepository {
    private val state = MutableStateFlow<List<PaymentEntity>>(emptyList())
    private var nextId = 1L
    val rows: List<PaymentEntity> get() = state.value

    override suspend fun insert(payment: PaymentEntity): Long {
        val id = nextId++
        state.value = state.value + payment.copy(id = id)
        return id
    }

    override fun observeBetween(start: Long, end: Long): Flow<List<PaymentEntity>> =
        state.map { list -> list.filter { it.timestamp >= start && it.timestamp < end } }

    override suspend fun between(start: Long, end: Long): List<PaymentEntity> =
        rows.filter { it.timestamp >= start && it.timestamp < end }

    override suspend fun findByUtr(utr: String): PaymentEntity? = rows.firstOrNull { it.utr == utr }

    override suspend fun findByAmountBetween(amountPaise: Long, start: Long, end: Long): List<PaymentEntity> =
        rows.filter { it.amountPaise == amountPaise && it.timestamp in start..end }

    override suspend fun all(): List<PaymentEntity> = rows
}

class FakeClaimRepository : ClaimRepository {
    private val state = MutableStateFlow<List<ClaimEntity>>(emptyList())
    private var nextId = 1L
    val rows: List<ClaimEntity> get() = state.value

    override suspend fun insert(claim: ClaimEntity): Long {
        val id = nextId++
        state.value = state.value + claim.copy(id = id)
        return id
    }

    override fun observeBetween(start: Long, end: Long): Flow<List<ClaimEntity>> =
        state.map { list -> list.filter { it.createdAt >= start && it.createdAt < end } }

    override fun observeNoMatchBetween(start: Long, end: Long): Flow<List<ClaimEntity>> =
        observeBetween(start, end).map { list -> list.filter { it.verdict == VerdictCode.NO_MATCH } }

    override suspend fun between(start: Long, end: Long): List<ClaimEntity> =
        rows.filter { it.createdAt >= start && it.createdAt < end }

    override suspend fun byId(id: Long): ClaimEntity? = rows.firstOrNull { it.id == id }

    override suspend fun all(): List<ClaimEntity> = rows
}

class FakeFlaggedVpaRepository : FlaggedVpaRepository {
    val rows = LinkedHashMap<String, FlaggedVpaEntity>()

    override suspend fun get(payerKey: String): FlaggedVpaEntity? = rows[payerKey]

    override suspend fun increment(payerKey: String, keyKind: String, failedAt: Long): Int {
        val prior = rows[payerKey]?.failCount ?: 0
        rows[payerKey] = FlaggedVpaEntity(payerKey, keyKind, prior + 1, failedAt)
        return prior
    }

    override suspend fun all(): List<FlaggedVpaEntity> = rows.values.toList()
}
