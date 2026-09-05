package com.crosscheck.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.data.SpeechLocales
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * On-device STT (SPEC section 4, decided): `createOnDeviceSpeechRecognizer()` on API 31+ when the
 * device reports on-device recognition, otherwise the default recogniser with
 * `EXTRA_PREFER_OFFLINE`. One utterance per [listenOnce]. Lifecycle is logged at
 * `Log.i("CrossCheckSTT", ...)`. The app has no INTERNET permission; nothing here needs one.
 */
class Listener(context: Context, private val settings: SettingsRepository) {
    private val appContext = context.applicationContext

    /** Why the last [listenOnce] returned null (null after a successful listen). */
    @Volatile var lastError: ListenError? = null
        private set

    enum class ListenError { NOT_AVAILABLE, NO_PERMISSION, LANGUAGE_UNAVAILABLE, NO_SPEECH, AUDIO, BUSY, OTHER }

    /** Per-locale on-device availability (API 33+ `checkRecognitionSupport`). */
    enum class Support { INSTALLED, DOWNLOADABLE, PENDING, ONLINE_ONLY, UNSUPPORTED, UNKNOWN }

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /** Listens for one utterance in the Settings speech locale. Null on any failure; see [lastError]. */
    suspend fun listenOnce(localeTag: String? = null): String? = withContext(Dispatchers.Main.immediate) {
        val tag = localeTag ?: settings.current().speechLocale
        lastError = null
        if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "listenOnce: RECORD_AUDIO not granted")
            lastError = ListenError.NO_PERMISSION
            return@withContext null
        }
        if (!isRecognitionAvailable()) {
            Log.w(TAG, "listenOnce: no speech recognition service on this device")
            lastError = ListenError.NOT_AVAILABLE
            return@withContext null
        }
        val onDevice = isOnDeviceAvailable()
        val recognizer = createRecognizer(onDevice)
        Log.i(TAG, "listenOnce locale=$tag onDevice=$onDevice api=${Build.VERSION.SDK_INT}")
        try {
            suspendCancellableCoroutine<String?> { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "onReadyForSpeech") }
                    override fun onBeginningOfSpeech() { Log.i(TAG, "onBeginningOfSpeech") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { Log.i(TAG, "onEndOfSpeech") }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        Log.w(TAG, "onError code=$error (${errorName(error)})")
                        lastError = mapError(error)
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val best = list.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        Log.i(TAG, "onResults n=${list.size} best=\"$best\"")
                        if (best == null) lastError = ListenError.NO_SPEECH
                        if (cont.isActive) cont.resume(best)
                    }
                })
                recognizer.startListening(recognizeIntent(tag))
                cont.invokeOnCancellation {
                    Log.i(TAG, "listenOnce cancelled")
                    recognizer.cancel()
                }
            }
        } finally {
            recognizer.destroy()
        }
    }

    /**
     * API 33+: asks the on-device recogniser which of [localeTags] are installed / downloadable.
     * Below API 33 every entry is [Support.UNKNOWN]. Each result is logged at [TAG].
     */
    suspend fun checkSupport(localeTags: List<String> = SpeechLocales.ALL): Map<String, Support> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "checkRecognitionSupport: needs API 33, running ${Build.VERSION.SDK_INT}")
            return localeTags.associateWith { Support.UNKNOWN }
        }
        if (!isRecognitionAvailable()) {
            Log.w(TAG, "checkRecognitionSupport: no recognition service")
            return localeTags.associateWith { Support.UNSUPPORTED }
        }
        return withContext(Dispatchers.Main.immediate) {
            val onDevice = isOnDeviceAvailable()
            val recognizer = createRecognizer(onDevice)
            try {
                localeTags.associateWith { tag -> querySupport(recognizer, tag, onDevice) }
            } finally {
                recognizer.destroy()
            }
        }
    }

    /** API 33+: asks the recogniser to download the on-device model for [localeTag]. Returns false when unsupported. */
    suspend fun triggerModelDownload(localeTag: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!isRecognitionAvailable()) return false
        return withContext(Dispatchers.Main.immediate) {
            val recognizer = createRecognizer(isOnDeviceAvailable())
            try {
                val intent = recognizeIntent(localeTag)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    recognizer.triggerModelDownload(intent, ContextCompat.getMainExecutor(appContext), object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) { Log.i(TAG, "modelDownload $localeTag progress=$completedPercent%") }
                        override fun onSuccess() { Log.i(TAG, "modelDownload $localeTag success") }
                        override fun onScheduled() { Log.i(TAG, "modelDownload $localeTag scheduled") }
                        override fun onError(error: Int) { Log.w(TAG, "modelDownload $localeTag error=$error (${errorName(error)})") }
                    })
                } else {
                    recognizer.triggerModelDownload(intent)
                }
                Log.i(TAG, "triggerModelDownload requested for $localeTag")
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "triggerModelDownload failed for $localeTag", e)
                false
            } finally {
                // The download continues inside the recogniser service; the client object can go.
                recognizer.destroy()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun querySupport(recognizer: SpeechRecognizer, tag: String, onDevice: Boolean): Support =
        suspendCancellableCoroutine { cont ->
            recognizer.checkRecognitionSupport(recognizeIntent(tag), ContextCompat.getMainExecutor(appContext), object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    val support = classify(tag, recognitionSupport)
                    Log.i(
                        TAG,
                        "checkRecognitionSupport $tag onDevice=$onDevice -> $support installed=${recognitionSupport.installedOnDeviceLanguages} " +
                            "downloadable=${recognitionSupport.supportedOnDeviceLanguages} pending=${recognitionSupport.pendingOnDeviceLanguages} " +
                            "online=${recognitionSupport.onlineLanguages}",
                    )
                    if (cont.isActive) cont.resume(support)
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "checkRecognitionSupport $tag error=$error (${errorName(error)})")
                    if (cont.isActive) cont.resume(Support.UNKNOWN)
                }
            })
        }

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }

    private fun recognizeIntent(localeTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

    companion object {
        const val TAG = "CrossCheckSTT"

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        internal fun classify(tag: String, support: RecognitionSupport): Support = when {
            support.installedOnDeviceLanguages.any { sameLanguage(it, tag) } -> Support.INSTALLED
            support.pendingOnDeviceLanguages.any { sameLanguage(it, tag) } -> Support.PENDING
            support.supportedOnDeviceLanguages.any { sameLanguage(it, tag) } -> Support.DOWNLOADABLE
            support.onlineLanguages.any { sameLanguage(it, tag) } -> Support.ONLINE_ONLY
            else -> Support.UNSUPPORTED
        }

        /** "ta-IN" matches "ta-IN", "ta_IN" and a bare "ta"; never a different country of the same language. */
        internal fun sameLanguage(listed: String, wanted: String): Boolean {
            val a = Locale.forLanguageTag(listed.replace('_', '-'))
            val b = Locale.forLanguageTag(wanted)
            if (!a.language.equals(b.language, ignoreCase = true)) return false
            return a.country.isEmpty() || a.country.equals(b.country, ignoreCase = true)
        }

        private fun mapError(code: Int): ListenError = when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ListenError.NO_PERMISSION
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ListenError.LANGUAGE_UNAVAILABLE
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenError.NO_SPEECH
            SpeechRecognizer.ERROR_AUDIO -> ListenError.AUDIO
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ListenError.BUSY
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            -> ListenError.NOT_AVAILABLE
            else -> ListenError.OTHER
        }

        internal fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "UNKNOWN"
        }
    }
}
