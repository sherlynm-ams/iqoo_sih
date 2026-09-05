package com.crosscheck.app.verify

import com.crosscheck.app.FakeClaimRepository
import com.crosscheck.app.FakeFlaggedVpaRepository
import com.crosscheck.app.FakePaymentRepository
import com.crosscheck.app.data.PayerKeyKind
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.data.VerdictCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationServiceTest {

    private val t0 = 1_800_000_000_000L
    private val payments = FakePaymentRepository()
    private val claims = FakeClaimRepository()
    private val flagged = FakeFlaggedVpaRepository()
    private val service = VerificationService(payments, claims, flagged, Reconciler(), clock = { t0 })

    private suspend fun seed(amount: Long = 50_000, utr: String? = "624912345678", ts: Long = t0 - 60_000) =
        payments.insert(PaymentEntity(0, amount, "MURUGAN", utr, ts, PaymentSource.SMS, "raw"))

    @Test
    fun match_writes_receipt_and_does_not_flag() = runTest {
        seed()
        val out = service.verify(Claim(50_000, "seller@upi", "624912345678", t0, payerName = "Murugan S"), "ocr")
        assertEquals(Verdict.Match, out.verdict)
        assertEquals(0, out.priorFailures)
        assertEquals(1, claims.rows.size)
        val receipt = claims.rows.single()
        assertEquals(VerdictCode.MATCH, receipt.verdict)
        assertNull(receipt.reason)
        assertEquals("ocr", receipt.extractorBackend)
        assertEquals("seller@upi", receipt.upiId)
        assertEquals("Murugan S", receipt.payerName)
        assertEquals("MURUGAN S", receipt.payerKey)
        assertEquals(PayerKeyKind.NAME, receipt.keyKind)
        assertEquals(t0, receipt.createdAt)
        assertTrue(flagged.rows.isEmpty())
    }

    @Test
    fun no_match_increments_payer_key_and_reports_prior_count() = runTest {
        val claim = Claim(50_000, "seller@upi", "111111111111", t0, payerName = " murugan   s ")
        val first = service.verify(claim, "ocr")
        assertEquals(Verdict.NoMatch(Reason.NOTHING_RECEIVED), first.verdict)
        assertEquals(0, first.priorFailures)
        assertEquals(1, flagged.rows["MURUGAN S"]?.failCount)

        val second = service.verify(claim, "ocr")
        assertEquals(1, second.priorFailures)
        assertEquals(2, flagged.rows["MURUGAN S"]?.failCount)
        assertEquals(t0, flagged.rows["MURUGAN S"]?.lastFailedAt)
        assertEquals(PayerKeyKind.NAME, flagged.rows["MURUGAN S"]?.keyKind)
        assertEquals(2, claims.rows.size)
        assertEquals("NOTHING_RECEIVED", claims.rows.last().reason)
    }

    @Test
    fun payer_key_prefers_vpa_then_name_then_bank_mask() {
        assertEquals(
            VerificationService.PayerKey("murugan@ybl", PayerKeyKind.VPA),
            VerificationService.payerKeyOf(Claim(1, null, null, null, payerName = "X", payerBankMask = "SBI-XX1", payerVpa = " Murugan@YBL ")),
        )
        assertEquals(
            VerificationService.PayerKey("MURUGAN S", PayerKeyKind.NAME),
            VerificationService.payerKeyOf(Claim(1, null, null, null, payerName = "murugan  s", payerBankMask = "SBI-XX1")),
        )
        assertEquals(
            VerificationService.PayerKey("SBI-XX4321", PayerKeyKind.BANK_MASK),
            VerificationService.payerKeyOf(Claim(1, null, null, null, payerBankMask = "sbi-xx4321")),
        )
        assertNull(VerificationService.payerKeyOf(Claim(1, "seller@upi", null, null)))
    }

    @Test
    fun unattributable_no_match_is_not_flagged_but_still_receipted() = runTest {
        val out = service.verify(Claim(50_000, "seller@upi", null, t0), "vlm")
        assertTrue(out.verdict is Verdict.NoMatch)
        assertNull(out.payerKey)
        assertTrue(flagged.rows.isEmpty())
        assertEquals(1, claims.rows.size)
        assertNull(claims.rows.single().payerKey)
    }

    @Test
    fun pending_claim_is_no_match_pending_and_flagged() = runTest {
        seed()
        val out = service.verify(
            Claim(50_000, "seller@upi", "624912345678", t0, payerBankMask = "SBI-XX4321", status = ClaimStatus.PENDING),
            "ocr",
        )
        assertEquals(Verdict.NoMatch(Reason.PENDING), out.verdict)
        assertEquals("PENDING", claims.rows.single().reason)
        assertEquals(1, flagged.rows["SBI-XX4321"]?.failCount)
        assertEquals(PayerKeyKind.BANK_MASK, flagged.rows["SBI-XX4321"]?.keyKind)
    }

    @Test
    fun utr_hit_outside_the_candidate_window_is_still_found() = runTest {
        seed(ts = t0 - 3 * 24 * 60 * 60_000L)
        val out = service.verify(Claim(50_000, null, "624912345678", t0), "ocr")
        assertEquals(Verdict.Match, out.verdict)
    }

    @Test
    fun likely_match_does_not_flag() = runTest {
        seed(utr = null)
        val out = service.verify(Claim(50_000, null, null, t0, payerName = "Murugan"), "ocr")
        assertEquals(Verdict.LikelyMatch(Reason.WRONG_UTR), out.verdict)
        assertTrue(flagged.rows.isEmpty())
        assertEquals(VerdictCode.LIKELY_MATCH, claims.rows.single().verdict)
        assertEquals("WRONG_UTR", claims.rows.single().reason)
    }
}
