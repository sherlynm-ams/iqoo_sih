# Phase 1 report — foundation (build order steps 1 + 3, pure-logic seams for step 2)

Date: 2026-09-06 · Emulator: `Medium_Phone_API_36.1` (Android 16 / API 36, Google Play x86_64) · Toolchain: Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.0, KSP 2.1.0-1.0.29, JDK 17.

## Status

| Gate | Result |
|---|---|
| `assembleDebug` | green, no warnings (`--warning-mode=all`) |
| `testDebugUnitTest` | green — 74 tests, 0 failures |
| (a) injected SMS → `payments` row | proven via debug Dump-DB logcat (see below) |
| (b) TTS `onStart`/`onDone` with spoken text | proven, Tamil voice (`ta-IN`) was available on the image |
| (c) Log screen screenshot | `artefacts/phase1-log-screen.png` shows both payments |
| Notification-listener path | proven (`cmd notification post` → `Inserted` → TTS) |
| On-device dedup | re-sent identical SMS → `result=Duplicate` |

## What was built

### Project scaffold
- Gradle wrapper 8.11.1 generated from the cached distribution (`gradle/wrapper/*`), `settings.gradle.kts` (version catalog, `FAIL_ON_PROJECT_REPOS`), root `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties` (`-Xmx3g`, AndroidX, official code style, build cache), `local.properties` (gitignored).
- `:app` — `com.crosscheck.app`, min 29 / target 35 / compile 35, Compose (Kotlin compose plugin), Room via KSP + `androidx.room` Gradle plugin with `schemaDirectory` (schema exported to `app/schemas/com.crosscheck.app.data.AppDatabase/1.json`), `buildConfig` for the debug-only `TRUST_SHELL_NOTIFICATIONS` flag.
- Manifest: `RECEIVE_SMS`, `POST_NOTIFICATIONS`, `CAMERA`, `RECORD_AUDIO`; **no INTERNET**. `<queries>` for `TTS_SERVICE` (package visibility). Receiver for `SMS_RECEIVED`, notification-listener service, single activity.

### `data/`
- `PaymentEntity` (`payments`: id, amountPaise, sender, utr, timestamp, source, raw; indices on utr / timestamp / amount+timestamp), `ClaimEntity` (`claims`: SPEC fields + `payerName`, `payerBankMask`, `payerKey`, `keyKind`), `FlaggedVpaEntity` (`flagged_vpas`: `payerKey` PK, `keyKind`, `failCount`, `lastFailedAt`).
- DAOs: insert / observe & list between window / find by UTR / find by amount within window / all; claims by verdict; flagged get + `@Transaction increment` returning the prior count.
- Repository interfaces + Room implementations (so the ingestor/reconciler/verification service are tested with in-memory fakes).
- `formatRupees(paise)` (single formatter, Indian grouping) + `parseRupeesToPaise`; `DayWindow` (local-day epoch window); `SettingsRepository` (DataStore: speech locale, source toggles, trusted-package set — `com.android.shell` is in the default set only when `TRUST_SHELL_NOTIFICATIONS`, i.e. debug).

### `signal/`
- `PaymentSignalSource` (+ `RawSignal`), `SmsReceiverSource` (BroadcastReceiver, multipart concatenation, `goAsync`), `NotificationListenerSource` (title + text + bigText, trusted-package filter, group-summary skip, cached settings flow; `isAccessGranted()` helper).
- `BankSmsParser` — pure Kotlin, credit-only. Reject filters (OTP, collect/request, failed, debit, promo) run before credit detection; amount = the non-balance amount nearest a credit keyword; UTR from `UPI/CR/…`-style slashes, keyword forms (`UPI Ref No`, `Ref no`, `RRN`, `UTR`, `UPI:`, `Txn ID`, …) then a bare 12-digit fallback; payer from `from …`, `by VPA …`, `… paid you`, `UPI/<ref>/<name>`, generic `by <name>` with a stop-word guard. Names are script-neutral (`\p{L}\p{N}`).
- `SenderTrust` — DLT-header shape or bank token → `senderTrusted`; numeric senders are never trusted (signal, not gate).
- `PaymentIngestor` — dedup (same UTR; or same amount + payer within 2 min where at least one side lacks a UTR), insert, announce via `PaymentAnnouncer` (fun interface).

### `voice/`
- `Speaker` — `TextToSpeech` wrapper: runtime locale switch, `ta-IN` → `en-IN` → `en` fallback with the chosen voice logged, utterance ids `cc-N`, pending queue until the engine is ready, `UtteranceProgressListener` logging `onStart/onDone/onError` with text at `Log.i("CrossCheckTTS")`. Spoken strings resolved through a configuration context in the *speech* locale (`values/`, `values-ta/`, `values-hi/`), not the UI locale.

### `verify/` (pure logic only)
- `Claim` (amountPaise, upiId, utr, claimedTimestamp, payerName, payerBankMask, payerVpa, app, status, otherIds), `ClaimApp`, `ClaimStatus`, `Reason {WRONG_AMOUNT, WRONG_UTR, NOTHING_RECEIVED, TIMESTAMP_MISMATCH, PENDING}`, `sealed class Verdict {Match, LikelyMatch(reason), NoMatch(reason)}`, `Utr` helpers (digits-only normalisation, ≥8-digit prefix/suffix partial match).
- `ClaimExtractor` interface (`backendName`, `suspend fun extract(bitmap): Claim?`).
- `Reconciler` — tiering exactly as SPEC §1 plus: `status == PENDING` → `NoMatch(PENDING)` first; best candidate wins (tier score, then closest in time); NoMatch reasons ranked by specificity (PENDING > WRONG_AMOUNT > WRONG_UTR > TIMESTAMP_MISMATCH > NOTHING_RECEIVED). Tolerance 10 min, hard skew 24 h, both constructor-configurable.
- `VerificationService` — gathers candidates (±24 h around the claimed time, plus any exact-UTR row outside that), reconciles, derives the payer key (payerVpa → normalised payerName → bank mask), reads prior failures, increments `flagged_vpas` on NoMatch, writes the `claims` receipt, returns `Outcome(claimId, verdict, matchedPayment, payerKey, priorFailures, receipt)`.

### `ui/`
- `LogScreen` — today's payments (amount, payer, time, UTR, source chip, running total) + flagged NoMatch claims, empty states, permission/listener warning banner, top-bar actions (debug Dump-DB ⓘ, permissions ⚠, settings ⚙), bottom "Verify a claim" + mic buttons → placeholder routes.
- `SettingsScreen` — locale radio (ta-IN/en-IN/hi-IN, DataStore), "Test speech", source switches, listener status + system notification-access intent, runtime-permission request, trusted-package checkboxes (+ shell entry in debug).
- `PermissionsScreen` — per-permission explanation, state (granted / denied / permanently denied → app settings), listener card, Continue.
- `Nav.kt`, `PlaceholderScreen`, `theme/Theme.kt` (Material3 light/dark + `VerdictColors`), two vector icons (mic, camera) to avoid the extended-icons dependency.

### `di/` — `AppContainer` (DB, repos, settings, speaker, ingestor, reconciler, verification service, app scope; settings→speaker locale binding; `dumpDatabaseToLog()`), `CrossCheckApp`.

## File map

```
CrossCheck/
  build.gradle.kts · settings.gradle.kts · gradle.properties · local.properties (gitignored)
  gradle/libs.versions.toml · gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties} · gradlew · gradlew.bat
  app/build.gradle.kts · app/proguard-rules.pro
  app/schemas/com.crosscheck.app.data.AppDatabase/1.json
  app/src/main/AndroidManifest.xml
  app/src/main/res/values/{strings,themes}.xml · values-ta/strings.xml · values-hi/strings.xml
  app/src/main/res/drawable/{ic_launcher,ic_mic,ic_camera}.xml
  app/src/main/java/com/crosscheck/app/
    CrossCheckApp.kt
    data/    Entities.kt · Daos.kt · AppDatabase.kt · Repositories.kt · Money.kt · DayWindow.kt · SettingsRepository.kt
    signal/  ParsedCredit.kt · SenderTrust.kt · BankSmsParser.kt · PaymentSignalSource.kt · PaymentIngestor.kt
             SmsReceiverSource.kt · NotificationListenerSource.kt
    voice/   Speaker.kt
    verify/  Verdict.kt · ClaimExtractor.kt · Reconciler.kt · VerificationService.kt
    ui/      MainActivity.kt · Nav.kt · AppPermissions.kt · LogScreen.kt · SettingsScreen.kt · PermissionsScreen.kt
             PlaceholderScreen.kt · theme/Theme.kt
    di/      AppContainer.kt
  app/src/test/java/com/crosscheck/app/
    Fakes.kt · data/MoneyTest.kt · signal/BankSmsParserTest.kt · signal/PaymentIngestorTest.kt
    verify/ReconcilerTest.kt · verify/VerificationServiceTest.kt
  docs/BUILD.md · docs/reports/phase1-foundation.md
  artefacts/ (gitignored) phase1-log-screen.png · phase1-settings-screen.png · phase1-permissions-screen.png
```

## How each gate was verified (observed output)

### Build + tests
```
> Task :app:assembleDebug
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 7s
45 actionable tasks: 10 executed, 35 up-to-date
```
Test counts (from `app/build/test-results`): `BankSmsParserTest` 34 (22 positive templates incl. SBI×2, HDFC, ICICI, Axis, Kotak, PNB, BoB, Canara, IndusInd, Yes, Federal, PPBL, lakh grouping, ₹/Rs/INR forms, GPay/PhonePe/Paytm notification texts, no-ref credit, bare-12-digit; 10 negatives: debit, OTP×2, promo, balance, collect×2, garbage×4, outgoing×2, credit-card bill, withdrawal, failed; sender-trust rules), `ReconcilerTest` 21 (every tier, partial UTR prefix/suffix/<8, tolerance boundary at exactly 10 min and 10 min + 1 ms, hard skew, PENDING, cross-candidate ranking, tie-break, custom tolerance, `Utr` helpers), `PaymentIngestorTest` 10, `VerificationServiceTest` 7, `MoneyTest` 2. Total 74.

### Emulator
Install / grant / listener (see BUILD.md). `dumpsys notification` confirms the listener is bound:
```
ComponentInfo{com.crosscheck.app/com.crosscheck.app.signal.NotificationListenerSource} (user 0): android.service.notification.INotificationListener$Stub$Proxy@d509251
```

Inject SMS + notification, then `adb logcat -d -s CrossCheckTTS CrossCheckSMS CrossCheckNotif CrossCheckDB`:
```
01:21:35.937 I CrossCheckTTS: locale requested=ta-IN using=ta-IN setLanguage=1
01:21:35.937 I CrossCheckTTS: engine ready; flushing 0 queued utterance(s)
01:21:36.062 I CrossCheckNotif: listener connected; trusted=[com.google.android.apps.nbu.paisa.user, com.phonepe.app, net.one97.paytm, in.org.npci.upiapp, com.sbi.lotusintouch, com.snapwork.hdfc, com.csam.icici.bank.imobile, com.axis.mobile, com.msf.kbank.mobile, com.android.shell]
01:21:40.493 I CrossCheckSMS: from=5551234 result=Inserted body="Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MUR"
01:21:41.472 I CrossCheckTTS: onStart id=cc-1 text="MURUGAN அனுப்பிய ₹500 பெறப்பட்டது."
01:21:44.774 I CrossCheckTTS: onDone id=cc-1 text="MURUGAN அனுப்பிய ₹500 பெறப்பட்டது."
01:22:16.207 I CrossCheckNotif: pkg=com.android.shell result=Inserted text="HDFC Bank Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345678 from KUMAR"
01:22:16.340 I CrossCheckTTS: onStart id=cc-2 text="KUMAR அனுப்பிய ₹750 பெறப்பட்டது."
01:22:19.948 I CrossCheckTTS: onDone id=cc-2 text="KUMAR அனுப்பிய ₹750 பெறப்பட்டது."
```
(a) Dump-DB action (ⓘ in the Log top bar) — `run-as … sqlite3` is not on this image:
```
01:22:58.350 I CrossCheckDB: payments: 2 row(s)
01:22:58.350 I CrossCheckDB: payment PaymentEntity(id=2, amountPaise=75000, sender=KUMAR, utr=624912345678, timestamp=1788637935978, source=notification, raw=HDFC Bank Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345678 from KUMAR)
01:22:58.350 I CrossCheckDB: payment PaymentEntity(id=1, amountPaise=50000, sender=MURUGAN, utr=426112345678, timestamp=1788637900408, source=sms, raw=Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI)
01:22:58.357 I CrossCheckDB: claims: 0 row(s)
01:22:58.360 I CrossCheckDB: flagged_vpas: 0 row(s)
```
Dedup, same SMS re-sent:
```
01:23:01.848 I CrossCheckSMS: from=5551234 result=Duplicate body="Rs.500.00 credited to A/c XX1234 …"
```
(c) `artefacts/phase1-log-screen.png` — "Confirmed payments · 2 payments · ₹1,250", rows ₹750 KUMAR (Notification, UTR 624912345678) and ₹500 MURUGAN (SMS, UTR 426112345678), "No mismatches today", bottom Verify / mic buttons. Settings and Permissions screens also opened without any `AndroidRuntime` error (`phase1-settings-screen.png`, `phase1-permissions-screen.png`).

Notification-listener access on API 36 for a sideloaded debug APK: `cmd notification allow_listener` worked directly; no "restricted settings" workaround was required. Note `settings get secure enabled_notification_listeners` does **not** list the component on this API level — use `dumpsys notification` or the in-app status ("Notification access: granted").

## SPEC deviations / additions (all deliberate, none silent)

1. **`Claim.payerVpa` added** (beyond the coordinator's field list). SPEC's `upiId` is the *payee* handle, so the "VPA" rung of the payer key needs a field that is only set when the extractor sees a handle clearly labelled as the payer's. Without it the VPA key kind could never occur. Phase 2 extractors should leave it null unless the screen explicitly says so.
2. **`ClaimEntity` carries `payerName` and `payerBankMask`** in addition to the requested `payerKey` + `keyKind`, so the receipt can show the payer identity SPEC §1 defines (name + bank mask) without a join.
3. **`READ_SMS` not declared.** SPEC lists it as optional for backfill; there is no backfill in Phase 1 and an unused dangerous permission is a Play-policy liability. `pm grant … READ_SMS` from the SPEC recipe therefore fails ("not requested") — BUILD.md says to skip it.
4. **CameraX and ML Kit are pinned in the version catalog but not wired into `:app`** — nothing in Phase 1 uses them and the quality bar forbids unused deps. Adding them is two lines in `app/build.gradle.kts` (`libs.bundles.camerax`, `libs.mlkit.text.recognition`).
5. **Reason semantics for LikelyMatch without a comparable UTR:** SPEC gives four (now five) reasons; a claim whose UTR could not be read yields `LikelyMatch(WRONG_UTR)` when the time is within tolerance (UTR is the field to confirm manually). No extra `UTR_MISSING` reason was introduced to stay within the enum.
6. **`Match` beats a far-off timestamp**: amount + exact UTR → `Match` even if the claimed time is days away (SPEC: "amount AND UTR match"). A `FAILED` status is not special-cased (not in scope of the coordinator's change; it reconciles normally and will be NoMatch unless the UTR is genuinely in `payments`).
7. **Dedup extension**: SPEC says "same amount+payer within 2 min when utr absent"; the implementation also treats an SMS *with* a UTR as a duplicate of a UTR-less app notification for the same amount + payer within 2 min (the same payment reported twice). Two UTR-less payments with identical amount, identical payer and empty payer name inside 2 min collapse to one — accepted edge case.
8. AVD name in SPEC §5 was corrected by the coordinator to `Medium_Phone_API_36.1`; BUILD.md uses that.

## Known gaps / not verified

- No instrumented tests (Room DAOs are exercised on the emulator only through the app path; the DAO queries are simple). `run-as sqlite3` is unavailable, so DB inspection is the Dump-DB logcat action.
- TTS output was verified through the progress listener only (headless emulator, `-no-audio`); actual audio not heard.
- Hindi (`hi-IN`) and English voices were not switched at runtime during the gate (only the code path + `ta-IN` was exercised); the fallback branch (`ta-IN` missing → `en-IN`) is implemented and logged but did not trigger because the Play image ships a Tamil voice.
- Notification-listener behaviour with real bank apps (non-shell packages) is untested — only `com.android.shell` was available.
- `senderTrusted=true` on the SMS path was covered by unit tests only during the Phase 1 gate; the research pass later showed `adb emu sms send AD-SBIINB-S "…"` does deliver alphanumeric senders on emulator 36.x (see BUILD.md), so this is now exercisable on the emulator.
- The Verify and Voice routes are placeholders by design (Phase 2 / step 4).
