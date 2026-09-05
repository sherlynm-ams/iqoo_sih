package com.crosscheck.app.signal

import com.crosscheck.app.FakePaymentRepository
import com.crosscheck.app.data.PaymentSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentIngestorTest {

    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val repo = FakePaymentRepository()
    private val spoken = mutableListOf<Pair<Long, String?>>()
    private val ingestor = PaymentIngestor(repo, { amount, payer -> spoken += amount to payer }, clock = { t0 })

    private fun parsed(amount: Long = 50_000, payer: String? = "MURUGAN", utr: String? = "624912345678") =
        ParsedCredit(amount, payer, utr, "5551234", false, "raw")

    @Test
    fun first_credit_is_inserted_and_spoken() = runTest {
        val r = ingestor.ingest(parsed(), PaymentSource.SMS, t0)
        assertTrue(r is PaymentIngestor.IngestResult.Inserted)
        val row = (r as PaymentIngestor.IngestResult.Inserted).payment
        assertEquals(1L, row.id)
        assertEquals(50_000L, row.amountPaise)
        assertEquals("MURUGAN", row.sender)
        assertEquals("624912345678", row.utr)
        assertEquals(PaymentSource.SMS, row.source)
        assertEquals(t0, row.timestamp)
        assertEquals(listOf(50_000L to "MURUGAN"), spoken)
    }

    @Test
    fun same_utr_is_duplicate_even_hours_later() = runTest {
        ingestor.ingest(parsed(), PaymentSource.SMS, t0)
        val r = ingestor.ingest(parsed(payer = "Murugan S"), PaymentSource.NOTIFICATION, t0 + 5 * 60 * minute)
        assertTrue(r is PaymentIngestor.IngestResult.Duplicate)
        assertEquals(1, repo.rows.size)
        assertEquals(1, spoken.size)
    }

    @Test
    fun no_utr_same_amount_and_payer_within_two_minutes_is_duplicate() = runTest {
        ingestor.ingest(parsed(utr = null), PaymentSource.NOTIFICATION, t0)
        val r = ingestor.ingest(parsed(utr = null, payer = "murugan"), PaymentSource.NOTIFICATION, t0 + 2 * minute)
        assertTrue(r is PaymentIngestor.IngestResult.Duplicate)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun no_utr_same_amount_and_payer_after_two_minutes_is_new() = runTest {
        ingestor.ingest(parsed(utr = null), PaymentSource.NOTIFICATION, t0)
        val r = ingestor.ingest(parsed(utr = null), PaymentSource.NOTIFICATION, t0 + 2 * minute + 1)
        assertTrue(r is PaymentIngestor.IngestResult.Inserted)
        assertEquals(2, repo.rows.size)
    }

    @Test
    fun no_utr_same_amount_different_payer_is_new() = runTest {
        ingestor.ingest(parsed(utr = null), PaymentSource.NOTIFICATION, t0)
        val r = ingestor.ingest(parsed(utr = null, payer = "RAVI"), PaymentSource.NOTIFICATION, t0 + minute)
        assertTrue(r is PaymentIngestor.IngestResult.Inserted)
    }

    @Test
    fun notification_without_utr_then_sms_with_utr_is_one_payment() = runTest {
        ingestor.ingest(parsed(utr = null, payer = "Murugan S"), PaymentSource.NOTIFICATION, t0)
        val r = ingestor.ingest(parsed(utr = "624912345678", payer = "MURUGAN S"), PaymentSource.SMS, t0 + 30_000)
        assertTrue(r is PaymentIngestor.IngestResult.Duplicate)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun different_utrs_same_amount_and_payer_are_two_payments() = runTest {
        ingestor.ingest(parsed(utr = "624912345678"), PaymentSource.SMS, t0)
        val r = ingestor.ingest(parsed(utr = "624912345679"), PaymentSource.SMS, t0 + 10_000)
        assertTrue(r is PaymentIngestor.IngestResult.Inserted)
        assertEquals(2, repo.rows.size)
        assertEquals(2, spoken.size)
    }

    @Test
    fun raw_signal_that_is_not_a_credit_is_ignored() = runTest {
        val r = ingestor.ingestRaw(RawSignal(PaymentSource.SMS, "5551234", null, "Your OTP is 123456", t0))
        assertEquals(PaymentIngestor.IngestResult.NotACredit, r)
        assertTrue(repo.rows.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun raw_signal_end_to_end_uses_source_and_derives_trust() = runTest {
        val r = ingestor.ingestRaw(
            RawSignal(
                PaymentSource.SMS,
                "VK-SBIINB",
                null,
                "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 624912345678 from MURUGAN. -SBI",
                t0,
            ),
        )
        val row = (r as PaymentIngestor.IngestResult.Inserted).payment
        assertEquals(PaymentSource.SMS, row.source)
        assertEquals("624912345678", row.utr)
        assertEquals(t0, row.timestamp)
    }

    @Test
    fun null_payer_is_stored_as_empty_sender_and_spoken_as_null() = runTest {
        val r = ingestor.ingest(parsed(payer = null), PaymentSource.SMS, t0)
        assertEquals("", (r as PaymentIngestor.IngestResult.Inserted).payment.sender)
        assertEquals(listOf(50_000L to null), spoken)
    }
}
