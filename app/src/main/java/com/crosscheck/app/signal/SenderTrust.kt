package com.crosscheck.app.signal

/**
 * Sender-ID trust is a *signal, not a gate* (SPEC section 4): a credit SMS with a full 12-digit
 * reference is accepted from any sender; this decides the `senderTrusted` flag and the one hard
 * rule the parser applies to untrusted senders (they must carry a 12-digit UTR).
 *
 * Real bank alerts arrive from TRAI/DLT headers: 2-char operator/circle prefix, `-`, the 6-char
 * registered header, and since May 2025 an optional category suffix `-S` (service), `-T`
 * (transactional), `-P` (promotional), `-G` (government). Examples: `AD-SBIINB-S`, `JD-AxisBk-S`,
 * `VM-KOTAKB-T`. Case varies, so everything is compared upper-cased.
 * 10-digit / `+91` numbers and look-alikes such as `SBI-ALERT` or `HDFC-PYMT` are never trusted.
 */
object SenderTrust {

    /** Registered 6-char headers of the institutions in docs/research/bank-sms-templates.md section 1. */
    val KNOWN_HEADERS: Set<String> = setOf(
        "SBIUPI", "SBIINB", "SBIPSG", "SBIBNK",
        "HDFCBK", "ICICIB", "ICICIT", "AXISBK", "KOTAKB", "PNBSMS",
        "BOBTXN", "BOBSMS", "CANBNK", "UNIONB", "INDUSB", "YESBNK", "FEDBNK",
        "IDFCFB", "IDFCBK", "AUBANK", "PAYTMB", "AIRBNK", "BHIMAP",
    )

    const val SUFFIX_SERVICE = 'S'
    const val SUFFIX_TRANSACTIONAL = 'T'
    const val SUFFIX_PROMO = 'P'
    const val SUFFIX_GOVERNMENT = 'G'

    /** A parsed DLT header: [header] is the upper-cased 6-char registered header, [suffix] the category letter. */
    data class DltHeader(val prefix: String, val header: String, val suffix: Char?)

    private val DLT = Regex("^([A-Z]{2})-([A-Z0-9]{6})(?:-([STPG]))?$", RegexOption.IGNORE_CASE)

    fun parseHeader(senderId: String?): DltHeader? {
        val m = DLT.matchEntire(senderId?.trim() ?: return null) ?: return null
        return DltHeader(
            prefix = m.groupValues[1].uppercase(),
            header = m.groupValues[2].uppercase(),
            suffix = m.groupValues[3].takeIf { it.isNotEmpty() }?.uppercase()?.single(),
        )
    }

    /** True only for a DLT-shaped sender whose header is a known bank/UPI header and is not promotional. */
    fun isTrustedSmsSender(senderId: String?): Boolean {
        val h = parseHeader(senderId) ?: return false
        return h.suffix != SUFFIX_PROMO && h.header in KNOWN_HEADERS
    }

    /** `-P` suffix = promotional route; such messages are never transaction alerts and are rejected outright. */
    fun isPromoHeader(senderId: String?): Boolean = parseHeader(senderId)?.suffix == SUFFIX_PROMO
}
