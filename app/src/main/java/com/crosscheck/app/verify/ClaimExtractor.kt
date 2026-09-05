package com.crosscheck.app.verify

import android.graphics.Bitmap

/**
 * Turns a camera frame of a GPay/PhonePe "payment successful" screen into a [Claim].
 * Phase 2 supplies the VLM and ML Kit OCR backends; Phase 1 only defines the seam.
 */
interface ClaimExtractor {
    /** Stored in `claims.extractorBackend` (e.g. "vlm", "ocr"). */
    val backendName: String

    suspend fun extract(bitmap: Bitmap): Claim?
}
