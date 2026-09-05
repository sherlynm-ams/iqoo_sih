package com.crosscheck.app.verify

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** One OCR text line with its bounding box in frame pixels (origin top-left). Pure Kotlin so the parser is unit-testable. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** All lines ML Kit found in one frame plus the frame size (for the "top 45 %" rule). */
data class OcrFrame(val lines: List<OcrLine>, val width: Int, val height: Int)

/** What the live analysis stream needs to decide on auto-capture. */
data class QuickScan(val hasAmount: Boolean, val hasUtr: Boolean) {
    val looksLikePaymentScreen: Boolean get() = hasAmount && hasUtr
}

/**
 * OCR fallback field parser (docs/research/payment-screens.md section 3b): ML Kit lines + boxes -> [Claim].
 *
 * - amount   = the currency-marked figure with the tallest box in the top ~45 % of the frame
 * - utr      = the best-scoring 12-digit run (label on the same/previous line +3, inside a T-id or long run -3,
 *              looks like +91 mobile -2, current-year prefix +1); anything below 0 -> null
 * - otherIds = PhonePe `T` + 20-22 digits, GPay `CICAg...` (never a UTR)
 * - app / status / timestamp / payee VPA / payer name + bank mask per the research regexes.
 *
 * Language-neutral: only digits, currency markers and the two apps' English labels are matched.
 */
class ClaimFieldParser(
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Kolkata")) },
) {

    fun parse(frame: OcrFrame): Claim? {
        if (frame.lines.isEmpty()) return null
        val lines = frame.lines.sortedWith(compareBy({ it.top }, { it.left })).map { Line(it) }
        val amount = pickAmount(lines, frame.height) ?: return null
        val utr = pickUtr(lines)
        val otherIds = collectOtherIds(lines)
        val app = detectApp(lines, otherIds)
        val status = detectStatus(lines)
        val timestamp = lines.firstNotNullOfOrNull { parseTimestamp(it.raw, zone, today()) }
        val upiId = lines.firstNotNullOfOrNull { findUpiId(it.raw) }
        val payerName = lines.firstNotNullOfOrNull { findPayerName(it.raw) }
        val payerBankMask = lines.firstNotNullOfOrNull { findBankMask(it.norm) }
        return Claim(
            amountPaise = amount,
            upiId = upiId,
            utr = utr,
            claimedTimestamp = timestamp,
            payerName = payerName,
            payerBankMask = payerBankMask,
            payerVpa = null,
            app = app,
            status = status,
            otherIds = otherIds,
        )
    }

    /** Cheap presence check for the analysis stream: a currency-marked figure and a 12-digit run. */
    fun quickScan(lines: List<OcrLine>): QuickScan {
        var amount = false
        var utr = false
        val parsed = lines.map { Line(it) }
        val median = medianHeight(parsed)
        for (line in parsed) {
            if (!amount && (AMOUNT.containsMatchIn(line.norm) || (BARE_AMOUNT.matches(line.norm) && isHeadline(line.height, median)))) amount = true
            if (!utr && UTR_RUN.containsMatchIn(line.collapsed)) utr = true
            if (amount && utr) break
        }
        return QuickScan(amount, utr)
    }

    // ---- amount -------------------------------------------------------------------------------

    /**
     * Currency-marked figures first. ML Kit's Latin model usually drops the "₹" glyph altogether (the
     * headline comes back as a bare "500" whose box starts after the symbol), so a bare number that is a
     * *headline* - in the top 45 %, at least [HEADLINE_FACTOR] x the median line height, <= 7 digits - is
     * the fallback. Figures next to "Debited from" / "From" lose unless nothing else exists.
     */
    private fun pickAmount(lines: List<Line>, frameHeight: Int): Long? {
        data class Candidate(val paise: Long, val height: Int, val top: Int, val nearDebit: Boolean)

        val marked = ArrayList<Candidate>()
        val bare = ArrayList<Candidate>()
        val median = medianHeight(lines)
        val topLimit = (frameHeight * TOP_FRACTION).toInt()
        for ((i, line) in lines.withIndex()) {
            val above = aboveOf(lines, i)
            val nearDebit = DEBIT_CONTEXT.containsMatchIn(line.raw) || (above != null && DEBIT_CONTEXT.containsMatchIn(above.raw))
            val m = AMOUNT.find(line.norm)
            if (m != null) {
                toPaise(m.groupValues[1], m.groupValues[2])?.let { marked.add(Candidate(it, line.height, line.top, nearDebit)) }
                continue
            }
            val b = BARE_AMOUNT.matchEntire(line.norm) ?: continue
            if (line.top > topLimit || !isHeadline(line.height, median)) continue
            toPaise(b.groupValues[1], b.groupValues[2])?.let { bare.add(Candidate(it, line.height, line.top, nearDebit)) }
        }
        val pool = when {
            marked.any { it.top <= topLimit && !it.nearDebit } -> marked.filter { it.top <= topLimit && !it.nearDebit }
            bare.any { !it.nearDebit } -> bare.filter { !it.nearDebit }
            marked.any { !it.nearDebit } -> marked.filter { !it.nearDebit }
            marked.isNotEmpty() -> marked
            else -> bare
        }
        return pool.maxWithOrNull(compareBy<Candidate> { it.height }.thenByDescending { it.top })?.paise
    }

    private fun medianHeight(lines: List<Line>): Int {
        val heights = lines.map { it.height }.filter { it > 0 }.sorted()
        return if (heights.isEmpty()) 0 else heights[heights.size / 2]
    }

    private fun isHeadline(height: Int, median: Int): Boolean = median > 0 && height >= median * HEADLINE_FACTOR

    private fun toPaise(rupees: String, decimals: String): Long? {
        val whole = rupees.replace(",", "").toLongOrNull() ?: return null
        val frac = if (decimals.isEmpty()) 0L else decimals.padEnd(2, '0').toLong()
        return whole * 100 + frac
    }

    // ---- UTR ----------------------------------------------------------------------------------

    private fun pickUtr(lines: List<Line>): String? {
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        val yearDigit = today().year % 10
        for ((i, line) in lines.withIndex()) {
            val runs = UTR_RUN.findAll(line.collapsed).map { it.groupValues[1] }.toList()
            if (runs.isEmpty()) continue
            val above = aboveOf(lines, i)
            val labelled = UTR_LABEL.containsMatchIn(line.raw) || (above != null && UTR_LABEL.containsMatchIn(above.raw))
            val insideLongerRun = T_ID_PREFIX.containsMatchIn(line.collapsed) || LONG_DIGIT_RUN.containsMatchIn(line.collapsed)
            for (run in runs) {
                var score = 0
                if (labelled) score += 3
                if (insideLongerRun) score -= 3
                if (MOBILE_LIKE.matches(run)) score -= 2
                if (run[0] - '0' == yearDigit && run.substring(1, 4).toInt() <= 366) score += 1
                if (score > bestScore) {
                    bestScore = score
                    best = run
                }
            }
        }
        return if (bestScore >= 0) best else null
    }

    // ---- other ids / app / status -------------------------------------------------------------

    private fun collectOtherIds(lines: List<Line>): List<String> {
        val ids = LinkedHashSet<String>()
        for (line in lines) {
            PHONEPE_TXN_ID.findAll(line.collapsed).forEach { ids.add(it.value) }
            GPAY_TXN_ID.findAll(line.raw).forEach { ids.add(it.value) }
        }
        return ids.toList()
    }

    private fun detectApp(lines: List<Line>, otherIds: List<String>): ClaimApp {
        var gpay = otherIds.count { it.startsWith("CICAg") }
        var phonepe = otherIds.count { it.startsWith("T") }
        for (line in lines) {
            if (GPAY_HINT.containsMatchIn(line.raw)) gpay++
            if (PHONEPE_HINT.containsMatchIn(line.raw)) phonepe++
        }
        return when {
            gpay == 0 && phonepe == 0 -> ClaimApp.UNKNOWN
            gpay >= phonepe -> ClaimApp.GPAY
            else -> ClaimApp.PHONEPE
        }
    }

    private fun detectStatus(lines: List<Line>): ClaimStatus {
        var failed = false
        var pending = false
        var success = false
        for (line in lines) {
            if (STATUS_FAILED.containsMatchIn(line.raw)) failed = true
            if (STATUS_PENDING.containsMatchIn(line.raw)) pending = true
            if (STATUS_SUCCESS.containsMatchIn(line.raw)) success = true
        }
        return when {
            failed -> ClaimStatus.FAILED
            pending -> ClaimStatus.PENDING
            success -> ClaimStatus.SUCCESS
            else -> ClaimStatus.UNKNOWN
        }
    }

    // ---- identity fields ----------------------------------------------------------------------

    private fun findUpiId(raw: String): String? {
        val m = UPI_ID.find(raw.lowercase()) ?: return null
        return m.groupValues[1] + "@" + m.groupValues[2]
    }

    private fun findPayerName(raw: String): String? {
        if (DEBIT_CONTEXT.containsMatchIn(raw) && !raw.trimStart().startsWith("From", ignoreCase = true)) return null
        val m = PAYER_FROM.find(raw) ?: return null
        return m.groupValues[1].trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
    }

    private fun findBankMask(norm: String): String? {
        val m = BANK_MASK.find(norm) ?: return null
        return (m.groupValues[1].replace(Regex("[•*xX]"), "X") + m.groupValues[2])
    }

    /** Nearest line above [index] that overlaps horizontally (label rows sit directly above their values). */
    private fun aboveOf(lines: List<Line>, index: Int): Line? {
        val me = lines[index]
        var best: Line? = null
        for (j in index - 1 downTo 0) {
            val other = lines[j]
            if (other.bottom > me.top + me.height / 2) continue
            val overlap = minOf(other.right, me.right) - maxOf(other.left, me.left)
            if (overlap <= 0) continue
            if (best == null || other.bottom > best.bottom) best = other
            if (me.top - other.bottom > me.height * 3) break
        }
        return best
    }

    /** Raw text plus the digit-run-normalised and space-collapsed variants used for numeric matching. */
    private class Line(source: OcrLine) {
        val raw: String = source.text
        val norm: String = normaliseDigitRuns(raw)
        val collapsed: String = norm.replace(SPACE_IN_DIGITS, "")
        val left = source.left
        val top = source.top
        val right = source.right
        val bottom = source.bottom
        val height = source.height
    }

    companion object {
        const val TOP_FRACTION = 0.45
        const val HEADLINE_FACTOR = 1.5

        private const val CURRENCY = "(?:₹|Rs\\.?|INR|\\?|र)"
        private val AMOUNT = Regex("$CURRENCY\\s?(\\d{1,3}(?:,\\d{2,3})*|\\d+)(?:\\.(\\d{1,2}))?(?!\\d)")

        /** A line that is nothing but a figure (<= 7 digits, Indian or plain grouping, optional paise). */
        private val BARE_AMOUNT = Regex("\\s*(\\d{1,3}(?:,\\d{2,3}){0,2}|\\d{1,7})(?:\\.(\\d{1,2}))?\\s*")
        private val UTR_RUN = Regex("(?<!\\d)(\\d{12})(?!\\d)")
        private val UTR_LABEL = Regex("UPI\\s*transaction\\s*ID|\\bUTR\\b|UPI\\s*Ref|\\bRRN\\b|Bank\\s*ref", RegexOption.IGNORE_CASE)
        private val T_ID_PREFIX = Regex("(?<![A-Za-z0-9])T\\d{12,}")
        private val LONG_DIGIT_RUN = Regex("\\d{13,}")
        private val MOBILE_LIKE = Regex("91[6-9]\\d{9}")
        private val PHONEPE_TXN_ID = Regex("(?<![A-Za-z0-9])T\\d{20,22}(?!\\d)")
        private val GPAY_TXN_ID = Regex("(?<![A-Za-z0-9])CICAg[A-Za-z0-9_-]{6,}")
        /** ML Kit reads "@" as "©" at times and often inserts a space before it ("sherwin @ybl"). */
        private val UPI_ID = Regex("([a-z0-9._-]{2,})\\s?[@©]\\s?([a-z]{2,})")
        private val GPAY_HINT = Regex("Google\\s*transaction\\s*ID|UPI\\s*transaction\\s*ID|Google\\s*Pay|\\bG\\s*Pay\\b", RegexOption.IGNORE_CASE)
        private val PHONEPE_HINT = Regex(
            "PhonePe|\\bUTR\\b|Debited\\s*from|Transaction\\s*ID\\s*:?\\s*T\\d|^\\s*Transaction\\s*ID\\s*:?\\s*$|Transfer\\s*Details",
            RegexOption.IGNORE_CASE,
        )
        private val STATUS_SUCCESS = Regex(
            "Completed|Successful|Success|Payment\\s*Successful|Transaction\\s*Successful|Paid\\s*to|Sent\\s*to",
            RegexOption.IGNORE_CASE,
        )
        private val STATUS_PENDING = Regex("Processing|Pending|In\\s*progress|Initiated", RegexOption.IGNORE_CASE)
        private val STATUS_FAILED = Regex("Failed|Declined|Unsuccessful|Reversed|Refund", RegexOption.IGNORE_CASE)
        private val DEBIT_CONTEXT = Regex("Debited\\s*from|\\bFrom\\b", RegexOption.IGNORE_CASE)
        private val PAYER_FROM = Regex("^\\s*From\\s*:?\\s*([^()]+?)\\s*\\(", RegexOption.IGNORE_CASE)
        private val BANK_MASK = Regex("(?<![A-Za-z0-9])([Xx•*]{2,})\\s?(\\d{4})(?!\\d)")
        private val SPACE_IN_DIGITS = Regex("(?<=\\d)[ \\u00A0](?=\\d)")

        /** Runs of digit-like glyphs (>= 6 long, mostly real digits) get the classic OCR confusions fixed. */
        private val DIGIT_LIKE_RUN = Regex("[0-9OoIlSB]{6,}")

        internal fun normaliseDigitRuns(text: String): String = DIGIT_LIKE_RUN.replace(text) { m ->
            val run = m.value
            val digits = run.count { it.isDigit() }
            if (digits * 10 < run.length * 6) run else run.map {
                when (it) {
                    'O', 'o' -> '0'
                    'I', 'l' -> '1'
                    'S' -> '5'
                    'B' -> '8'
                    else -> it
                }
            }.joinToString("")
        }

        private const val MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
        private val TS_DAY_FIRST = Regex(
            "(\\d{1,2})\\s+($MONTHS)[a-z]*\\.?,?\\s+(\\d{4}),?\\s*(?:at\\s+)?(\\d{1,2}):(\\d{2})\\s*([AaPp]\\.?[Mm]\\.?)?",
        )
        private val TS_MONTH_FIRST = Regex("($MONTHS)[a-z]*\\s+(\\d{1,2}),\\s*(\\d{4})\\s+at\\s+(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])")
        private val TS_RELATIVE = Regex("(Today|Yesterday),?\\s+(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?", RegexOption.IGNORE_CASE)
        private val TS_NUMERIC = Regex("(?<!\\d)(\\d{2})[-/](\\d{2})[-/](\\d{2,4})\\s+(\\d{1,2}):(\\d{2})(?!\\d)")

        /**
         * Parses the on-screen date/time formats of both apps ("6 Sept 2026, 12:41 pm", "06 Sep 2026, 12:41 pm",
         * "December 8, 2020 at 7:25 PM", "Today, 10:30 AM", "06-09-26 12:41") in [zone] -> epoch millis UTC.
         */
        fun parseTimestamp(text: String, zone: ZoneId = ZoneId.of("Asia/Kolkata"), today: LocalDate = LocalDate.now(zone)): Long? {
            TS_DAY_FIRST.find(text)?.let { m ->
                val month = monthOf(m.groupValues[2]) ?: return@let
                return toEpoch(m.groupValues[3].toInt(), month, m.groupValues[1].toInt(), m.groupValues[4], m.groupValues[5], m.groupValues[6], zone)
            }
            TS_MONTH_FIRST.find(text)?.let { m ->
                val month = monthOf(m.groupValues[1]) ?: return@let
                return toEpoch(m.groupValues[3].toInt(), month, m.groupValues[2].toInt(), m.groupValues[4], m.groupValues[5], m.groupValues[6], zone)
            }
            TS_RELATIVE.find(text)?.let { m ->
                val day = if (m.groupValues[1].equals("Today", ignoreCase = true)) today else today.minusDays(1)
                return toEpoch(day.year, day.monthValue, day.dayOfMonth, m.groupValues[2], m.groupValues[3], m.groupValues[4], zone)
            }
            TS_NUMERIC.find(text)?.let { m ->
                val yy = m.groupValues[3].toInt()
                val year = if (m.groupValues[3].length == 2) 2000 + yy else yy
                return toEpoch(year, m.groupValues[2].toInt(), m.groupValues[1].toInt(), m.groupValues[4], m.groupValues[5], "", zone)
            }
            return null
        }

        private fun monthOf(token: String): Int? {
            val key = token.take(3).lowercase()
            val names = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            val idx = names.indexOf(key)
            return if (idx < 0) null else idx + 1
        }

        private fun toEpoch(year: Int, month: Int, day: Int, hourText: String, minuteText: String, ampm: String, zone: ZoneId): Long? {
            var hour = hourText.toIntOrNull() ?: return null
            val minute = minuteText.toIntOrNull() ?: return null
            val marker = ampm.lowercase().filter { it.isLetter() }
            if (marker.isNotEmpty()) {
                if (hour < 1 || hour > 12) return null
                hour = hour % 12 + if (marker.startsWith("p")) 12 else 0
            } else if (hour > 23) {
                return null
            }
            if (minute > 59) return null
            return try {
                LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
            } catch (e: java.time.DateTimeException) {
                null
            }
        }
    }
}
