package com.crosscheck.app.signal

import com.crosscheck.app.data.parseRupeesToPaise

/**
 * Rules/regex parser for bank and UPI-app credit alerts. Pure Kotlin, no Android types.
 * Vocabulary and check ordering follow docs/research/bank-sms-templates.md section 2; every entry of
 * docs/research/bank-sms-corpus.json is asserted by BankSmsCorpusTest.
 *
 * Credit-only: debit alerts, OTPs, promos, balance-only alerts, collect requests, failed/reversed
 * transactions and card-bill credits return null. Language-neutral: amounts and references are
 * numeric; the English keywords below are the vocabulary of Indian bank SMS templates, not the UI.
 *
 * Check order (first hit returns null):
 *  0. DLT promo header (`-P` suffix) - never a transaction alert.
 *  1. OTP, collect/request, failed/reversed/refund, promo, card-bill wording.
 *  2. Direction: any debit verb (with the "paid/sent [to] you" exceptions) rejects; a credit
 *     keyword is required. Stricter than the research note's "debit before credit" because a
 *     verifier must prefer a missed announcement over a false one.
 *  3. Amount required (nearest non-balance figure to a credit keyword); UTR/payer optional.
 *  4. Scam guard: an untrusted sender (10-digit, +91, look-alike header, unknown) must carry a
 *     full 12-digit reference; trusted headers and app notifications may omit it (Canara, Paytm PB).
 *
 * Input may contain newlines (notification title + "\n" + body); a newline ends a payer name.
 */
object BankSmsParser {

    fun parse(
        body: String,
        senderId: String? = null,
        senderTrusted: Boolean = SenderTrust.isTrustedSmsSender(senderId),
    ): ParsedCredit? {
        if (SenderTrust.isPromoHeader(senderId)) return null
        val text = normalise(body)
        if (text.isBlank()) return null
        if (REJECT_OTP.containsMatchIn(text)) return null
        if (REJECT_COLLECT.containsMatchIn(text)) return null
        if (REJECT_FAILED.containsMatchIn(text)) return null
        if (REJECT_PROMO.containsMatchIn(text)) return null
        if (REJECT_CARD.containsMatchIn(text)) return null
        if (DEBIT_VERB.containsMatchIn(text)) return null

        val creditPositions = CREDIT_KEYWORD.findAll(text).map { it.range.first }.toList()
        if (creditPositions.isEmpty()) return null

        val amountPaise = pickAmount(text, creditPositions) ?: return null
        val utr = extractUtr(text)
        val payer = extractPayer(text)

        if (!senderTrusted && (utr == null || utr.length != UTR_LENGTH)) return null

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

    /** Collapses horizontal whitespace but keeps single newlines: they delimit notification title/body. */
    private fun normalise(body: String): String =
        body.replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

    // ---- vocabulary ---------------------------------------------------------------------

    private const val MAX_NAME_LENGTH = 48
    private const val BALANCE_LOOKBEHIND = 24
    private const val UTR_LENGTH = 12

    private val REJECT_OTP = Regex(
        "\\bOTP\\b|one[\\s-]?time\\s+pass(?:word|code)|\\bpasscode\\b|verification\\s+code|\\bsecret\\s+code\\b|" +
            "\\bdo\\s+not\\s+share\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_COLLECT = Regex(
        "\\brequest(?:ed|ing|s)?\\b|\\bcollect\\s+request\\b|\\bapprove\\b|\\bdecline\\b|\\bpay\\s+now\\b|\\btap\\s+to\\s+pay\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_FAILED = Regex(
        "\\bfailed\\b|\\bdeclined\\b|\\bunsuccessful\\b|\\bcould\\s+not\\s+be\\s+processed\\b|" +
            "\\brevers(?:al|ed)\\b|\\brefund(?:ed)?\\b|\\bcredited\\s+back\\b",
        RegexOption.IGNORE_CASE,
    )
    private val REJECT_PROMO = Regex(
        "\\boffers?\\b|T&C|\\bapply\\s+now\\b|\\bclick\\b|https?://|\\bwww\\.|\\bcoupon\\b|\\bdiscount\\b|" +
            "\\bupgrade\\b|\\bpre-?approved\\b|\\bloan\\b|\\bEMI\\b|\\binsurance\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Payments *into* a card/loan of yours are not money received by you. */
    private val REJECT_CARD = Regex(
        "\\bcredit\\s+card\\b|\\btowards\\s+your\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Any debit verb rejects, except the counter-party phrasing "paid (to) you" / "sent (to) you". */
    private val DEBIT_VERB = Regex(
        "\\bdebited\\b|\\bdebit\\b|\\bwithdrawn\\b|\\bwithdrawal\\b|\\bspent\\b|\\bdeducted\\b|\\bpurchased?\\b|\\bcharged\\b|" +
            "\\bsent\\b(?!\\s+(?:you|to\\s+you)\\b)|\\bpaid\\b(?!\\s+(?:you|to\\s+you)\\b)|\\btransferred\\b(?!\\s+to\\s+you\\b)|" +
            "UPI/DR\\b|/DR/",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT_KEYWORD = Regex(
        "\\bcredited\\b|\\breceived\\b|\\bdeposited\\b|\\bpaid\\s+(?:to\\s+)?you\\b|\\bcredit\\s+alert\\b|UPI/CR\\b|/CR/",
        RegexOption.IGNORE_CASE,
    )

    /** "Rs.500.00", "Rs 500", "Rs8700", "INR 1,50,000.00", "₹500", "credited:Rs 500". Group 1 = numeric text. */
    private val AMOUNT = Regex(
        "(?:Rs\\.?|INR|₹|Rupees)\\s*:?\\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE,
    )
    /** Words that precede a balance/limit figure rather than the credited amount. */
    private val BALANCE_WORD = Regex(
        "\\b(?:bal|balance|avl|avail|available|limit|lmt|total)\\b\\.?:?-?\\s*(?:amt|bal|balance)?",
        RegexOption.IGNORE_CASE,
    )

    /** "UPI/CR/426112345678/...", "UPI/P2A/426112345678/...", "UPI/426112345678/..." */
    private val UTR_SLASH = Regex("\\bUPI\\s*/\\s*(?:[A-Z0-9]{2,4}\\s*/\\s*)?([0-9]{10,12})(?![0-9])", RegexOption.IGNORE_CASE)

    /** "UPI Ref No 4261...", "Ref no: 4261...", "RRN 4261...", "UTR 4261...", "UPI:4261...", "(UPI 4261...)", "Txn ID 4261...". */
    private val UTR_KEYWORD = Regex(
        "\\b(?:UPI\\s*Ref(?:erence)?\\.?\\s*(?:No\\.?|Number|ID)?|Ref(?:erence)?\\.?\\s*(?:No\\.?|Number|ID)?|RRN|UTR|" +
            "Txn\\s*(?:ID|No\\.?|Number)?|Transaction\\s*(?:ID|No\\.?|Number|Ref(?:erence)?)?|UPI\\s*(?:ID|Txn\\s*ID)?|IMPS)" +
            "\\s*[:#.\\-]?\\s*\\(?\\s*([0-9]{10,12})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
    /** Fallback: a standalone 12-digit run that is not the tail of a masked account number. */
    private val UTR_BARE_12 = Regex("(?<![0-9Xx*])[0-9]{12}(?![0-9])")

    // One name/VPA token: letters/digits in any script with @ _ ' -; dots only *between* word chars so a
    // sentence-ending "." is never swallowed ("MURUGAN." -> "MURUGAN", "murugan.s@okaxis" stays whole).
    private const val TOKEN = "[\\p{L}\\p{N}@_'\\-]+(?:\\.[\\p{L}\\p{N}@_'\\-]+)*"

    // Name: one or more tokens separated by spaces (lazy, never across a newline); ends at a delimiter/keyword.
    private const val NAME = "($TOKEN(?:[ \\t]+$TOKEN)*?)"
    private const val NAME_END =
        "(?=[ \\t]*(?:\\n|\\.\\s|\\.$|,|\\(|\\)|;|:|[ \\t]-|[ \\t]+(?:on|Ref|UPI|via|in|to|at|for|towards|thru|through|with|and|" +
            "dated|dt|Avl|Avail|Available|Bal|Total|Txn|Transaction|RRN|UTR|Info|Not|Never|Call|If|Your|Dial|SMS|Money|Amount)\\b|$))"

    private val PAYER_PATTERNS = listOf(
        Regex("\\bfrom[ \\t]+(?:VPA[ \\t]+|a/c[ \\t]+linked[ \\t]+to[ \\t]+VPA[ \\t]+|a/c[ \\t]+)?$NAME$NAME_END", RegexOption.IGNORE_CASE),
        Regex("\\bby[ \\t]+(?:a/c[ \\t]+linked[ \\t]+to[ \\t]+)?VPA[ \\t]+$NAME$NAME_END", RegexOption.IGNORE_CASE),
        Regex("$NAME[ \\t]+paid[ \\t]+you\\b", RegexOption.IGNORE_CASE),
        Regex(
            "\\bUPI/(?:[A-Z0-9]{2,4}/)?[0-9]{10,12}/$NAME(?=[ \\t]*(?:/|\\.\\s|\\.$|,|;|[ \\t]-|\\n|$))",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "\\bby[ \\t]+(?!UPI\\b|Ref\\b|Rs\\.?\\s*[0-9]|INR\\b|₹|Rupees\\b|a/c\\b|account\\b|transfer\\b|NEFT\\b|IMPS\\b|" +
                "cash\\b|cheque\\b|clearing\\b|\\()$NAME$NAME_END",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val LONG_DIGIT_RUN = Regex("[0-9]{6,}")

    private val NAME_STOPWORDS = Regex(
        "(?i)^(?:upi|ref|rs|inr|a/c|account|transfer|neft|imps|vpa|you|your|bank|cash|cheque|the)$",
    )
}
