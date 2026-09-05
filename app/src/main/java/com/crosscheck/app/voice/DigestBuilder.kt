package com.crosscheck.app.voice

import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.VerdictCode

/**
 * End-of-day numbers (SPEC section 1, "how was today?"): confirmed = payments today,
 * mismatches = NoMatch claims today, flagged = sum of those claims' amounts.
 */
data class Digest(
    val confirmedCount: Int,
    val totalPaise: Long,
    val mismatchCount: Int,
    val flaggedPaise: Long,
)

object DigestBuilder {
    /** [claims] may be every claim of the day; only NoMatch rows count. */
    fun build(payments: List<PaymentEntity>, claims: List<ClaimEntity>): Digest {
        val noMatch = claims.filter { it.verdict == VerdictCode.NO_MATCH }
        return Digest(
            confirmedCount = payments.size,
            totalPaise = payments.sumOf { it.amountPaise },
            mismatchCount = noMatch.size,
            flaggedPaise = noMatch.sumOf { it.amountPaise },
        )
    }
}
