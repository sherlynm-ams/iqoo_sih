package com.crosscheck.app.verify

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.crosscheck.app.data.parseRupeesToPaise
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Primary backend (SPEC section 4 / docs/research/vlm-runtime.md section 3.1): LiteRT-LM + Gemma 4 E2B,
 * one pass image -> constrained JSON -> [Claim].
 *
 * GPU (OpenCL) first, CPU on init failure; the backend actually used is logged at `Log.i("CrossCheckVlm")`.
 * Every failure mode (model file missing, native library unavailable on this ABI, engine init error,
 * timeout, unparseable JSON) is caught and surfaces as `null` / [isAvailable] == false so the router can
 * fall back to OCR. Nothing here is verifiable on the x86_64 AVD - see docs/reports/phase2-mode2.md.
 */
class VlmClaimExtractor(
    context: Context,
    private val modelPath: suspend () -> String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : ClaimExtractor {

    private val appContext = context.applicationContext
    private val initLock = Mutex()
    private var engine: Engine? = null
    private var initialisedFor: String? = null
    private var failedFor: String? = null

    /** "vlm-gpu" | "vlm-cpu" after a successful init; "vlm" before. */
    @Volatile override var backendName: String = BACKEND_UNKNOWN
        private set

    /** Cheap pre-check the router uses in AUTO mode: does the model file exist and is this an arm64 process? */
    suspend fun modelFileExists(): Boolean = File(modelPath()).let { it.isFile && it.length() > 0 }

    /**
     * Initialises the engine on [Dispatchers.IO] (can take ~10 s cold). Returns false, without throwing,
     * when the model is absent or the runtime cannot load; a failed path is not retried until it changes.
     */
    suspend fun ensureInitialised(): Boolean = initLock.withLock {
        val path = modelPath()
        if (engine != null && initialisedFor == path) return true
        if (failedFor == path) return false
        if (!File(path).isFile) {
            Log.i(TAG, "model file absent at $path -> VLM unavailable")
            failedFor = path
            return false
        }
        withContext(Dispatchers.IO) {
            engine?.let { runCatching { it.close() } }
            engine = null
            val started = System.currentTimeMillis()
            val gpu = tryInit(path, Backend.GPU(), Backend.GPU())
            val created = gpu ?: tryInit(path, Backend.CPU(), Backend.CPU())
            if (created == null) {
                Log.e(TAG, "engine init failed on GPU and CPU for $path")
                failedFor = path
                backendName = BACKEND_UNKNOWN
            } else {
                engine = created
                initialisedFor = path
                backendName = if (gpu != null) BACKEND_GPU else BACKEND_CPU
                Log.i(TAG, "engine ready backend=$backendName init_ms=${System.currentTimeMillis() - started} model=$path")
            }
            created != null
        }
    }

    private fun tryInit(path: String, backend: Backend, visionBackend: Backend): Engine? = try {
        Engine(
            EngineConfig(
                modelPath = path,
                backend = backend,
                visionBackend = visionBackend,
                maxNumTokens = MAX_NUM_TOKENS,
                maxNumImages = 1,
                cacheDir = appContext.cacheDir.path,
            ),
        ).also { it.initialize() }
    } catch (t: Throwable) {
        // LiteRtLmJniException, IllegalStateException, UnsatisfiedLinkError (wrong ABI / no OpenCL)...
        Log.w(TAG, "init on ${backend.name} failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    override suspend fun extract(bitmap: Bitmap): Claim? {
        if (!ensureInitialised()) return null
        val engine = engine ?: return null
        val jpeg = toJpeg(downscale(bitmap, MAX_SIDE))
        val started = System.currentTimeMillis()
        val json = try {
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.IO) { generate(engine, jpeg) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "extract failed after ${System.currentTimeMillis() - started} ms: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
        Log.i(TAG, "backend=$backendName total_ms=${System.currentTimeMillis() - started} json=$json")
        return parseClaimJson(json)
    }

    private fun generate(engine: Engine, jpeg: ByteArray): String {
        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                enableResponseFormat = true,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            ),
        ).use { conversation ->
            val reply = conversation.sendMessage(
                Contents.of(Content.ImageBytes(jpeg), Content.Text(USER_PROMPT)), // image BEFORE text
                maxOutputToken = MAX_OUTPUT_TOKENS,
                responseFormat = ResponseFormat.json(CLAIM_SCHEMA),
            )
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    fun close() {
        engine?.let { runCatching { it.close() } }
        engine = null
        initialisedFor = null
    }

    companion object {
        const val TAG = "CrossCheckVlm"
        const val BACKEND_UNKNOWN = "vlm"
        const val BACKEND_GPU = "vlm-gpu"
        const val BACKEND_CPU = "vlm-cpu"
        const val DEFAULT_TIMEOUT_MILLIS: Long = 8_000
        const val MAX_SIDE = 1024
        const val JPEG_QUALITY = 85
        const val MAX_NUM_TOKENS = 2048

        /**
         * vlm-runtime.md section 3.2 budgets 96 output tokens. Gemma tokenises every digit separately, so the
         * schema below (with the status/payer fields the reconciler needs) is ~110-130 tokens; a truncated
         * object would fail to parse and waste the pass, hence 160.
         */
        const val MAX_OUTPUT_TOKENS = 160

        /** vlm-runtime.md section 3.2 schema + `status`, `payer_name`, `payer_bank_mask` (payment-screens.md section 3a). */
        val CLAIM_SCHEMA: String = """
            {"type":"object","additionalProperties":false,
             "required":["app","amount_text","amount_paise","upi_id","utr","timestamp","status","payer_name","payer_bank_mask"],
             "properties":{
              "app":{"type":"string","enum":["gpay","phonepe","unknown"]},
              "amount_text":{"type":"string"},
              "amount_paise":{"type":"integer","minimum":0},
              "upi_id":{"type":["string","null"]},
              "utr":{"type":["string","null"],"pattern":"^[0-9]{10,16}$"},
              "timestamp":{"type":["string","null"]},
              "status":{"type":"string","enum":["success","pending","failed","unknown"]},
              "payer_name":{"type":["string","null"]},
              "payer_bank_mask":{"type":["string","null"]}}}
        """.trimIndent()

        val SYSTEM_PROMPT: String = """
            You read a photo of another phone's screen showing a UPI "payment successful" screen and extract fields as JSON. Output only JSON.
            Layouts you will see:
            - Google Pay (GPay): big amount like "₹1,250" near the top; "Paid to <name>" or "To <name>"; a line "UPI transaction ID" followed by a 12-digit number; sometimes "Google transaction ID" (ignore it); the payee UPI ID like name@okaxis / name@okicici / name@oksbi; date like "12 Sept 2026, 4:32 pm".
            - PhonePe: "Paid Successfully" or "Transaction Successful"; amount "₹1,250"; "Transaction ID" starting with T + digits (ignore it); "UTR" or "UPI Ref No" followed by a 12-digit number — this is the utr; payee UPI ID like name@ybl / name@axl / name@ibl; date like "12 Sept 2026 04:32 PM".
            Rules: amount_text = the amount exactly as printed. amount_paise = rupees × 100 as an integer (₹1,250.00 → 125000; ₹80 → 8000). utr = the 12-digit UTR/UPI transaction ID digits only; if you cannot read all digits, null. upi_id = the payee's UPI ID (contains @); if none visible, null. timestamp = the date/time text as printed or null. Never invent digits.
            status = "success" only if a completed/successful indicator is visible; "pending" for processing/in progress/pending; "failed" for failed/declined/reversed; else "unknown". payer_name = the name after "From:" (GPay) or null. payer_bank_mask = the masked account like "XX4321" / "XXXXXX4321" after "From:" or "Debited from", or null.
            Examples:
            {"app":"gpay","amount_text":"₹1,250","amount_paise":125000,"upi_id":"murugan.stores@okaxis","utr":"426112345678","timestamp":"12 Sept 2026, 4:32 pm","status":"success","payer_name":"MURUGAN","payer_bank_mask":"XX4321"}
            {"app":"phonepe","amount_text":"₹80","amount_paise":8000,"upi_id":"kavitha@ybl","utr":"526198765432","timestamp":"12 Sept 2026 04:32 PM","status":"success","payer_name":null,"payer_bank_mask":"XXXXXX4321"}
        """.trimIndent()

        const val USER_PROMPT = "Extract the fields from this payment screen photo. Output only the JSON object."

        fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= maxSide) return bitmap
            val scale = maxSide.toFloat() / longest
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        fun toJpeg(bitmap: Bitmap): ByteArray =
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

        /**
         * Post-processing per vlm-runtime.md section 3.2: paise recomputed from `amount_text` (preferred over the
         * model's arithmetic), utr -> digits, timestamp text -> epoch via the same parser the OCR path uses.
         * Returns null when the text is not the expected object or has no usable amount.
         */
        fun parseClaimJson(json: String): Claim? {
            val start = json.indexOf('{')
            val end = json.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = try {
                JSONObject(json.substring(start, end + 1))
            } catch (e: org.json.JSONException) {
                Log.w(TAG, "unparseable JSON: ${e.message}")
                return null
            }
            val amountText = obj.optString("amount_text", "")
            val fromText = parseRupeesToPaise(amountText.replace(CURRENCY_MARKERS, "").ifEmpty { "x" })
            val fromModel = obj.optLong("amount_paise", -1L).takeIf { it >= 0 }
            val amount = fromText ?: fromModel ?: return null
            val utr = Utr.normalise(obj.optStringOrNull("utr"))
            val timestampText = obj.optStringOrNull("timestamp")
            return Claim(
                amountPaise = amount,
                upiId = obj.optStringOrNull("upi_id")?.lowercase()?.takeIf { it.contains('@') },
                utr = utr,
                claimedTimestamp = timestampText?.let { ClaimFieldParser.parseTimestamp(it) },
                payerName = obj.optStringOrNull("payer_name"),
                payerBankMask = obj.optStringOrNull("payer_bank_mask")?.uppercase(),
                payerVpa = null,
                app = when (obj.optString("app")) {
                    "gpay" -> ClaimApp.GPAY
                    "phonepe" -> ClaimApp.PHONEPE
                    else -> ClaimApp.UNKNOWN
                },
                status = when (obj.optString("status")) {
                    "success" -> ClaimStatus.SUCCESS
                    "pending" -> ClaimStatus.PENDING
                    "failed" -> ClaimStatus.FAILED
                    else -> ClaimStatus.UNKNOWN
                },
                otherIds = emptyList(),
            )
        }

        /** "₹1,250.50" / "Rs. 80" / "INR 80" -> "1,250.50" / "80" (the decimal point is kept). */
        private val CURRENCY_MARKERS = Regex("₹|Rs\\.?|INR|\\s", RegexOption.IGNORE_CASE)

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
    }
}
