# CrossCheck — final verification report (pre-event prototype), 6 Sept 2026

**What this is:** the pre-event prototype of CrossCheck — a UPI payment-claim verifier for sellers on personal UPI IDs (no merchant account, no soundbox) — built to validate the toolchain, models and demo before the iQOO Hackathon Chennai City Battle (12–13 Sept). Everything below was built and verified on this machine on 6 Sept. The hackathon's "original code written inside the event window" rule is the team's to apply (see SPEC §7).

## Build

| Item | Result |
|---|---|
| Toolchain | Gradle 8.11.1 · AGP 8.9.1 · Kotlin 2.3.10 / KSP 2.3.5 · JDK 17 · minSdk 29 / target 35 |
| `assembleDebug --warning-mode=all` | **BUILD SUCCESSFUL**, zero warnings |
| `testDebugUnitTest` | **180 tests, 0 failures, 0 errors** (14 suites) |
| APK | `app-debug.apk` 130.9 MB (LiteRT-LM JNI + bundled ML Kit model); **no `INTERNET` permission** (verified with `dumpsys package`) |
| Git | `integ` branch = Phase 1 + hardening + voice/export + Mode 2, all merged; to be fast-forwarded to `main` |

## End-to-end gates on one emulator (`Medium_Phone_API_36.1`, Android 16, x86_64 Google Play image)

| # | Gate | Evidence |
|---|---|---|
| 1 | **Mode 1 — trusted bank SMS** (`AD-SBIINB-S`, UTR 624900000011) | `CrossCheckSMS: result=Inserted` → `CrossCheckFGS: speaking id=cc-1` → `CrossCheckTTS: onStart/onDone "MURUGAN அனுப்பிய ₹500 பெறப்பட்டது."` → `CrossCheckFGS: utterance cc-1 finished ok=true` (shortService FGS path, Tamil voice) |
| 2 | **Mode 1 — UPI-app notification** (shell-posted "HDFC Bank", UTR 624900000012) | `CrossCheckNotif: result=Inserted` → TTS `"KUMAR அனுப்பிய ₹750 பெறப்பட்டது."`, **no** FGS lines (system-bound listener speaks directly) |
| 3 | **Dedupe** — same SMS re-sent | `result=Duplicate`, no announcement |
| 4 | **Scam-pattern SMS** (10-digit sender, no UTR, "credited") | `result=NotACredit` |
| 5 | **Mode 2 fixture suite** — 10 mock GPay/PhonePe screens (genuine, wrong UTR, wrong amount, pending, photo-of-screen variants) | `CrossCheckFixture: summary 10/10 PASS`; every field strict (amount, UTR, payee VPA, payer name/mask, app, status, other IDs, timestamp); router: `mode=AUTO abi=x86_64 arm64=false model=false -> ocr` |
| 6 | **Mode 2 single verify via photo picker** (PhonePe wrong-UTR image) | `CrossCheckOcr: claim=…utr=519876543210…app=PHONEPE` → verdict card **NO MATCH — UTR does not match**, repeat-offender line, extractor `ocr` (`artefacts/final-verdict.png`) → spoken: `"குறிப்பு: இந்த நபர் இதற்கு முன் 10 முறை சரிபார்ப்பில் தோல்வியடைந்துள்ளார். பொருந்தவில்லை — UTR பொருந்தவில்லை."` |
| 7 | **Voice query** (typed into the debug field — the AVD has no mic) `did Murugan pay 500 today` | `CrossCheckSTT: parsed=DidPay(name=Murugan, amountPaise=50000)` → spoken `"ஆம் — MURUGAN அனுப்பிய ₹500 02:39 மணிக்கு பெறப்பட்டது."` (`artefacts/final-voice-didpay.png`) |
| 8 | **End-of-day digest** `how was today` | `parsed=DigestToday` → spoken `"இன்று 5 பணங்கள் உறுதி செய்யப்பட்டன, 20 பொருத்தமின்மைகள், ₹37,000 குறியிடப்பட்டது."` (`artefacts/final-voice-digest.png`; counts are inflated by two fixture-suite runs) |
| 9 | **Office Kit export** | `CrossCheckExport: wrote …reconciliation.csv (301 B) / flagged.csv (1819 B) / summary.txt (511 B)`; share sheet (`ChooserActivityLauncher`) opened (`artefacts/final-export.png`); CSV rows carry payer key + kind (`NAME`, `BANK_MASK`) and backend |

## What is NOT verified (needs the iQOO 15)

1. **Gemma 4 E2B on-device VLM** — compiles, wired, fails soft to OCR on the emulator (arm64-only runtime path, no OpenCL). Model file downloaded and SHA-256-verified (`models/gemma-4-E2B-it.litertlm`). Benchmark plan: `docs/research/vlm-runtime.md` §4. Expect **GPU**, not NPU (no SM8850 vision builds).
2. **Tamil speech recognition** — the emulator image has no `ta-IN` on-device model (not even downloadable). Check `checkRecognitionSupport("ta-IN")` on the loaner; fall back to `en-IN` (parser is language-neutral).
3. **Real bank SMS / real GPay-PhonePe notifications** — corpus is 8 verbatim + 37 reconstructed templates; capture real ones on the loaner (`content query --uri content://sms/inbox`, `dumpsys notification --noredact`) and add to the corpus test.
4. **Real camera on a real screen** — auto-capture, tap-to-focus and −1 EV were only exercised against the emulator's virtual scene.
5. Microphone end-to-end (no mic on the AVD).

## Where things are

- Spec (contract): `docs/SPEC.md` · Build/run/test recipes: `docs/BUILD.md` · Demo run-book: `docs/DEMO.md`
- Research: `docs/research/{vlm-runtime, payment-screens, bank-sms-templates}.md`, `bank-sms-corpus.json`
- Phase reports: `docs/reports/{phase1-foundation, phase1-hardening, phase2-mode2, phase2-voice-export, integration-6sep}.md`
- Fixtures: `fixtures/` (10 PNGs + manifest + generator) · Model: `models/` (gitignored, 2.59 GB)
- Screenshots: `artefacts/` (gitignored)

## Team to-do before 12 Sept

- [ ] Decide how the event rule on original code applies (SPEC §7); at minimum use this repo as the hour-by-hour rebuild plan.
- [ ] Copy `models/gemma-4-E2B-it.litertlm` to the laptop going to the venue.
- [ ] Run `docs/DEMO.md` §0 on the loaner in the first hour; run the VLM benchmark; pick VLM-AUTO or OCR.
- [ ] Rehearse the 50-second script twice; record the backup clip of Beat 1.
