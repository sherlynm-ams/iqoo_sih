package com.crosscheck.app.signal

/**
 * A bank credit alert reduced to the fields Mode 1 needs.
 * [payer] is the counter-party name or VPA as printed in the alert (null when the template has none).
 * [utr] is digits only (null when the alert carries no reference).
 */
data class ParsedCredit(
    val amountPaise: Long,
    val payer: String?,
    val utr: String?,
    val senderId: String?,
    val senderTrusted: Boolean,
    val raw: String,
)
