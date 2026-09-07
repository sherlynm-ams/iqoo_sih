package com.crosscheck.app.export

import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.data.VerdictCode
import com.crosscheck.app.voice.Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReportContentTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 9, 6, h, m, s, 0, zone).toInstant().toEpochMilli()

    @Test
    fun csv_field_quoting() {
        assertEquals("plain", ReportContent.csvField("plain"))
        assertEquals("\"a,b\"", ReportContent.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ReportContent.csvField("say \"hi\""))
        assertEquals("\"line\nbreak\"", ReportContent.csvField("line\nbreak"))
    }

    @Test
    fun csv_field_defuses_leading_formula_triggers() {
        // payerName/payerBankMask come from OCR text on a stranger's screen: a leading =, +, -, @,
        // tab or CR must never reach a spreadsheet as a live formula (OWASP CSV injection).
        assertEquals("'=cmd|'/c calc'!A0", ReportContent.csvField("=cmd|'/c calc'!A0"))
        assertEquals("'+1+1", ReportContent.csvField("+1+1"))
        assertEquals("'-1-1", ReportContent.csvField("-1-1"))
        assertEquals("'@SUM(A1)", ReportContent.csvField("@SUM(A1)"))
        // a value that also needs RFC 4180 quoting still gets the defusal applied first
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", ReportContent.csvField("=HYPERLINK(\"x\")"))
        // an ordinary negative-looking number or hyphenated name is untouched by anything but the defusal
        assertEquals("'-50000", ReportContent.csvField("-50000"))
    }

    @Test
    fun reconciliation_csv_rows_in_time_order_with_paise_column() {
        val rows = listOf(
            PaymentEntity(2, 75_000, "KUMAR", "624912345678", at(12, 55), PaymentSource.NOTIFICATION, "raw"),
            PaymentEntity(1, 50_050, "MURUGAN, S", null, at(12, 41, 7), PaymentSource.SMS, "raw"),
        )
        val csv = ReportContent.reconciliationCsv(rows, zone)
        val lines = csv.trimEnd().lines()
        assertEquals(ReportContent.RECONCILIATION_HEADER, lines[0])
        assertEquals("12:41:07,500.50,50050,\"MURUGAN, S\",,sms", lines[1])
        assertEquals("12:55:00,750.00,75000,KUMAR,624912345678,notification", lines[2])
    }

    @Test
    fun flagged_csv_keeps_only_nomatch_and_likely() {
        fun claim(id: Long, verdict: String, reason: String?) = ClaimEntity(
            id, 50_000, "seller@upi", "699912345678", at(13, 0), verdict, reason, at(13, 1), "ocr",
            "RAVI", "SBI-XX4321", "RAVI", "NAME",
        )
        val csv = ReportContent.flaggedCsv(
            listOf(claim(1, VerdictCode.MATCH, null), claim(2, VerdictCode.NO_MATCH, "NOTHING_RECEIVED"), claim(3, VerdictCode.LIKELY_MATCH, "WRONG_UTR")),
            zone,
        )
        val lines = csv.trimEnd().lines()
        assertEquals(ReportContent.FLAGGED_HEADER, lines[0])
        assertEquals(3, lines.size)
        assertEquals("13:01:00,500.00,50000,699912345678,NO_MATCH,NOTHING_RECEIVED,RAVI,SBI-XX4321,RAVI,NAME,ocr", lines[1])
        assertTrue(lines[2].contains(",LIKELY_MATCH,WRONG_UTR,"))
    }

    @Test
    fun summary_text_carries_digest_sentence_and_numbers() {
        val text = ReportContent.summaryText(
            LocalDate.of(2026, 9, 6), Digest(2, 125_000, 1, 50_000), "2 payments confirmed, 1 mismatch, ₹500 flagged today.", at(18, 0), zone,
        )
        assertTrue(text.startsWith("CrossCheck — end-of-day reconciliation, 2026-09-06"))
        assertTrue(text.contains("2 payments confirmed, 1 mismatch, ₹500 flagged today."))
        assertTrue(text.contains("Total received:     ₹1,250"))
        assertTrue(text.contains("crosscheck-2026-09-06-reconciliation.csv"))
        assertEquals("crosscheck-2026-09-06", ReportContent.fileBaseName(LocalDate.of(2026, 9, 6)))
    }
}
