package com.crosscheck.app.voice

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.StringRes
import com.crosscheck.app.R
import com.crosscheck.app.data.SpeechLocales
import com.crosscheck.app.data.formatRupees
import com.crosscheck.app.signal.PaymentAnnouncer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device TTS wrapper. Locale is switchable at runtime; if the requested voice is unavailable it
 * falls back to en-IN (then plain English) and logs which one is in use. Every utterance gets an id
 * and its lifecycle is logged at `Log.i("CrossCheckTTS", ...)`, which is how TTS is verified headless.
 *
 * Spoken strings are resolved from string resources in the *speech* locale, independent of the UI
 * locale, via a configuration context.
 */
class Speaker(
    context: Context,
    initialLocaleTag: String = SpeechLocales.DEFAULT,
) : PaymentAnnouncer {

    private val appContext: Context = context.applicationContext
    private val counter = AtomicInteger()
    private val texts = ConcurrentHashMap<String, String>()
    private val pending = ArrayDeque<Pair<String, String>>()
    private val lock = Any()

    @Volatile private var ready = false
    @Volatile private var requestedLocaleTag: String = initialLocaleTag

    /** The locale the engine is actually using (null until initialised). */
    @Volatile var activeLocale: Locale? = null
        private set

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            applyLocale(requestedLocaleTag)
            val toFlush: List<Pair<String, String>>
            synchronized(lock) {
                ready = true
                toFlush = pending.toList()
                pending.clear()
            }
            Log.i(TAG, "engine ready; flushing ${toFlush.size} queued utterance(s)")
            toFlush.forEach { (id, text) -> enqueue(id, text) }
        } else {
            Log.e(TAG, "TextToSpeech init failed status=$status")
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.i(TAG, "onStart id=$utteranceId text=\"${texts[utteranceId]}\"")
            }

            override fun onDone(utteranceId: String) {
                Log.i(TAG, "onDone id=$utteranceId text=\"${texts.remove(utteranceId)}\"")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                Log.e(TAG, "onError id=$utteranceId text=\"${texts.remove(utteranceId)}\"")
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                Log.e(TAG, "onError id=$utteranceId code=$errorCode text=\"${texts.remove(utteranceId)}\"")
            }
        })
    }

    fun setLocale(tag: String) {
        requestedLocaleTag = tag
        if (ready) applyLocale(tag)
    }

    /** Speaks [text]; returns the utterance id. Queued until the engine is ready. */
    fun speak(text: String): String {
        val id = "cc-" + counter.incrementAndGet()
        texts[id] = text
        val queueNow: Boolean
        synchronized(lock) {
            queueNow = ready
            if (!queueNow) pending.addLast(id to text)
        }
        if (queueNow) enqueue(id, text) else Log.i(TAG, "queued id=$id (engine not ready) text=\"$text\"")
        return id
    }

    override fun announcePayment(amountPaise: Long, payer: String?) {
        val name = payer?.takeIf { it.isNotBlank() } ?: localizedString(R.string.tts_unknown_sender)
        speak(localizedString(R.string.tts_payment_received, formatRupees(amountPaise), name))
    }

    /** Resolves a string resource in the current speech locale (not the UI locale). */
    fun localizedString(@StringRes resId: Int, vararg args: Any): String {
        val locale = activeLocale ?: Locale.forLanguageTag(requestedLocaleTag)
        val config = Configuration(appContext.resources.configuration).apply { setLocale(locale) }
        return appContext.createConfigurationContext(config).getString(resId, *args)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun enqueue(id: String, text: String) {
        val params = Bundle()
        val rc = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        if (rc != TextToSpeech.SUCCESS) Log.e(TAG, "speak() rejected id=$id rc=$rc text=\"$text\"")
    }

    private fun applyLocale(tag: String) {
        val wanted = Locale.forLanguageTag(tag)
        val fallback = Locale.forLanguageTag(SpeechLocales.FALLBACK)
        val chosen = when {
            available(wanted) -> wanted
            available(fallback) -> fallback
            else -> Locale.ENGLISH
        }
        val rc = tts.setLanguage(chosen)
        activeLocale = chosen
        Log.i(TAG, "locale requested=$tag using=${chosen.toLanguageTag()} setLanguage=$rc")
    }

    private fun available(locale: Locale): Boolean = try {
        tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "isLanguageAvailable threw for $locale", e)
        false
    }

    companion object {
        const val TAG = "CrossCheckTTS"
    }
}
