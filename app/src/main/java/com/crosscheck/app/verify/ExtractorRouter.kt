package com.crosscheck.app.verify

import android.graphics.Bitmap
import android.os.Build
import android.util.Log

/** Settings value for the Mode 2 backend choice (SPEC section 1 Settings: auto / VLM / OCR-fallback). */
enum class ExtractorMode { AUTO, VLM, OCR }

/**
 * The single [ClaimExtractor] the UI talks to. AUTO -> VLM when the process is arm64, the model file exists
 * and the engine initialises; anything else (mode OCR, VLM exception, timeout, null claim) -> OCR.
 * Decisions are logged at `Log.i("CrossCheckRouter")`; the backend that produced the claim is returned so
 * it lands in `claims.extractorBackend`.
 */
class ExtractorRouter(
    private val ocr: OcrClaimExtractor,
    private val vlm: VlmClaimExtractor,
    private val mode: suspend () -> ExtractorMode,
    private val primaryAbi: () -> String? = { Build.SUPPORTED_ABIS.firstOrNull() },
) : ClaimExtractor {

    data class Extraction(val claim: Claim?, val backend: String, val decision: String)

    /** Backend of the most recent extraction ("ocr" until the first run). */
    @Volatile override var backendName: String = OcrClaimExtractor.BACKEND
        private set

    override suspend fun extract(bitmap: Bitmap): Claim? = run(bitmap).claim

    suspend fun run(bitmap: Bitmap): Extraction {
        val mode = mode()
        val abi = primaryAbi()
        val decision: String
        if (mode != ExtractorMode.OCR) {
            val arm64 = abi == "arm64-v8a"
            val modelPresent = vlm.modelFileExists()
            val attempt = when (mode) {
                ExtractorMode.VLM -> true
                else -> arm64 && modelPresent
            }
            if (attempt) {
                val claim = try {
                    vlm.extract(bitmap)
                } catch (t: Throwable) {
                    Log.w(TAG, "VLM threw ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
                if (claim != null) {
                    backendName = vlm.backendName
                    Log.i(TAG, "mode=$mode abi=$abi -> ${vlm.backendName}")
                    return Extraction(claim, vlm.backendName, "vlm")
                }
                decision = "mode=$mode abi=$abi model=$modelPresent: VLM returned nothing -> ocr"
            } else {
                decision = "mode=$mode abi=$abi arm64=$arm64 model=$modelPresent -> ocr"
            }
        } else {
            decision = "mode=OCR -> ocr"
        }
        Log.i(TAG, decision)
        backendName = OcrClaimExtractor.BACKEND
        return Extraction(ocr.extract(bitmap), OcrClaimExtractor.BACKEND, decision)
    }

    companion object {
        const val TAG = "CrossCheckRouter"
    }
}
