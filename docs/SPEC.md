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
- `ClaimExtractor` returns structured `Claim {amountPaise, upiId, utr, claimedTimestamp}` from a camera frame.
  - **Primary backend:** small on-device vision-language model, one pass, image → JSON (Gemma-3n / Qwen2-VL-class, quantized, via LiteRT-LM / MediaPipe LLM Inference / ExecuTorch — the research report in `docs/research/vlm-runtime.md` decides which).
  - **Fallback backend (same interface):** ML Kit on-device Text Recognition + a structured field parser. Exists so Mode 2 can be *run and verified on the x86_64 emulator today* (no NPU, no ARM) and as an automatic fallback on stage if the VLM fails to load or returns unparseable JSON. It is a backend behind `ClaimExtractor`, not a second pipeline in the UI.
- `Reconciler` compares the claim against the local `payments` table → **tiered verdict** (`sealed class Verdict`):
  - `Match` — amount AND UTR match a stored payment → "MATCH — payment confirmed."
  - `LikelyMatch(reason)` — amount matches but timestamp differs by a few minutes, or UTR is partial/prefix match → "LIKELY MATCH — [specific discrepancy], confirm manually."
  - `NoMatch(reason)` — no matching amount/UTR at all → "NO MATCH — nothing received for this claim." Reason is one of: wrong amount / wrong UTR / nothing received / timestamp mismatch.
- Speak the verdict via TTS with the specific reason.
- **Repeat-offender memory:** `flagged_vpas` table keyed by `upiId` with `failCount`, `lastFailedAt`; increment on every `NoMatch`. If the claimed `upiId` has prior failures, **prepend**: "Note: this ID has failed verification [N] times before."
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
- **Libraries:** Jetpack Compose (BOM), Material3, Navigation-Compose, Room + KSP, Kotlin Coroutines/Flow, CameraX (camera2 + view + lifecycle), ML Kit Text Recognition (`com.google.mlkit:text-recognition`, bundled/on-device), Android `TextToSpeech`, Android `SpeechRecognizer` (on-device where available), DataStore for settings. VLM runtime dependency per `docs/research/vlm-runtime.md`.
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
- **UTR:** normalise to digits only; typical UPI UTR/RRN is 12 digits. GPay shows "UPI transaction ID" (may be 12 digits) — treat both as `utr`. Partial match = one is a suffix/prefix of the other with ≥ 8 digits overlapping.
- **Timestamps:** epoch millis UTC; "few minutes" tolerance = configurable, default **10 min**.
- **Sender ID:** in the real world, bank SMS come from alphanumeric IDs (e.g. `VK-SBIINB`, `AD-HDFCBK`). The emulator console can only send from a numeric address, so sender-ID matching is a **signal, not a gate**: `BankSmsParser` must accept a credit SMS from any sender if the body matches a bank credit template; it records `senderId` and a `senderTrusted` boolean.
- **Credit-only:** ignore debit alerts, OTPs, promos. Parser must return null for those (tests required).
- **Foreground/permissions:** runtime request for `RECEIVE_SMS` (+`READ_SMS` if backfill), `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`; notification-listener access via the system settings intent. Handle denial gracefully with an explanatory screen.
- **No network permission at all** in the manifest for core logic. (If the VLM runtime insists on one for model download, load the model from device storage instead — document the `adb push` path.)

---

## 5. Build environment (this machine)

- JDK: `D:\Android Studio\jbr` → set `JAVA_HOME` for every Gradle invocation:
  `$env:JAVA_HOME = "D:\Android Studio\jbr"; .\gradlew.bat assembleDebug` (PowerShell) or `JAVA_HOME="/d/Android Studio/jbr" ./gradlew assembleDebug` (Git Bash)
- Android SDK: `C:\Users\Sherlyn\AppData\Local\Android\Sdk` → write `local.properties` with `sdk.dir=C\:\\Users\\Sherlyn\\AppData\\Local\\Android\\Sdk`
- adb: `C:\Users\Sherlyn\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Emulator: `C:\Users\Sherlyn\AppData\Local\Android\Sdk\emulator\emulator.exe`; AVD **`Medium_Phone`** (API 36.1, Google Play image, x86_64). Also available: `Pixel_7a` (API 36).
- Gradle 8.11.1 dist and AGP 8.9.1 are in `~/.gradle` cache already.
- No physical device. VLM real-device benchmarking happens on the iQOO loaner at the event; on the emulator use the OCR backend.

### Emulator test recipes
- Inject a bank-style SMS (numeric sender only):
  `adb emu sms send 5551234 "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI"`
- Post a fake bank *notification* to exercise `NotificationListenerSource` (appears from package `com.android.shell` — make the trusted-package list configurable so tests can include it):
  `adb shell cmd notification post -S bigtext -t "HDFC Bank" tag1 "Rs.500.00 credited to a/c XX1234 via UPI Ref 426112345678 from MURUGAN"`
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
