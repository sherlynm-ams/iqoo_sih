package com.crosscheck.app.voice

import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.data.VerdictCode
import org.junit.Assert.assertEquals
import org.junit.Test

class DigestBuilderTest {

    private fun payment(amount: Long, sender: String = "X", ts: Long = 1L) =
        PaymentEntity(0, amount, sender, null, ts, PaymentSource.SMS, "raw")

    private fun claim(amount: Long, verdict: String) =
        ClaimEntity(0, amount, null, null, null, verdict, null, 1L, "ocr", null, null, null, null)

    @Test
    fun empty_day() {
        assertEquals(Digest(0, 0, 0, 0), DigestBuilder.build(emptyList(), emptyList()))
    }

    @Test
    fun counts_and_sums_payments() {
        val d = DigestBuilder.build(listOf(payment(50_000), payment(75_000), payment(1_00_000)), emptyList())
        assertEquals(3, d.confirmedCount)
        assertEquals(225_000L, d.totalPaise)
        assertEquals(0, d.mismatchCount)
        assertEquals(0L, d.flaggedPaise)
    }

    @Test
    fun only_nomatch_claims_count_as_mismatches() {
        val claims = listOf(
            claim(50_000, VerdictCode.NO_MATCH),
            claim(20_000, VerdictCode.MATCH),
            claim(30_000, VerdictCode.LIKELY_MATCH),
            claim(10_000, VerdictCode.NO_MATCH),
        )
        val d = DigestBuilder.build(listOf(payment(50_000)), claims)
        assertEquals(1, d.confirmedCount)
        assertEquals(2, d.mismatchCount)
        assertEquals(60_000L, d.flaggedPaise)
    }

    @Test
    fun spec_example_five_confirmed_one_mismatch() {
        val payments = (1..5).map { payment(10_000L * it) }
        val d = DigestBuilder.build(payments, listOf(claim(50_000, VerdictCode.NO_MATCH)))
        assertEquals(Digest(5, 150_000, 1, 50_000), d)
    }
}
