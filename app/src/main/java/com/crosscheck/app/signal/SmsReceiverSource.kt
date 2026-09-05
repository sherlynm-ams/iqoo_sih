package com.crosscheck.app.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.crosscheck.app.CrossCheckApp
import com.crosscheck.app.data.PaymentSource
import com.crosscheck.app.voice.SpeakService
import kotlinx.coroutines.launch

/**
 * Mode 1 SMS path. Registered in the manifest for SMS_RECEIVED (needs RECEIVE_SMS at runtime).
 *
 * Background budget (docs/research/bank-sms-templates.md section 3): telephony delivers the
 * broadcast with a ~20 s temp-allowlist. Parse + insert happen inside [goAsync] (<= 10 s); the
 * spoken confirmation is handed to [SpeakService], a `shortService` foreground service that owns
 * the utterance and stops itself on completion, so speech is not cut off when the receiver ends.
 * If the foreground service cannot be started, it falls back to speaking directly.
 */
class SmsReceiverSource : BroadcastReceiver(), PaymentSignalSource {

    override val sourceName: String = PaymentSource.SMS

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val parts = messages.map { SmsDelivery.Part(it.displayOriginatingAddress, it.displayMessageBody) }
        val container = CrossCheckApp.from(context).container
        val appContext = context.applicationContext
        val receivedAt = System.currentTimeMillis()
        val pending = goAsync()

        container.appScope.launch {
            try {
                if (!container.settings.current().smsSourceEnabled) {
                    Log.i(TAG, "SMS source disabled in settings; ignoring ${messages.size} part(s)")
                    return@launch
                }
                val delivered = SmsDelivery(container.ingestor).deliver(parts, receivedAt) { payment ->
                    val text = container.speaker.paymentAnnouncement(payment.amountPaise, payment.sender.takeIf { it.isNotBlank() })
                    if (!SpeakService.start(appContext, text)) {
                        Log.w(TAG, "SpeakService unavailable; speaking directly")
                        container.speaker.speak(text)
                    }
                }
                delivered.forEach {
                    Log.i(TAG, "from=${it.address} result=${it.result::class.simpleName} body=\"${it.body.take(80)}\"")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "CrossCheckSMS"
    }
}
