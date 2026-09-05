package com.crosscheck.app.di

import android.content.Context
import android.util.Log
import com.crosscheck.app.data.AppDatabase
import com.crosscheck.app.data.ClaimRepository
import com.crosscheck.app.data.FlaggedVpaRepository
import com.crosscheck.app.data.PaymentRepository
import com.crosscheck.app.data.RoomClaimRepository
import com.crosscheck.app.data.RoomFlaggedVpaRepository
import com.crosscheck.app.data.RoomPaymentRepository
import com.crosscheck.app.data.SettingsRepository
import com.crosscheck.app.signal.PaymentIngestor
import com.crosscheck.app.verify.Reconciler
import com.crosscheck.app.verify.VerificationService
import com.crosscheck.app.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.net.Uri
import com.crosscheck.app.data.ExtractorSettings
import com.crosscheck.app.verify.ExtractorMode
import com.crosscheck.app.verify.ExtractorRouter
import com.crosscheck.app.verify.FixtureSuite
import com.crosscheck.app.verify.OcrClaimExtractor
import com.crosscheck.app.verify.VlmClaimExtractor
import kotlinx.coroutines.withContext
import java.io.File

/** Manual DI (SPEC section 4): one instance per process, built in [com.crosscheck.app.CrossCheckApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.build(appContext)
    val payments: PaymentRepository = RoomPaymentRepository(database.paymentDao())
    val claims: ClaimRepository = RoomClaimRepository(database.claimDao())
    val flaggedVpas: FlaggedVpaRepository = RoomFlaggedVpaRepository(database.flaggedVpaDao())
    val settings: SettingsRepository = SettingsRepository(appContext)

    val speaker: Speaker = Speaker(appContext)
    val ingestor: PaymentIngestor = PaymentIngestor(payments, speaker)
    val reconciler: Reconciler = Reconciler()
    val verification: VerificationService = VerificationService(payments, claims, flaggedVpas, reconciler)

    // Mode 2 extractors (Phase 2): OCR fallback, VLM primary, and the router the UI talks to.
    val ocrExtractor: OcrClaimExtractor = OcrClaimExtractor()
    val vlmExtractor: VlmClaimExtractor = VlmClaimExtractor(appContext, modelPath = { settings.current().vlmModelPath })
    val extractor: ExtractorRouter = ExtractorRouter(
        ocrExtractor,
        vlmExtractor,
        mode = { runCatching { ExtractorMode.valueOf(settings.current().extractorMode) }.getOrDefault(ExtractorMode.AUTO) },
    )
    val fixtureSuite: FixtureSuite = FixtureSuite(appContext, payments, extractor, verification)

    init {
        appScope.launch {
            settings.settings.map { it.speechLocale }.distinctUntilChanged().collect { speaker.setLocale(it) }
        }
    }

    /** Debug aid: logs every row of every table at `Log.i("CrossCheckDB", ...)`. */
    suspend fun dumpDatabaseToLog() {
        val p = payments.all()
        Log.i(DB_TAG, "payments: ${p.size} row(s)")
        p.forEach { Log.i(DB_TAG, "payment $it") }
        val c = claims.all()
        Log.i(DB_TAG, "claims: ${c.size} row(s)")
        c.forEach { Log.i(DB_TAG, "claim $it") }
        val f = flaggedVpas.all()
        Log.i(DB_TAG, "flagged_vpas: ${f.size} row(s)")
        f.forEach { Log.i(DB_TAG, "flagged $it") }
    }

    /**
     * Debug aid (SPEC section 4 fallback path): copies a user-picked model file (Downloads) into
     * `filesDir/llm/` and points the VLM at it. Returns the destination path.
     */
    suspend fun importModel(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(appContext.filesDir, "llm").apply { mkdirs() }
            val dest = File(dir, ExtractorSettings.MODEL_FILE_NAME)
            val input = appContext.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
            input.use { src -> dest.outputStream().use { dst -> src.copyTo(dst, 1 shl 20) } }
            settings.setVlmModelPath(dest.path)
            Log.i(VlmClaimExtractor.TAG, "model imported to ${dest.path} (${dest.length()} bytes)")
            dest.path
        }
    }

    companion object {
        const val DB_TAG = "CrossCheckDB"
    }
}
