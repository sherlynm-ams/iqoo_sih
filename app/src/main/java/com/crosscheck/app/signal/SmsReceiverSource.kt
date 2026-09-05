package com.crosscheck.app.signal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.crosscheck.app.CrossCheckApp
import com.crosscheck.app.data.PaymentSource
import kotlinx.coroutines.launch

/**
 * Mode 1 SMS path. Registered in the manifest for SMS_RECEIVED (needs RECEIVE_SMS at runtime).
 * Multipart messages from the same address are concatenated before parsing.
 */
class SmsReceiverSource : BroadcastReceiver(), PaymentSignalSource {

    override val sourceName: String = PaymentSource.SMS

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val byAddress = messages.groupBy { it.displayOriginatingAddress ?: "" }
        val container = CrossCheckApp.from(context).container
        val receivedAt = System.currentTimeMillis()
        val pending = goAsync()

        container.appScope.launch {
            try {
                if (!container.settings.current().smsSourceEnabled) {
                    Log.i(TAG, "SMS source disabled in settings; ignoring ${messages.size} part(s)")
                    return@launch
                }
                for ((address, parts) in byAddress) {
                    val body = parts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
                    val result = deliver(container.ingestor, address, null, body, receivedAt)
                    Log.i(TAG, "from=$address result=${result::class.simpleName} body=\"${body.take(80)}\"")
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
