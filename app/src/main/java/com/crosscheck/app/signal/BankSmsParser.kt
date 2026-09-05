package com.crosscheck.app.signal

import com.crosscheck.app.data.parseRupeesToPaise

/**
 * Rules/regex parser for bank and UPI-app credit alerts. Pure Kotlin, no Android types.
 *
 * Credit-only: debit alerts, OTPs, promos, balance-only alerts and collect requests return null.
 * Language-neutral: amounts and references are numeric; the English keywords below are the
 * vocabulary of Indian bank SMS templates, not of the app UI.
 */
object BankSmsParser {

    fun parse(
        body: String,
        senderId: String? = null,
        senderTrusted: Boolean = SenderTrust.isTrustedSmsSender(senderId),
    ): ParsedCredit? {
        val text = normalise(body)
        if (text.isBlank()) return null
        if (REJECT_OTP.containsMatchIn(text)) return null
        if (REJECT_COLLECT.containsMatchIn(text)) return null
        if (REJECT_FAILED.containsMatchIn(text)) return null
        if (REJECT_DEBIT.containsMatchIn(text)) return null
        if (REJECT_PROMO.containsMatchIn(text)) return null

        val creditPositions = CREDIT_KEYWORD.findAll(text).map { it.range.first }.toList()
        if (creditPositions.isEmpty()) return null

        val amountPaise = pickAmount(text, creditPositions) ?: return null
        val utr = extractUtr(text)
        val payer = extractPayer(text)

        return ParsedCredit(
            amountPaise = amountPaise,
            payer = payer,
            utr = utr,
            senderId = senderId,
            senderTrusted = senderTrusted,
            raw = body,
        )
    }

    // ---- amount -------------------------------------------------------------------------

    private fun pickAmount(text: String, creditPositions: List<Int>): Long? {
        val candidates = AMOUNT.findAll(text).mapNotNull { m ->
            val prefix = text.substring(maxOf(0, m.range.first - BALANCE_LOOKBEHIND), m.range.first)
            if (BALANCE_WORD.containsMatchIn(prefix)) return@mapNotNull null
            val paise = parseRupeesToPaise(m.groupValues[1]) ?: return@mapNotNull null
            if (paise <= 0L) return@mapNotNull null
            m.range.first to paise
        }.toList()
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { (pos, _) -> creditPositions.minOf { kotlin.math.abs(it - pos) } }?.second
    }

    // ---- reference ----------------------------------------------------------------------

    private fun extractUtr(text: String): String? {
        UTR_SLASH.find(text)?.let { return it.groupValues[1] }
        UTR_KEYWORD.find(text)?.let { return it.groupValues[1] }
        UTR_BARE_12.find(text)?.let { return it.value }
        return null
    }

    // ---- payer --------------------------------------------------------------------------

    private fun extractPayer(text: String): String? {
        for (pattern in PAYER_PATTERNS) {
            val m = pattern.find(text) ?: continue
            val cleaned = cleanName(m.groupValues[1]) ?: continue
            return cleaned
        }
        return null
    }

    private fun cleanName(raw: String): String? {
        var s = raw.trim().trimEnd('.', ',', ';', ':', '-', '/').trim()
        s = s.replace(Regex("\\s+"), " ")
        // A trailing run of 6+ digits is a reference number that followed the name, not part of it.
        s = s.split(' ').takeWhile { !LONG_DIGIT_RUN.matches(it) }.joinToString(" ")
        if (s.isEmpty()) return null
        if (s.length > MAX_NAME_LENGTH) s = s.substring(0, MAX_NAME_LENGTH).trim()
        if (s.contains('@')) s = s.lowercase()
        if (NAME_STOPWORDS.matches(s)) return null
        return s
    }

    private fun normalise(body: String): String =
        body.replace('\u00A0', ' ').replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()

    // ---- vocabulary ---------------------------------------------------------------------

    private const val MAX_NAME_LENGTH = 48
    private const val BALANCE_LOOKBEHIND = 24

    private val REJECT_OTP = Regex(
        "\\bOTP\\b|one[\\s-]?time\\s+pass(?:word|code)|\\bpasscode\\b|verification\\s+code|\\bsecret\\s+code\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_COLLECT = Regex(
        "\\brequest(?:ed|ing|s)?\\b|\\bcollect\\s+request\\b|\\bapprove\\b|\\bdecline\\b|\\bpay\\s+now\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_FAILED = Regex(
        "\\bfailed\\b|\\bdeclined\\b|\\bunsuccessful\\b|\\bcould\\s+not\\s+be\\s+processed\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_DEBIT = Regex(
        "\\bdebited\\b|\\bdebit\\b|\\bwithdrawn\\b|\\bwithdrawal\\b|\\bspent\\b|\\bpaid\\s+to\\b|\\bsent\\s+to\\b|" +
            "\\btransferred\\s+to\\b|\\byou\\s+(?:paid|sent)\\b|\\bpayment\\s+of\\s+(?:Rs\\.?|INR|₹)?\\s*[\\d,.]+\\s+to\\b|" +
            "UPI/DR|/DR/|\\bpurchase\\b|\\bcharged\\b|\\bdeducted\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_PROMO = Regex(
        "\\boffers?\\b|T&C|\\bapply\\s+now\\b|\\bclick\\b|https?://|\\bwww\\.|\\bcoupon\\b|\\bdiscount\\b|" +
            "\\bupgrade\\b|\\bpre-?approved\\b|\\bloan\\b|\\bEMI\\b|\\binsurance\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT_KEYWORD = Regex(
        "\\bcredited\\b|\\breceived\\b|\\bdeposited\\b|\\bpaid\\s+you\\b|UPI/CR\\b|/CR/",
        RegexOption.IGNORE_CASE,
    )

    /** "Rs.500.00", "Rs 500", "INR 1,50,000.00", "₹500", "Rupees 20". Group 1 = numeric text. */
    private val AMOUNT = Regex(
        "(?:Rs\\.?|INR|₹|Rupees)\\s*:?\\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE,
    )
    private val BALANCE_WORD = Regex("\\b(?:bal|balance|avl|avail|available|limit|total)\\b\\.?:?-?\\s*(?:amt|bal|balance)?", RegexOption.IGNORE_CASE)

    /** "UPI/CR/426112345678/...", "UPI/P2M/426112345678/...", "UPI/426112345678/..." */
    private val UTR_SLASH = Regex("\\bUPI\\s*/\\s*(?:[A-Z0-9]{2,4}\\s*/\\s*)?([0-9]{10,18})(?![0-9])", RegexOption.IGNORE_CASE)

    /** "UPI Ref No 4261...", "Ref no: 4261...", "RRN 4261...", "UTR 4261...", "UPI:4261...", "Txn ID 4261...". */
    private val UTR_KEYWORD = Regex(
        "\\b(?:UPI\\s*Ref(?:erence)?\\.?\\s*(?:No\\.?|Number|ID)?|Ref(?:erence)?\\.?\\s*(?:No\\.?|Number|ID)?|RRN|UTR|" +
            "Txn\\s*(?:ID|No\\.?|Number)?|Transaction\\s*(?:ID|No\\.?|Number|Ref(?:erence)?)?|UPI\\s*(?:ID|Txn\\s*ID)?|IMPS)" +
            "\\s*[:#.\\-]?\\s*([0-9]{10,18})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val UTR_BARE_12 = Regex("(?<![0-9])[0-9]{12}(?![0-9])")

    // One name/VPA token: letters/digits in any script with @ _ ' -; dots only *between* word chars so a
    // sentence-ending "." is never swallowed ("MURUGAN." -> "MURUGAN", "murugan.s@okaxis" stays whole).
    private const val TOKEN = "[\\p{L}\\p{N}@_'\\-]+(?:\\.[\\p{L}\\p{N}@_'\\-]+)*"

    // Name: one or more tokens separated by spaces (lazy); ends at a delimiter or keyword.
    private const val NAME = "($TOKEN(?:\\s+$TOKEN)*?)"
    private const val NAME_END =
        "(?=\\s*(?:\\.\\s|\\.$|,|\\(|\\)|;|:|\\s-|\\s+(?:on|Ref|UPI|via|in|to|at|for|towards|thru|through|with|and|dated|dt|" +
            "Avl|Avail|Available|Bal|Total|Txn|Transaction|RRN|UTR|Info|Not|Never|Call|If|Your|Dial|SMS)\\b|$))"

    private val PAYER_PATTERNS = listOf(
        Regex("\\bfrom\\s+(?:VPA\\s+|a/c\\s+linked\\s+to\\s+VPA\\s+|a/c\\s+)?$NAME$NAME_END", RegexOption.IGNORE_CASE),
        Regex("\\bby\\s+(?:a/c\\s+linked\\s+to\\s+)?VPA\\s+$NAME$NAME_END", RegexOption.IGNORE_CASE),
        Regex("$NAME\\s+paid\\s+you\\b", RegexOption.IGNORE_CASE),
        Regex(
            "\\bUPI/(?:[A-Z0-9]{2,4}/)?[0-9]{10,18}/$NAME(?=\\s*(?:/|\\.\\s|\\.$|,|;|\\s-|$))",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "\\bby\\s+(?!UPI\\b|Ref\\b|Rs\\b|INR\\b|a/c\\b|account\\b|transfer\\b|NEFT\\b|IMPS\\b|cash\\b|cheque\\b|clearing\\b)$NAME$NAME_END",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val LONG_DIGIT_RUN = Regex("[0-9]{6,}")

    private val NAME_STOPWORDS = Regex(
        "(?i)^(?:upi|ref|rs|inr|a/c|account|transfer|neft|imps|vpa|you|your|bank|cash|cheque|the)$",
    )
}
