# Integration record — 6 Sept 2026

Branch `integ` = `main` (Phase 1, 7b95ebd) + `harden/phase1` (3686c95, fast-forward) + `worktree-agent-a3ffdea00688b9e56` (7fcb340, voice/digest/export). Two both-appended conflicts resolved by keeping both sides: `AndroidManifest.xml` (SpeakService + FileProvider) and `values/strings.xml` (FGS strings + voice/export strings).

## Build

- `gradlew --warning-mode=all assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL in 40s**, no warnings.
- Unit tests: **151 total, 0 failures, 0 errors, 0 skipped** across 13 suites (Phase 1 74 → hardening 99 → + voice/export 52).
- `app-debug.apk` 29.8 MB.

## Mode 1 re-verification on the merged APK (emulator-5556, Pixel_7a, API 36 Play image)

Install via `adb install -r`, `pm grant` RECEIVE_SMS / POST_NOTIFICATIONS / RECORD_AUDIO / CAMERA, `cmd notification allow_listener`, launch, `logcat -c`. Then, in order:

| Step | Input | Expected | Observed (logcat) |
|---|---|---|---|
| 1 | `adb emu sms send AD-SBIINB-S "Rs.500.00 credited … UPI Ref No 624900000001 from MURUGAN. -SBI"` | Inserted → shortService FGS → Tamil TTS → FGS released | `CrossCheckSMS: from=AD-SBIINB-S result=Inserted` → `CrossCheckFGS: speaking id=cc-1 startId=1 text="MURUGAN அனுப்பிய ₹500 பெறப்பட்டது."` → `CrossCheckTTS: onStart id=cc-1` → `onDone id=cc-1` → `CrossCheckFGS: utterance cc-1 finished ok=true (startId=1)` |
| 2 | `adb emu sms send 9812345678 "Rs 4,800 credited to your A/c XX7843. Available balance: Rs 12,340."` (scam pattern: 10-digit sender, no UTR) | rejected | `CrossCheckSMS: from=9812345678 result=NotACredit` |
| 3 | `adb shell "cmd notification post -S bigtext -t 'HDFC Bank' tag1 'Rs.750.00 credited … UPI Ref 624900000002 from KUMAR'"` | Inserted → TTS directly, **no** FGS | `CrossCheckNotif: pkg=com.android.shell result=Inserted` → `CrossCheckTTS: onStart id=cc-2 text="KUMAR அனுப்பிய ₹750 பெறப்பட்டது."` → `onDone id=cc-2`; no `CrossCheckFGS` lines |
| 4 | re-send step 1 SMS | Duplicate, no FGS | `CrossCheckSMS: from=AD-SBIINB-S result=Duplicate` |

- `ActivityManager:E` / `AndroidRuntime:E`: nothing.
- `dumpsys activity services com.crosscheck.app` after the run: no `SpeakService` entry (stopped itself after the utterance, as designed).
- TTS locale: `locale requested=ta-IN using=ta-IN setLanguage=1` (Tamil voice present on this AVD).
- Screenshot: `artefacts/integ-mode1.png` — Log screen shows the two new payments (₹750 KUMAR · Notification, ₹500 MURUGAN · SMS) on top of the rows seeded by the voice/export agent earlier.

## Not yet on this branch

Mode 2 (camera → extractor → verdict → receipt) — being built in the `main` working tree; to be merged next, then the full gate set re-run on one emulator: Mode 1 (above), fixture suite 10/10 via the OCR backend, verdict TTS, voice query, digest, export.
