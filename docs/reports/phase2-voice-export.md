# Phase 2 report — voice query, end-of-day digest, Office Kit export (build order steps 4 + 6-export)

Date: 2026-09-06 · Worktree branch `worktree-agent-a3ffdea00688b9e56` (baseline `7b95ebd`) · Emulator: `Pixel_7a` on `emulator-5556` (Android 16 / API 36, Google Play x86_64) · Toolchain unchanged from Phase 1 (Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.0, JDK 17).

## Status

| Gate | Result |
|---|---|
| `assembleDebug --warning-mode=all` | green, no warnings |
| `testDebugUnitTest` | green — **126 tests** (Phase 1's 74 + 52 new), 0 failures |
| (a) typed "did Murugan pay 500 today" → Tamil answer spoken | proven: `CrossCheckSTT query … parsed=DidPay(name=Murugan, amountPaise=50000)`, `CrossCheckTTS onStart/onDone id=cc-3` with the Tamil text; `artefacts/phase2-voice-didpay.png` |
| (b) typed "how was today" → digest spoken and shown | proven: `parsed=DigestToday`, `onStart/onDone id=cc-4`; `artefacts/phase2-voice-digest.png` |
| (c) "Export today" → 3 files + share sheet | proven: files listed and printed below; `artefacts/phase2-export-share.png` shows the system chooser "Sharing 3 files" with Quick Share / Drive / Messages / Photos targets |
| (d) `checkRecognitionSupport` for ta-IN / hi-IN / en-IN logged | proven — **ta-IN is not available on-device on this image** (see STT finding) |
| Real microphone path | exercised: emulator has no mic and no Tamil model → `onError code=12 (LANGUAGE_NOT_SUPPORTED)` → error card with "Open Settings"; `artefacts/phase2-voice-mic-emulator.png` |

Nothing in `verify/`, `VerifyScreen`, `ReceiptScreen` or the Verify route was touched. No INTERNET permission was added.

## What was built

### `voice/`
- **`Query.kt`** — `sealed class Query { DidPay(name: String?, amountPaise: Long?), DigestToday, Unknown }`.
- **`QueryParser`** — pure Kotlin, language-neutral. Tokenises on whitespace, strips edge punctuation, currency prefixes (`₹500`, `Rs.500`, `inr500`, `ரூ500`) and possessives; finds the first run of numeric tokens (digits with Indian grouping / paise decimals, English number words up to lakhs, a few Hindi-Latin and Tamil number words, `"2 thousand five hundred"`, `"one thousand and fifty"`); 12-digit UTRs (> 8 digits) are never amounts; a lone ambiguous word (`do` = Hindi 2 / English "do") is not an amount. Name = leftover tokens that contain letters and are not fillers / pay-cues / digest-cues / number words, original casing preserved. Decision: no amount + no name + a "how was it" cue → `DigestToday`; amount or name → `DidPay`; pay-cue alone → `DidPay(null, null)`; else `Unknown`. Word lists are small per-locale constants (English, Tamil script + Tanglish, Hindi script + Latin).
- **`DigestBuilder`** — `Digest(confirmedCount, totalPaise, mismatchCount, flaggedPaise)` from today's payments and claims (only `NO_MATCH` rows count).
- **`QueryAnswerer`** — runs a `Query` against today's rows → structured `Answer` (`Yes`, `NothingFromName`, `NothingOfAmount`, `DifferentAmount`, `PaidList`, `DigestAnswer`, `NotUnderstood`) → phrased through a `StringProvider` (string resources in the *speech* locale, with plurals for the digest). Name match = case-insensitive contains on the normalised sender (uppercase, letters/marks/digits only — `\p{M}` keeps Tamil/Devanagari vowel signs), or every asked token present; amount match = exact paise; name matches but amount differs → "MURUGAN paid ₹200 today, not ₹500."
- **`LocalizedStrings`** — `StringProvider` over a configuration context in the locale `Speaker` is actually using (`speaker.activeLocale`), so `Speaker.kt` is untouched.
- **`VoiceQueryService`** — transcript → parse → `payments.between(today)` + `claims.between(today)` → answer → `Speaker.speak`. Both the mic path and the debug typed path go through it. Logs `query transcript=… parsed=… answer=…` at `CrossCheckSTT`.
- **`Listener`** — `SpeechRecognizer` wrapper. API 31+: `createOnDeviceSpeechRecognizer()` when `isOnDeviceRecognitionAvailable()`, else `createSpeechRecognizer()`. Intent: `ACTION_RECOGNIZE_SPEECH`, free-form, `EXTRA_LANGUAGE`/`EXTRA_LANGUAGE_PREFERENCE` = Settings speech locale, `EXTRA_PREFER_OFFLINE = true`, `EXTRA_PARTIAL_RESULTS = false`, `EXTRA_MAX_RESULTS = 3`. `suspend fun listenOnce(): String?` (main-thread, cancellable, recogniser destroyed after each use) with `lastError: ListenError` (`NOT_AVAILABLE`, `NO_PERMISSION`, `LANGUAGE_UNAVAILABLE`, `NO_SPEECH`, `AUDIO`, `BUSY`, `OTHER`). API 33+: `checkSupport(tags)` → `Support {INSTALLED, DOWNLOADABLE, PENDING, ONLINE_ONLY, UNSUPPORTED, UNKNOWN}` per locale via `checkRecognitionSupport()`; `triggerModelDownload(tag)` (API 34 listener variant when available, logging progress/success/error). Everything logged at `Log.i("CrossCheckSTT", …)`.

### `export/`
- **`ReportContent`** — pure builders: `crosscheck-YYYY-MM-DD-reconciliation.csv` (`time,amount,amount_paise,sender,utr,source`), `crosscheck-YYYY-MM-DD-flagged.csv` (`time,amount,amount_paise,utr,verdict,reason,payer_name,payer_bank_mask,payer_key,key_kind,backend`; NoMatch + LikelyMatch only), `crosscheck-YYYY-MM-DD-summary.txt` (digest sentence + numbers). RFC 4180 quoting.
- **`ReportExporter`** — writes the three files to `getExternalFilesDir("reports")` (logs each path at `CrossCheckExport`), builds `ACTION_SEND_MULTIPLE` (`*/*`, `EXTRA_STREAM` content URIs from `FileProvider` authority `${applicationId}.fileprovider`, `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`) wrapped in `Intent.createChooser`.
- `res/xml/file_paths.xml` — `<external-files-path name="reports" path="reports/"/>`; manifest `<provider>` for `androidx.core.content.FileProvider`.

### `ui/`
- **`VoiceScreen`** — replaces the Voice placeholder route. Big mic button → `Listening` (spinner, red) → `Thinking(transcript)` → `Answered` card (Heard / Understood as / Answer, green for yes/digest, red for no/different amount) → error card per `ListenError` with an "Open Settings" action for missing language / recogniser. Requests `RECORD_AUDIO` on first tap. **Debug builds only:** "Type a query" + Run (same `VoiceQueryService` pipeline, no microphone — how the emulator gate was run) and "Seed a NoMatch claim" (inserts a ₹500 / RAVI / `NOTHING_RECEIVED` receipt with backend `debug-seed`, so the digest and flagged export have a mismatch row).
- **`SttSettingsSection`** (new file, one call appended to `SettingsScreen`) — "On-device speech recognition": engine line (on-device / default / none), per-locale status from `checkSupport` run when the screen opens, Download button when `DOWNLOADABLE`, Refresh.
- **`LogScreen`** — one new top-bar action (share icon, "Export today"): export → open share sheet → snackbar with the directory.

### Shared-file edits (append-style, for merge review)
- `ui/Nav.kt` — Voice route now `VoiceScreen(container, onBack, onSettings)`; Verify route untouched.
- `di/AppContainer.kt` — imports + five appended vals: `listener`, `speechStrings`, `answerer`, `voiceQuery`, `exporter`.
- `ui/LogScreen.kt` — `Share` icon import, two string lookups, one `IconButton` in `actions` before the Permissions icon.
- `ui/SettingsScreen.kt` — `HorizontalDivider` + `SttSettingsSection(container)` before the trailing `Spacer`.
- `AndroidManifest.xml` — `<intent>` for `android.speech.RecognitionService` inside the existing `<queries>` (package visibility for `isRecognitionAvailable()` on API 30+); `<provider>` before `</application>`.
- `res/values/strings.xml` — new block at the end (`voice_*`, `voice_answer_*`, `voice_digest` + two `<plurals>`, `log_export_today`, `export_*`, `settings_stt_*`). `values-ta/` and `values-hi/` — only the spoken strings (`voice_answer_*`, `voice_digest`, plurals) appended at the end; UI labels stay English as in Phase 1.

## File map (new files)

```
app/src/main/java/com/crosscheck/app/
  voice/   Query.kt · QueryParser.kt · DigestBuilder.kt · QueryAnswerer.kt · LocalizedStrings.kt
           VoiceQueryService.kt · Listener.kt
  export/  ReportContent.kt · ReportExporter.kt
  ui/      VoiceScreen.kt · SttSettingsSection.kt
app/src/main/res/xml/file_paths.xml
app/src/test/java/com/crosscheck/app/
  voice/   QueryParserTest.kt (29) · QueryAnswererTest.kt (15) · DigestBuilderTest.kt (4)
  export/  ReportContentTest.kt (4)
docs/reports/phase2-voice-export.md · docs/BUILD.md (appended section)
artefacts/ (gitignored) phase2-voice-didpay.png · phase2-voice-digest.png · phase2-export-share.png
           phase2-settings-stt.png · phase2-voice-mic-emulator.png
```

## Gate evidence (observed output)

### Build + tests
```
> Task :app:assembleDebug
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 48s
45 actionable tasks: 13 executed, 32 up-to-date
```
Per-suite counts from `app/build/test-results/testDebugUnitTest`:
```
MoneyTest 2 · ReportContentTest 4 · BankSmsParserTest 34 · PaymentIngestorTest 10 · ReconcilerTest 21
VerificationServiceTest 7 · DigestBuilderTest 4 · QueryAnswererTest 15 · QueryParserTest 29   → 126, 0 failures
```
`QueryParserTest` covers: English name+amount (digits, `1,250`, `₹500`, `Rs 500`, `Rs.500`, `500 rupees`, `500.50`), number words (`five hundred`, `one thousand two hundred fifty`, `fifteen hundred`, `two thousand`, `2 thousand five hundred`, `one thousand and fifty`), two-word names, name-only, amount-only, fillers/possessive, 12-digit UTR ignored, Tanglish `Murugan 500 vandhucha`, Tamil script `முருகன் 500 கொடுத்தாரா`, Tamil number words, Hindi Latin `murugan ne 500 diya kya`, Devanagari, Hindi number words (`paanch sau`, `do hazaar`), digest in three languages (`how was today`, `today's summary`, `what's the total today`, `இன்று எப்படி`, `innaiku eppadi`, `aaj kaisa raha`, `आज कैसा रहा`), unknown/empty, `zero`.

### Emulator seed (emulator-5556)
```
01:47:31.518 I CrossCheckSMS: from=VM-SBIINB result=Inserted body="Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MUR"
01:47:33.105 I CrossCheckNotif: pkg=com.android.shell result=Inserted text="HDFC Bank Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345678 from KUMAR"
01:47:34.254 I CrossCheckTTS: onStart id=cc-1 text="MURUGAN அனுப்பிய ₹500 பெறப்பட்டது."
01:47:37.767 I CrossCheckTTS: onStart id=cc-2 text="KUMAR அனுப்பிய ₹750 பெறப்பட்டது."
```
(The alphanumeric sender `VM-SBIINB` was accepted by this emulator version, as the coordinator noted.) A NoMatch claim was then seeded from the Voice screen's debug button (`Inserted claims#1 (₹500, RAVI, nothing received)`).

### (a) DidPay
```
01:48:45.222 I CrossCheckSTT: query transcript="did Murugan pay 500 today" parsed=DidPay(name=Murugan, amountPaise=50000) answer="ஆம் — MURUGAN அனுப்பிய ₹500 01:47 மணிக்கு பெறப்பட்டது."
01:48:46.171 I CrossCheckTTS: onStart id=cc-3 text="ஆம் — MURUGAN அனுப்பிய ₹500 01:47 மணிக்கு பெறப்பட்டது."
01:48:53.100 I CrossCheckTTS: onDone id=cc-3 text="ஆம் — MURUGAN அனுப்பிய ₹500 01:47 மணிக்கு பெறப்பட்டது."
```
`phase2-voice-didpay.png`: Heard "did Murugan pay 500 today" · Understood as "Payment from Murugan · ₹500 · today" · green answer card with the Tamil sentence.

### (b) Digest
```
01:50:26.922 I CrossCheckSTT: query transcript="how was today " parsed=DigestToday answer="இன்று 2 பணங்கள் உறுதி செய்யப்பட்டன, 1 பொருத்தமின்மை, ₹500 குறியிடப்பட்டது."
01:50:27.228 I CrossCheckTTS: onStart id=cc-4 text="இன்று 2 பணங்கள் உறுதி செய்யப்பட்டன, 1 பொருத்தமின்மை, ₹500 குறியிடப்பட்டது."
01:50:33.975 I CrossCheckTTS: onDone id=cc-4 text="…"
```
`phase2-voice-digest.png`: Understood as "Today's digest", answer card with the Tamil digest (2 payments confirmed, 1 mismatch, ₹500 flagged).

### (c) Export
```
01:53:35.496 I CrossCheckExport: wrote /storage/emulated/0/Android/data/com.crosscheck.app/files/reports/crosscheck-2026-09-06-reconciliation.csv (144 bytes)
01:53:35.497 I CrossCheckExport: wrote …/crosscheck-2026-09-06-flagged.csv (196 bytes)
01:53:35.497 I CrossCheckExport: wrote …/crosscheck-2026-09-06-summary.txt (494 bytes)
01:53:39.539 I ActivityTaskManager: START u0 {act=android.intent.action.CHOOSER … clip={text/comma-separated-values hasLabel(7) 3 items: {U(content)} {U(content)} {U(content)}} …} from uid 10220
```
`adb -s emulator-5556 shell ls -l /sdcard/Android/data/com.crosscheck.app/files/reports/`:
```
-rw-rw---- 1 u0_a220 ext_data_rw 196 2026-09-06 01:50 crosscheck-2026-09-06-flagged.csv
-rw-rw---- 1 u0_a220 ext_data_rw 144 2026-09-06 01:50 crosscheck-2026-09-06-reconciliation.csv
-rw-rw---- 1 u0_a220 ext_data_rw 494 2026-09-06 01:50 crosscheck-2026-09-06-summary.txt
```
Contents:
```
time,amount,amount_paise,sender,utr,source
01:47:31,500.00,50000,MURUGAN,426112345678,sms
01:47:32,750.00,75000,KUMAR,624912345678,notification
```
```
time,amount,amount_paise,utr,verdict,reason,payer_name,payer_bank_mask,payer_key,key_kind,backend
01:49:33,500.00,50000,699912345678,NO_MATCH,NOTHING_RECEIVED,RAVI,SBI-XX4321,RAVI,NAME,debug-seed
```
```
CrossCheck — end-of-day reconciliation, 2026-09-06
இன்று 2 பணங்கள் உறுதி செய்யப்பட்டன, 1 பொருத்தமின்மை, ₹500 குறியிடப்பட்டது.

Confirmed payments: 2
Total received:     ₹1,250
Mismatches:         1
Flagged amount:     ₹500

Files: crosscheck-2026-09-06-reconciliation.csv, crosscheck-2026-09-06-flagged.csv
Generated 2026-09-06 01:50:54 on device; no data left the phone.
```
`phase2-export-share.png`: system chooser "Sharing 3 files — crosscheck-2026-09-06-reconciliation.csv + 2 more files" with Quick Share, Drive, Maps, Messages, Photos.

The 4 s gap between the last `wrote …` line and `START … CHOOSER` above came from `showSnackbar` (which suspends until the snackbar is dismissed) running before `startActivity`; the order was swapped and re-verified after a rebuild + reinstall:
```
01:58:53.204 I CrossCheckExport: wrote …/crosscheck-2026-09-06-summary.txt (494 bytes)
01:58:53.360 I ActivityTaskManager: START u0 {act=android.intent.action.CHOOSER … 3 items …} from uid 10220
01:58:55.314 I ActivityTaskManager: Displayed com.android.intentresolver/.ChooserActivityLauncher for user 0: +1s896ms
```

### (d) STT availability on this image (`checkRecognitionSupport`, on-device recogniser)
```
01:51:28.085 I CrossCheckSTT: checkRecognitionSupport ta-IN onDevice=true -> UNSUPPORTED installed=[en-US] downloadable=[de-DE, es-ES, fr-FR, it-IT, en-AU, en-GB, en-IE, en-SG, ja-JP, de-AT, de-BE, de-CH, en-CA, en-IN, es-US, fr-BE, fr-CA, fr-CH, hi-IN, id-ID, it-CH, ko-KR, pt-BR, th-TH, cmn-Hans-CN, cmn-Hant-TW, pl-PL, ru-RU, tr-TR, vi-VN] pending=[] online=[]
01:51:28.201 I CrossCheckSTT: checkRecognitionSupport en-IN onDevice=true -> DOWNLOADABLE …
01:51:28.266 I CrossCheckSTT: checkRecognitionSupport hi-IN onDevice=true -> DOWNLOADABLE …
```
Real mic tap with the default `ta-IN`:
```
01:52:53.004 I CrossCheckSTT: listenOnce locale=ta-IN onDevice=true api=36
01:52:53.441 W CrossCheckSTT: onError code=12 (LANGUAGE_NOT_SUPPORTED)
```
`triggerModelDownload(en-IN)` from the Settings Download button logged `triggerModelDownload requested for en-IN` but no `ModelDownloadListener` callback arrived within 20 s and the status stayed "Available to download" (the Google recogniser downloads on its own schedule; the emulator's network path was not investigated further).

## STT finding and fallback behaviour

- **Tamil (`ta-IN`) is not on the on-device recogniser's language list at all** on the API 36 Google Play image — neither installed nor downloadable (`online=[]` because the on-device recogniser reports no online languages). `en-IN` and `hi-IN` are downloadable; only `en-US` is installed. This is consistent with SPEC §4's warning that Tamil on-device STT is unverified. On the iQOO loaner (OriginOS) the same Settings section will show the real answer at a glance.
- **Fallback:** the Voice screen turns `ERROR_LANGUAGE_NOT_SUPPORTED` / `ERROR_LANGUAGE_UNAVAILABLE` into a card that tells the seller to download the language or switch to English (India), with an "Open Settings" button; Settings shows the per-language status and a Download action. The app never falls back to cloud recognition: the on-device recogniser is used whenever `isOnDeviceRecognitionAvailable()`, and the default recogniser (API 29/30 only, or devices without an on-device recogniser) is asked with `EXTRA_PREFER_OFFLINE`. The parser is language-neutral, so switching the speech locale to `en-IN` keeps Tamil/Tanglish names and digits working.
- **TTS:** contrary to the earlier research note, the Tamil TTS voice *was* available on this Pixel_7a image (`CrossCheckTTS: locale requested=ta-IN using=ta-IN setLanguage=1`) — no voice-pack download was needed; all spoken answers above are Tamil.

## SPEC deviations / additions (deliberate)

1. **`DidPay` with only a name or only an amount is answered** (SPEC only lists "did [name] pay [amount]"): name-only → what that payer sent today (single payment → "Yes — …", several → "MURUGAN paid ₹300, ₹200 today."); amount-only → whether anyone sent that amount. Cheap and natural for speech.
2. **Digest string uses plurals** (`voice_digest_payments` / `voice_digest_mismatches`) rather than one flat sentence, so English reads "1 mismatch" vs "2 mismatches"; the SPEC sentence shape is preserved.
3. **`flagged.csv` includes `LIKELY_MATCH` rows** as well as `NO_MATCH` — the coordinator's brief asked for both; the digest's mismatch count stays NoMatch-only per SPEC.
4. **Export CSVs carry both `amount` (rupees, two decimals) and `amount_paise`** so spreadsheets get a numeric column and the integer is preserved.
5. **Debug-only seeding lives on the Voice screen** (not the Log screen) to keep the `LogScreen` edit to a single action; it is compiled out of release (`BuildConfig.DEBUG`).
6. **`Speaker` was not modified**; a separate `LocalizedStrings` provider reads `speaker.activeLocale` (falls back to `ta-IN` before the engine has initialised — a sub-second window at process start).

## Known gaps / not verified

- Real microphone recognition was not exercised end-to-end (headless emulator, no mic, no Tamil model). The mic path was verified only up to the recogniser's error callback and the error UI; `onResults` → pipeline is the same code the typed path proves.
- `triggerModelDownload` produced no completion callback on the emulator within the time budget; whether the Google recogniser honours it for `en-IN` on this image is unknown.
- Share sheet was shown but no target was tapped (nothing to receive files headlessly); URI grants are the standard `FileProvider` + `ClipData` pattern.
- The Hindi (`hi-IN`) and English answer strings are exercised by unit tests through templates, not by switching the speech locale on the emulator.
- `Listener.checkSupport` calls `checkRecognitionSupport` once per locale (three calls) because the API answers per intent; the lists are identical each time — acceptable cost at Settings open.
- Instrumented/UI tests: none (consistent with Phase 1).
