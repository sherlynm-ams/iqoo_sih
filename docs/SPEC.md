# CrossCheck — build spec (source of truth for every agent)

CrossCheck is a **UPI payment-claim verifier** for sellers who take payments on a personal UPI ID (no merchant account, no soundbox). Native Android/Kotlin, Room, **on-device only** — no backend, no network calls in core logic.

Read this whole file before writing code. If something here is wrong or impossible, say so in your report — do not silently deviate.

---

## 1. Product behaviour

### Mode 1 — passive confirmation (default, every incoming payment, no camera)
- A background source watches for bank **credit-alert** messages on this device only:
  - `SmsReceiverSource` — `BroadcastReceiver` for `SMS_RECEIVED` (needs `RECEIVE_SMS`, optionally `READ_SMS` for backfill).
  - `NotificationListenerSource` — `NotificationListenerService` reading bank/UPI app notifications (the production path; Play-policy-safe).
  - Both implement one interface: `PaymentSignalSource`.
- Parse **amount + sender/payer name + UTR** with a **rules/regex parser** (`BankSmsParser`). Bank SMS is templated — **no ML model here**.
- Save to `payments` table: `{id, amountPaise, sender, utr, timestamp, source ("sms"|"notification"), raw}`.
- Speak via on-device TTS: **"₹[amount] received from [sender]."**

### Mode 2 — dispute verification (triggered only when a customer claims they paid)
- Camera opens; seller points it at the customer's phone showing a **GPay** or **PhonePe** "payment successful" screen. Demo scope = **these 2 layouts only**.
- `ClaimExtractor` returns structured `Claim {amountPaise, upiId, utr, claimedTimestamp, payerName, payerBankMask, app ("gpay"|"phonepe"|"unknown"), status ("success"|"pending"|"failed"|"unknown"), otherIds}` from a camera frame. **Research finding (docs/research/payment-screens.md):** the payer's own VPA is usually NOT on the payer's screen — GPay shows `From: NAME (Bank XX4321)`, PhonePe shows `Debited from Bank XXXXXX4321`; the `@handle` visible is normally the *seller's own*. So `upiId` is the payee handle if seen; the payer identity is `payerName` + `payerBankMask`.
  - **Primary backend:** small on-device vision-language model, one pass, image → JSON (Gemma-3n / Qwen2-VL-class, quantized, via LiteRT-LM / MediaPipe LLM Inference / ExecuTorch — the research report in `docs/research/vlm-runtime.md` decides which).
  - **Fallback backend (same interface):** ML Kit on-device Text Recognition + a structured field parser. Exists so Mode 2 can be *run and verified on the x86_64 emulator today* (no NPU, no ARM) and as an automatic fallback on stage if the VLM fails to load or returns unparseable JSON. It is a backend behind `ClaimExtractor`, not a second pipeline in the UI.
- `Reconciler` compares the claim against the local `payments` table → **tiered verdict** (`sealed class Verdict`):
  - `Match` — amount AND UTR match a stored payment → "MATCH — payment confirmed."
  - `LikelyMatch(reason)` — amount matches but timestamp differs by a few minutes, or UTR is partial/prefix match → "LIKELY MATCH — [specific discrepancy], confirm manually."
  - `NoMatch(reason)` — no matching amount/UTR at all → "NO MATCH — nothing received for this claim." Reason is one of: wrong amount / wrong UTR / nothing received / timestamp mismatch / **pending** (claim `status == pending` → always `NoMatch(PENDING)`: "NO MATCH — payment still pending on the customer's side"; a pending screen is a known scam pattern and must never map to Match, regardless of amount).
- Speak the verdict via TTS with the specific reason.
- **Repeat-offender memory:** `flagged_vpas` table keyed by a **payer key** = first available of: payer VPA (only if it is clearly the *payer's*, not the seller's own handle) → normalised `payerName` → `payerBankMask` (e.g. `SBI-XX4321`); store `keyKind` alongside. `failCount`, `lastFailedAt`; increment on every `NoMatch`. If the payer key has prior failures, **prepend**: "Note: this payer has failed verification [N] times before." The receipt shows which key kind was used.
- Every Mode 2 verdict writes a `claims` row = the **verification receipt** `{id, amountPaise, upiId, utr, claimedTimestamp, verdict, reason, createdAt, extractorBackend}`; the receipt screen is a formatted view of that row (screenshot-able evidence). No new pipeline.
- **Never** analyse the image for tampering/forensics. The verdict comes **only** from reconciliation against local records.

### Voice query (secondary mic interaction over the same log)
- "did [name] pay [amount] today?" → on-device STT → parse name + amount → search `payments` for today → answer aloud.
- "how was today?" → spoken digest: "[N] payments confirmed, [M] mismatches, ₹[X] flagged today." (confirmed = payments count today; mismatches = `NoMatch` claims today; flagged = sum of `NoMatch` claim amounts.)

### UI (Jetpack Compose, minimal)
- **Log screen (home):** today's confirmed payments list + flagged mismatches list. A "Verify a claim" button opens Mode 2. A mic button opens voice query. Fallback visual if the spoken verdict needs backup on stage.
- **Verify screen:** camera preview → capture/auto-capture → extracting → verdict card (big colour-coded MATCH / LIKELY / NO MATCH + reason + repeat-offender note) → "View receipt".
- **Receipt screen:** formatted claims row.
- **Settings:** TTS/STT locale picker (start `ta-IN` for demo; `en-IN`, `hi-IN` selectable), signal source toggles, extractor backend (auto / VLM / OCR-fallback).

### Language rule
STT/TTS locale is **switchable at runtime**. Parsing/matching logic is **language-neutral** — amounts/UTRs are numeric; never hardcode Tamil (or any language) into parsing or matching. Spoken strings come from string resources with locale variants.

### Office Kit export
End-of-day reconciliation report (CSV + short text summary) and flagged-mismatch log written to app external files dir, shareable via `FileProvider`/share intent so the Office Kit phone↔laptop file transfer can pick it up. No network.

### Intents
`upi://pay` deep link **only** if a demo of sending money is needed. Never touch real payment rails, never initiate an actual transfer.

---

## 2. Out of scope — do not build
- Reading any SMS/notifications except this device's own.
- Any real fund transfer, payment initiation, or sandbox UPI integration.
- Credit scoring, KYC, or lending logic of any kind.
- Multi-SIM / multi-account bank-selection logic.
- Layouts beyond GPay + PhonePe (Paytm only as step 7 stretch).
- Image-forensic "does this look edited" detection.
- Login, cloud sync, analytics, crash reporting, any network SDK.

---

## 3. Build order (verification gates — do not parallelize past step 3 until flawless)
1. Mode 1 passive listener + parser + Room + TTS confirmation, rock solid. **Gate:** injected SMS on the emulator → row in DB → TTS utterance logged.
2. Mode 2 camera → extractor → reconcile → tiered spoken verdict, 2 layouts. **Gate:** fixture screenshots (GPay, PhonePe) of a real and a fake claim produce Match / NoMatch correctly on the emulator via the OCR backend; VLM backend wired and documented for the real device.
3. Log screen (today's payments + flagged list).
4. Voice query + end-of-day digest.
5. Repeat-offender flagging (flagged_vpas + spoken warning).
6. Office Kit export + verification receipt view.
7. Only if 1–6 are done early: third layout (Paytm).

---

## 4. Architecture (all agents conform)

- **Package:** `com.crosscheck.app`  · **App name:** CrossCheck
- **minSdk 29 · targetSdk 35 · compileSdk 35** (SDK 35 is installed; avoids AGP-unsupported-compileSdk warnings)
- **Toolchain (all already cached locally — use these exact versions to avoid big downloads):** Gradle **8.11.1** (wrapper distributionUrl), AGP **8.9.1**, Kotlin **2.1.x**, JDK **17** (`D:\Android Studio\jbr`), KSP for Room.
- **Libraries:** Jetpack Compose (BOM), Material3, Navigation-Compose, Room + KSP, Kotlin Coroutines/Flow, CameraX (camera2 + view + lifecycle), ML Kit Text Recognition (`com.google.mlkit:text-recognition`, bundled/on-device), Android `TextToSpeech`, Android `SpeechRecognizer` (on-device where available), DataStore for settings.
- **VLM runtime — DECIDED (see `docs/research/vlm-runtime.md`):** `implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")` (pin 0.16.1 if 0.17.0 misbehaves). Model **Gemma 4 E2B** `gemma-4-E2B-it.litertlm` (2.59 GB, Apache-2.0, ungated) from `huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`; pushed to `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` (fallback: `/sdcard/Download/` + one-time copy into `filesDir` via a debug "Import model" button). `EngineConfig(backend = Backend.GPU(), visionBackend = Backend.GPU(), maxNumTokens = 2048, maxNumImages = 1, cacheDir)`, automatic fallback to `Backend.CPU()` on init failure; **log the backend actually used**. Manifest: `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and `libvndksupport.so`. Use **constrained decoding**: `ConversationConfig(enableResponseFormat = true, samplerConfig = SamplerConfig(topK=1, topP=1.0, temperature=0.0))` + `sendMessage(Contents.of(Content.ImageBytes(jpeg), Content.Text(prompt)), maxOutputToken = 96, responseFormat = ResponseFormat.json(CLAIM_SCHEMA))` — image BEFORE text; disable Gemma 4 thinking via `ThinkingConfig` (check `Config.kt`). Downscale to max side 1024, JPEG 85. Prompt, JSON schema and post-processing (recompute paise from `amount_text`, utr → digits) are in vlm-runtime.md §3.2 — use them verbatim. **Facts to respect:** the VLM runs on the **Adreno GPU, not the NPU** (no SM8850 vision builds exist in Google's stack; say "on-device, GPU-accelerated" — never claim NPU for the VLM); the Kotlin AAR is **arm64-only and needs `libOpenCL.so`**, so the VLM backend **cannot run on the x86_64 AVD** — the OCR backend is the emulator path; the `npu` product flavour with Qualcomm GenieX is stretch-only (needs INTERNET permission, no JSON constraints).
- **STT — decided:** `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31+); at startup call `checkRecognitionSupport()` for `ta-IN`, `hi-IN`, `en-IN`, surface `installedOnDeviceLanguages` in Settings, and offer `triggerModelDownload()`. Tamil on-device availability on OriginOS is **unverified** — `QueryParser` must work on digits + a name in any locale, and the app must accept `en-IN` when `ta-IN` is absent. No cloud recognition.
- **Layering:**
  ```
  com.crosscheck.app
    data/        Room: AppDatabase, entities (PaymentEntity, ClaimEntity, FlaggedVpaEntity), DAOs, repositories
    signal/      PaymentSignalSource, SmsReceiverSource (BroadcastReceiver), NotificationListenerSource, BankSmsParser (+ bank templates), PaymentIngestor
    verify/      ClaimExtractor (interface), VlmClaimExtractor, OcrClaimExtractor, ClaimFieldParser, Reconciler, Verdict
    voice/       Speaker (TTS wrapper, locale-switchable, utterance ids + progress listener), Listener (STT), QueryParser, DigestBuilder
    export/      ReportExporter (CSV/text), FileProvider config
    ui/          Compose screens: LogScreen, VerifyScreen, ReceiptScreen, SettingsScreen, nav graph, theme
    di/          simple manual DI (AppContainer) — no Hilt, keep it light
  ```
- **Money is `Long` paise. Never `Double`.** Display via a single `formatRupees(paise)`.
- **UTR:** normalise to digits only; UPI UTR/RRN is exactly 12 digits, NPCI-generated, **identical on payer and payee side** (verified — see payment-screens.md §5), and the payee's bank SMS carries the same number as "UPI Ref No". GPay labels it "UPI transaction ID", PhonePe "UTR"; treat `UPI transaction ID | UPI Ref No | Bank reference ID | UTR | RRN` as synonyms. **Never** treat PhonePe `T`+22-digit Transaction IDs or GPay `CICAg…` Google transaction IDs as UTR (app-internal, never on payee side) — keep them in `otherIds`. Partial match = one is a suffix/prefix of the other with ≥ 8 digits overlapping. Fake generators produce format-valid 12-digit UTRs, so format checks are worthless — only absence from `payments` catches them. For realistic 2026 test data use UTRs starting with year digit 6 (e.g. `624912345678`); `426112345678` decodes to a 2024 date (harmless, but looks odd to a sharp judge).
- **Timestamps:** epoch millis UTC; "few minutes" tolerance = configurable, default **10 min**.
- **Sender ID:** bank SMS come from DLT alphanumeric headers — `XX-HHHHHH[-S|T|P|G]` (e.g. `AD-SBIINB-S`, `VM-HDFCBK-S`; the `-S/-T/-P/-G` suffix is mandatory since May 2025; match the 6-char header case-insensitively). **Correction (6 Sept, emulator-verified):** the emulator console DOES deliver alphanumeric senders (`adb emu sms send VM-SBIINB "…"`), so trusted-sender paths are testable. Sender-ID matching is still a **signal, not a gate**: `BankSmsParser` accepts a credit from any sender if the body matches a credit template, records `senderId` and `senderTrusted`; a `-P` (promo) header is rejected outright; 10-digit/+91 senders and look-alike headers (`SBI-ALERT`) are untrusted and must never alone satisfy a Mode 2 `Match`. Full header list and per-bank templates: `docs/research/bank-sms-templates.md`; 45-entry test corpus: `docs/research/bank-sms-corpus.json` (all entries must pass the parser tests).
- **Product fact (research-verified):** for small UPI credits most banks send **no SMS at all** — HDFC and Kotak only above ₹500, Axis email-only below ₹10,000, SBI only for `@sbi`/`@oksbi` VPAs. The UPI app's own notification (GPay "X paid you ₹A", PhonePe "Received ₹A from X", Paytm, BHIM) is the only reliable Mode 1 signal — the notification listener is the primary production path, SMS is the emulator/test path and an opt-in on sideloaded builds.
- **Background execution (real device):** the `SMS_RECEIVED` receiver gets a ~20 s temp-allowlist; use `goAsync()` for parse+insert and a `shortService` foreground service for the TTS utterance (`onTimeout` implemented). Never `dataSync`. The notification-listener path speaks directly (system-bound service, no FGS needed).
- **Credit-only:** ignore debit alerts, OTPs, promos. Parser must return null for those (tests required).
- **Foreground/permissions:** runtime request for `RECEIVE_SMS` (+`READ_SMS` if backfill), `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`; notification-listener access via the system settings intent. Handle denial gracefully with an explanatory screen.
- **No network permission at all** in the manifest for core logic. (If the VLM runtime insists on one for model download, load the model from device storage instead — document the `adb push` path.)

---

## 5. Build environment (this machine)

- JDK: `D:\Android Studio\jbr` → set `JAVA_HOME` for every Gradle invocation:
  `$env:JAVA_HOME = "D:\Android Studio\jbr"; .\gradlew.bat assembleDebug` (PowerShell) or `JAVA_HOME="/d/Android Studio/jbr" ./gradlew assembleDebug` (Git Bash)
- Android SDK: `C:\Users\Sherlyn\AppData\Local\Android\Sdk` → write `local.properties` with `sdk.dir=C\:\\Users\\Sherlyn\\AppData\\Local\\Android\\Sdk`
- adb: `C:\Users\Sherlyn\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Emulator: `C:\Users\Sherlyn\AppData\Local\Android\Sdk\emulator\emulator.exe`; AVD **`Medium_Phone_API_36.1`** (API 36.1, Google Play image, x86_64; note the `.avd` folder is named `Medium_Phone` but `-avd` needs the `.ini` name — run `emulator -list-avds` to confirm). Also available: `Pixel_7a` (API 36).
  Boot headless: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_36.1 -no-snapshot-load -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect -no-metrics`
- Gradle 8.11.1 dist and AGP 8.9.1 are in `~/.gradle` cache already.
- No physical device. VLM real-device benchmarking happens on the iQOO loaner at the event; on the emulator use the OCR backend.
- **Model file already downloaded and verified (6 Sept):** `CrossCheck/models/gemma-4-E2B-it.litertlm` — 2,588,147,712 bytes, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` (matches the Hugging Face LFS oid). `models/` is gitignored; copy it to the laptop you take to the venue. Push to the phone: `adb push "c:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\models\gemma-4-E2B-it.litertlm" /data/local/tmp/llm/gemma-4-E2B-it.litertlm; adb shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm` (~2–3 min over USB). Also on HF, same repo: `gemma-4-E2B-it-gpu.litertlm` (2.0 GB, purpose undocumented — read its README before using) and `_qualcomm_sm8750` (3.0 GB, NPU build for the *previous* chip — not SM8850; do not use).

### Emulator test recipes
- Inject a bank-style SMS (numeric sender only):
  `adb emu sms send 5551234 "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI"`
- Post a fake bank *notification* to exercise `NotificationListenerSource` (appears from package `com.android.shell`, `EXTRA_BIG_TEXT` is null even with `-S bigtext` — read `EXTRA_TEXT` as fallback; the trusted-package list includes `com.android.shell` in debug builds). **The whole command must be one quoted string for the device shell**, otherwise the remote shell re-splits the args:
  `adb shell "cmd notification post -S bigtext -t 'HDFC Bank' tag1 'Rs.500.00 credited to a/c XX1234 via UPI Ref 426112345678 from MURUGAN'"`
- Trusted alphanumeric sender: `adb emu sms send AD-HDFCBK-S "Credit Alert! Rs.750.00 credited to HDFC Bank A/c XX1234 on 06-09-26 from VPA kumar@okaxis (UPI 624912345678)"`
- Tamil TTS voice on a fresh AVD: the Play image ships only en-US offline; download Tamil (India) once via `adb shell am start -n com.google.android.tts/com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity` → Tamil (India) → Download (~9.9 MB; logcat `VoiceDataDownloader: Download ta-in Success true`), then snapshot the AVD. `Medium_Phone_API_36.1` already has it. Repeat once on the iQOO loaner.
- Install on the loaner with `adb install`, not by copying the APK — adb installs are exempt from Android 13–16 "Restricted settings" (which otherwise blocks notification-listener access and, on 15+, the SMS permission group for sideloaded apps). If it was sideloaded anyway: Settings → Apps → CrossCheck → ⋮ → Allow restricted settings, or `adb shell appops set com.crosscheck.app ACCESS_RESTRICTED_SETTINGS allow`.
- Grant permissions without UI: `adb shell pm grant com.crosscheck.app android.permission.RECEIVE_SMS` (and READ_SMS, CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS)
- Enable notification listener: `adb shell cmd notification allow_listener com.crosscheck.app/.signal.NotificationListenerSource`
- Verify TTS without speakers: `Speaker` logs `UtteranceProgressListener.onStart/onDone` with the utterance text at `Log.i("CrossCheckTTS", ...)`; check `adb logcat -s CrossCheckTTS`.
- Inspect DB: `adb shell run-as com.crosscheck.app sqlite3 databases/crosscheck.db "select * from payments;"` (if sqlite3 missing, use Room's exported schema + a debug "dump DB" log action).
- Screenshot: `adb exec-out screencap -p > shot.png`
- Feed a fixture image to the camera path: put test images under `app/src/androidTest/assets/fixtures/` and unit/instrumented-test `OcrClaimExtractor` directly on the bitmap; for a manual UI run use the emulator's virtual scene camera (`-camera-back virtualscene`) or a "pick image" debug button on the Verify screen (debug builds only).

---

## 6. Quality bar
- `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` must pass with **zero** warnings-as-errors disabled hacks; no `@Suppress` blankets.
- Unit tests required for: `BankSmsParser` (≥ 10 real-format templates incl. negatives), `ClaimFieldParser`, `Reconciler` (all three tiers + partial UTR + timestamp tolerance), `QueryParser`, `DigestBuilder`.
- No TODOs left in shipped paths; no unused deps; no network permission.
- Every agent ends its work with `assembleDebug` green and a short report: what was built, how it was verified (commands + observed output), what is not verified and why.

---

## 7. Rules-of-the-event note (for the humans, not the agents)
The hackathon requires code to be written inside the event window (12–13 Sept). This repository is the **pre-event prototype**: toolchain validation, model benchmarking, bug-finding and the rebuild plan. Whether any of it is reused on the day is the team's decision.
