package com.crosscheck.app.signal

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

/**
 * Corpus-driven parser test. The fixture `src/test/resources/bank-sms-corpus.json` is a verbatim
 * copy of docs/research/bank-sms-corpus.json (the source of truth - fix the parser, never the copy).
 *
 * SMS entries are parsed with the sender's trust derived from its DLT header; notification entries
 * are fed `title + "\n" + body` with senderTrusted = true, exactly as NotificationListenerSource does
 * for a package in the trusted set.
 */
class BankSmsCorpusTest {

    data class Expected(val amountPaise: Long?, val utr: String?, val payer: String?)

    data class Entry(
        val id: String,
        val source: String,
        val channel: String,
        val senderId: String?,
        val title: String?,
        val body: String,
        val kind: String,
        val expected: Expected?,
        val confidence: String?,
    )

    private val entries: List<Entry> = javaClass.getResourceAsStream("/bank-sms-corpus.json").use { stream ->
        Gson().fromJson(InputStreamReader(stream!!, Charsets.UTF_8), Array<Entry>::class.java).toList()
    }

    private fun parse(e: Entry): ParsedCredit? = when (e.channel) {
        "notification" -> BankSmsParser.parse(
            body = listOfNotNull(e.title, e.body).joinToString("\n"),
            senderId = e.senderId,
            senderTrusted = true,
        )
        else -> BankSmsParser.parse(e.body, e.senderId)
    }

    private fun norm(s: String?): String? = s?.trim()?.replace(Regex("\\s+"), " ")?.lowercase()?.takeIf { it.isNotEmpty() }

    @Test
    fun corpus_has_45_entries() {
        assertEquals(45, entries.size)
        assertEquals(27, entries.count { it.kind == "credit" })
    }

    @Test
    fun every_credit_entry_parses_to_its_expected_fields() {
        val failures = ArrayList<String>()
        for (e in entries.filter { it.kind == "credit" }) {
            val expected = e.expected
            if (expected == null) {
                failures += "${e.id}: credit entry without expected"
                continue
            }
            val p = parse(e)
            if (p == null) {
                failures += "${e.id}: returned null"
                continue
            }
            if (p.amountPaise != expected.amountPaise) failures += "${e.id}: amount ${p.amountPaise} != ${expected.amountPaise}"
            if (p.utr != expected.utr) failures += "${e.id}: utr ${p.utr} != ${expected.utr}"
            if (norm(p.payer) != norm(expected.payer)) failures += "${e.id}: payer '${p.payer}' != '${expected.payer}'"
        }
        assertTrue("credit failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun every_non_credit_entry_is_rejected() {
        val failures = ArrayList<String>()
        for (e in entries.filter { it.kind != "credit" }) {
            val p = parse(e)
            if (p != null) failures += "${e.id} (${e.kind}): parsed as credit $p"
        }
        assertTrue("non-credit failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun sender_trust_matches_corpus_headers() {
        // DLT headers with -S/-T (or none) from known banks are trusted; scam senders are not.
        val trusted = entries.filter { it.channel == "sms" && it.kind == "credit" && it.senderId != null }
        trusted.forEach { e ->
            assertTrue("${e.id}: ${e.senderId} should be trusted", SenderTrust.isTrustedSmsSender(e.senderId))
        }
        entries.filter { it.kind == "scam" }.forEach { e ->
            assertTrue("${e.id}: ${e.senderId} must not be trusted", !SenderTrust.isTrustedSmsSender(e.senderId))
        }
    }
}
