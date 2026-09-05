package com.crosscheck.app.verify

import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcilerTest {

    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val reconciler = Reconciler() // 10 min tolerance, 24 h hard skew

    private fun payment(
        id: Long = 1,
        amountPaise: Long = 50_000,
        utr: String? = "624912345678",
        timestamp: Long = t0,
    ) = PaymentEntity(id, amountPaise, "MURUGAN", utr, timestamp, PaymentSource.SMS, "raw")

    private fun claim(
        amountPaise: Long = 50_000,
        utr: String? = "624912345678",
        ts: Long? = t0,
        status: ClaimStatus = ClaimStatus.SUCCESS,
    ) = Claim(amountPaise, "seller@upi", utr, ts, status = status)

    @Test
    fun exact_amount_and_utr_is_match() {
        val r = reconciler.reconcile(claim(), listOf(payment()))
        assertEquals(Verdict.Match, r.verdict)
        assertEquals(1L, r.matchedPayment?.id)
    }

    @Test
    fun exact_utr_wins_even_when_timestamp_is_far() {
        val r = reconciler.reconcile(claim(ts = t0 + 3 * 60 * minute), listOf(payment()))
        assertEquals(Verdict.Match, r.verdict)
    }

    @Test
    fun utr_normalisation_ignores_non_digits() {
        val r = reconciler.reconcile(claim(utr = "UTR 6249-1234-5678"), listOf(payment()))
        assertEquals(Verdict.Match, r.verdict)
    }

    @Test
    fun partial_utr_eight_digit_suffix_is_likely_match() {
        val r = reconciler.reconcile(claim(utr = "12345678"), listOf(payment()))
        assertEquals(Verdict.LikelyMatch(Reason.WRONG_UTR), r.verdict)
        assertEquals(1L, r.matchedPayment?.id)
    }

    @Test
    fun partial_utr_prefix_is_likely_match() {
        val r = reconciler.reconcile(claim(utr = "6249123456"), listOf(payment()))
        assertEquals(Verdict.LikelyMatch(Reason.WRONG_UTR), r.verdict)
    }

    @Test
    fun partial_utr_below_eight_digits_is_no_match_wrong_utr() {
        val r = reconciler.reconcile(claim(utr = "5678"), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.WRONG_UTR), r.verdict)
    }

    @Test
    fun different_utr_same_amount_is_no_match_wrong_utr() {
        val r = reconciler.reconcile(claim(utr = "999999999999"), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.WRONG_UTR), r.verdict)
    }

    @Test
    fun missing_claim_utr_within_tolerance_is_likely_match() {
        val r = reconciler.reconcile(claim(utr = null, ts = t0 + 10 * minute), listOf(payment()))
        assertEquals(Verdict.LikelyMatch(Reason.WRONG_UTR), r.verdict)
    }

    @Test
    fun missing_claim_utr_just_outside_tolerance_is_likely_timestamp_mismatch() {
        val r = reconciler.reconcile(claim(utr = null, ts = t0 + 10 * minute + 1), listOf(payment()))
        assertEquals(Verdict.LikelyMatch(Reason.TIMESTAMP_MISMATCH), r.verdict)
    }

    @Test
    fun missing_claim_utr_beyond_hard_skew_is_no_match_timestamp() {
        val r = reconciler.reconcile(claim(utr = null, ts = t0 + 25 * 60 * minute), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.TIMESTAMP_MISMATCH), r.verdict)
    }

    @Test
    fun missing_payment_utr_and_no_timestamp_is_likely_match() {
        val r = reconciler.reconcile(claim(ts = null), listOf(payment(utr = null)))
        assertEquals(Verdict.LikelyMatch(Reason.WRONG_UTR), r.verdict)
    }

    @Test
    fun utr_matches_but_amount_differs_is_wrong_amount() {
        val r = reconciler.reconcile(claim(amountPaise = 60_000), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.WRONG_AMOUNT), r.verdict)
        assertEquals(1L, r.matchedPayment?.id)
    }

    @Test
    fun partial_utr_but_amount_differs_is_wrong_amount() {
        val r = reconciler.reconcile(claim(amountPaise = 60_000, utr = "912345678"), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.WRONG_AMOUNT), r.verdict)
    }

    @Test
    fun nothing_related_is_nothing_received() {
        val r = reconciler.reconcile(claim(amountPaise = 60_000, utr = "111111111111"), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.NOTHING_RECEIVED), r.verdict)
        assertNull(r.matchedPayment)
    }

    @Test
    fun empty_candidates_is_nothing_received() {
        val r = reconciler.reconcile(claim(), emptyList())
        assertEquals(Verdict.NoMatch(Reason.NOTHING_RECEIVED), r.verdict)
    }

    @Test
    fun pending_status_is_always_no_match_pending() {
        val r = reconciler.reconcile(claim(status = ClaimStatus.PENDING), listOf(payment()))
        assertEquals(Verdict.NoMatch(Reason.PENDING), r.verdict)
        assertNull(r.matchedPayment)
    }

    @Test
    fun best_candidate_wins_across_tiers() {
        val candidates = listOf(
            payment(id = 1, amountPaise = 60_000, utr = "624912345678"), // wrong amount
            payment(id = 2, amountPaise = 50_000, utr = "111111111111"), // wrong utr
            payment(id = 3, amountPaise = 50_000, utr = "624912345678"), // exact
        )
        val r = reconciler.reconcile(claim(), candidates)
        assertEquals(Verdict.Match, r.verdict)
        assertEquals(3L, r.matchedPayment?.id)
    }

    @Test
    fun most_specific_no_match_reason_wins() {
        val candidates = listOf(
            payment(id = 1, amountPaise = 70_000, utr = "222222222222"), // unrelated
            payment(id = 2, amountPaise = 60_000, utr = "624912345678"), // utr hit, wrong amount
        )
        val r = reconciler.reconcile(claim(), candidates)
        assertEquals(Verdict.NoMatch(Reason.WRONG_AMOUNT), r.verdict)
        assertEquals(2L, r.matchedPayment?.id)
    }

    @Test
    fun closest_in_time_wins_inside_a_tier() {
        val candidates = listOf(
            payment(id = 1, utr = null, timestamp = t0 - 8 * minute),
            payment(id = 2, utr = null, timestamp = t0 - 1 * minute),
        )
        val r = reconciler.reconcile(claim(utr = null), candidates)
        assertEquals(Verdict.LikelyMatch(Reason.WRONG_UTR), r.verdict)
        assertEquals(2L, r.matchedPayment?.id)
    }

    @Test
    fun custom_tolerance_is_respected() {
        val strict = Reconciler(toleranceMillis = 2 * minute)
        val r = strict.reconcile(claim(utr = null, ts = t0 + 5 * minute), listOf(payment()))
        assertEquals(Verdict.LikelyMatch(Reason.TIMESTAMP_MISMATCH), r.verdict)
    }

    @Test
    fun utr_helpers() {
        assertEquals("624912345678", Utr.normalise(" 6249 1234 5678 "))
        assertNull(Utr.normalise("abc"))
        assertNull(Utr.normalise(null))
        assertTrue(Utr.isPartialMatch("624912345678", "12345678"))
        assertTrue(Utr.isPartialMatch("62491234", "624912345678"))
        assertFalse(Utr.isPartialMatch("624912345678", "1234567"))
        assertFalse(Utr.isPartialMatch("624912345678", "624912345678"))
        assertFalse(Utr.isPartialMatch("624912345678", "49123456"))
    }
}
