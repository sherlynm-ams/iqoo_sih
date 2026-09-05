package com.crosscheck.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryParserTest {

    private fun didPay(text: String): Query.DidPay {
        val q = QueryParser.parse(text)
        assertTrue("expected DidPay for \"$text\" but got $q", q is Query.DidPay)
        return q as Query.DidPay
    }

    // ---- English

    @Test
    fun english_name_amount_today() {
        val q = didPay("did Murugan pay 500 today")
        assertEquals("Murugan", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun english_question_mark_and_lowercase_name() {
        val q = didPay("Did murugan pay 500 today?")
        assertEquals("murugan", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun english_number_words_five_hundred() {
        val q = didPay("has murugan paid five hundred")
        assertEquals("murugan", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun english_number_words_thousands() {
        val q = didPay("did Kumar pay one thousand two hundred fifty today")
        assertEquals("Kumar", q.name)
        assertEquals(125_000L, q.amountPaise)
    }

    @Test
    fun english_number_words_fifteen_hundred_and_two_thousand() {
        assertEquals(150_000L, didPay("did Kumar pay fifteen hundred").amountPaise)
        assertEquals(200_000L, didPay("did Kumar pay two thousand").amountPaise)
        assertEquals(250_000L, didPay("did Kumar pay 2 thousand five hundred").amountPaise)
    }

    @Test
    fun english_number_words_with_and() {
        assertEquals(105_000L, didPay("did Kumar pay one thousand and fifty").amountPaise)
    }

    @Test
    fun amount_with_indian_grouping_comma() {
        val q = didPay("did Murugan pay 1,250 today")
        assertEquals(125_000L, q.amountPaise)
        assertEquals("Murugan", q.name)
    }

    @Test
    fun amount_with_rupee_symbol_glued() {
        assertEquals(50_000L, didPay("did Murugan pay ₹500 today").amountPaise)
    }

    @Test
    fun amount_with_rs_prefix_variants() {
        assertEquals(50_000L, didPay("did Murugan pay Rs 500 today").amountPaise)
        assertEquals(50_000L, didPay("did Murugan pay Rs.500 today").amountPaise)
        assertEquals(50_000L, didPay("did Murugan pay 500 rupees today").amountPaise)
        assertEquals("Murugan", didPay("did Murugan pay Rs.500 today").name)
    }

    @Test
    fun amount_with_paise_decimal() {
        assertEquals(50_050L, didPay("did Murugan pay 500.50 today").amountPaise)
    }

    @Test
    fun two_word_name_is_kept_in_order() {
        val q = didPay("did Murugan Selvam pay 500 today")
        assertEquals("Murugan Selvam", q.name)
    }

    @Test
    fun name_only_without_amount() {
        val q = didPay("did Murugan pay today")
        assertEquals("Murugan", q.name)
        assertNull(q.amountPaise)
    }

    @Test
    fun how_much_did_name_pay_is_name_only() {
        val q = didPay("how much did murugan pay today")
        assertEquals("murugan", q.name)
        assertNull(q.amountPaise)
    }

    @Test
    fun amount_only_without_name() {
        val q = didPay("did anyone pay 500 today")
        assertNull(q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun possessive_and_fillers_are_stripped() {
        val q = didPay("hey please check if Murugan's 500 payment came today")
        assertEquals("Murugan", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun twelve_digit_utr_is_not_an_amount() {
        val q = didPay("did Murugan pay 500 utr 624912345678")
        assertEquals(50_000L, q.amountPaise)
        assertEquals("Murugan utr", q.name) // "utr" is an unknown token; harmless for contains-matching
    }

    // ---- Tamil / Tanglish

    @Test
    fun tanglish_name_amount_vandhucha() {
        val q = didPay("Murugan 500 vandhucha")
        assertEquals("Murugan", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun tamil_script_name_amount() {
        val q = didPay("முருகன் 500 கொடுத்தாரா")
        assertEquals("முருகன்", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun tamil_number_words() {
        assertEquals(50_000L, didPay("முருகன் ஐந்து நூறு கொடுத்தாரா").amountPaise)
        assertEquals(100_000L, didPay("Murugan aayiram koduthara").amountPaise)
    }

    @Test
    fun tamil_digest() {
        assertEquals(Query.DigestToday, QueryParser.parse("இன்று எப்படி"))
        assertEquals(Query.DigestToday, QueryParser.parse("innaiku eppadi"))
    }

    // ---- Hindi

    @Test
    fun hindi_latin_name_amount() {
        val q = didPay("murugan ne 500 diya kya")
        assertEquals("murugan", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun hindi_script_name_amount() {
        val q = didPay("मुरुगन ने 500 दिया क्या")
        assertEquals("मुरुगन", q.name)
        assertEquals(50_000L, q.amountPaise)
    }

    @Test
    fun hindi_number_words() {
        assertEquals(50_000L, didPay("murugan ne paanch sau diye").amountPaise)
        assertEquals(200_000L, didPay("murugan ne do hazaar diye").amountPaise)
    }

    @Test
    fun hindi_digest() {
        assertEquals(Query.DigestToday, QueryParser.parse("aaj kaisa raha"))
        assertEquals(Query.DigestToday, QueryParser.parse("आज कैसा रहा"))
    }

    // ---- digest and unknown

    @Test
    fun english_digest_variants() {
        assertEquals(Query.DigestToday, QueryParser.parse("how was today"))
        assertEquals(Query.DigestToday, QueryParser.parse("How was today?"))
        assertEquals(Query.DigestToday, QueryParser.parse("today's summary"))
        assertEquals(Query.DigestToday, QueryParser.parse("what's the total today"))
    }

    @Test
    fun digest_cue_with_a_name_is_still_a_payment_question() {
        val q = didPay("how much did Kumar pay")
        assertEquals("Kumar", q.name)
    }

    @Test
    fun empty_or_filler_only_is_unknown() {
        assertEquals(Query.Unknown, QueryParser.parse(""))
        assertEquals(Query.Unknown, QueryParser.parse("   "))
        assertEquals(Query.Unknown, QueryParser.parse("hello there please"))
    }

    @Test
    fun pay_cue_without_name_or_amount_is_didpay_with_nothing() {
        assertEquals(Query.DidPay(null, null), QueryParser.parse("did anyone pay today"))
    }

    @Test
    fun zero_amount_is_ignored() {
        val q = didPay("did Murugan pay zero today")
        assertNull(q.amountPaise)
        assertEquals("Murugan", q.name)
    }
}
