package com.crosscheck.app.export

import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.VerdictCode
import com.crosscheck.app.data.formatRupees
import com.crosscheck.app.voice.Digest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure builders for the three Office Kit files (SPEC section 1). Amounts are written as rupees with
 * two decimals ("500.00") so spreadsheets read them as numbers; a separate `amount_paise` column
 * keeps the exact integer.
 */
object ReportContent {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    const val RECONCILIATION_HEADER = "time,amount,amount_paise,sender,utr,source"
    const val FLAGGED_HEADER = "time,amount,amount_paise,utr,verdict,reason,payer_name,payer_bank_mask,payer_key,key_kind,backend"

    fun fileBaseName(date: LocalDate): String = "crosscheck-$date"

    fun reconciliationCsv(payments: List<PaymentEntity>, zone: ZoneId): String = buildString {
        appendLine(RECONCILIATION_HEADER)
        payments.sortedBy { it.timestamp }.forEach { p ->
            appendLine(
                row(
                    time(p.timestamp, zone),
                    rupees(p.amountPaise),
                    p.amountPaise.toString(),
                    p.sender,
                    p.utr.orEmpty(),
                    p.source,
                ),
            )
        }
    }

    /** Only NoMatch and LikelyMatch claims: the rows a human should look at. */
    fun flaggedCsv(claims: List<ClaimEntity>, zone: ZoneId): String = buildString {
        appendLine(FLAGGED_HEADER)
        claims.filter { it.verdict == VerdictCode.NO_MATCH || it.verdict == VerdictCode.LIKELY_MATCH }
            .sortedBy { it.createdAt }
            .forEach { c ->
                appendLine(
                    row(
                        time(c.createdAt, zone),
                        rupees(c.amountPaise),
                        c.amountPaise.toString(),
                        c.utr.orEmpty(),
                        c.verdict,
                        c.reason.orEmpty(),
                        c.payerName.orEmpty(),
                        c.payerBankMask.orEmpty(),
                        c.payerKey.orEmpty(),
                        c.keyKind.orEmpty(),
                        c.extractorBackend,
                    ),
                )
            }
    }

    fun summaryText(date: LocalDate, digest: Digest, digestSentence: String, generatedAt: Long, zone: ZoneId): String = buildString {
        appendLine("CrossCheck — end-of-day reconciliation, $date")
        appendLine(digestSentence)
        appendLine()
        appendLine("Confirmed payments: ${digest.confirmedCount}")
        appendLine("Total received:     ${formatRupees(digest.totalPaise)}")
        appendLine("Mismatches:         ${digest.mismatchCount}")
        appendLine("Flagged amount:     ${formatRupees(digest.flaggedPaise)}")
        appendLine()
        appendLine("Files: ${fileBaseName(date)}-reconciliation.csv, ${fileBaseName(date)}-flagged.csv")
        appendLine("Generated ${Instant.ofEpochMilli(generatedAt).atZone(zone).format(DATE_TIME)} on device; no data left the phone.")
    }

    /** RFC 4180 quoting: wrap when the field has a comma, quote, or line break; double the quotes. */
    fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    private fun row(vararg fields: String): String = fields.joinToString(",") { csvField(it) }

    private fun rupees(paise: Long): String {
        val abs = if (paise < 0) -paise else paise
        val sign = if (paise < 0) "-" else ""
        return sign + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }

    private fun time(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(TIME)
}
