package com.crosscheck.app.signal

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {

    @Test
    fun big_text_is_preferred_over_text() {
        assertEquals("HDFC Bank\nlong body", NotificationText.compose("HDFC Bank", "short", "long body"))
    }

    @Test
    fun text_is_used_when_big_text_is_null_or_blank() {
        // `cmd notification post -S bigtext` leaves EXTRA_BIG_TEXT null and puts the body in EXTRA_TEXT.
        assertEquals("HDFC Bank\nRs.500.00 credited", NotificationText.compose("HDFC Bank", "Rs.500.00 credited", null))
        assertEquals("HDFC Bank\nRs.500.00 credited", NotificationText.compose("HDFC Bank", "Rs.500.00 credited", "   "))
    }

    @Test
    fun title_only_and_body_only() {
        assertEquals("Murugan paid you ₹500", NotificationText.compose("Murugan paid you ₹500", null, null))
        assertEquals("UPI transaction ID: 426112345696", NotificationText.compose(null, "UPI transaction ID: 426112345696", null))
        assertEquals("", NotificationText.compose(null, null, null))
        assertEquals("", NotificationText.compose(" ", "", null))
    }

    @Test
    fun composed_text_parses_like_the_corpus_notification_entries() {
        val p = BankSmsParser.parse(
            NotificationText.compose("Murugan paid you ₹500", "UPI transaction ID: 426112345696", null),
            "com.google.android.apps.nbu.paisa.user",
            senderTrusted = true,
        )
        assertEquals(50_000L, p?.amountPaise)
        assertEquals("426112345696", p?.utr)
        assertEquals("Murugan", p?.payer)
    }
}
