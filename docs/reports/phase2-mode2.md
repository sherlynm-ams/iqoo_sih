# Phase 2 report — Mode 2 (build-order step 2, spoken repeat-offender prefix from step 5, receipt view from step 6)

Date: 2026-09-06 · Emulator: `emulator-5554` = `Medium_Phone_API_36.1` (Android 16 / API 36, Google Play x86_64) · Toolchain: Gradle 8.11.1, AGP 8.9.1, **Kotlin 2.3.10, KSP 2.3.5** (changed — see deviations), JDK 17.

## Status

| Gate | Result |
|---|---|
| `assembleDebug --warning-mode=all` | green, **zero** compiler / Gradle warnings |
| `testDebugUnitTest` | green — **103 tests**, 0 failures (Phase 1's 74 + 29 new `ClaimFieldParserTest`) |
| (a) `adb -s emulator-5554 install -r` on the x86_64 AVD | `Success` (no flavours / ABI splits needed — the LiteRT-LM AAR ships `jni/x86_64` too) |
| (b) fixture suite, OCR backend | **10/10 PASS**, every field strict (amount, UTR, app, status, otherIds, payee VPA, payer name, bank mask, timestamp, verdict) |
| (c) screenshots | `artefacts/phase2-verify-genuine.png` (MATCH), `artefacts/phase2-verify-wrong-utr.png` (NO MATCH + repeat-offender note), `artefacts/phase2-receipt.png` |
| (d) logcat | `CrossCheckTTS onStart/onDone` with the Tamil verdict; `CrossCheckRouter: mode=AUTO abi=x86_64 arm64=false model=false -> ocr` |
| VLM wiring | compiles against LiteRT-LM 0.17.0; the real `Engine.initialize()` was driven on the AVD with a junk model file and failed *soft* (`LiteRtLmJniException` on GPU, then CPU → OCR fallback, verdict still spoken) |
| Manifest | **no INTERNET** (a library merged it in; stripped with `tools:node="remove"`) |

## What was built

### Dependencies / toolchain (`gradle/libs.versions.toml`, `app/build.gradle.kts`, manifests)
- `:app` now uses `libs.bundles.camerax` (core, camera2, lifecycle, view 1.4.2), `com.google.mlkit:text-recognition:16.0.1` (bundled Latin model, no Play-services download) and `com.google.ai.edge.litertlm:litertlm-android:0.17.0`.
- `AndroidManifest.xml`: the two `<uses-native-library android:name="libOpenCL.so|libvndksupport.so" android:required="false"/>` lines from SPEC §4, plus `<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`.
- `app/src/debug/AndroidManifest.xml` (new): `READ_MEDIA_IMAGES`, debug-only, for the fixture suite.
- Kotlin 2.3.10 / KSP 2.3.5; `kotlinOptions {}` replaced by `kotlin { compilerOptions { jvmTarget; -Xannotation-default-target=param-property } }`.

### `verify/`
- **`ClaimFieldParser`** (pure Kotlin) — `OcrFrame(lines: List<OcrLine>, width, height)` → `Claim?`, implementing payment-screens.md §3b: digit-run normalisation (`O/o→0, I/l→1, S→5, B→8` only inside ≥6-char mostly-digit runs), space-collapsing inside digit runs; amount = tallest currency-marked figure in the top 45 % (candidates next to "Debited from"/"From" lose), with a **bare-headline fallback** (a line that is only a ≤7-digit figure, ≥1.5× the median line height) because ML Kit drops the "₹" glyph; UTR = best-scoring 12-digit run (label on same/previous overlapping line +3, inside a `T…` id or ≥13-digit run −3, `91[6-9]…` −2, current-year prefix +1, accept only ≥0); `T\d{20,22}` and `CICAg…` → `otherIds` (never UTR); app by label/ID hit count; status precedence failed > pending > success; timestamps in Asia/Kolkata for `6 Sept 2026, 12:41 pm`, `06 Sep 2026, 12:41 pm`, `December 8, 2020 at 7:25 PM`, `Today/Yesterday, 10:30 AM`, `06-09-26 12:41`; payee VPA (`@` or `©`, optional spaces — ML Kit yields `sherwin @ybl`); payer name from `From: NAME (…)`; bank mask `XX4321` / `XXXXXX4321`; `payerVpa` always null. `quickScan()` gives the auto-capture signal.
- **`OcrClaimExtractor`** — ML Kit `TextRecognition` on a `Bitmap` (or an `InputImage` from the analysis stream) → lines with boxes → parser. Logs every line at `CrossCheckOcr:D` (the unit-test input format) and the claim at `:I`.
- **`VlmClaimExtractor`** — vlm-runtime.md §3.1 verbatim, adapted: `EngineConfig(modelPath, Backend.GPU(), visionBackend = Backend.GPU(), maxNumTokens = 2048, maxNumImages = 1, cacheDir)`, CPU fallback, backend logged (`vlm-gpu` / `vlm-cpu`), lazy init on `Dispatchers.IO` under a mutex (a failed path is not retried until the path changes), model path from Settings, downscale 1024 / JPEG 85, `ConversationConfig(systemInstruction, SamplerConfig(1, 1.0, 0.0), enableResponseFormat = true, thinkingConfig = ThinkingConfig(enableThinking = false))`, `sendMessage(Contents.of(ImageBytes, Text), maxOutputToken = 160, responseFormat = ResponseFormat.json(CLAIM_SCHEMA))`, image before text, `withTimeout(8_000)`. Post-processing: paise recomputed from `amount_text` (preferred), utr → digits, timestamp through the same parser, schema enums → `ClaimApp`/`ClaimStatus`. Every `Throwable` (incl. `UnsatisfiedLinkError`) is caught and logged at `CrossCheckVlm`.
- **`ExtractorRouter`** (`ClaimExtractor`) — mode from Settings {AUTO, VLM, OCR}; AUTO → VLM iff `Build.SUPPORTED_ABIS[0] == "arm64-v8a"` && model file exists && init OK; VLM → always attempts; any exception / timeout / null → OCR. `run()` returns `Extraction(claim, backend, decision)`; `backendName` is the last backend used; decisions at `CrossCheckRouter`.
- **`VerdictSpeech`** — the spoken sentence in the current speech locale: MATCH / LIKELY MATCH + discrepancy / NO MATCH + reason (`PENDING` → the dedicated "still pending" string), prefixed with `tts_repeat_offender` when `priorFailures > 0`. Strings are the Phase 1 ones in `values/`, `values-ta/`, `values-hi/`.
- **`FixtureSuite`** (debug action) — resolves `fixtures.json` + PNGs from `/sdcard/Download/crosscheck-fixtures/` or the app's external files dir, seeds p1/p2 from `fixtures/README.md` if their UTRs are absent, runs router → `VerificationService` per image, compares every expected field and logs `CrossCheckFixture: <file> backend=<b> claim=<…> verdict=<…> expected=<…> PASS|FAIL <mismatches>` plus `summary N/10 PASS`.

### `ui/`
- **`VerifyScreen`** + `VerifyViewModel` — CameraX `PreviewView` (back camera, FILL_CENTER), `ImageCapture` MAXIMIZE_QUALITY with a `ResolutionSelector` targeting 1080×1920 (closest higher then lower), `ImageAnalysis` KEEP_ONLY_LATEST throttled to one ML Kit `quickScan` per 700 ms, **auto-capture** after two consecutive positive frames, Capture button, tap-to-focus (`FocusMeteringAction` AF+AE, 3 s auto-cancel), exposure compensation −1 EV when supported, torch off. States: Preview → Extracting (spinner + extractor mode) → verdict card (40 sp colour-coded MATCH / LIKELY MATCH / NO MATCH, reason, repeat-offender note, extracted fields, backend) → "View receipt" / "Verify another"; Failed (camera error or unreadable frame) with retry; camera-permission prompt. Debug only: **Fixture image** (`PickVisualMedia`) and **Run suite** top-bar actions.
- **`ReceiptScreen`** — formatted `ClaimEntity` row: claim id, verdict + reason, verified-at, amount, UTR, claimed time, payee, payer (name · bank mask), verdict/reason codes, payer key + key kind, extractor backend, footer.
- `Nav.kt` — Verify placeholder replaced; new `receipt/{claimId}` route. `SettingsScreen` — appended "Claim extractor" section (Auto / VLM / OCR radios, model path + found/absent, debug "Import model file" via the document picker). `PlaceholderScreen` and the `placeholder_verify_*` strings stay (the Voice route still uses the composable).

### `data/`, `di/`, resources
- `Settings.extractorMode` (`AUTO|VLM|OCR`), `Settings.vlmModelPath` (default `/data/local/tmp/llm/gemma-4-E2B-it.litertlm`) + setters; `ExtractorSettings` constants.
- `AppContainer`: `ocrExtractor`, `vlmExtractor`, `extractor` (router), `fixtureSuite`, `importModel(uri)` (copies into `filesDir/llm/` and updates the path). Append-only edits.
- `values/strings.xml`: Phase 2 strings appended at the end (UI only; the spoken strings already existed in all three locales).

### File map (new = `+`, edited = `~`)
```
~ gradle/libs.versions.toml            ~ app/build.gradle.kts            ~ app/src/main/AndroidManifest.xml
+ app/src/debug/AndroidManifest.xml
+ verify/ClaimFieldParser.kt  + verify/OcrClaimExtractor.kt  + verify/VlmClaimExtractor.kt
+ verify/ExtractorRouter.kt   + verify/VerdictSpeech.kt      + verify/FixtureSuite.kt
+ ui/VerifyScreen.kt          + ui/ReceiptScreen.kt          ~ ui/Nav.kt   ~ ui/SettingsScreen.kt
~ data/SettingsRepository.kt  ~ di/AppContainer.kt           ~ res/values/strings.xml (appended)
+ app/src/test/java/com/crosscheck/app/verify/ClaimFieldParserTest.kt (29 tests)
+ artefacts/phase2-verify-genuine.png · phase2-verify-wrong-utr.png · phase2-receipt.png
~ docs/BUILD.md  + docs/reports/phase2-mode2.md
```
Not touched: `LogScreen.kt`, `voice/` (only `Speaker.speak/localizedString` are called), `export/`, `docs/SPEC.md` (its working-tree diff is the coordinator's Mode 1 sender-ID update).

## How each gate was verified (observed output)

### Build + tests
```
> Task :app:assembleDebug
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 14s
```
`app/build/test-results/testDebugUnitTest`: MoneyTest 2 · BankSmsParserTest 34 · PaymentIngestorTest 10 · **ClaimFieldParserTest 29** · ReconcilerTest 21 · VerificationServiceTest 7 → **103 / 0 failures**. The 29 cover all 10 fixtures as hand-written line lists (incl. the `*_photo` geometry), the *real* ML Kit output of `gpay_genuine.png` (bare `"500"`), details block cut off → `utr = null`, a 10-digit run, a 12-digit run inside a split `T…` id, an 18-digit reference, a `91…` mobile-shaped run, OCR confusions (`4261l23456O8`, `T26O9…`), spaces inside a UTR, `?500` / `Rs. 499.50` / `₹1,50,000`, headline-vs-small figure, non-headline bare numbers, status precedence, app detection, `©` and `sherwin @ybl`, `quickScan`, and 12 timestamp forms.

### (a) Install on the x86_64 AVD
```
adb -s emulator-5554 install -r app-debug.apk   → Performing Streamed Install / Success
dumpsys package: primaryCpuAbi=x86_64; READ_MEDIA_IMAGES granted=true; CAMERA granted=true; (no android.permission.INTERNET)
```
APK 131.8 MB. `unzip -l` of the AAR: `jni/arm64-v8a/liblitertlm_jni.so` (21.5 MB) **and** `jni/x86_64/liblitertlm_jni.so` (25.6 MB) — the "arm64-only" note in vlm-runtime.md §2.1 is wrong for 0.16.1/0.17.0; no `NO_MATCHING_ABIS`, so no product flavours were needed.

### (b) Fixture suite — 10/10 PASS (Verify → Run suite; `adb logcat -d -s CrossCheckFixture:V`)
```
CrossCheckRouter: mode=AUTO abi=x86_64 arm64=false model=false -> ocr
CrossCheckFixture: gpay_genuine.png backend=ocr claim=Claim(amountPaise=50000, upiId=sherwin@oksbi, utr=426112345678, claimedTimestamp=1788678660000, payerName=MURUGAN, payerBankMask=XX4321, payerVpa=null, app=GPAY, status=SUCCESS, otherIds=[CICAgKDx1b3fSA]) verdict=Match expected=Match PASS
CrossCheckFixture: gpay_wrong_utr.png backend=ocr claim=Claim(amountPaise=50000, upiId=sherwin@oksbi, utr=519876543210, …, otherIds=[CICAgMzQ9pXbQg]) verdict=NoMatch(WRONG_UTR) expected=NoMatch(WRONG_UTR) PASS
CrossCheckFixture: gpay_wrong_amount.png backend=ocr claim=Claim(amountPaise=500000, …, utr=426112345678, …) verdict=NoMatch(WRONG_AMOUNT) expected=NoMatch(WRONG_AMOUNT) PASS
CrossCheckFixture: gpay_pending.png backend=ocr claim=Claim(amountPaise=50000, …, utr=null, …, status=PENDING, otherIds=[CICAgLm2c7vXRw]) verdict=NoMatch(PENDING) expected=NoMatch(PENDING) PASS
CrossCheckFixture: phonepe_genuine.png backend=ocr claim=Claim(amountPaise=50000, upiId=sherwin@ybl, utr=426112345678, …, payerName=null, payerBankMask=XXXXXX4321, …, app=PHONEPE, status=SUCCESS, otherIds=[T2609061241123456789012]) verdict=Match expected=Match PASS
CrossCheckFixture: phonepe_wrong_utr.png … utr=519876543210 … otherIds=[T2609061240987654321098]) verdict=NoMatch(WRONG_UTR) expected=NoMatch(WRONG_UTR) PASS
CrossCheckFixture: phonepe_wrong_amount.png … amountPaise=500000 … verdict=NoMatch(WRONG_AMOUNT) expected=NoMatch(WRONG_AMOUNT) PASS
CrossCheckFixture: phonepe_pending.png … utr=null … status=PENDING, otherIds=[T2609061241555555555555]) verdict=NoMatch(PENDING) expected=NoMatch(PENDING) PASS
CrossCheckFixture: gpay_genuine_photo.png … verdict=Match expected=Match PASS
CrossCheckFixture: phonepe_genuine_photo.png … verdict=Match expected=Match PASS
CrossCheckFixture: summary 10/10 PASS manifest=/storage/emulated/0/Android/data/com.crosscheck.app/files/crosscheck-fixtures/fixtures.json
```
(`1788678660000` = 2026-09-06 12:41 IST.) Two parser fixes came out of the first runs, both now unit-tested: ML Kit returns the headline as a bare `"500"` / `"5,000"` without "₹" (box `[395,597,681,695]`), and the PhonePe handle as `"sherwin @ybl"`. Per image the x86_64 AVD needs ~2 s warm / ~15 s cold for ML Kit.

### (c) Image-picker flow + screenshots
Verify → **Fixture image** → picker → `gpay_genuine.png` → Done:
```
CrossCheckRouter: mode=AUTO abi=x86_64 arm64=false model=false -> ocr
CrossCheckOcr: claim=Claim(amountPaise=50000, upiId=sherwin@oksbi, utr=426112345678, …, app=GPAY, status=SUCCESS, otherIds=[CICAgKDx1b3fSA])
CrossCheckTTS: onStart id=cc-1 text="குறிப்பு: இந்த நபர் இதற்கு முன் 6 முறை சரிபார்ப்பில் தோல்வியடைந்துள்ளார். பொருந்துகிறது — பணம் உறுதி செய்யப்பட்டது."
CrossCheckTTS: onDone  id=cc-1 text="…"
```
→ `artefacts/phase2-verify-genuine.png`: green **MATCH** card, "MATCH — payment confirmed.", "This payer has failed verification 6 time(s) before." (MURUGAN is keyed by NAME and the suite runs had flagged the three GPay fakes twice), fields ₹500 / 426112345678 / Google Pay / Success / 6 Sep 2026, 12:41 PM / sherwin@oksbi / MURUGAN · XX4321 / CICAgKDx1b3fSA / backend `ocr`.

Verify another → picker → `phonepe_wrong_utr.png`:
```
CrossCheckOcr: claim=Claim(amountPaise=50000, upiId=sherwin@ybl, utr=519876543210, …, payerBankMask=XXXXXX4321, …, app=PHONEPE, …)
CrossCheckTTS: onStart id=cc-2 text="குறிப்பு: இந்த நபர் இதற்கு முன் 6 முறை சரிபார்ப்பில் தோல்வியடைந்துள்ளார். பொருந்தவில்லை — UTR பொருந்தவில்லை."
CrossCheckTTS: onDone  id=cc-2 …
```
→ `artefacts/phase2-verify-wrong-utr.png`: red **NO MATCH**, "UTR does not match", repeat-offender note (key `XXXXXX4321`, BANK_MASK). **View receipt** → `artefacts/phase2-receipt.png`: "Claim #22 · NO MATCH · UTR does not match", Verified at 6 Sep 2026, 2:23 AM, ₹500, UTR 519876543210, claimed time, payee `sherwin@ybl`, payer `XXXXXX4321`, `NO_MATCH` / `WRONG_UTR`, payer key `XXXXXX4321` / `BANK_MASK`, backend `ocr`.

### Camera path on the AVD
The virtual-scene back camera binds (`PreviewView` shows the scene), the analysis stream runs without errors, and **Capture** takes a real picture (`CrossCheckCamera: captured 1280x960 rotation=90` — the AVD's virtual camera tops out below the 1080×1920 the `ResolutionSelector` asks for, so the CLOSEST_HIGHER_THEN_LOWER fallback engaged; a phone camera will give ≥1080p): router → OCR → `CrossCheckOcr: claim=null` → "Could not read a payment screen…" with retry. (A first version aborted its own capture — switching to *Extracting* disposed the camera composable and unbound the in-flight `takePicture` (`ImageCaptureException: Camera is closed`) — fixed by gating captures with a flag and leaving the preview only once the bitmap exists.) Auto-capture never fires on the virtual scene because it has no ₹ figure / 12-digit run, which is the intended negative.

### VLM fail-soft on the AVD (Settings → extractor VLM, 4 KB junk file at the model path)
```
W CrossCheckVlm: init on GPU failed: LiteRtLmJniException: Failed to create engine: INVALID_ARGUMENT: Invalid magic number or failed to read:
W CrossCheckVlm: init on CPU failed: LiteRtLmJniException: Failed to create engine: INVALID_ARGUMENT: Invalid magic number or failed to read:
E CrossCheckVlm: engine init failed on GPU and CPU for /data/local/tmp/llm/gemma-4-E2B-it.litertlm
I CrossCheckRouter: mode=VLM abi=x86_64 model=true: VLM returned nothing -> ocr
I CrossCheckOcr: claim=Claim(amountPaise=50000, … app=GPAY …)
I CrossCheckTTS: onStart id=cc-3 text="… பொருந்துகிறது — பணம் உறுதி செய்யப்பட்டது."
```
So the native library loads, `EngineConfig`/`Engine` are the right API, the exception path is caught, and the fallback + verdict still happen. With the model absent the router logs `model file absent at … -> VLM unavailable` instead. The junk file was removed and the mode reset to Auto afterwards.

## VLM wiring status and what the AI lead must do on the iQOO 15

Status: **compiled, wired, fail-soft proven; never executed with the real model** (no arm64 device here; SPEC says not to push the 2.6 GB file to the AVD).

1. Push the model (2–3 min over USB):
   `adb push "…\CrossCheck\models\gemma-4-E2B-it.litertlm" /data/local/tmp/llm/gemma-4-E2B-it.litertlm; adb shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm`
   If Settings shows "Model file not found" on OriginOS, copy the file to the phone's Downloads and use Settings → **Import model file (debug)** (document picker → `filesDir/llm/`, path updated).
2. `adb install -r app-debug.apk`, grant CAMERA, open Settings → Claim extractor → **Auto** (default). "Model file found" must be green.
3. `adb logcat -s CrossCheckVlm CrossCheckRouter CrossCheckTTS`, then Verify a claim (camera or Fixture image). Expected:
   `CrossCheckVlm: engine ready backend=vlm-gpu init_ms=<~5000–10000 cold> model=/data/local/tmp/llm/…`
   `CrossCheckVlm: backend=vlm-gpu total_ms=<2000–4000> json={"app":"gpay","amount_text":"₹500","amount_paise":50000,"upi_id":"sherwin@oksbi","utr":"426112345678","timestamp":"6 Sept 2026, 12:41 pm","status":"success","payer_name":"MURUGAN","payer_bank_mask":"XX4321"}`
   `CrossCheckRouter: mode=AUTO abi=arm64-v8a -> vlm-gpu` — the receipt then shows "Extractor backend: vlm-gpu".
   GPU init failure → `init on GPU failed: …` then `backend=vlm-cpu`; total failure → `VLM returned nothing -> ocr` (OCR still verifies, so the demo never blocks).
4. Benchmark per vlm-runtime.md §4 with the 10 fixtures (Run suite works on the phone too — it logs the backend per image). Knobs: `VlmClaimExtractor.MAX_OUTPUT_TOKENS` (160; raise if `json=` is truncated → parse failure → OCR), `DEFAULT_TIMEOUT_MILLIS` (8 s), `MAX_SIDE` (1024). If `sendMessage` throws "more images than expected" that is LiteRT-LM issue #1874 → pin `litertlm = "0.16.1"` (same API, verified with javap).
5. Note for the pitch: "on-device, GPU-accelerated" — never NPU.

## SPEC deviations (deliberate, with reasons)

1. **Kotlin 2.1.0 → 2.3.10, KSP 2.1.0-1.0.29 → 2.3.5.** Both LiteRT-LM releases are compiled with newer Kotlin (0.16.1 = metadata 2.3.0, 0.17.0 = 2.4.0); the 2.1 compiler rejects both (`Module was compiled with an incompatible version of Kotlin`). 2.3.10's compiler + build-tools were already in `D:\gradle-cache`; only KSP, the compose-compiler plugin and the AAR were downloaded. KSP 2.3.10 refuses AGP < 8.10 and AGP 8.10+ needs Gradle 8.13 (not cached), so KSP 2.3.5 keeps AGP 8.9.1 / Gradle 8.11.1. Side effects: `kotlinOptions` → `compilerOptions`; `-Xannotation-default-target=param-property` silences Kotlin 2.3's annotation-target notice on `@StringRes val` constructor properties (incl. Phase 1's `AppPermissions.kt`, untouched).
2. **LiteRT-LM AAR is not arm64-only** — it carries x86_64 JNI. No `emu`/`phone` flavours; the runtime even executes engine init on the AVD. Running the CPU backend on the AVD with the real model is therefore plausible but untried.
3. **JSON schema extended** with `status`, `payer_name`, `payer_bank_mask` (payment-screens.md §3a recommends `status` and the payer identity; without `status` the VLM path could never honour the pending → NoMatch rule). The SYSTEM_PROMPT is vlm-runtime.md §3.2 verbatim plus one rule sentence for the three fields and the two examples extended. **`maxOutputToken` 96 → 160**: Gemma tokenises digits one per token; the object is ~110–130 tokens and a truncated object is a wasted pass.
4. **INTERNET removed via `tools:node="remove"`** — `com.google.android.datatransport:transport-backend-cct` (ML Kit's telemetry transport) merges it in. `ACCESS_NETWORK_STATE` (normal-level, connectivity state only, same library) is left in place because that library's job scheduler queries `ConnectivityManager` and would throw inside a `JobService` without it.
5. **Debug-only `READ_MEDIA_IMAGES`** and a **second fixture location**: scoped storage lets the app read PNGs in Download with that grant but never a non-media `fixtures.json`, so the suite resolves each file from Download first, then `Android/data/com.crosscheck.app/files/crosscheck-fixtures/` (BUILD.md pushes to both; Download is what the photo picker shows).
6. **"Import model from Downloads"** is a document picker (`OpenDocument`) rather than a fixed path, for the same scoped-storage reason; it copies into `filesDir/llm/` and repoints the setting.
7. `Claim.otherIds` is empty on the VLM path (schema has no such field; the receipt row does not persist it anyway).
8. The repeat-offender prefix is spoken and shown on **every** verdict when the payer key has prior failures, including MATCH — SPEC §1 says "if the payer key has prior failures, prepend", not "on NoMatch only".
9. `VerifyUiState`/`VerifyViewModel` live in `ui/VerifyScreen.kt`; `VerdictSpeech` and `FixtureSuite` live in `verify/` (they are Mode 2 logic that happens to touch `Speaker`/files).

## Known gaps / not verified

- The VLM has not produced a single real JSON: accuracy, latency, GPU-vs-CPU selection, thinking-off behaviour and the 160-token budget are all for the iQOO benchmark. `withTimeout` cannot interrupt a native `sendMessage`; on timeout the result is discarded and the thread finishes in the background.
- `Engine` is kept open for the process lifetime once initialised (no explicit close on app exit).
- Auto-capture, tap-to-focus and −1 EV are implemented but only exercised against the AVD's virtual scene (no payment screen to trigger auto-capture; the virtual camera reports no exposure-compensation range). Real-phone photo tests (glare, moiré, tilt) are pending.
- ML Kit on the x86_64 AVD is slow (cold ~15 s/image); irrelevant on the phone.
- The LikelyMatch tier and the timestamp-tolerance paths are unit-tested (`ReconcilerTest`) but not driven on the emulator (no fixture yields them).
- Phase 2 UI strings are English only (`values/`); all *spoken* strings are localised (ta/hi) as before.
- Fixture-suite runs write real `claims`/`flagged_vpas` rows (debug builds only) — the Log screen's flagged list fills up; a debug "clear" action was not built.
- Lint was not run; no instrumented tests.
- `PickVisualMedia` on this API 36 image is multi-select-styled (needs Done); on other images a single tap may return immediately.
