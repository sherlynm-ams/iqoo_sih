package com.crosscheck.app.signal

import com.crosscheck.app.data.PaymentEntity
import com.crosscheck.app.data.PaymentSource

/**
 * The pure half of [SmsReceiverSource]: groups PDU parts by originating address, concatenates
 * multipart bodies, ingests each message *without* speaking, and reports inserted payments through
 * [onInserted] so the receiver can hand the utterance to a foreground service.
 */
class SmsDelivery(private val ingestor: PaymentIngestor) {

    data class Part(val address: String?, val body: String?)

    data class Delivered(val address: String, val body: String, val result: PaymentIngestor.IngestResult)

    suspend fun deliver(
        parts: List<Part>,
        receivedAt: Long,
        onInserted: suspend (PaymentEntity) -> Unit,
    ): List<Delivered> {
        val out = ArrayList<Delivered>()
        for ((address, group) in parts.groupBy { it.address.orEmpty() }) {
            val body = group.joinToString(separator = "") { it.body.orEmpty() }
            if (body.isBlank()) continue
            val result = ingestor.ingestRaw(
                RawSignal(PaymentSource.SMS, address.takeIf { it.isNotEmpty() }, null, body, receivedAt),
                announce = false,
            )
            if (result is PaymentIngestor.IngestResult.Inserted) onInserted(result.payment)
            out += Delivered(address, body, result)
        }
        return out
    }
}
