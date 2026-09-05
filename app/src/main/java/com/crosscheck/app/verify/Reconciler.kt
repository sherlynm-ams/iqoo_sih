package com.crosscheck.app.verify

import com.crosscheck.app.data.PaymentEntity
import kotlin.math.abs

/**
 * Pure tiering logic (SPEC section 1). Given a claim and candidate stored payments:
 *  - amount AND UTR exact                                        -> Match
 *  - amount + partial UTR (>= 8 overlapping digits)              -> LikelyMatch(WRONG_UTR)
 *  - amount, UTR not comparable, time within tolerance/unknown   -> LikelyMatch(WRONG_UTR)
 *  - amount, UTR not comparable, time outside tolerance          -> LikelyMatch(TIMESTAMP_MISMATCH)
 *  - amount, UTR not comparable, time beyond [hardSkewMillis]    -> NoMatch(TIMESTAMP_MISMATCH)
 *  - amount, UTR present on both and different                   -> NoMatch(WRONG_UTR)
 *  - UTR exact/partial but amount differs                        -> NoMatch(WRONG_AMOUNT)
 *  - nothing relates                                             -> NoMatch(NOTHING_RECEIVED)
 *  - claim status PENDING (checked first)                        -> NoMatch(PENDING)
 * The best verdict across candidates wins; NoMatch reasons are ranked by specificity.
 */
class Reconciler(
    val toleranceMillis: Long = DEFAULT_TOLERANCE_MILLIS,
    val hardSkewMillis: Long = DEFAULT_HARD_SKEW_MILLIS,
) {
    data class Result(val verdict: Verdict, val matchedPayment: PaymentEntity?)

    fun reconcile(claim: Claim, candidates: List<PaymentEntity>): Result {
        // A "pending" screen is a known scam pattern: never a Match, whatever the amount/UTR say.
        if (claim.status == ClaimStatus.PENDING) return Result(Verdict.NoMatch(Reason.PENDING), null)
        if (candidates.isEmpty()) return Result(Verdict.NoMatch(Reason.NOTHING_RECEIVED), null)
        val claimUtr = Utr.normalise(claim.utr)
        var best: Result? = null
        var bestScore = Int.MIN_VALUE
        var bestDelta = Long.MAX_VALUE
        for (payment in candidates) {
            val verdict = evaluate(claim, claimUtr, payment)
            val score = score(verdict)
            val delta = timeDelta(claim, payment)
            if (score > bestScore || (score == bestScore && delta < bestDelta)) {
                bestScore = score
                bestDelta = delta
                best = Result(verdict, payment.takeIf { verdict !is Verdict.NoMatch || verdict.reason != Reason.NOTHING_RECEIVED })
            }
        }
        return best ?: Result(Verdict.NoMatch(Reason.NOTHING_RECEIVED), null)
    }

    private fun evaluate(claim: Claim, claimUtr: String?, payment: PaymentEntity): Verdict {
        val amountOk = payment.amountPaise == claim.amountPaise
        val paymentUtr = Utr.normalise(payment.utr)
        val utr = when {
            claimUtr == null || paymentUtr == null -> UtrRelation.UNKNOWN
            claimUtr == paymentUtr -> UtrRelation.EXACT
            Utr.isPartialMatch(claimUtr, paymentUtr) -> UtrRelation.PARTIAL
            else -> UtrRelation.DIFFERENT
        }
        val delta = claim.claimedTimestamp?.let { abs(it - payment.timestamp) }
        val time = when {
            delta == null -> TimeRelation.UNKNOWN
            delta <= toleranceMillis -> TimeRelation.WITHIN
            delta <= hardSkewMillis -> TimeRelation.SKEWED
            else -> TimeRelation.FAR
        }

        return if (amountOk) {
            when (utr) {
                UtrRelation.EXACT -> Verdict.Match
                UtrRelation.PARTIAL -> Verdict.LikelyMatch(Reason.WRONG_UTR)
                UtrRelation.UNKNOWN -> when (time) {
                    TimeRelation.WITHIN, TimeRelation.UNKNOWN -> Verdict.LikelyMatch(Reason.WRONG_UTR)
                    TimeRelation.SKEWED -> Verdict.LikelyMatch(Reason.TIMESTAMP_MISMATCH)
                    TimeRelation.FAR -> Verdict.NoMatch(Reason.TIMESTAMP_MISMATCH)
                }
                UtrRelation.DIFFERENT -> Verdict.NoMatch(Reason.WRONG_UTR)
            }
        } else {
            when (utr) {
                UtrRelation.EXACT, UtrRelation.PARTIAL -> Verdict.NoMatch(Reason.WRONG_AMOUNT)
                UtrRelation.UNKNOWN, UtrRelation.DIFFERENT -> Verdict.NoMatch(Reason.NOTHING_RECEIVED)
            }
        }
    }

    /** Higher is better. Match > LikelyMatch > NoMatch; NoMatch reasons ordered by specificity. */
    private fun score(verdict: Verdict): Int = when (verdict) {
        Verdict.Match -> 100
        is Verdict.LikelyMatch -> when (verdict.reason) {
            Reason.WRONG_UTR -> 80
            else -> 70
        }
        is Verdict.NoMatch -> when (verdict.reason) {
            Reason.PENDING -> 50
            Reason.WRONG_AMOUNT -> 40
            Reason.WRONG_UTR -> 30
            Reason.TIMESTAMP_MISMATCH -> 20
            Reason.NOTHING_RECEIVED -> 10
        }
    }

    /** Tie-break inside a tier: the candidate closest in time wins; unknown claim time ranks last. */
    private fun timeDelta(claim: Claim, payment: PaymentEntity): Long {
        val ts = claim.claimedTimestamp ?: return Long.MAX_VALUE - 1
        return abs(ts - payment.timestamp)
    }

    private enum class UtrRelation { EXACT, PARTIAL, UNKNOWN, DIFFERENT }
    private enum class TimeRelation { WITHIN, SKEWED, FAR, UNKNOWN }

    companion object {
        const val DEFAULT_TOLERANCE_MILLIS: Long = 10 * 60 * 1000
        const val DEFAULT_HARD_SKEW_MILLIS: Long = 24 * 60 * 60 * 1000
    }
}
