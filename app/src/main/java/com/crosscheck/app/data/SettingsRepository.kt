package com.crosscheck.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crosscheck.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Speech locales the UI offers (SPEC: start ta-IN, en-IN / hi-IN selectable). */
object SpeechLocales {
    const val TAMIL = "ta-IN"
    const val ENGLISH_INDIA = "en-IN"
    const val HINDI = "hi-IN"
    val ALL = listOf(TAMIL, ENGLISH_INDIA, HINDI)
    const val DEFAULT = TAMIL
    const val FALLBACK = ENGLISH_INDIA
}

/** Well-known packages whose notifications carry credit alerts. */
object TrustedPackages {
    const val SHELL = "com.android.shell"
    const val GPAY = "com.google.android.apps.nbu.paisa.user"
    const val PHONEPE = "com.phonepe.app"
    const val PAYTM = "net.one97.paytm"
    const val BHIM = "in.org.npci.upiapp"
    const val SBI_YONO = "com.sbi.lotusintouch"
    const val HDFC = "com.snapwork.hdfc"
    const val ICICI = "com.csam.icici.bank.imobile"
    const val AXIS = "com.axis.mobile"
    const val KOTAK = "com.msf.kbank.mobile"

    /** Label -> package, in display order. */
    val KNOWN: List<Pair<String, String>> = listOf(
        "Google Pay" to GPAY,
        "PhonePe" to PHONEPE,
        "Paytm" to PAYTM,
        "BHIM" to BHIM,
        "SBI YONO" to SBI_YONO,
        "HDFC Bank" to HDFC,
        "ICICI iMobile" to ICICI,
        "Axis Mobile" to AXIS,
        "Kotak" to KOTAK,
    )

    val DEFAULT: Set<String> = buildSet {
        KNOWN.forEach { add(it.second) }
        if (BuildConfig.TRUST_SHELL_NOTIFICATIONS) add(SHELL)
    }
}

/** Mode 2 extractor settings (SPEC section 1 Settings: auto / VLM / OCR-fallback; section 4 model path). */
object ExtractorSettings {
    const val MODE_AUTO = "AUTO"
    const val MODE_VLM = "VLM"
    const val MODE_OCR = "OCR"
    val MODES = listOf(MODE_AUTO, MODE_VLM, MODE_OCR)
    const val DEFAULT_MODEL_PATH = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"
    const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
}

data class Settings(
    val speechLocale: String = SpeechLocales.DEFAULT,
    val smsSourceEnabled: Boolean = true,
    val notificationSourceEnabled: Boolean = true,
    val trustedPackages: Set<String> = TrustedPackages.DEFAULT,
    val extractorMode: String = ExtractorSettings.MODE_AUTO,
    val vlmModelPath: String = ExtractorSettings.DEFAULT_MODEL_PATH,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            speechLocale = p[KEY_LOCALE] ?: SpeechLocales.DEFAULT,
            smsSourceEnabled = p[KEY_SMS] ?: true,
            notificationSourceEnabled = p[KEY_NOTIFICATION] ?: true,
            trustedPackages = p[KEY_TRUSTED] ?: TrustedPackages.DEFAULT,
            extractorMode = p[KEY_EXTRACTOR_MODE] ?: ExtractorSettings.MODE_AUTO,
            vlmModelPath = p[KEY_MODEL_PATH] ?: ExtractorSettings.DEFAULT_MODEL_PATH,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setSpeechLocale(tag: String) = store.edit { it[KEY_LOCALE] = tag }
    suspend fun setSmsSourceEnabled(enabled: Boolean) = store.edit { it[KEY_SMS] = enabled }
    suspend fun setNotificationSourceEnabled(enabled: Boolean) = store.edit { it[KEY_NOTIFICATION] = enabled }
    suspend fun setTrustedPackage(pkg: String, trusted: Boolean) = store.edit { p ->
        val cur = p[KEY_TRUSTED] ?: TrustedPackages.DEFAULT
        p[KEY_TRUSTED] = if (trusted) cur + pkg else cur - pkg
    }
    suspend fun setExtractorMode(mode: String) = store.edit { it[KEY_EXTRACTOR_MODE] = mode }
    suspend fun setVlmModelPath(path: String) = store.edit { it[KEY_MODEL_PATH] = path }

    private companion object {
        val KEY_EXTRACTOR_MODE = stringPreferencesKey("extractor_mode")
        val KEY_MODEL_PATH = stringPreferencesKey("vlm_model_path")
        val KEY_LOCALE = stringPreferencesKey("speech_locale")
        val KEY_SMS = booleanPreferencesKey("source_sms")
        val KEY_NOTIFICATION = booleanPreferencesKey("source_notification")
        val KEY_TRUSTED = stringSetPreferencesKey("trusted_packages")
    }
}
