package com.crosscheck.app.voice

import com.crosscheck.app.R
import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.data.VerdictCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** English templates mirroring values/strings.xml so phrasing is checked without Android resources. */
class EnglishStrings : StringProvider {
    private val strings = mapOf(
        R.string.voice_answer_yes to "Yes — %1\$s received from %2\$s at %3\$s.",
        R.string.voice_answer_no_name to "No — nothing from %1\$s today.",
        R.string.voice_answer_no_amount to "No — no payment of %1\$s today.",
        R.string.voice_answer_different to "%1\$s paid %2\$s today, not %3\$s.",
        R.string.voice_answer_paid_list to "%1\$s paid %2\$s today.",
        R.string.voice_answer_unknown to "Sorry, I did not understand. Try: did Murugan pay 500 today.",
        R.string.voice_digest to "%1\$s, %2\$s, %3\$s flagged today.",
        R.string.tts_unknown_sender to "an unknown sender",
    )
    private val plurals = mapOf(
        R.plurals.voice_digest_payments to ("%d payment confirmed" to "%d payments confirmed"),
        R.plurals.voice_digest_mismatches to ("%d mismatch" to "%d mismatches"),
    )

    override fun get(resId: Int, vararg args: Any): String = String.format(strings.getValue(resId), *args)
    override fun quantity(resId: Int, quantity: Int, vararg args: Any): String {
        val (one, other) = plurals.getValue(resId)
        return String.format(if (quantity == 1) one else other, *args)
    }
}

class QueryAnswererTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val answerer = QueryAnswerer(EnglishStrings(), zone)

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 9, 6, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun payment(amount: Long, sender: String, ts: Long, id: Long = 0) =
        PaymentEntity(id, amount, sender, "624912345678", ts, PaymentSource.SMS, "raw")

    private fun noMatch(amount: Long) =
        ClaimEntity(0, amount, null, null, null, VerdictCode.NO_MATCH, "NOTHING_RECEIVED", at(13, 0), "ocr", null, null, null, null)

    private val murugan500 = payment(50_000, "MURUGAN", at(12, 41))
    private val kumar750 = payment(75_000, "KUMAR", at(12, 55))

    @Test
    fun yes_when_name_and_amount_match() {
        val s = answerer.answer(Query.DidPay("Murugan", 50_000), listOf(murugan500, kumar750), emptyList())
        assertTrue(s.answer is Answer.Yes)
        assertEquals("Yes — ₹500 received from MURUGAN at 12:41.", s.text)
    }

    @Test
    fun name_match_is_case_insensitive_contains() {
        val rows = listOf(payment(50_000, "Murugan S Kumar", at(9, 5)))
        assertTrue(answerer.decide(Query.DidPay("murugan", 50_000), rows, emptyList()) is Answer.Yes)
        assertTrue(answerer.decide(Query.DidPay("MURUGAN KUMAR", 50_000), rows, emptyList()) is Answer.Yes)
        assertTrue(answerer.decide(Query.DidPay("Murugan S", 50_000), rows, emptyList()) is Answer.Yes)
    }

    @Test
    fun no_when_name_absent_today() {
        val s = answerer.answer(Query.DidPay("Ravi", 50_000), listOf(murugan500, kumar750), emptyList())
        assertEquals(Answer.NothingFromName("RAVI"), s.answer)
        assertEquals("No — nothing from RAVI today.", s.text)
    }

    @Test
    fun different_amount_says_what_was_received() {
        val s = answerer.answer(Query.DidPay("Murugan", 20_000), listOf(murugan500), emptyList())
        assertTrue(s.answer is Answer.DifferentAmount)
        assertEquals("MURUGAN paid ₹500 today, not ₹200.", s.text)
    }

    @Test
    fun different_amount_lists_every_payment_from_that_name() {
        val rows = listOf(payment(20_000, "MURUGAN", at(10, 0)), payment(30_000, "MURUGAN", at(11, 0)))
        val s = answerer.answer(Query.DidPay("Murugan", 50_000), rows, emptyList())
        assertEquals("MURUGAN paid ₹300, ₹200 today, not ₹500.", s.text)
    }

    @Test
    fun name_only_single_payment_is_yes() {
        val s = answerer.answer(Query.DidPay("Kumar", null), listOf(murugan500, kumar750), emptyList())
        assertEquals("Yes — ₹750 received from KUMAR at 12:55.", s.text)
    }

    @Test
    fun name_only_multiple_payments_lists_them() {
        val rows = listOf(payment(20_000, "MURUGAN", at(10, 0)), payment(30_000, "MURUGAN", at(11, 0)))
        val s = answerer.answer(Query.DidPay("murugan", null), rows, emptyList())
        assertTrue(s.answer is Answer.PaidList)
        assertEquals("MURUGAN paid ₹300, ₹200 today.", s.text)
    }

    @Test
    fun amount_only_found_and_not_found() {
        val yes = answerer.answer(Query.DidPay(null, 75_000), listOf(murugan500, kumar750), emptyList())
        assertEquals("Yes — ₹750 received from KUMAR at 12:55.", yes.text)
        val no = answerer.answer(Query.DidPay(null, 10_000), listOf(murugan500, kumar750), emptyList())
        assertEquals(Answer.NothingOfAmount(10_000), no.answer)
        assertEquals("No — no payment of ₹100 today.", no.text)
    }

    @Test
    fun amount_match_is_exact_paise() {
        val rows = listOf(payment(50_050, "MURUGAN", at(12, 41)))
        assertTrue(answerer.decide(Query.DidPay("Murugan", 50_000), rows, emptyList()) is Answer.DifferentAmount)
        assertTrue(answerer.decide(Query.DidPay("Murugan", 50_050), rows, emptyList()) is Answer.Yes)
    }

    @Test
    fun latest_matching_payment_is_the_one_reported() {
        val rows = listOf(payment(50_000, "MURUGAN", at(9, 0)), payment(50_000, "MURUGAN", at(17, 30)))
        val s = answerer.answer(Query.DidPay("Murugan", 50_000), rows, emptyList())
        assertEquals("Yes — ₹500 received from MURUGAN at 17:30.", s.text)
    }

    @Test
    fun blank_sender_is_spoken_as_unknown() {
        val s = answerer.answer(Query.DidPay(null, 50_000), listOf(payment(50_000, "", at(8, 0))), emptyList())
        assertEquals("Yes — ₹500 received from an unknown sender at 08:00.", s.text)
    }

    @Test
    fun digest_sentence_matches_spec_example() {
        val payments = (1..5).map { payment(10_000L * it, "P$it", at(10, it)) }
        val s = answerer.answer(Query.DigestToday, payments, listOf(noMatch(50_000)))
        assertEquals(Answer.DigestAnswer(Digest(5, 150_000, 1, 50_000)), s.answer)
        assertEquals("5 payments confirmed, 1 mismatch, ₹500 flagged today.", s.text)
    }

    @Test
    fun digest_plurals_and_empty_day() {
        assertEquals(
            "0 payments confirmed, 0 mismatches, ₹0 flagged today.",
            answerer.answer(Query.DigestToday, emptyList(), emptyList()).text,
        )
        assertEquals(
            "1 payment confirmed, 2 mismatches, ₹1,250 flagged today.",
            answerer.answer(Query.DigestToday, listOf(murugan500), listOf(noMatch(50_000), noMatch(75_000))).text,
        )
    }

    @Test
    fun unknown_query_and_empty_didpay_are_not_understood() {
        assertEquals(Answer.NotUnderstood, answerer.decide(Query.Unknown, listOf(murugan500), emptyList()))
        assertEquals(Answer.NotUnderstood, answerer.decide(Query.DidPay(null, null), listOf(murugan500), emptyList()))
        assertEquals("Sorry, I did not understand. Try: did Murugan pay 500 today.", answerer.answer(Query.Unknown, emptyList(), emptyList()).text)
    }

    @Test
    fun name_normalisation_is_script_neutral() {
        assertEquals("MURUGAN S", QueryAnswerer.normaliseName("  murugan-s. "))
        assertEquals("முருகன்", QueryAnswerer.normaliseName("முருகன்"))
        assertTrue(QueryAnswerer.senderMatches("முருகன்", QueryAnswerer.normaliseName("முருகன்")))
        assertTrue(!QueryAnswerer.senderMatches("KUMAR", "MURUGAN"))
    }
}
