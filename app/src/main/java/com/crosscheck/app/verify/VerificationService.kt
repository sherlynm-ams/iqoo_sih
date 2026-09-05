package com.crosscheck.app.verify

import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.ClaimRepository
import com.crosscheck.app.data.FlaggedVpaRepository
import com.crosscheck.app.data.PayerKeyKind
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentRepository

/**
 * Mode 2 back half: reconcile a [Claim] against local payments, write the verification receipt
 * (`claims` row), and bump the repeat-offender counter on NoMatch. Returns the verdict plus the
 * number of failures the payer key had BEFORE this verification (for the spoken prefix).
 */
class VerificationService(
    private val payments: PaymentRepository,
    private val claims: ClaimRepository,
    private val flaggedVpas: FlaggedVpaRepository,
    private val reconciler: Reconciler = Reconciler(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val candidateWindowMillis: Long = DEFAULT_CANDIDATE_WINDOW_MILLIS,
) {
    /** The repeat-offender key derived from a claim; [kind] is a [PayerKeyKind]. */
    data class PayerKey(val key: String, val kind: String)

    data class Outcome(
        val claimId: Long,
        val verdict: Verdict,
        val matchedPayment: PaymentEntity?,
        val payerKey: PayerKey?,
        val priorFailures: Int,
        val receipt: ClaimEntity,
    )

    suspend fun verify(claim: Claim, extractorBackend: String): Outcome {
        val now = clock()
        val centre = claim.claimedTimestamp ?: now
        val candidates = LinkedHashMap<Long, PaymentEntity>()
        payments.between(centre - candidateWindowMillis, centre + candidateWindowMillis + 1)
            .forEach { candidates[it.id] = it }
        Utr.normalise(claim.utr)?.let { utr -> payments.findByUtr(utr)?.let { candidates[it.id] = it } }

        val result = reconciler.reconcile(claim, candidates.values.toList())
        val payerKey = payerKeyOf(claim)
        val prior = payerKey?.let { flaggedVpas.get(it.key)?.failCount } ?: 0
        if (result.verdict is Verdict.NoMatch && payerKey != null) {
            flaggedVpas.increment(payerKey.key, payerKey.kind, now)
        }

        val receipt = ClaimEntity(
            amountPaise = claim.amountPaise,
            upiId = normaliseVpa(claim.upiId),
            utr = Utr.normalise(claim.utr),
            claimedTimestamp = claim.claimedTimestamp,
            verdict = result.verdict.code,
            reason = result.verdict.reasonOrNull?.name,
            createdAt = now,
            extractorBackend = extractorBackend,
            payerName = claim.payerName?.trim()?.takeIf { it.isNotEmpty() },
            payerBankMask = claim.payerBankMask?.trim()?.takeIf { it.isNotEmpty() },
            payerKey = payerKey?.key,
            keyKind = payerKey?.kind,
        )
        val id = claims.insert(receipt)
        return Outcome(id, result.verdict, result.matchedPayment, payerKey, prior, receipt.copy(id = id))
    }

    companion object {
        const val DEFAULT_CANDIDATE_WINDOW_MILLIS: Long = 24 * 60 * 60 * 1000

        /**
         * First available of: payer VPA (only when the extractor marked a handle as the payer's),
         * normalised payer name, bank mask. Null means the claim cannot be attributed and is not flagged.
         */
        fun payerKeyOf(claim: Claim): PayerKey? {
            normaliseVpa(claim.payerVpa)?.let { return PayerKey(it, PayerKeyKind.VPA) }
            normaliseName(claim.payerName)?.let { return PayerKey(it, PayerKeyKind.NAME) }
            normaliseBankMask(claim.payerBankMask)?.let { return PayerKey(it, PayerKeyKind.BANK_MASK) }
            return null
        }

        fun normaliseVpa(raw: String?): String? =
            raw?.trim()?.lowercase()?.takeIf { it.contains('@') }

        /** Trim, uppercase, collapse internal whitespace. */
        fun normaliseName(raw: String?): String? =
            raw?.trim()?.uppercase()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

        fun normaliseBankMask(raw: String?): String? =
            raw?.trim()?.uppercase()?.replace(Regex("\\s+"), "")?.takeIf { it.isNotEmpty() }
    }
}
