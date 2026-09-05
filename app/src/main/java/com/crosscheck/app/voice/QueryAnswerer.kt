package com.crosscheck.app.voice

import com.crosscheck.app.R
import com.crosscheck.app.data.ClaimEntity
import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.formatRupees
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resolves string resources in the *speech* locale. [com.crosscheck.app.voice.Speaker] implements it in the app. */
interface StringProvider {
    fun get(resId: Int, vararg args: Any): String
    fun quantity(resId: Int, quantity: Int, vararg args: Any): String
}

/** Structured result of a query, before it is phrased. */
sealed class Answer {
    /** A payment matched name (if asked) and amount (if asked). */
    data class Yes(val payment: PaymentEntity) : Answer()

    /** Nothing from that name today. */
    data class NothingFromName(val name: String) : Answer()

    /** No payment of that amount today (no name asked). */
    data class NothingOfAmount(val amountPaise: Long) : Answer()

    /** The name paid today, but not the asked amount. */
    data class DifferentAmount(val name: String, val payments: List<PaymentEntity>, val askedPaise: Long) : Answer()

    /** Name asked without an amount and it paid more than once. */
    data class PaidList(val name: String, val payments: List<PaymentEntity>) : Answer()

    data class DigestAnswer(val digest: Digest) : Answer()

    data object NotUnderstood : Answer()
}

/** An [Answer] plus the sentence to speak and show. */
data class Spoken(val answer: Answer, val text: String)

/**
 * Runs a [Query] against today's rows. Name match = case-insensitive contains on the stored sender
 * after normalisation (uppercase, letters/digits only, single spaces); amount match = exact paise.
 * Sentences come from string resources so the spoken locale follows Settings.
 */
class QueryAnswerer(
    private val strings: StringProvider,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun answer(query: Query, todaysPayments: List<PaymentEntity>, todaysClaims: List<ClaimEntity>): Spoken {
        val answer = decide(query, todaysPayments, todaysClaims)
        return Spoken(answer, phrase(answer))
    }

    fun decide(query: Query, todaysPayments: List<PaymentEntity>, todaysClaims: List<ClaimEntity>): Answer = when (query) {
        Query.Unknown -> Answer.NotUnderstood
        Query.DigestToday -> Answer.DigestAnswer(DigestBuilder.build(todaysPayments, todaysClaims))
        is Query.DidPay -> didPay(query, todaysPayments)
    }

    private fun didPay(q: Query.DidPay, payments: List<PaymentEntity>): Answer {
        val name = q.name?.let { normaliseName(it) }?.takeIf { it.isNotEmpty() }
        val amount = q.amountPaise
        // Newest first, so "at HH:mm" names the latest instance.
        val newestFirst = payments.sortedByDescending { it.timestamp }

        if (name == null) {
            val asked = amount ?: return Answer.NotUnderstood
            val hits = newestFirst.filter { it.amountPaise == asked }
            return if (hits.isNotEmpty()) Answer.Yes(hits.first()) else Answer.NothingOfAmount(asked)
        }

        val byName = newestFirst.filter { senderMatches(it.sender, name) }
        if (byName.isEmpty()) return Answer.NothingFromName(name)
        if (amount == null) {
            return if (byName.size == 1) Answer.Yes(byName.first()) else Answer.PaidList(displayName(name, byName), byName)
        }
        val exact = byName.filter { it.amountPaise == amount }
        return if (exact.isNotEmpty()) Answer.Yes(exact.first()) else Answer.DifferentAmount(displayName(name, byName), byName, amount)
    }

    /** Prefer the stored sender spelling when the name matched a row. */
    private fun displayName(asked: String, matched: List<PaymentEntity>): String =
        matched.firstOrNull()?.sender?.takeIf { it.isNotBlank() } ?: asked

    fun phrase(answer: Answer): String = when (answer) {
        is Answer.Yes -> strings.get(
            R.string.voice_answer_yes,
            formatRupees(answer.payment.amountPaise),
            answer.payment.sender.ifBlank { strings.get(R.string.tts_unknown_sender) },
            formatTime(answer.payment.timestamp),
        )
        is Answer.NothingFromName -> strings.get(R.string.voice_answer_no_name, answer.name)
        is Answer.NothingOfAmount -> strings.get(R.string.voice_answer_no_amount, formatRupees(answer.amountPaise))
        is Answer.DifferentAmount -> strings.get(
            R.string.voice_answer_different,
            answer.name,
            answer.payments.joinToString(", ") { formatRupees(it.amountPaise) },
            formatRupees(answer.askedPaise),
        )
        is Answer.PaidList -> strings.get(
            R.string.voice_answer_paid_list,
            answer.name,
            answer.payments.joinToString(", ") { formatRupees(it.amountPaise) },
        )
        is Answer.DigestAnswer -> {
            val d = answer.digest
            strings.get(
                R.string.voice_digest,
                strings.quantity(R.plurals.voice_digest_payments, d.confirmedCount, d.confirmedCount),
                strings.quantity(R.plurals.voice_digest_mismatches, d.mismatchCount, d.mismatchCount),
                formatRupees(d.flaggedPaise),
            )
        }
        Answer.NotUnderstood -> strings.get(R.string.voice_answer_unknown)
    }

    private fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(TIME)

    companion object {
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        // \p{M} keeps Tamil/Devanagari vowel signs and viramas, which are marks, not letters.
        private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N} ]")
        private val SPACES = Regex("\\s+")

        /** Uppercase, drop punctuation, collapse whitespace. Script-neutral. */
        fun normaliseName(raw: String): String =
            raw.uppercase().replace(NON_WORD, " ").replace(SPACES, " ").trim()

        /** Contains-match on the normalised sender, or every asked token appears somewhere in it. */
        fun senderMatches(sender: String, normalisedAsked: String): Boolean {
            val s = normaliseName(sender)
            if (s.isEmpty() || normalisedAsked.isEmpty()) return false
            if (s.contains(normalisedAsked)) return true
            val tokens = normalisedAsked.split(' ').filter { it.isNotEmpty() }
            return tokens.isNotEmpty() && tokens.all { s.contains(it) }
        }
    }
}
