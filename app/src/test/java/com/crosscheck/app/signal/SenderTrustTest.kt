package com.crosscheck.app.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderTrustTest {

    @Test
    fun every_known_header_is_trusted_with_common_prefixes_and_suffixes() {
        val prefixes = listOf("AD", "VM", "JD", "AX", "CP", "TM", "JM", "BX", "VK")
        SenderTrust.KNOWN_HEADERS.forEach { header ->
            prefixes.forEach { prefix ->
                assertTrue("$prefix-$header", SenderTrust.isTrustedSmsSender("$prefix-$header"))
                assertTrue("$prefix-$header-S", SenderTrust.isTrustedSmsSender("$prefix-$header-S"))
                assertTrue("$prefix-$header-T", SenderTrust.isTrustedSmsSender("$prefix-$header-T"))
                assertTrue("$prefix-$header-G", SenderTrust.isTrustedSmsSender("$prefix-$header-G"))
            }
        }
    }

    @Test
    fun header_matching_is_case_insensitive() {
        assertTrue(SenderTrust.isTrustedSmsSender("JD-AxisBk-S"))
        assertTrue(SenderTrust.isTrustedSmsSender("ad-hdfcbk-s"))
        assertEquals(SenderTrust.DltHeader("JD", "AXISBK", 'S'), SenderTrust.parseHeader("jd-AxisBk-s"))
    }

    @Test
    fun promo_suffix_is_never_trusted_and_flagged_as_promo() {
        assertFalse(SenderTrust.isTrustedSmsSender("AD-HDFCBK-P"))
        assertTrue(SenderTrust.isPromoHeader("AD-HDFCBK-P"))
        assertTrue(SenderTrust.isPromoHeader("VM-XYZ123-P"))
        assertFalse(SenderTrust.isPromoHeader("AD-HDFCBK-S"))
        assertFalse(SenderTrust.isPromoHeader("+919876543210"))
        assertFalse(SenderTrust.isPromoHeader(null))
    }

    @Test
    fun unknown_headers_are_untrusted_even_with_dlt_shape() {
        assertFalse(SenderTrust.isTrustedSmsSender("VM-SBICRD-T"))
        assertFalse(SenderTrust.isTrustedSmsSender("AD-ICICIO-T"))
        assertFalse(SenderTrust.isTrustedSmsSender("VM-AMAZON"))
        assertEquals(SenderTrust.DltHeader("VM", "SBICRD", 'T'), SenderTrust.parseHeader("VM-SBICRD-T"))
    }

    @Test
    fun numeric_and_lookalike_senders_are_untrusted() {
        listOf("9812345678", "+919876543210", "5551234", "SBI-ALERT", "HDFC-PYMT", "SBIINB", "AD-SBIINB-X", "ADSBIINB", "", " ")
            .forEach { assertFalse(it, SenderTrust.isTrustedSmsSender(it)) }
        assertFalse(SenderTrust.isTrustedSmsSender(null))
        assertNull(SenderTrust.parseHeader("SBI-ALERT"))
        assertNull(SenderTrust.parseHeader("9812345678"))
    }

    @Test
    fun app_package_names_are_not_sms_headers() {
        assertFalse(SenderTrust.isTrustedSmsSender("com.google.android.apps.nbu.paisa.user"))
        assertFalse(SenderTrust.isTrustedSmsSender("com.android.shell"))
    }
}
