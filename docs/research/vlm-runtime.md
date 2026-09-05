# VLM runtime for CrossCheck Mode 2 — research report

Written 2026-09-06 for the iQOO City Battles Chennai (12–13 Sept 2026). Scope: SPEC §1 Mode 2 / §4 — one camera photo of a GPay or PhonePe "payment successful" screen → strict JSON `{"amount_paise": int, "upi_id": str|null, "utr": str|null, "timestamp": str|null}`, one pass, fully offline, on the loaner phone.

Legend: **[V]** = verified against a source I fetched (URL + date given). **[I]** = my inference/estimate — treat as a hypothesis to benchmark, not a fact.

---

## Decisions for the build (summary)

| Item | Decision |
|---|---|
| Runtime (primary) | **LiteRT-LM Kotlin API** — `implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")` (Google Maven; 0.17.0 published 2026-09-04) [V]. MediaPipe LLM Inference is officially "maintenance-only; migrate to LiteRT-LM" [V]. |
| Model (primary) | **Gemma 4 E2B** `gemma-4-E2B-it.litertlm` (2.59 GB, Apache 2.0, **no HF gating**) from `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm` [V]. Multimodal (image+audio) [V]. |
| Backends | `backend = Backend.GPU()` (Adreno 840 via OpenCL), `visionBackend = Backend.GPU()`; automatic fallback to `Backend.CPU()` if GPU init throws. **No NPU path for a VLM on this phone via LiteRT-LM** (see §2.1) [V]. |
| Structured output | Constrained decoding: `ConversationConfig(enableResponseFormat = true)` + `sendMessage(contents, responseFormat = ResponseFormat.json(schema))` [V — Kotlin source]. |
| On-device path | `adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma-4-E2B-it.litertlm` (same convention Google's docs use) [V]; no `INTERNET` permission needed. |
| Emulator | **VLM backend cannot run on the x86_64 emulator** (arm64-only native libs, no `libOpenCL.so`) — keep the ML Kit OCR backend there, exactly as SPEC already plans. [V for OpenCL; arm64-only is community-reported, verify with `unzip -l` on the AAR] |
| Fallback (on stage) | ML Kit OCR + `ClaimFieldParser` (already in SPEC). Stretch "NPU points" option: **Qualcomm GenieX** with the AI Hub `Qwen3-VL-4B-Instruct` bundle for SM8850 — only if the primary is green by Saturday noon (§3.3). |
| Prompt | §3.2 below (system + few-shot, layouts described, JSON schema enforced by the decoder). |

---

## 1. Target hardware — iQOO 15 (Snapdragon 8 Elite Gen 5)

- **Loaner is the iQOO 15.** Reskilll's launch post (2026-08-22): "You build with the iQOO 15's Snapdragon NPU for on-device AI, use Office Kit to bridge phone and laptop" — https://reskilll.com/blogs/iqoo-hackathon-2026-india-phone-first-ai-hackathon-iqoo-reskilll/ [V]. FoneArena's coverage also names the iQOO 15 as the device provided — https://www.fonearena.com/blog/489783/iqoo-hackathon-2026-hybrid-mobile-development-challenge.html [V]. The Chennai page only says "iQOO phone with Snapdragon NPU (free loaner)" [V]. Which RAM variant is handed out is **not stated anywhere** [V]; assume the 12 GB base [I].
- **SoC:** Qualcomm **SM8850-AC Snapdragon 8 Elite Gen 5** (3 nm), 2×4.6 GHz + 6×3.62 GHz Oryon V3, **Adreno 840** GPU, Hexagon NPU (HTP **v81**). RAM/storage 12/16 GB, 256 GB–1 TB. Ships **Android 16 / OriginOS 6** (first Indian phone on OriginOS). India price from ₹64,999 (12/256). Sources: GSMArena https://www.gsmarena.com/vivo_iqoo_15_5g-14198.php; 91mobiles https://www.91mobiles.com/hub/iqoo-15-launched-in-india-price-availability/ [V].
- **Why it matters:** the SoC is *SM8850*, not SM8750 (8 Elite). Every NPU-specific model file and runtime table must list **SM8850** — several do not (§2).
- **Judging (from Reskilll's strategy guide, https://reskilll.com/blogs/how-to-win-iqoo-city-battles-strategy-guide-phone-first-ai-hackathon/):** End product 30 %, Novelty/impact 20 %, Creative phone use (camera, voice, on-device AI) 15 %, Technical depth 15 %, Office Kit 10 %, Demo 10 %; 25 % comes from HackTracker device telemetry [V]. Reskilll's own model suggestions are "Phi-3, Gemma 2B, and Whisper Tiny" [V] — i.e. judges expect a *local* model; GPU vs NPU is unlikely to be inspected [I].

Quick on-device sanity check at check-in: `adb shell getprop ro.soc.model` → expect `SM8850`; `adb shell getprop ro.build.version.release` → `16`.

## 2. Runtime options (state as of 2026-09-06)

### 2.1 LiteRT-LM (Google AI Edge) — recommended
- **Coordinate/version:** `com.google.ai.edge.litertlm:litertlm-android` — versions 0.8.0 … 0.16.1, **0.17.0** (metadata updated 2026-09-04) — https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml [V]. GitHub releases: v0.14.0 (2026-07-08, Android x86_64/aarch64 for CLI/Python, auto backend selection for vision/audio), v0.15.0 (08-04), v0.16.0 (08-11), v0.16.1 (08-18) — https://api.github.com/repos/google-ai-edge/LiteRT-LM/releases [V]. v0.17.0 has **no GitHub release page yet** [V]; if it misbehaves, pin **0.16.1**.
- **Image input on Android:** yes. `EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)`; `Content.ImageBytes(bytes: ByteArray)` / `Content.ImageFile(absolutePath)`; `Conversation.sendMessage(contents, …, maxOutputToken, thinkingConfig, responseFormat)` — from `kotlin/java/com/google/ai/edge/litertlm/{Config,Conversation,Message,ResponseFormat}.kt` on `main` [V]. Docs: https://developers.google.com/edge/litert-lm/android and https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md [V].
- **Structured JSON:** `ResponseFormat.json(schema: String)` / `.regex(pattern)`; only usable when `ConversationConfig(enableResponseFormat = true)` [V]. Under the hood it is LLGuidance-style constrained decoding (JSON Schema / regex / Lark) — https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/cpp/constrained-decoding.md [V]. This is the single biggest reason to pick LiteRT-LM: the decoder *cannot* emit malformed JSON.
- **Models:**
  - `litert-community/gemma-4-E2B-it-litert-lm`: `gemma-4-E2B-it.litertlm` 2.59 GB; also `-gpu` (2.01 GB, 29 days old — purpose undocumented, verify README before using), `-web`, `_qualcomm_sm8750`, `_qualcomm_qcs8275`, `_Google_Tensor_G5/G6`, `_intel_*`. Apache 2.0, **ungated** [V]. Vision/audio "loaded on demand" [V]. Gemma 4 vision uses variable resolution with token budgets 70/140/280/560/1120 (model card, https://ai.google.dev/gemma/docs/core/model_card_4) [V]; which budget LiteRT-LM applies is **not documented** [V→unknown].
  - `google/gemma-3n-E2B-it-litert-lm`: `gemma-3n-E2B-it-int4.litertlm` **3.66 GB**, Gemma licence (**HF gating: must accept**), images normalised to 256/512/768 px → 256 tokens each; benchmarks S24 Ultra GPU prefill 816 tok/s / decode 15.6 tok/s [V]. Older, bigger, slower decode than Gemma 4 E2B → not chosen.
- **GPU/NPU on Snapdragon 8-series:** GPU = OpenCL ("ML Drift"); manifest needs `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and `libvndksupport.so` [V]. **NPU:** LiteRT-LM's NPU page lists Qualcomm **SM8750, SM8650, SM8550 only** and NPU model builds only for **Gemma3-1B** (text) on Qualcomm; QAIRT 2.42 libs must be fetched from Qualcomm's software centre — https://developers.google.com/edge/litert/next/litert_lm_npu [V]. HF has `Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm` and `gemma3-270m-it-q8.qualcomm.sm8850.litertlm` (text-only) [V] but **no sm8850 build of any vision model**, and the AI Edge Gallery 1.0.19 allowlist has no per-SoC file for Gemma 4 E2B / Gemma 3n [V]. Even the sm8750 build hit a "QNN System library version mismatch" on S25 Ultra (issue #2226, open) [V]. **Conclusion: the VLM runs on the Adreno GPU; NPU is not available for it this week.** [V]
- **Latency (published):** Gemma 4 E2B text-only on S26 Ultra GPU: prefill 3,808 tok/s, decode 52.1 tok/s, TTFT 0.3 s, peak mem 676 MB; CPU: 557 / 46.9 tok/s, 1.8 s, 1.7 GB (HF model card) [V]. Gemma 3n E2B on S24 FE GPU: TTFT 1.9 s, decode 11.9 tok/s (community) [V]. Vision encoder cost is the unknown: on a low-end Qualcomm IoT SoC it was ~8 s/image on CPU (issue #2187, open) [V]. **Estimate for iQOO 15 GPU, ~1 MP photo + ~350-token prompt + 60 output tokens: 2–4 s end-to-end; CPU fallback 6–15 s** [I]. `engine.initialize()` "can take up to 10 seconds" (docs) [V]; use `cacheDir` for faster second load [V].
- **Memory:** 2.59 GB file is mmapped; expect ~1–1.5 GB resident on GPU path with vision loaded [I]. Fine on 12 GB.
- **x86_64 emulator:** CLI/Python support x86_64 Android [V], but the **Kotlin AAR is reported arm64-v8a only** ("x86_64 Android emulators are not [supported]" — react-native-litert-lm README, community) and emulators ship no `libOpenCL.so` [V]. Verify: `unzip -l litertlm-android-0.17.0.aar | grep jni/`. Treat as **not runnable on our AVD**.
- **Pitfalls:** (1) `visionBackend == null` → native SIGSEGV when an image is sent (community) [V]; (2) put the image **before** the text in `Contents` (community) [V]; (3) issue #1874 (v0.10.1): Kotlin could not do Gemma 4 multimodal ("Provided more images than expected") — AI Edge Gallery 1.0.18 (2026-08-10) added image chat with Gemma 4 in Kotlin, so it is presumably fixed [I]; verify on 0.17.0 on day one; (4) Gemma 4 has a thinking mode — pass a `ThinkingConfig` that disables it or you pay for thought tokens (field shape: check `Config.kt`); (5) GPU silently falls back to CPU on some devices — log the backend actually used [V]; (6) set `maxNumTokens` ≈ 2048 to cap KV-cache memory; (7) GGUF is not accepted — `.litertlm` only [V]; (8) small models get arithmetic wrong — also ask for the printed amount string and recompute paise in Kotlin (§3.2).

### 2.2 MediaPipe LLM Inference (`com.google.mediapipe:tasks-genai`)
Latest **0.10.35** (2026-04-27) [V]; docs still show 0.10.27. Supports Gemma 3n `.litertlm` with `setEnableVisionModality(true)`, `setMaxNumImages`, `LlmInferenceSession.addImage(MPImage)`; `adb push … /data/local/tmp/llm/` — https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android [V]. **"Maintenance-only mode; migrate to LiteRT-LM"** [V]. No constrained decoding, Gemma 3n only for vision, x86_64 GPU unsupported [V]. Use only if LiteRT-LM 0.17.0 fails to load on the phone.

### 2.3 Qualcomm GenieX (Hexagon NPU) — the only real NPU-VLM path
- Developer preview, BSD-3. AAR `geniex-android-aar-v0.6.1.aar` (81.9 MB) / `-cpu` (7.3 MB) on GitHub releases (**2026-09-03**); README quotes `"com.qualcomm.qti:geniex-android:0.3.1"` — Maven publishing is marked TODO, so use the AAR [V]. `minSdk 27`, `arm64-v8a` only, supports **SM8750 and SM8850** [V]. Docs: https://geniex.aihub.qualcomm.com/en/run/android/quickstart.md, `/api-reference.md` [V].
- VLM API: `VlmWrapper` + `VlmCreateInput(model_path, mmproj_path)`; images passed as **absolute file paths** in `VlmContent("image", path)`; `GenerationConfig(maxTokens)`; "for VLMs, only `npu` is supported with `qairt`" [V]. No JSON/grammar constraints [V].
- Models: any GGUF (llama.cpp runtime; Q4_0 best on Hexagon) or AI Hub bundles (`qairt`, NPU-only). **Qwen3-VL-4B-Instruct** on AI Hub lists Snapdragon 8 Elite Gen 5 [V]; no 2B VLM bundle found [V]. GGUF `Qwen/Qwen3-VL-2B-Instruct-GGUF` (with mmproj) exists [V] but the platforms page lists Android runtime as "Qualcomm AI Engine Direct (NPU only)" while the quickstart shows `runtime_id = "llama_cpp"` — contradictory; verify [V].
- Blockers: install doc says `android.permission.INTERNET` is **mandatory** (SPEC forbids it; `HubSource.LOCALFS` exists but permission still declared) [V]; no published latency; bundle sizes unknown; no constrained JSON. → **stretch only**.

### 2.4 Others (checked, not recommended)
- **llama.cpp (JNI) + `libmtmd`:** supports Qwen2/2.5-VL, Qwen3-VL GGUF, SmolVLM, Gemma 3/4; GBNF grammars give JSON; Hexagon backend exists (`docs/backend/snapdragon`) but tested on 8 Gen 3/8 Elite, ~10 tok/s [V]. Cost: NDK build + JNI in 30 h. [I: too much integration risk]
- **ExecuTorch** `org.pytorch:executorch-android:1.4.0` (Aug 2026) with a MultiModal runner (Llava, Qwen3-VL export via optimum-executorch) [V]; needs exporting `.pte` yourself; QNN LLM flow is expert-only. Skip.
- **ONNX Runtime GenAI:** Java `MultiModalProcessor` for Phi-3.5/4-vision [V]; Phi-4-mm is ~5.6B, CPU-only on Android, too slow [I]. Skip.
- **MNN / mllm:** Qwen3-VL on OpenCL, Chinese-first docs, benchmark data thin [V]. Skip.
- **FastVLM on 8 Elite Gen 5 NPU (0.12 s TTFT at 1024²)** is a Google/Qualcomm research demo (blog 2025-11-24), not a downloadable Android package [V].

## 3. Recommendation

**Primary:** LiteRT-LM 0.17.0 + Gemma 4 E2B on GPU with constrained JSON. Rationale: only option with *guaranteed* JSON, official Kotlin API, ungated 2.6 GB model, documented 0.3 s text TTFT / 52 tok/s on a 2026 flagship GPU, and nothing to compile. **Fallback:** ML Kit OCR backend (already in SPEC) — automatic when init fails, JSON parse fails, or latency > 8 s. **Stretch:** GenieX/Qwen3-VL-4B on the NPU as a demo-only toggle (needs INTERNET permission in a *separate build flavour*).

### 3.1 Minimal Kotlin (VlmClaimExtractor)
```kotlin
// build.gradle.kts: implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")
// AndroidManifest: <uses-native-library android:name="libOpenCL.so" android:required="false"/>
//                  <uses-native-library android:name="libvndksupport.so" android:required="false"/>
import com.google.ai.edge.litertlm.*

class GemmaVlm(private val ctx: Context, private val modelPath: String) : AutoCloseable {
    private lateinit var engine: Engine
    var backendUsed = "none"; private set

    fun init() {                       // call on Dispatchers.IO; can take ~10 s
        engine = try {
            Engine(EngineConfig(modelPath, backend = Backend.GPU(), visionBackend = Backend.GPU(),
                maxNumTokens = 2048, maxNumImages = 1, cacheDir = ctx.cacheDir.path)).also { it.initialize(); backendUsed = "GPU" }
        } catch (e: Exception) {       // LiteRtLmJniException etc. -> CPU
            Engine(EngineConfig(modelPath, backend = Backend.CPU(), visionBackend = Backend.CPU(),
                maxNumTokens = 2048, maxNumImages = 1, cacheDir = ctx.cacheDir.path)).also { it.initialize(); backendUsed = "CPU" }
        }
    }

    fun extract(bitmap: Bitmap): String {            // returns JSON text
        val scaled = downscale(bitmap, maxSide = 1024)
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        engine.createConversation(ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            enableResponseFormat = true,
            // thinkingConfig = ThinkingConfig(...disable...)  // Gemma 4: check Config.kt for the exact field
        )).use { conv ->
            val reply = conv.sendMessage(
                Contents.of(Content.ImageBytes(jpeg), Content.Text(USER_PROMPT)),   // image BEFORE text
                maxOutputToken = 96,
                responseFormat = ResponseFormat.json(CLAIM_SCHEMA))
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }
    override fun close() = engine.close()
}
```
Push: `adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma-4-E2B-it.litertlm && adb shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm`. If OriginOS blocks app reads of `/data/local/tmp` [I: unverified], push to `/sdcard/Download/` and copy into `context.filesDir` once (debug "Import model" button).

### 3.2 Prompt + schema
`CLAIM_SCHEMA` (JSON Schema string; the decoder enforces it):
```json
{"type":"object","additionalProperties":false,
 "required":["app","amount_text","amount_paise","upi_id","utr","timestamp"],
 "properties":{
  "app":{"type":"string","enum":["gpay","phonepe","unknown"]},
  "amount_text":{"type":"string"},
  "amount_paise":{"type":"integer","minimum":0},
  "upi_id":{"type":["string","null"]},
  "utr":{"type":["string","null"],"pattern":"^[0-9]{10,16}$"},
  "timestamp":{"type":["string","null"]}}}
```
`SYSTEM_PROMPT`:
```
You read a photo of another phone's screen showing a UPI "payment successful" screen and extract fields as JSON. Output only JSON.
Layouts you will see:
- Google Pay (GPay): big amount like "₹1,250" near the top; "Paid to <name>" or "To <name>"; a line "UPI transaction ID" followed by a 12-digit number; sometimes "Google transaction ID" (ignore it); the payee UPI ID like name@okaxis / name@okicici / name@oksbi; date like "12 Sept 2026, 4:32 pm".
- PhonePe: "Paid Successfully" or "Transaction Successful"; amount "₹1,250"; "Transaction ID" starting with T + digits (ignore it); "UTR" or "UPI Ref No" followed by a 12-digit number — this is the utr; payee UPI ID like name@ybl / name@axl / name@ibl; date like "12 Sept 2026 04:32 PM".
Rules: amount_text = the amount exactly as printed. amount_paise = rupees × 100 as an integer (₹1,250.00 → 125000; ₹80 → 8000). utr = the 12-digit UTR/UPI transaction ID digits only; if you cannot read all digits, null. upi_id = the payee's UPI ID (contains @); if none visible, null. timestamp = the date/time text as printed or null. Never invent digits.
Examples:
{"app":"gpay","amount_text":"₹1,250","amount_paise":125000,"upi_id":"murugan.stores@okaxis","utr":"426112345678","timestamp":"12 Sept 2026, 4:32 pm"}
{"app":"phonepe","amount_text":"₹80","amount_paise":8000,"upi_id":"kavitha@ybl","utr":"526198765432","timestamp":"12 Sept 2026 04:32 PM"}
```
`USER_PROMPT`: `Extract the fields from this payment screen photo. Output only the JSON object.`
Kotlin post-processing: recompute paise from `amount_text` (strip `₹`, commas) and prefer it when it disagrees with `amount_paise`; normalise `utr` to digits; a 10-digit `utr` is still accepted by the schema so the reconciler's prefix/suffix rule can work.

### 3.3 GenieX stretch (only if primary is green)
Separate product flavour `npu` with INTERNET permission; `ModelPullInput(model_name="ai-hub-models/Qwen3-VL-4B-Instruct", hub=HubSource.AUTO, chipset="SM8850")`, `VlmWrapper` with `VlmContent("image", absPath)`; parse JSON leniently (no constraints available). Judge-facing value: "runs on the Hexagon NPU". Budget ≤ 3 h; abandon at the first native crash.

## 4. Benchmark plan for the AI lead (≤ 2 h on the iQOO 15)

Prep on the laptop *before* check-in: download `gemma-4-E2B-it.litertlm` (2.59 GB) and AI Edge Gallery `ai-edge-gallery-sm8850.apk` v1.0.19 (2026-09-02, https://github.com/google-ai-edge/gallery/releases) [V]; 10 fixture **photos** (phone-of-a-phone, not screenshots: 5 GPay, 5 PhonePe; include glare and a tilted one); build the debug APK with a "Bench" screen that runs `extract()` on fixtures and logs `CrossCheckVlm: init_ms, backend, ttft_ms, total_ms, json`.

| Step | Time | Measure | Pass |
|---|---|---|---|
| 0. Identify device | 5 min | `getprop ro.soc.model`, RAM (`dumpsys meminfo` total) | SM8850, ≥ 12 GB |
| 1. Gallery sanity | 15 min | Import Gemma-4-E2B in Gallery → "Ask Image" with a GPay photo; note its stats (TTFT, tok/s) on GPU | Sensible fields read; TTFT < 3 s |
| 2. Our init | 10 min | `adb push` model; 3× cold/warm `init()`; log backend | Warm init < 10 s; backend = GPU |
| 3. Accuracy + latency | 35 min | 10 fixtures × 1 run; JSON validity, amount, utr, upi_id vs ground truth; p50/p95 total_ms | JSON 10/10; amount ≥ 9/10; utr exact ≥ 8/10; p95 < 5 s |
| 4. Thermal/repeat | 10 min | 10 runs back-to-back on one image; latency drift | Last run < 1.5× first |
| 5. CPU fallback | 10 min | Force `Backend.CPU()`; same 3 fixtures | < 15 s, same fields (documents fallback cost) |
| 6. Memory | 5 min | `adb shell dumpsys meminfo com.crosscheck.app` after 3 runs | PSS < 3 GB, no OOM |
| 7. Decide | 10 min | If utr < 8/10: hybrid — VLM for amount/upi_id/app, ML Kit OCR for the 12-digit UTR; if JSON/latency fail: OCR primary, VLM off | Written down in this file's "Results" section |

If step 2 fails on 0.17.0, retry with 0.16.1; if `sendMessage` with an image throws "more images than expected", that is issue #1874 — switch to Gemma 3n E2B `.litertlm` (needs HF licence acceptance) as the vision model.

## 5. Offline Tamil STT (`ta-IN`)

- **API facts [V]:** `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31) forces on-device and fails if no local engine; `checkRecognitionSupport(intent, executor, cb)` (API 33) returns `RecognitionSupport` with `installedOnDeviceLanguages`, `supportedOnDeviceLanguages` (downloadable), `pendingOnDeviceLanguages`, `onlineLanguages`; `triggerModelDownload(intent)` (API 33). Use `RecognizerIntent.EXTRA_LANGUAGE = "ta-IN"`.
- **Is Tamil offline?** *Not verifiable from documentation.* Google's published on-device lists exclude Tamil: Gboard advanced voice typing = EN/FR/DE/IT/JA/ES (Pixel 6+) [V]; ML Kit GenAI Speech Recognition (alpha, `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`) has no `ta-IN` [V]. Google's classic offline packs may include Tamil (voice typing added Tamil in 2017, online) — **unknown for offline on OriginOS** [I]. Cloud recognition is off-limits (no INTERNET). → On day one run `checkRecognitionSupport` for `ta-IN`, `hi-IN`, `en-IN` and call `triggerModelDownload` while on venue Wi-Fi.
- **Fallback:** Vosk has **no Tamil model** (vosk-api issue #1936 open) [V]. Options: **whisper.cpp** (Tamil is one of Whisper's 99 languages; `ggml-small-q5_1` ≈ 190 MB; Tamil WER on small is mediocre [I]) or **sherpa-onnx + AI4Bharat IndicConformer-600M** (MIT, Tamil covered, ~493 MB ONNX per language via community conversion) [V]. For the demo, the voice query needs only a name + an amount, so constrain: parse digits/amount language-neutrally and accept `en-IN` if Tamil on-device is unavailable at check-in.

## Sources (all fetched 2026-09-05/06)
Reskilll launch post (2026-08-22), Chennai page, strategy guide; FoneArena; GSMArena iQOO 15; 91mobiles iQOO 15 India launch; Google Maven metadata for `litertlm-android` and `tasks-genai`; GitHub API releases for LiteRT-LM, GenieX, AI Edge Gallery; LiteRT-LM `Config.kt`, `Conversation.kt`, `Message.kt`, `ResponseFormat.kt`, `docs/api/kotlin/getting_started.md`, `docs/api/cpp/constrained-decoding.md`, issues #1874, #2187, #2226; https://developers.google.com/edge/litert-lm/android; https://developers.google.com/edge/litert/next/litert_lm_npu; https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android; HF `litert-community/gemma-4-E2B-it-litert-lm` (+ tree), `google/gemma-3n-E2B-it-litert-lm` (+ tree), `litert-community/Gemma3-1B-IT`, `Qwen/Qwen3-VL-2B-Instruct-GGUF`; Gemma 4 model card; Gallery `model_allowlists/1_0_19.json`; GenieX README, `/en/get-started/platforms.md`, `/en/run/android/{install,quickstart,api-reference}.md`; AI Hub Qwen3-VL-4B-Instruct page; Google blog "Unlocking Peak Performance on Qualcomm NPU with LiteRT" (2025-11-24); llama.cpp `docs/multimodal.md`, `docs/backend/snapdragon`; ExecuTorch Maven Central; ML Kit GenAI Speech Recognition page; Gboard advanced voice typing help; vosk-api #1936; sherpa-onnx discussion #3199; AI4Bharat IndicConformer HF; community posts (react-native-litert-lm README, dev.to "Gemma 4 on Android tricks", urjalabs NativeLM vision post) marked as community where used.
