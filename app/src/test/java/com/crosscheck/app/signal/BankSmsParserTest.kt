package com.crosscheck.app.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankSmsParserTest {

    private fun credit(body: String, sender: String? = null): ParsedCredit {
        val parsed = BankSmsParser.parse(body, sender)
        assertNotNull("expected a credit for: $body", parsed)
        return parsed!!
    }

    // ---- positive templates: banks ----------------------------------------------------

    @Test
    fun sbi_spec_recipe() {
        val p = credit("Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI", "5551234")
        assertEquals(50_000L, p.amountPaise)
        assertEquals("426112345678", p.utr)
        assertEquals("MURUGAN", p.payer)
        assertEquals("5551234", p.senderId)
        assertFalse(p.senderTrusted)
        assertTrue(p.raw.startsWith("Rs.500.00"))
    }

    @Test
    fun sbi_dear_user_variant() {
        val p = credit(
            "Dear SBI User, your A/c X1234-credited by Rs.1500.00 on 06Sep26 transfer from MURUGAN S Ref No 624912345678 -SBI",
            "VK-SBIINB",
        )
        assertEquals(150_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN S", p.payer)
        assertTrue(p.senderTrusted)
    }

    @Test
    fun hdfc_vpa_linked_account() {
        val p = credit(
            "Rs. 2,500.00 credited to a/c XXXXXX1234 on 06-09-26 by a/c linked to VPA murugan.s@okaxis (UPI Ref No 624912345678). -HDFC Bank",
            "AD-HDFCBK-S",
        )
        assertEquals(250_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("murugan.s@okaxis", p.payer)
        assertTrue(p.senderTrusted)
    }

    @Test
    fun icici_credited_with() {
        val p = credit(
            "ICICI Bank Acct XX123 credited with Rs 1,000.00 on 06-Sep-26 from Murugan S. UPI:624912345678 - ICICI Bank.",
            "JD-ICICIB",
        )
        assertEquals(100_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("Murugan S", p.payer)
    }

    @Test
    fun axis_upi_p2m_with_balance() {
        val p = credit(
            "INR 750.00 credited to A/c no. XX1234 on 06-09-26 at 10:30:15 IST. Info- UPI/P2M/624912345678/MURUGAN. Avl Bal- INR 5000.00 - Axis Bank",
            "AX-AXISBK",
        )
        assertEquals(75_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN", p.payer)
    }

    @Test
    fun kotak_received_in_account() {
        val p = credit("Received Rs.300.00 in your Kotak Bank AC X1234 from murugan@ybl on 06-09-26.UPI Ref:624912345678.", "KOTAKB")
        assertEquals(30_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("murugan@ybl", p.payer)
    }

    @Test
    fun pnb_credited_inr_through_upi() {
        val p = credit(
            "Your A/c XXXX1234 Credited INR 1,200.00 on 06/09/26 through UPI Ref 624912345678 by MURUGAN. Bal INR 8000.00 -PNB",
            "VM-PNBSMS",
        )
        assertEquals(120_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN", p.payer)
    }

    @Test
    fun bank_of_baroda_upi_cr_slashes() {
        val p = credit(
            "Rs.450.00 Credited to A/c ...1234 thru UPI/CR/624912345678/MURUGAN/YESB/murugan@ybl. Total Bal:Rs.6000.00CR. Avl Amt:Rs.6000.00 (06-09-2026 10:30:00) - Bank of Baroda",
            "BOBTXN",
        )
        assertEquals(45_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN", p.payer)
    }

    @Test
    fun canara_amount_has_been_credited() {
        val p = credit(
            "An amount of INR 999.00 has been CREDITED to your account XXX1234 on 06/09/2026 by UPI ref no. 624912345678 from MURUGAN. Total Avail.bal INR 7000.00 - Canara Bank",
            "CANBNK",
        )
        assertEquals(99_900L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN", p.payer)
    }

    @Test
    fun indusind_towards_upi_slash_vpa() {
        val p = credit(
            "Your A/c XX1234 is credited by INR 620.00 on 06-SEP-26 towards UPI/624912345678/murugan@okicici. Avl Bal INR 3000.00 -IndusInd Bank",
            "INDUSB",
        )
        assertEquals(62_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("murugan@okicici", p.payer)
    }

    @Test
    fun yes_bank_by_vpa_ref_no() {
        val p = credit(
            "Rs 150.00 credited to your A/c XX1234 via UPI on 06-Sep-2026 by VPA murugan@okhdfcbank. Ref No 624912345678. YES BANK",
            "YESBNK",
        )
        assertEquals(15_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("murugan@okhdfcbank", p.payer)
    }

    @Test
    fun federal_rrn() {
        val p = credit("Rs 800.00 credited to your A/c XX1234 on 06SEP26 by UPI RRN 624912345678 from MURUGAN S -Federal Bank", "FEDBNK")
        assertEquals(80_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN S", p.payer)
    }

    @Test
    fun paytm_payments_bank() {
        val p = credit("Rs.250 received in your Paytm Payments Bank A/c XX1234 from murugan@paytm. UPI Ref: 624912345678. -PPBL", "PYTMPB")
        assertEquals(25_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("murugan@paytm", p.payer)
    }

    @Test
    fun indian_lakh_grouping() {
        val p = credit("INR 1,50,000.00 credited to A/c XX9876 on 06-09-26 by UPI Ref No 624912345678 from RAVI KUMAR. -SBI")
        assertEquals(15_000_000L, p.amountPaise)
        assertEquals("RAVI KUMAR", p.payer)
    }

    @Test
    fun rupee_symbol_no_decimals() {
        val p = credit("₹500 credited to your account XX1234 via UPI from Murugan S. Ref no 624912345678")
        assertEquals(50_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("Murugan S", p.payer)
    }

    @Test
    fun rs_with_space_and_no_paise() {
        val p = credit("Rs 500 credited to A/c XX1234 by UPI Ref No 624912345678 from MURUGAN -SBI")
        assertEquals(50_000L, p.amountPaise)
        assertEquals("MURUGAN", p.payer)
    }

    @Test
    fun hdfc_notification_recipe_from_spec() {
        val p = credit("HDFC Bank Rs.500.00 credited to a/c XX1234 via UPI Ref 426112345678 from MURUGAN", "com.android.shell")
        assertEquals(50_000L, p.amountPaise)
        assertEquals("426112345678", p.utr)
        assertEquals("MURUGAN", p.payer)
    }

    // ---- positive templates: app notifications ----------------------------------------

    @Test
    fun gpay_notification_paid_you() {
        val p = credit("Murugan S paid you ₹500", "com.google.android.apps.nbu.paisa.user")
        assertEquals(50_000L, p.amountPaise)
        assertNull(p.utr)
        assertEquals("Murugan S", p.payer)
    }

    @Test
    fun gpay_notification_received_from() {
        val p = credit("Google Pay ₹1,000 received from Murugan S")
        assertEquals(100_000L, p.amountPaise)
        assertEquals("Murugan S", p.payer)
        assertNull(p.utr)
    }

    @Test
    fun phonepe_notification() {
        val p = credit("PhonePe Received ₹500 from MURUGAN S. UTR: 624912345678")
        assertEquals(50_000L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("MURUGAN S", p.payer)
    }

    @Test
    fun paytm_app_notification() {
        val p = credit("Paytm Money received: Rs.320.50 from Murugan in your Paytm Wallet. Txn ID 624912345678")
        assertEquals(32_050L, p.amountPaise)
        assertEquals("624912345678", p.utr)
        assertEquals("Murugan", p.payer)
    }

    @Test
    fun credit_without_any_reference_or_payer() {
        val p = credit("Rs.100.00 credited to your A/c XX1234 on 06-09-26. -SBI")
        assertEquals(10_000L, p.amountPaise)
        assertNull(p.utr)
        assertNull(p.payer)
    }

    @Test
    fun bare_twelve_digit_reference_is_picked_up() {
        val p = credit("Rs.90.00 credited to A/c XX1234 from RAJ 624912345678 -SBI")
        assertEquals("624912345678", p.utr)
        assertEquals("RAJ", p.payer)
    }

    // ---- negatives ----------------------------------------------------------------------

    @Test
    fun debit_alert_is_rejected() {
        assertNull(BankSmsParser.parse("Rs.500.00 debited from A/c XX1234 on 06-09-26 to MURUGAN UPI Ref No 624912345678 -SBI"))
    }

    @Test
    fun otp_is_rejected() {
        assertNull(BankSmsParser.parse("426112 is your OTP for txn of Rs.500.00 at Amazon. Do not share. -SBI"))
        assertNull(BankSmsParser.parse("Your one time password is 123456 for payment of Rs 500 received via UPI"))
    }

    @Test
    fun promo_is_rejected() {
        assertNull(BankSmsParser.parse("Get 10% cashback on UPI payments above Rs.500 this weekend! Offer valid till 30-09. T&C apply. -HDFC Bank"))
    }

    @Test
    fun balance_alert_is_rejected() {
        assertNull(BankSmsParser.parse("Your A/c XX1234 available balance is Rs.5,000.00 as on 06-09-26 -SBI"))
    }

    @Test
    fun collect_request_is_rejected() {
        assertNull(BankSmsParser.parse("MURUGAN has requested Rs.500.00 from you via UPI. Approve in app before 06-09-26. Ref 624912345678"))
        assertNull(BankSmsParser.parse("You have received a payment request of Rs 500 from murugan@ybl. Pay now in your app."))
    }

    @Test
    fun garbage_and_empty_are_rejected() {
        assertNull(BankSmsParser.parse("hello how are you"))
        assertNull(BankSmsParser.parse(""))
        assertNull(BankSmsParser.parse("credited"))
        assertNull(BankSmsParser.parse("Rs. credited to A/c from XYZ"))
    }

    @Test
    fun outgoing_payment_success_is_rejected() {
        assertNull(BankSmsParser.parse("Payment of Rs 500 to MURUGAN successful. UPI Ref 624912345678"))
        assertNull(BankSmsParser.parse("You paid ₹500 to Murugan S"))
    }

    @Test
    fun credit_card_bill_is_rejected() {
        assertNull(BankSmsParser.parse("Your HDFC credit card bill of Rs.5,000 is due on 15-09-26. Pay to avoid charges."))
    }

    @Test
    fun withdrawal_is_rejected() {
        assertNull(BankSmsParser.parse("Rs 2000.00 withdrawn from ATM at CHENNAI from A/c XX1234 on 06-09-26. Avl Bal Rs 3000 -SBI"))
    }

    @Test
    fun failed_transaction_is_rejected() {
        assertNull(BankSmsParser.parse("Transaction of Rs 500 received from MURUGAN failed. Ref 624912345678 -SBI"))
    }

    // ---- sender trust -------------------------------------------------------------------

    @Test
    fun sender_trust_rules() {
        assertTrue(SenderTrust.isTrustedSmsSender("VK-SBIINB"))
        assertTrue(SenderTrust.isTrustedSmsSender("AD-HDFCBK-S"))
        assertTrue(SenderTrust.isTrustedSmsSender("JD-ICICIB"))
        assertTrue(SenderTrust.isTrustedSmsSender("AX-AXISBK"))
        assertFalse(SenderTrust.isTrustedSmsSender("5551234"))
        assertFalse(SenderTrust.isTrustedSmsSender("+919876543210"))
        assertFalse(SenderTrust.isTrustedSmsSender(null))
        assertFalse(SenderTrust.isTrustedSmsSender(""))
        assertFalse(SenderTrust.isTrustedSmsSender("AMAZON"))
    }
}
