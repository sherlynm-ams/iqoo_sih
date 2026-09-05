package com.crosscheck.app.signal

/**
 * Sender-ID trust is a *signal, not a gate* (SPEC section 4): a credit SMS is accepted from any
 * sender if the body matches a template; this only decides the `senderTrusted` flag.
 *
 * Real bank alerts arrive from DLT headers such as `VK-SBIINB`, `AD-HDFCBK-S`, `JD-ICICIB`.
 * Numeric senders (what the emulator console can send) are never trusted.
 */
object SenderTrust {
    private val BANK_TOKENS = listOf(
        "SBI", "HDFC", "ICICI", "AXIS", "KOTAK", "PNB", "BOB", "BARODA", "CANARA", "CANBNK",
        "INDUS", "YESB", "YESBNK", "FEDBNK", "FEDERAL", "PAYTM", "PYTM", "PPBL", "IDFC", "IDBI",
        "UNION", "UCOBNK", "CENTBK", "BOIIND", "IOBCHN", "KARB", "KVB", "CUB", "TMB", "SIB",
        "RBL", "AUBANK", "BANDHN", "JIOPAY", "AIRTEL", "GPAY", "PHONPE", "BHIM",
    )

    /** DLT header shape: 2-letter prefix, 6 alphanumerics, optional 1-letter category suffix. */
    private val DLT_HEADER = Regex("^[A-Z]{2}-[A-Z0-9]{6}(?:-[A-Z])?$")

    fun isTrustedSmsSender(senderId: String?): Boolean {
        if (senderId.isNullOrBlank()) return false
        val id = senderId.trim().uppercase()
        if (id.all { it.isDigit() || it == '+' || it == ' ' }) return false
        val core = id.split('-').maxByOrNull { it.length } ?: id
        return DLT_HEADER.matches(id) || BANK_TOKENS.any { core.contains(it) }
    }
}
