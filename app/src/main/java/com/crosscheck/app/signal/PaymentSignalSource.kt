package com.crosscheck.app.signal

/** A raw alert as delivered by a signal source, before parsing. */
data class RawSignal(
    val source: String,
    val senderId: String?,
    /** Null lets the ingestor derive trust from [senderId] using [SenderTrust]. */
    val senderTrusted: Boolean?,
    val text: String,
    val receivedAt: Long,
)

/**
 * Anything that feeds bank/UPI alerts into the Mode 1 pipeline (SPEC section 1).
 * Implemented by [SmsReceiverSource] and [NotificationListenerSource]; both hand raw text to the
 * shared [PaymentIngestor] through [deliver].
 */
interface PaymentSignalSource {
    /** Stored in `payments.source`: [com.crosscheck.app.data.PaymentSource.SMS] or `.NOTIFICATION`. */
    val sourceName: String

    suspend fun deliver(
        ingestor: PaymentIngestor,
        senderId: String?,
        senderTrusted: Boolean?,
        text: String,
        receivedAt: Long,
    ): PaymentIngestor.IngestResult =
        ingestor.ingestRaw(RawSignal(sourceName, senderId, senderTrusted, text, receivedAt))
}
