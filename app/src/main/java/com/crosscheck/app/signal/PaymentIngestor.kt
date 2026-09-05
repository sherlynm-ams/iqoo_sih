package com.crosscheck.app.signal

import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentRepository

/** Whatever speaks the confirmation; [com.crosscheck.app.voice.Speaker] in the app, a fake in tests. */
fun interface PaymentAnnouncer {
    fun announcePayment(amountPaise: Long, payer: String?)
}

/**
 * Parse -> dedup -> persist -> announce. Dedup rules:
 *  - same UTR already stored -> duplicate;
 *  - otherwise same amount + same payer (case-insensitive) within [dedupWindowMillis] where at
 *    least one side has no UTR -> duplicate (SMS and app notification describing one payment).
 */
class PaymentIngestor(
    private val payments: PaymentRepository,
    private val announcer: PaymentAnnouncer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dedupWindowMillis: Long = DEFAULT_DEDUP_WINDOW_MILLIS,
) {
    sealed class IngestResult {
        data class Inserted(val payment: PaymentEntity) : IngestResult()
        data class Duplicate(val existing: PaymentEntity) : IngestResult()
        data object NotACredit : IngestResult()
    }

    /**
     * [announce] = false persists without speaking; the SMS path uses it and hands the utterance to
     * [com.crosscheck.app.voice.SpeakService] so speech survives the receiver's short background window.
     */
    suspend fun ingestRaw(signal: RawSignal, announce: Boolean = true): IngestResult {
        val trusted = signal.senderTrusted ?: SenderTrust.isTrustedSmsSender(signal.senderId)
        val parsed = BankSmsParser.parse(signal.text, signal.senderId, trusted) ?: return IngestResult.NotACredit
        return ingest(parsed, signal.source, signal.receivedAt, announce)
    }

    suspend fun ingest(
        parsed: ParsedCredit,
        source: String,
        receivedAt: Long = clock(),
        announce: Boolean = true,
    ): IngestResult {
        val sender = parsed.payer.orEmpty()
        val utr = parsed.utr

        if (utr != null) {
            payments.findByUtr(utr)?.let { return IngestResult.Duplicate(it) }
        }
        val nearby = payments.findByAmountBetween(
            parsed.amountPaise,
            receivedAt - dedupWindowMillis,
            receivedAt + dedupWindowMillis,
        )
        nearby.firstOrNull { existing ->
            existing.sender.equals(sender, ignoreCase = true) && (utr == null || existing.utr == null)
        }?.let { return IngestResult.Duplicate(it) }

        val entity = PaymentEntity(
            amountPaise = parsed.amountPaise,
            sender = sender,
            utr = utr,
            timestamp = receivedAt,
            source = source,
            raw = parsed.raw,
        )
        val id = payments.insert(entity)
        val stored = entity.copy(id = id)
        if (announce) announcer.announcePayment(stored.amountPaise, parsed.payer)
        return IngestResult.Inserted(stored)
    }

    companion object {
        const val DEFAULT_DEDUP_WINDOW_MILLIS: Long = 2 * 60 * 1000
    }
}
