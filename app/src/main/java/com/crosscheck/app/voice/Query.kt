package com.crosscheck.app.voice

/** What the seller asked, extracted from an STT transcript by [QueryParser]. */
sealed class Query {
    /**
     * "did [name] pay [amount] today?" — either part may be missing: a bare name asks what that
     * payer sent today, a bare amount asks whether anyone sent that amount.
     */
    data class DidPay(val name: String?, val amountPaise: Long?) : Query()

    /** "how was today?" — the end-of-day digest. */
    data object DigestToday : Query()

    data object Unknown : Query()
}
