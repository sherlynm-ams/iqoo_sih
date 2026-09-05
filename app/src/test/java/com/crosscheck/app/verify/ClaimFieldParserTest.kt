package com.crosscheck.app.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Hand-written ML Kit line lists that mirror the 10 fixture PNGs (fixtures/fixtures.json is the contract),
 * plus glare / partial / OCR-confusion cases. Coordinates are 1080x2400 frame pixels.
 */
class ClaimFieldParserTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val parser = ClaimFieldParser(zone = ist, today = { LocalDate.of(2026, 9, 6) })

    private fun ist(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(ist).toInstant().toEpochMilli()

    private val t1241 = ist(2026, 9, 6, 12, 41)

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) = OcrLine(text, left, top, right, bottom)

    // ---- fixture line lists ---------------------------------------------------------------------

    private fun gpayLines(
        amount: String = "₹500",
        utr: String? = "426112345678",
        gid: String = "CICAgKDx1b3fSA",
        pending: Boolean = false,
    ): List<OcrLine> = buildList {
        add(line("12:41", 48, 28, 130, 52))
        add(line(amount, 390, 590, 690, 700))
        add(line(if (pending) "Paying SHERWIN STORES" else "Paid to SHERWIN STORES", 258, 750, 822, 800))
        add(line(if (pending) "In progress" else "Completed", 444, 860, 636, 900))
        add(line("6 Sept 2026, 12:41 pm", 344, 925, 736, 965))
        var y = 1128
        if (utr != null) {
            add(line("UPI transaction ID", 64, y, 332, y + 32))
            add(line(utr, 64, y + 57, 350, y + 97))
            y += 167
        }
        add(line("To: SHERWIN STORES (sherwin@oksbi)", 64, y, 745, y + 45))
        add(line("From: MURUGAN (State Bank of India XX4321)", 64, y + 105, 882, y + 150))
        add(line("Google transaction ID", 64, y + 215, 392, y + 250))
        add(line(gid, 64, y + 273, 400, y + 317))
        add(line("Share screenshot", 255, 2190, 560, 2235))
        add(line("Close", 726, 2190, 826, 2235))
    }

    private fun phonepeLines(
        amount: String = "₹500",
        utr: String? = "426112345678",
        tid: String = "T2609061241123456789012",
        pending: Boolean = false,
    ): List<OcrLine> = buildList {
        add(line("12:41", 48, 28, 130, 52))
        add(line("Help", 960, 130, 1035, 165))
        add(line(if (pending) "Payment Pending" else "Payment Successful", 256, 490, 824, 545))
        add(line("06 Sep 2026, 12:41 pm", 350, 580, 730, 615))
        add(line("Paid to", 92, 735, 196, 765))
        add(line("SHERWIN STORES", 92, 795, 470, 840))
        add(line(amount, 712, 765, 985, 870))
        add(line("sherwin@ybl", 92, 860, 285, 895))
        add(line("Transfer Details", 64, 1020, 344, 1062))
        add(line("Transaction ID", 92, 1152, 305, 1185))
        add(line(tid, 92, 1205, 612, 1250))
        var y = 1312
        if (utr != null) {
            add(line("UTR", 92, y, 152, y + 33))
            add(line(utr, 92, y + 53, 363, y + 98))
            y += 158
        }
        add(line("Debited from", 92, y, 292, y + 35))
        add(line("State Bank of India", 216, y + 65, 546, y + 105))
        add(line(amount, 892, y + 85, 988, y + 125))
        add(line("XXXXXX4321", 216, y + 120, 408, y + 152))
        add(line("Share", 230, 2195, 335, 2240))
        add(line("Done", 748, 2195, 850, 2240))
    }

    /** The *_photo fixtures: same screen scaled 0.86 inside a dark surround, offset by the bezel. */
    private fun photo(lines: List<OcrLine>): List<OcrLine> = lines.map {
        OcrLine(
            it.text,
            (it.left * 0.86).toInt() + 76,
            (it.top * 0.86).toInt() + 150,
            (it.right * 0.86).toInt() + 76,
            (it.bottom * 0.86).toInt() + 150,
        )
    }

    private fun parse(lines: List<OcrLine>): Claim = requireNotNull(parser.parse(OcrFrame(lines, 1080, 2400)))

    // ---- the 10 fixtures --------------------------------------------------------------------------

    @Test
    fun gpay_genuine() {
        val c = parse(gpayLines())
        assertEquals(50_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
        assertEquals(listOf("CICAgKDx1b3fSA"), c.otherIds)
        assertEquals(ClaimApp.GPAY, c.app)
        assertEquals(ClaimStatus.SUCCESS, c.status)
        assertEquals(t1241, c.claimedTimestamp)
        assertEquals("sherwin@oksbi", c.upiId)
        assertEquals("MURUGAN", c.payerName)
        assertEquals("XX4321", c.payerBankMask)
        assertNull(c.payerVpa)
    }

    @Test
    fun gpay_wrong_utr() {
        val c = parse(gpayLines(utr = "519876543210", gid = "CICAgMzQ9pXbQg"))
        assertEquals(50_000L, c.amountPaise)
        assertEquals("519876543210", c.utr)
        assertEquals(listOf("CICAgMzQ9pXbQg"), c.otherIds)
        assertEquals(ClaimStatus.SUCCESS, c.status)
    }

    @Test
    fun gpay_wrong_amount() {
        val c = parse(gpayLines(amount = "₹5,000"))
        assertEquals(500_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
        assertEquals(ClaimApp.GPAY, c.app)
    }

    @Test
    fun gpay_pending_has_no_utr_and_status_pending() {
        val c = parse(gpayLines(utr = null, gid = "CICAgLm2c7vXRw", pending = true))
        assertEquals(50_000L, c.amountPaise)
        assertNull(c.utr)
        assertEquals(ClaimStatus.PENDING, c.status)
        assertEquals(listOf("CICAgLm2c7vXRw"), c.otherIds)
        assertEquals(ClaimApp.GPAY, c.app)
        assertEquals("MURUGAN", c.payerName)
        assertEquals("XX4321", c.payerBankMask)
        assertEquals(t1241, c.claimedTimestamp)
    }

    @Test
    fun phonepe_genuine() {
        val c = parse(phonepeLines())
        assertEquals(50_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
        assertEquals(listOf("T2609061241123456789012"), c.otherIds)
        assertEquals(ClaimApp.PHONEPE, c.app)
        assertEquals(ClaimStatus.SUCCESS, c.status)
        assertEquals(t1241, c.claimedTimestamp)
        assertEquals("sherwin@ybl", c.upiId)
        assertNull(c.payerName)
        assertEquals("XXXXXX4321", c.payerBankMask)
    }

    @Test
    fun phonepe_wrong_utr() {
        val c = parse(phonepeLines(utr = "519876543210", tid = "T2609061240987654321098"))
        assertEquals("519876543210", c.utr)
        assertEquals(listOf("T2609061240987654321098"), c.otherIds)
        assertEquals(ClaimApp.PHONEPE, c.app)
    }

    @Test
    fun phonepe_wrong_amount_ignores_the_debited_from_row() {
        val c = parse(phonepeLines(amount = "₹5,000"))
        assertEquals(500_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
    }

    @Test
    fun phonepe_pending() {
        val c = parse(phonepeLines(utr = null, tid = "T2609061241555555555555", pending = true))
        assertEquals(50_000L, c.amountPaise)
        assertNull(c.utr)
        assertEquals(ClaimStatus.PENDING, c.status)
        assertEquals(listOf("T2609061241555555555555"), c.otherIds)
        assertEquals("XXXXXX4321", c.payerBankMask)
        assertNull(c.payerName)
    }

    @Test
    fun gpay_genuine_photo() {
        val c = parse(photo(gpayLines()))
        assertEquals(50_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
        assertEquals(ClaimApp.GPAY, c.app)
        assertEquals(ClaimStatus.SUCCESS, c.status)
        assertEquals("MURUGAN", c.payerName)
    }

    @Test
    fun phonepe_genuine_photo() {
        val c = parse(photo(phonepeLines()))
        assertEquals(50_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
        assertEquals(ClaimApp.PHONEPE, c.app)
        assertEquals(ClaimStatus.SUCCESS, c.status)
        assertEquals("sherwin@ybl", c.upiId)
    }

    // ---- glare / partial / adversarial ------------------------------------------------------------

    @Test
    fun details_block_cut_off_gives_null_utr_not_a_guess() {
        val c = parse(gpayLines().filter { it.top < 1000 })
        assertEquals(50_000L, c.amountPaise)
        assertNull(c.utr)
        assertEquals(ClaimStatus.SUCCESS, c.status)
        assertEquals(t1241, c.claimedTimestamp)
    }

    @Test
    fun ten_digit_run_is_not_a_utr() {
        val c = parse(gpayLines(utr = "4261123456"))
        assertNull(c.utr)
    }

    @Test
    fun digit_run_inside_a_split_t_id_is_not_a_utr() {
        // OCR broke the 23-char PhonePe id into two runs; the second is 12 digits long.
        val c = parse(phonepeLines(utr = null, tid = "T26090612411 23456789012"))
        assertNull(c.utr)
        assertEquals(listOf("T2609061241123456789012"), c.otherIds)
    }

    @Test
    fun unlabelled_twelve_digit_run_in_a_long_reference_is_not_a_utr() {
        val lines = gpayLines(utr = null) + line("Ref 426112345678901234", 64, 1900, 700, 1940)
        assertNull(parse(lines).utr)
    }

    @Test
    fun mobile_number_shaped_run_loses_to_a_labelled_utr() {
        val lines = phonepeLines() + line("Call 916123456789", 64, 1900, 500, 1940)
        assertEquals("426112345678", parse(lines).utr)
    }

    @Test
    fun ocr_confusions_inside_digit_runs_are_repaired() {
        val c = parse(phonepeLines(utr = "4261l23456O8", tid = "T26O9061241123456789012"))
        assertEquals("426112345608", c.utr)
        assertEquals(listOf("T2609061241123456789012"), c.otherIds)
    }

    @Test
    fun spaces_inside_a_utr_are_collapsed() {
        assertEquals("426112345678", parse(gpayLines(utr = "4261 1234 5678")).utr)
    }

    @Test
    fun rupee_sign_misread_as_question_mark_still_yields_the_amount() {
        assertEquals(50_000L, parse(gpayLines(amount = "?500")).amountPaise)
        assertEquals(49_950L, parse(gpayLines(amount = "Rs. 499.50")).amountPaise)
        assertEquals(15_000_000L, parse(gpayLines(amount = "₹1,50,000")).amountPaise)
    }

    @Test
    fun ml_kit_drops_the_rupee_glyph_so_a_bare_headline_figure_is_the_amount() {
        // Exactly what ML Kit returned for gpay_genuine.png on the emulator: "500" at [395,597,681,695], no "₹".
        val real = listOf(
            line("12:41", 52, 35, 122, 59),
            line("Paid to SHERWIN STORES", 262, 756, 820, 794),
            line("UPI transaction ID", 89, 1128, 331, 1159),
            line("500", 395, 597, 681, 695),
            line("426112345678", 64, 1191, 347, 1222),
            line("6 Sept 2026, 12:41 pm", 345, 926, 733, 967),
            line("Completed", 444, 864, 634, 903),
            line("To: SHERWIN STORES (sherwin@oksbi)", 75, 1297, 743, 1336),
            line("From: MURUGAN (State Bank of India XX4321)", 68, 1407, 880, 1443),
            line("Google transaction ID", 65, 1508, 387, 1544),
            line("CICAgKDx1b3fSA", 66, 1568, 397, 1613),
            line("Share screenshot", 257, 2197, 561, 2227),
            line("Close", 730, 2197, 825, 2227),
        )
        val c = parse(real)
        assertEquals(50_000L, c.amountPaise)
        assertEquals("426112345678", c.utr)
        assertEquals(ClaimApp.GPAY, c.app)
        assertTrue(parser.quickScan(real).looksLikePaymentScreen)
        // "5,000" the same way (gpay_wrong_amount.png): [339,596,745,711]
        assertEquals(500_000L, parse(gpayLines(amount = "5,000")).amountPaise)
        // PhonePe: bare hero "500" (tall, right column) and bare "500" in the Debited-from row (small, low).
        assertEquals(50_000L, parse(phonepeLines(amount = "500")).amountPaise)
    }

    @Test
    fun a_bare_number_that_is_not_a_headline_is_not_an_amount() {
        // Body-sized bare numbers (a house number, a count) never become the amount.
        val lines = gpayLines().filter { !it.text.contains('₹') } + line("42", 64, 1700, 120, 1740)
        assertNull(parser.parse(OcrFrame(lines, 1080, 2400)))
        // A tall bare UTR-length run is not an amount either.
        val tallUtr = gpayLines().filter { !it.text.contains('₹') }.map { if (it.text == "426112345678") it.copy(top = 590, bottom = 700) else it }
        assertNull(parser.parse(OcrFrame(tallUtr, 1080, 2400)))
    }

    @Test
    fun amount_prefers_the_tallest_currency_figure_in_the_top_half() {
        // A small "₹500" in the top area next to a tall "₹5,000" headline: the headline wins.
        val lines = phonepeLines(amount = "₹5,000") + line("₹500", 40, 300, 120, 330)
        assertEquals(500_000L, parse(lines).amountPaise)
    }

    @Test
    fun no_amount_means_no_claim() {
        assertNull(parser.parse(OcrFrame(gpayLines().filter { !it.text.contains('₹') }, 1080, 2400)))
        assertNull(parser.parse(OcrFrame(emptyList(), 1080, 2400)))
    }

    @Test
    fun status_precedence_failed_over_pending_over_success() {
        assertEquals(ClaimStatus.FAILED, parse(gpayLines() + line("Failed", 400, 2000, 600, 2040)).status)
        assertEquals(ClaimStatus.PENDING, parse(phonepeLines() + line("Processing", 400, 2000, 600, 2040)).status)
        assertEquals(ClaimStatus.SUCCESS, parse(phonepeLines()).status)
        assertEquals(ClaimStatus.UNKNOWN, parse(listOf(line("₹500", 390, 590, 690, 700))).status)
    }

    @Test
    fun app_detection_by_labels_and_ids() {
        assertEquals(ClaimApp.UNKNOWN, parse(listOf(line("₹500", 390, 590, 690, 700))).app)
        assertEquals(ClaimApp.GPAY, parse(listOf(line("₹500", 390, 590, 690, 700), line("Google Pay", 0, 1200, 300, 1240))).app)
        assertEquals(ClaimApp.PHONEPE, parse(listOf(line("₹500", 390, 590, 690, 700), line("PhonePe", 0, 1200, 300, 1240))).app)
    }

    @Test
    fun upi_id_accepts_the_copyright_glyph_misread_of_at() {
        assertEquals("sherwin@oksbi", parse(gpayLines().map { if (it.text.contains("To:")) it.copy(text = "To: SHERWIN STORES (sherwin©oksbi)") else it }).upiId)
    }

    @Test
    fun upi_id_survives_the_space_ml_kit_inserts_before_the_at_sign() {
        // Observed on the emulator for phonepe_genuine.png: line [94,867,282,901] "sherwin @ybl".
        assertEquals("sherwin@ybl", parse(phonepeLines().map { if (it.text == "sherwin@ybl") it.copy(text = "sherwin @ybl") else it }).upiId)
    }

    @Test
    fun quick_scan_needs_both_an_amount_and_a_twelve_digit_run() {
        assertTrue(parser.quickScan(gpayLines()).looksLikePaymentScreen)
        assertFalse(parser.quickScan(gpayLines(utr = null)).looksLikePaymentScreen)
        assertFalse(parser.quickScan(gpayLines().filter { !it.text.contains('₹') }).looksLikePaymentScreen)
    }

    // ---- timestamps -------------------------------------------------------------------------------

    @Test
    fun timestamp_formats_of_both_apps() {
        assertEquals(t1241, ClaimFieldParser.parseTimestamp("6 Sept 2026, 12:41 pm", ist))
        assertEquals(t1241, ClaimFieldParser.parseTimestamp("06 Sep 2026, 12:41 pm", ist))
        assertEquals(t1241, ClaimFieldParser.parseTimestamp("Paid on 06 Sep 2026, 12:41 PM", ist))
        assertEquals(ist(2026, 9, 6, 0, 15), ClaimFieldParser.parseTimestamp("6 Sept 2026, 12:15 am", ist))
        assertEquals(ist(2026, 9, 6, 16, 32), ClaimFieldParser.parseTimestamp("6 Sept 2026, 4:32 pm", ist))
        assertEquals(ist(2024, 4, 12, 18, 45), ClaimFieldParser.parseTimestamp("12 Apr 2024, 18:45", ist))
        assertEquals(ist(2020, 12, 8, 19, 25), ClaimFieldParser.parseTimestamp("December 8, 2020 at 7:25 PM", ist))
        assertEquals(ist(2026, 9, 6, 10, 30), ClaimFieldParser.parseTimestamp("Today, 10:30 AM", ist, LocalDate.of(2026, 9, 6)))
        assertEquals(ist(2026, 9, 5, 22, 5), ClaimFieldParser.parseTimestamp("Yesterday 10:05 pm", ist, LocalDate.of(2026, 9, 6)))
        assertEquals(t1241, ClaimFieldParser.parseTimestamp("on 06-09-26 12:41", ist))
        assertNull(ClaimFieldParser.parseTimestamp("Completed", ist))
        assertNull(ClaimFieldParser.parseTimestamp("6 Sept 2026, 13:41 pm", ist))
    }

    @Test
    fun timestamp_epoch_is_utc_from_ist() {
        // 12:41 IST == 07:11 UTC
        val utc = LocalDateTime.of(2026, 9, 6, 7, 11).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(utc, t1241)
        assertNotNull(ClaimFieldParser.parseTimestamp("6 Sept 2026, 12:41 pm"))
    }
}
