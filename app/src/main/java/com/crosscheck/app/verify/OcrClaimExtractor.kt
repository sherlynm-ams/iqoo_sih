package com.crosscheck.app.verify

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fallback backend (SPEC section 1): ML Kit bundled Latin text recognition -> [ClaimFieldParser].
 * Runs on any ABI, including the x86_64 emulator. Every recognised line is logged at
 * `Log.d("CrossCheckOcr")` so parser failures can be reproduced as unit-test line lists.
 */
class OcrClaimExtractor(
    private val parser: ClaimFieldParser = ClaimFieldParser(),
) : ClaimExtractor {

    override val backendName: String = BACKEND

    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun extract(bitmap: Bitmap): Claim? {
        val frame = recognise(InputImage.fromBitmap(bitmap, 0), bitmap.width, bitmap.height)
        Log.d(TAG, "frame ${frame.width}x${frame.height}: ${frame.lines.size} line(s)")
        frame.lines.forEach { Log.d(TAG, "line [${it.left},${it.top},${it.right},${it.bottom}] \"${it.text}\"") }
        val claim = parser.parse(frame)
        Log.i(TAG, "claim=$claim")
        return claim
    }

    /** Presence-only scan for the live analysis stream (auto-capture). */
    suspend fun quickScan(image: InputImage): QuickScan {
        val frame = recognise(image, image.width, image.height)
        return parser.quickScan(frame.lines)
    }

    suspend fun recognise(image: InputImage, width: Int, height: Int): OcrFrame {
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
        val lines = ArrayList<OcrLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val content = line.text.trim()
                if (content.isEmpty()) continue
                lines.add(OcrLine(content, box.left, box.top, box.right, box.bottom))
            }
        }
        return OcrFrame(lines, width, height)
    }

    fun close() {
        recognizer.close()
    }

    companion object {
        const val BACKEND = "ocr"
        const val TAG = "CrossCheckOcr"
    }
}
