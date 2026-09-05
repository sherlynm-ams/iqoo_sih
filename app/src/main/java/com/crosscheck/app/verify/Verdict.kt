package com.crosscheck.app.verify

import com.crosscheck.app.data.VerdictCode

/** Which app's success screen the claim was read from. */
enum class ClaimApp { GPAY, PHONEPE, UNKNOWN }

/** Transaction status as shown on the customer's screen. PENDING always reconciles to NoMatch. */
enum class ClaimStatus { SUCCESS, PENDING, FAILED, UNKNOWN }

/**
 * What the customer's screen claims (SPEC section 1). Money in paise; [utr] raw as read
 * (normalised by the reconciler).
 *
 * The payer's own VPA is usually NOT on the payer's screen: [upiId] is the *payee* handle if seen,
 * and the payer identity is [payerName] + [payerBankMask] (e.g. "SBI-XX4321"). [payerVpa] is set
 * only when the screen explicitly labels a handle as the payer's. [otherIds] holds app-internal ids
 * (PhonePe `T...`, GPay `CICAg...`) which are never treated as a UTR.
 */
data class Claim(
    val amountPaise: Long,
    val upiId: String?,
    val utr: String?,
    val claimedTimestamp: Long?,
    val payerName: String? = null,
    val payerBankMask: String? = null,
    val payerVpa: String? = null,
    val app: ClaimApp = ClaimApp.UNKNOWN,
    val status: ClaimStatus = ClaimStatus.UNKNOWN,
    val otherIds: List<String> = emptyList(),
)

/** The specific discrepancy behind a LikelyMatch / NoMatch (SPEC section 1). */
enum class Reason { WRONG_AMOUNT, WRONG_UTR, NOTHING_RECEIVED, TIMESTAMP_MISMATCH, PENDING }

sealed class Verdict {
    data object Match : Verdict()
    data class LikelyMatch(val reason: Reason) : Verdict()
    data class NoMatch(val reason: Reason) : Verdict()

    val code: String
        get() = when (this) {
            Match -> VerdictCode.MATCH
            is LikelyMatch -> VerdictCode.LIKELY_MATCH
            is NoMatch -> VerdictCode.NO_MATCH
        }

    val reasonOrNull: Reason?
        get() = when (this) {
            Match -> null
            is LikelyMatch -> reason
            is NoMatch -> reason
        }
}

/** UTR helpers shared by the parser tests and the reconciler. Digits only; 8+ overlapping digits = partial. */
object Utr {
    const val MIN_PARTIAL_OVERLAP = 8

    fun normalise(raw: String?): String? = raw?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }

    /** True when one normalised UTR is a prefix or suffix of the other with at least 8 digits overlapping. */
    fun isPartialMatch(a: String, b: String): Boolean {
        if (a == b) return false
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length < MIN_PARTIAL_OVERLAP) return false
        return long.startsWith(short) || long.endsWith(short)
    }
}
