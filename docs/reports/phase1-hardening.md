# Phase 1 hardening — real-corpus parser, sender trust, background execution

Worktree: `C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck-harden` · branch `harden/phase1` (from `main` @ 7b95ebd, not committed) · 2026-09-06.
Verification is unit-test only (emulators 5554/5556 were reserved); the Mode 1 emulator gate must be re-run after merge — steps in §5.

## Status

| Gate | Result |
|---|---|
| `testDebugUnitTest` | green — **99 tests, 0 failures** (74 previous + 25 new; the corpus test covers all 45 entries in 3 aggregate assertions) |
| `assembleDebug --warning-mode=all` | green, no warnings |
| Corpus | 27/27 credits parse to the exact `expected` amount/UTR/payer; 18/18 non-credits return null; expectations untouched |

Test classes (worktree `app/build/test-results`): `BankSmsCorpusTest` 4, `BankSmsParserTest` 38, `SenderTrustTest` 6, `NotificationTextTest` 4, `SmsDeliveryTest` 4, `PaymentIngestorTest` 13, `ReconcilerTest` 21, `VerificationServiceTest` 7, `MoneyTest` 2.

## 1. Parser rules added / changed (`signal/BankSmsParser.kt`)

Check order is now: promo DLT header → OTP → collect → failed/reversal/refund → promo → card-bill → **any debit verb** → credit keyword required → amount → UTR → payer → **untrusted-sender guard**.

| Rule | What changed | Corpus entries that needed it |
|---|---|---|
| Newline-aware input | `normalise()` keeps single `\n` (was flattened to space); payer names never cross a newline, `\n` is a name terminator; notifications are fed `title + "\n" + body` | `phonepe-notif-credit-1` (payer was "Murugan Money received"), `gpay-notif-credit-1`, `paytm-notif-credit-1`, `hdfc-upi-credit-2` (multi-line) |
| `credit alert`, `paid to you` as credit keywords | `CREDIT_KEYWORD` += `credit\s+alert`, `paid\s+(to\s+)?you` | `hdfc-upi-credit-2`, `union-upi-credit-1` |
| Debit verbs with counter-party exceptions | `DEBIT_VERB` now bare `sent`, `paid`, `transferred`, `purchase(d)`, `charged`, `deducted` (+ existing) with `(?!\s+(you|to\s+you))` exceptions; any debit verb rejects (stricter than §2's "debit before credit" — a verifier must prefer a missed announcement over a false one) | `hdfc-upi-debit-1` ("Sent Rs.10000.00"), `union-debit-with-credited-word`, `gpay-notif-debit-1`, `paytm-upi-debit-1` |
| Reversal / refund reject | `REJECT_FAILED` += `revers(al|ed)`, `refund(ed)?`, `credited back` | `sbi-upi-reversal-credit`, `sbi-upi-failed-1`, `scam-sbi-lookalike-header` |
| Card-bill reject | new `REJECT_CARD`: `credit card`, `towards your` | `kotak-cc-payment-received` |
| Collect/OTP vocabulary | `tap to pay`, `do not share` | `phonepe-notif-collect-request`, `sbi-otp-txn-1` (belt-and-braces; `requested`/`OTP` already hit) |
| Balance-figure avoidance | `BALANCE_WORD` += `lmt`; lookbehind 24 chars covers `Total Bal:`, `Avl Amt:`, `Avl Bal-`, `Available Balance is`, `Total Avail.bal` | `axis-upi-credit-1`, `bob-upi-credit-1`, `icici-upi-credit-2`, `canara-upi-credit-1-no-utr`, `pnb-upi-credit-1`, `icici-card-debit-1` |
| Glued / bracketed refs | `UTR_KEYWORD` accepts `UPI:426…` (ICICI/PNB), `(UPI 426…)` (HDFC new), `UPI transaction ID:` (GPay), `Ref no`, `Ref`, `RRN`, `UTR`; `UTR_SLASH` accepts `UPI/426…/NAME` (ICICI "Info UPI/…"), `UPI/CR/…`, `UPI/P2A|P2M/…` | `icici-upi-credit-1/2`, `pnb-upi-credit-1`, `hdfc-upi-credit-2`, `gpay-notif-credit-1`, `bob-upi-credit-1`, `union-upi-credit-1`, `axis-upi-credit-1` |
| Reference length | keyword/slash paths capture 10–12 digits (11-digit scam refs are seen, then judged by the trust guard); bare fallback stays exactly 12 and skips digits glued to `X`/`*` masks | `scam-hdfc-lookalike-10digit`, `scam-sbi-lookalike-header` |
| No-UTR credits | UTR optional; `Canara` and `Paytm PB` parse with `utr = null` | `canara-upi-credit-1-no-utr`, `paytmpb-upi-credit-1` |
| Amount forms | `Rs8700`, `Rs1500.00`, `credited:Rs 500.00`, `Rs. 5,000.00`, `INR 1,50,000.00`, `₹1,250` | `sbi-upi-credit-1/2`, `icici-upi-credit-2`, `hdfc-*`, `phonepe-notif-credit-1` |
| Payer: `by Rs…` is not a name | generic `by <name>` pattern now refuses `Rs\.?\s*\d`, `INR`, `₹`, `(` | `sbi-upi-credit-1/2` (payer must be null) |
| Payer: slash names | `UPI/(CR|P2A|…)/<ref>/<NAME>/` ends at `/`, `.`, newline | `bob`, `union`, `axis`, `icici-2` |
| Payer: `from NAME (vpa)` keeps the name | `(` terminates the name | `federal-upi-credit-1`, `sbi-generic-credit-3`, `paytm-notif-credit-1` |
| Payer: `from VPA x@y`, `by a/c linked to VPA x@y` | unchanged, verified | `hdfc-*`, `kotak`, `yes`, `bhim` |
| **Untrusted-sender guard** | after parsing: `if (!senderTrusted && (utr == null \|\| utr.length != 12)) return null` | `scam-hdfc-lookalike-10digit`, `scam-generic-credited-10digit` |
| Promo header | `SenderTrust.isPromoHeader()` (`-P`) rejects before any body check | `hdfc-promo-loan-credited` (body promo words would also reject) |

### Which rule rejects each scam entry (pinned by `BankSmsParserTest.scam_entries_are_rejected_by_the_documented_rule`)

| Entry | Sender | Rejecting rule | Proof |
|---|---|---|---|
| `scam-hdfc-lookalike-10digit` | `+919876543210` | untrusted sender + 11-digit ref (`41246673198`) | same body from `AD-HDFCBK-S` parses, with the 11-digit ref kept as a partial UTR |
| `scam-generic-credited-10digit` | `9812345678` | untrusted sender + no reference | same body from `AD-SBIINB-S` parses (₹4,800, utr null) |
| `scam-sbi-lookalike-header` | `SBI-ALERT` | `REJECT_FAILED` (`refund`) fires first; would also fail on `sent` (debit verb) and on the untrusted guard (11 digits, `SBI-ALERT` is not a DLT header) | same body from `AD-SBIUPI-S` is still null |

## 2. Sender trust (`signal/SenderTrust.kt`)

- `DltHeader(prefix, header, suffix)` parsed by `^([A-Z]{2})-([A-Z0-9]{6})(?:-([STPG]))?$` case-insensitively; `KNOWN_HEADERS` = SBIUPI, SBIINB, SBIPSG, SBIBNK, HDFCBK, ICICIB, ICICIT, AXISBK, KOTAKB, PNBSMS, BOBTXN, BOBSMS, CANBNK, UNIONB, INDUSB, YESBNK, FEDBNK, IDFCFB, IDFCBK, AUBANK, PAYTMB, AIRBNK, BHIMAP.
- `isTrustedSmsSender` = DLT shape ∧ header known ∧ suffix ≠ `P`. `isPromoHeader` = suffix `P` → parser rejects outright.
- Untrusted: 10-digit, `+91…`, `SBI-ALERT`, `HDFC-PYMT`, bare `SBIINB`, unknown headers with DLT shape (`VM-SBICRD-T`, `AD-ICICIO-T`), app package names. The old bank-token substring heuristic (which trusted `SBI-ALERT`) is gone.
- `SenderTrustTest` covers every known header × 9 prefixes × {none,-S,-T,-G}, case-insensitivity, `-P`, look-alikes, numerics, packages; `BankSmsCorpusTest.sender_trust_matches_corpus_headers` checks every corpus sender.

## 3. Background robustness — FGS design

- **`SmsReceiverSource`** (manifest receiver, `SMS_RECEIVED`): `goAsync()` → on `appScope` → `SmsDelivery.deliver()` (pure: group parts by address, concatenate multipart, `ingestRaw(announce = false)`), then for each `Inserted` payment builds the localized sentence with `Speaker.paymentAnnouncement()` and calls `SpeakService.start(context, text)`. If `startForegroundService` throws (`ForegroundServiceStartNotAllowedException` ⊂ `IllegalStateException`, or `SecurityException`) it logs and speaks directly as a fallback. `pending.finish()` in `finally`.
- **`voice/SpeakService`** — `Service` declared with `android:foregroundServiceType="shortService"`, `exported=false`. `onStartCommand`: `startForeground(1001, lowImportanceNotification, FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)` (type only on API ≥ 34; plain `startForeground` on 29–33), then `speaker.speak(text) { id, ok -> stopSelfResult(startId) }`; when the last start id is released it calls `stopForeground(STOP_FOREGROUND_REMOVE)` so the notification vanishes with the utterance. `onTimeout(startId)` and `onTimeout(startId, fgsType)` both stop the service (shortService budget ≈ 3 min). Channel `tts_announcements` (IMPORTANCE_LOW). Multiple SMS in quick succession = multiple start ids on one service instance, each released by its own utterance.
- **`Speaker`**: `speak(text, onComplete)` with a per-utterance `UtteranceCallback`; fires on `onDone`/`onError`, on `tts.speak()` rejection, and for every queued utterance if engine init fails (so the service never waits on a dead engine). New `paymentAnnouncement(amount, payer)` returns the localized sentence without speaking.
- **Notification-listener path** keeps `announce = true` (speaks directly): the listener service is system-bound and needs no FGS.
- **Manifest** (append-style, two lines + one `<service>`): `FOREGROUND_SERVICE` permission and the `SpeakService` declaration. **Note:** the task asked for `FOREGROUND_SERVICE_SHORT_SERVICE`; no such permission exists in the platform — `shortService` is the one FGS type with no per-type permission (Android docs, FGS types table), so only `FOREGROUND_SERVICE` is declared.
- `POST_NOTIFICATIONS` denied ⇒ the FGS notification is hidden but the service still runs (documented platform behaviour).

## 4. Notification text and dedupe

- `signal/NotificationText.compose(title, text, bigText)` = `title + "\n" + (bigText ?: text)`, blank-safe; `NotificationListenerSource.extractText` delegates to it. `NotificationTextTest` covers bigText preference, the `cmd notification post -S bigtext` case (bigText null → text), title-only/body-only/empty, and that the composed GPay text parses to the corpus expectation.
- Dedupe (`PaymentIngestor`): unchanged rules (UTR; else amount + payer within 2 min where a side lacks a UTR) now proven for the three-way case in `PaymentIngestorTest.one_credit_via_bank_sms_gpay_notification_and_messages_app_notification_is_one_row` (bank SMS → GPay notification with the same UTR → Messages-app notification carrying the SMS body: 1 row, 0 direct announcements) and for the no-UTR case (Paytm PB SMS + PhonePe notification → amount + payer). Note the Messages app package is not in the default trusted set, so on the emulator that third signal never reaches the ingestor; the test proves it would dedupe if it did.

## 5. Manual verification to run on the emulator after merge

1. Build + install + grant + `allow_listener` + launch (BUILD.md). `adb logcat -c`.
2. `adb emu sms send AD-SBIINB-S "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 624912345678 from MURUGAN. -SBI"`
   Expect in `adb logcat -d -s CrossCheckSMS CrossCheckFGS CrossCheckTTS`:
   `CrossCheckSMS: from=AD-SBIINB-S result=Inserted …` → `CrossCheckFGS: speaking id=cc-N startId=1 text="…"` → `CrossCheckTTS: onStart … onDone …` → `CrossCheckFGS: utterance cc-N finished ok=true (startId=1)`.
   While it speaks, `adb shell dumpsys activity services com.crosscheck.app` lists `SpeakService` with `isForeground=true`, and the shade shows "Announcing payment…" (pull it with `adb shell cmd statusbar expand-notifications`, screenshot, `collapse`); after `onDone` the service and notification are gone.
3. Repeat with the numeric sender `5551234` and the SPEC body (12-digit UTR) → `Inserted`, `senderTrusted=false` in the Dump-DB row is not stored but the row exists; then `adb emu sms send 9812345678 "₹4,800 credited to your A/c XX7843. Available balance: ₹12,340."` → `result=NotACredit` (scam guard).
4. `adb shell "cmd notification post -S bigtext -t 'HDFC Bank' tag1 'Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345679 from KUMAR'"` → `CrossCheckNotif: … result=Inserted` and TTS lines with **no** `CrossCheckFGS` lines (listener path speaks directly).
5. Re-send the step-2 SMS → `result=Duplicate`, no FGS start.
6. Background case: `adb shell am force-stop com.crosscheck.app` is *not* representative (force-stop blocks receivers until next launch); instead press Home, wait ~1 min, send the SMS again with a fresh UTR and confirm the same FGS → TTS sequence from a cached/background process. On API 34+ also check `adb logcat -s ActivityManager | Select-String "shortService|SpeakService"` for no `ForegroundServiceDidNotStartInTimeException`.

## 6. Files touched (worktree)

Shared files (other agents also edit — merge with care): **`app/src/main/AndroidManifest.xml`** (+`FOREGROUND_SERVICE` permission line, +`SpeakService` `<service>` block at the end of `<application>`), **`app/src/main/res/values/strings.xml`** (+2 strings appended at the end: `tts_channel_name`, `tts_fgs_title`), **`gradle/libs.versions.toml`** (+`gson` version and library), **`app/build.gradle.kts`** (+`testImplementation(libs.gson)`). `AppContainer` and `values-ta`/`values-hi` untouched.

Own files:
- modified: `signal/BankSmsParser.kt`, `signal/SenderTrust.kt`, `signal/PaymentIngestor.kt` (announce flag), `signal/PaymentSignalSource.kt` (announce flag), `signal/SmsReceiverSource.kt`, `signal/NotificationListenerSource.kt`, `voice/Speaker.kt`
- new: `signal/NotificationText.kt`, `signal/SmsDelivery.kt`, `voice/SpeakService.kt`
- tests new: `signal/BankSmsCorpusTest.kt`, `signal/SenderTrustTest.kt`, `signal/NotificationTextTest.kt`, `signal/SmsDeliveryTest.kt`; modified: `signal/BankSmsParserTest.kt`, `signal/PaymentIngestorTest.kt`
- resources: `app/src/test/resources/bank-sms-corpus.json` (copy of `docs/research/bank-sms-corpus.json`, source noted in the test's KDoc)
- docs: `docs/BUILD.md` (alphanumeric-sender recipe, FGS expectations, `CrossCheckFGS` tag), `docs/reports/phase1-foundation.md` (removed the "numeric only" caveat), this report.

## 7. Notes / deviations

- SPEC §4 and §5 still say the emulator console is numeric-only; per research §4 that is wrong on emulator 36.x. Not edited (SPEC is the coordinator's file).
- Debit handling is stricter than research §2 (any debit verb rejects, not only one before the first credit verb) — deliberate, see §1.
- Behaviour change for existing callers: a credit **without** a 12-digit UTR from an untrusted/unknown sender is now rejected (previously accepted). The SPEC recipe (`5551234` + 12-digit UTR) still passes; notification-listener signals pass `senderTrusted = true`.
- Gson is a test-only dependency (org.json would collide with the android.jar stubs on the unit-test classpath).
