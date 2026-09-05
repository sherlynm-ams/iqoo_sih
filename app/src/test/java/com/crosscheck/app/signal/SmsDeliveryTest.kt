package com.crosscheck.app.signal

import com.crosscheck.app.FakePaymentRepository
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDeliveryTest {

    private val t0 = 1_800_000_000_000L
    private val repo = FakePaymentRepository()
    private val spokenDirectly = mutableListOf<Long>()
    private val ingestor = PaymentIngestor(repo, { amount, _ -> spokenDirectly += amount }, clock = { t0 })
    private val delivery = SmsDelivery(ingestor)

    private val credit = "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 624912345678 from MURUGAN. -SBI"

    @Test
    fun inserted_payment_is_reported_and_not_spoken_by_the_ingestor() = runTest {
        val handed = mutableListOf<PaymentEntity>()
        val out = delivery.deliver(listOf(SmsDelivery.Part("VM-SBIINB", credit)), t0) { handed += it }
        assertEquals(1, out.size)
        assertTrue(out[0].result is PaymentIngestor.IngestResult.Inserted)
        assertEquals("VM-SBIINB", out[0].address)
        assertEquals(1, handed.size)
        assertEquals(50_000L, handed[0].amountPaise)
        assertEquals("MURUGAN", handed[0].sender)
        assertEquals(PaymentSource.SMS, handed[0].source)
        assertTrue("SMS path must not speak inside the receiver", spokenDirectly.isEmpty())
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun multipart_parts_from_one_address_are_concatenated_before_parsing() = runTest {
        val handed = mutableListOf<PaymentEntity>()
        val (a, b) = credit.substring(0, 40) to credit.substring(40)
        val out = delivery.deliver(listOf(SmsDelivery.Part("5551234", a), SmsDelivery.Part("5551234", b)), t0) { handed += it }
        assertEquals(1, out.size)
        assertEquals(credit, out[0].body)
        assertEquals(1, handed.size)
        assertEquals("624912345678", handed[0].utr)
    }

    @Test
    fun non_credit_and_duplicate_do_not_reach_the_callback() = runTest {
        val handed = mutableListOf<PaymentEntity>()
        delivery.deliver(listOf(SmsDelivery.Part("VM-SBIINB", credit)), t0) { handed += it }
        val out = delivery.deliver(
            listOf(SmsDelivery.Part("VM-SBIINB", credit), SmsDelivery.Part("AD-HDFCBK-T", "Your OTP for UPI registration is 739201.")),
            t0 + 1000,
        ) { handed += it }
        assertEquals(2, out.size)
        assertTrue(out.first { it.address == "VM-SBIINB" }.result is PaymentIngestor.IngestResult.Duplicate)
        assertEquals(PaymentIngestor.IngestResult.NotACredit, out.first { it.address == "AD-HDFCBK-T" }.result)
        assertEquals(1, handed.size)
        assertEquals(1, repo.rows.size)
    }

    @Test
    fun blank_parts_are_skipped() = runTest {
        val out = delivery.deliver(listOf(SmsDelivery.Part(null, ""), SmsDelivery.Part("x", null)), t0) { }
        assertTrue(out.isEmpty())
    }
}
