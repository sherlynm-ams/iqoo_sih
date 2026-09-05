# CrossCheck — build, run and verify (Windows 11, PowerShell 5.1)

Everything below was executed verbatim on this machine on 2026-09-06 and worked.
Repo root: `C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck` (the harness cwd is the parent folder, so every command uses absolute paths).

## Toolchain

| Piece | Version / path |
|---|---|
| JDK | 17 — `D:\Android Studio\jbr` (set `JAVA_HOME` on every Gradle call; nothing on PATH) |
| Gradle | 8.11.1 via wrapper (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP | 8.9.1 · **Kotlin 2.3.10 · KSP 2.3.5** (Phase 2: LiteRT-LM's AAR is Kotlin-2.4 metadata, unreadable by the 2.1 compiler; KSP ≥ 2.3.10 would need AGP 8.10 + a Gradle 8.13 download) · Room 2.7.1 (+ `androidx.room` Gradle plugin) |
| Mode 2 libs | CameraX 1.4.2 · ML Kit `text-recognition` 16.0.1 (bundled Latin model) · `com.google.ai.edge.litertlm:litertlm-android` 0.17.0 (ships `jni/arm64-v8a` **and** `jni/x86_64` — installs and loads on the AVD) |
| SDK | `C:\Users\Sherlyn\AppData\Local\Android\Sdk` (`local.properties` is generated, gitignored) |
| compile/target/min | 35 / 35 / 29 |

`local.properties` (create if missing):

```
sdk.dir=C\:\\Users\\Sherlyn\\AppData\\Local\\Android\\Sdk
```

## Build

```powershell
$env:JAVA_HOME = "D:\Android Studio\jbr"
& "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\gradlew.bat" -p "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck" assembleDebug --console=plain
```

APK: `app\build\outputs\apk\debug\app-debug.apk`. Room schema is exported to `app\schemas\com.crosscheck.app.data.AppDatabase\1.json`.

## Unit tests

```powershell
$env:JAVA_HOME = "D:\Android Studio\jbr"
& "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\gradlew.bat" -p "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck" testDebugUnitTest --console=plain
```

HTML report: `app\build\reports\tests\testDebugUnitTest\index.html`. Both tasks in one go: `testDebugUnitTest assembleDebug --warning-mode=all`.

### Regenerating the wrapper (only if `gradle/wrapper` is lost)

No `gradle` on PATH; run the cached distribution directly (needs network the first time to resolve plugin markers — do not pass `--offline`):

```powershell
$env:JAVA_HOME = "D:\Android Studio\jbr"
& "C:\Users\Sherlyn\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat" wrapper --gradle-version 8.11.1 --distribution-type bin -p "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck" --no-daemon
```

## Emulator

AVD name is `Medium_Phone_API_36.1` (API 36, Google Play, x86_64). Confirm with `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds`.

Boot headless (runs in its own window-less process; takes 1–3 min):

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_36.1 -no-snapshot-load -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect -no-metrics
```

Wait for it:

```powershell
adb wait-for-device
adb shell getprop sys.boot_completed     # poll until it prints 1 ("unauthorized" in `adb devices` clears itself once booted)
```

## Install, grant, enable listener, launch

```powershell
adb install -r "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\app\build\outputs\apk\debug\app-debug.apk"
adb shell pm grant com.crosscheck.app android.permission.RECEIVE_SMS
adb shell pm grant com.crosscheck.app android.permission.POST_NOTIFICATIONS
adb shell pm grant com.crosscheck.app android.permission.CAMERA
adb shell pm grant com.crosscheck.app android.permission.RECORD_AUDIO
adb shell cmd notification allow_listener com.crosscheck.app/.signal.NotificationListenerSource
adb logcat -c
adb shell am start -n com.crosscheck.app/.ui.MainActivity
```

Notes:
- `READ_SMS` is **not** declared (no backfill in Phase 1), so `pm grant … READ_SMS` would fail with "not requested by package". Skip it.
- `allow_listener` worked directly on API 36 for this sideloaded debug APK — no "restricted settings" workaround was needed. Verify with `adb shell dumpsys notification --noredact | Select-String crosscheck` (look for `ComponentInfo{com.crosscheck.app/…NotificationListenerSource}`); **do not** trust `settings get secure enabled_notification_listeners`, it is not the source of truth on this API level and does not list the component. If a future image refuses via the UI ("Restricted setting"), the adb route above bypasses it; the manual fallback is *App info → ⋮ → Allow restricted settings*, then the notification-access page.

## Test recipes (what the Phase 1 gate used)

Inject a bank SMS. The emulator console accepts **alphanumeric DLT senders too** (verified on emulator 36.x — see `docs/research/bank-sms-templates.md` §4), so both trust paths can be exercised:

```powershell
# trusted DLT header -> senderTrusted=true, a missing UTR is tolerated
adb emu sms send AD-SBIINB-S "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 624912345678 from MURUGAN. -SBI"
# 10-digit / unknown sender -> senderTrusted=false; accepted only because it carries a full 12-digit UTR
adb emu sms send 5551234 "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI"
```

What to expect after the Phase 1 hardening (branch `harden/phase1`): the receiver logs `CrossCheckSMS: … result=Inserted`, then starts the `shortService` foreground service `SpeakService` — a low-importance notification "Announcing payment…" appears for the duration of the utterance and `CrossCheckFGS: speaking id=cc-N …` / `utterance cc-N finished ok=true` bracket the `CrossCheckTTS: onStart/onDone` lines. Confirm the service came and went with `adb shell dumpsys activity services com.crosscheck.app` (empty once done). If `startForegroundService` is refused, the receiver logs `SpeakService unavailable; speaking directly` and still speaks.

Post a fake bank notification. **Quoting matters**: in PowerShell wrap the whole remote command in one string and use single quotes inside, otherwise the device shell re-splits `"HDFC Bank"` into title/tag/text and the body is lost:

```powershell
adb shell "cmd notification post -S bigtext -t 'HDFC Bank' tag1 'Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345678 from KUMAR'"
```

Read the evidence:

```powershell
adb logcat -d -s CrossCheckTTS CrossCheckSMS CrossCheckNotif CrossCheckFGS CrossCheckDB
```

- `CrossCheckSMS` / `CrossCheckNotif`: `result=Inserted | Duplicate | NotACredit` per signal. The Messages app's own notification of an injected SMS is *not* in the trusted-package set, so it never reaches the ingestor; if you add it, it dedups on UTR (unit-tested).
- `CrossCheckFGS`: `SpeakService` lifecycle (SMS path only).
- `CrossCheckTTS`: `locale requested=… using=…`, then `onStart id=… text="…"` / `onDone …` per utterance.
- `CrossCheckDB`: every row of every table — produced by the debug-only **ⓘ** action in the Log screen top bar (tap it, or `adb shell input tap 754 146` on the 1080×2400 AVD). `run-as com.crosscheck.app sqlite3` is **not** available on this image.

Screenshot (PowerShell `>` corrupts binary stdout, so capture on-device and pull):

```powershell
adb shell screencap -p /sdcard/shot.png
adb pull /sdcard/shot.png "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\artefacts\phase1-log-screen.png"
adb shell rm /sdcard/shot.png
```

Change the speech locale from the Settings screen (gear icon) — Tamil / English-India / Hindi; `CrossCheckTTS` logs which voice the engine actually picked (`ta-IN` was available on this Google Play image).

## Phase 2 — Mode 2 (Verify screen, OCR/VLM extractors, fixture suite)

Always address the emulator explicitly (`adb -s emulator-5554 …`); another agent uses `emulator-5556`. The debug APK is now ~131 MB (LiteRT-LM JNI for two ABIs + ML Kit model); first launch after an install takes ~8 s, so wait before scripting taps.

### Extra debug-only grant

`app/src/debug/AndroidManifest.xml` declares `READ_MEDIA_IMAGES` so the fixture suite can read PNGs from `/sdcard/Download`. Release builds do not carry it.

```powershell
adb -s emulator-5554 shell pm grant com.crosscheck.app android.permission.READ_MEDIA_IMAGES
```

### Push the fixtures (two locations)

Scoped storage lets the app read *images* from Download (with the grant above) but never a non-media `fixtures.json` there, so the suite looks for every file first in Download, then in the app-specific directory. Push the whole folder to both: Download feeds the system photo picker, the app directory feeds the suite.

```powershell
$src = "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\fixtures"
foreach ($dst in @("/sdcard/Download/crosscheck-fixtures", "/sdcard/Android/data/com.crosscheck.app/files/crosscheck-fixtures")) {
  adb -s emulator-5554 shell mkdir -p $dst
  foreach ($f in (Get-ChildItem $src -Include *.png,fixtures.json -Recurse)) { adb -s emulator-5554 push $f.FullName "$dst/" }
}
# make the picker see them straight away
foreach ($f in @('gpay_genuine.png','phonepe_wrong_utr.png')) { adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/crosscheck-fixtures/$f" }
```

### Run the fixture suite (gate for build-order step 2)

Verify screen (Log → **Verify a claim**, or `adb shell input tap 460 2305`) → top-bar **Run suite** (debug only; `adb shell input tap 902 146` — the `Fixture image` button is at `706 146`). It seeds the two payments from `fixtures/README.md` when their UTRs are absent, runs the router on the 10 PNGs (~15 s each on the x86_64 AVD, the ML Kit model is CPU/software here) and logs one line per image plus a summary:

```powershell
adb -s emulator-5554 logcat -d -s CrossCheckFixture:V
# ... gpay_genuine.png backend=ocr claim=Claim(...) verdict=Match expected=Match PASS
# ... summary 10/10 PASS manifest=/storage/emulated/0/Android/data/com.crosscheck.app/files/crosscheck-fixtures/fixtures.json
```

A FAIL line lists every mismatching field (`utr=… (expected …)`). The raw ML Kit lines behind any image are at `adb logcat -d -s CrossCheckOcr:V` (`line [l,t,r,b] "text"`), which is the input format of `ClaimFieldParserTest` — copy them into a test when the parser needs a fix.

Note: the suite writes real `claims` rows and bumps `flagged_vpas` (MURUGAN by NAME for the GPay fakes, XXXXXX4321 by BANK_MASK for PhonePe), so after a run the repeat-offender note appears on the next verdicts. That is intended.

### Verify a single fixture through the photo picker

Verify → **Fixture image** → the system picker (`PickVisualMedia`) opens as a bottom sheet after ~5 s; tap a tile to select (a tick appears), then **Done** (`adb shell input tap 906 2200`). The card shows within ~3 s; the verdict is spoken (`adb logcat -d -s CrossCheckTTS:I` shows `onStart/onDone` with the Tamil text). **View receipt** opens the `claims` row; **Verify another** returns to the camera.

Screenshots for the report were taken with `adb shell screencap -p /sdcard/shot.png; adb pull …` exactly as in Phase 1 (`artefacts/phase2-verify-genuine.png`, `phase2-verify-wrong-utr.png`, `phase2-receipt.png`).

### Logcat tags added in Phase 2

| Tag | What |
|---|---|
| `CrossCheckRouter` | backend decision per extraction: `mode=AUTO abi=x86_64 arm64=false model=false -> ocr` |
| `CrossCheckVlm` | engine init (`engine ready backend=vlm-gpu init_ms=…` / `init on GPU failed: …`), per-call `backend=… total_ms=… json=…`, model import |
| `CrossCheckOcr` | `I`: parsed claim; `D`: frame size + every recognised line with its box |
| `CrossCheckCamera` | CameraX bind / capture / analysis errors |
| `CrossCheckFixture` | suite lines + summary |

### Prove the VLM path fails soft on the emulator (optional)

The x86_64 JNI is present, so a junk file at the model path drives the real engine init and its exception handling:

```powershell
adb -s emulator-5554 shell mkdir -p /data/local/tmp/llm
adb -s emulator-5554 push any-small-file /data/local/tmp/llm/gemma-4-E2B-it.litertlm
adb -s emulator-5554 shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm
# Settings → Claim extractor → VLM, then verify a fixture; expect:
#   W CrossCheckVlm: init on GPU failed: LiteRtLmJniException: Failed to create engine: INVALID_ARGUMENT: Invalid magic number ...
#   W CrossCheckVlm: init on CPU failed: ...
#   I CrossCheckRouter: mode=VLM abi=x86_64 model=true: VLM returned nothing -> ocr
adb -s emulator-5554 shell rm /data/local/tmp/llm/gemma-4-E2B-it.litertlm   # and set the extractor back to Auto
```

### Real device (iQOO 15) — switch the VLM on

```powershell
adb push "C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck\models\gemma-4-E2B-it.litertlm" /data/local/tmp/llm/gemma-4-E2B-it.litertlm
adb shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s CrossCheckVlm CrossCheckRouter
```

Settings → Claim extractor shows "Model file found" when the app can read that path; on Auto the router uses the VLM when `abi=arm64-v8a` and the file exists. If OriginOS blocks `/data/local/tmp`, copy the file to the phone's Downloads and use Settings → **Import model file (debug)** (document picker → copied into `filesDir/llm/`, path updated automatically).

## PowerShell 5.1 gotchas hit during Phase 1

- No `&&` / `||`; use `;` and `if ($?) { … }`.
- `Get-ChildItem … -ErrorAction SilentlyContinue` still makes the tool report exit 1; wrap in `try/catch` if it matters.
- `adb exec-out … > file` and `>` in general re-encode bytes (BOM + UTF-8) — never redirect binary output.
- Git Bash also works for Gradle: `JAVA_HOME="/d/Android Studio/jbr" ./gradlew assembleDebug`.
- `adb logcat -s Tag:D Tag:I` — the *last* spec for a tag wins, so listing a tag twice hides its debug lines; use `Tag:V` once.
- Scripted taps right after `am start` land on the splash; wait ~8–10 s after a fresh install, ~4 s otherwise.

## Phase 2 — voice & export

Verified on 2026-09-06 on a second AVD so two agents could work at once: `Pixel_7a` (API 36, Google Play x86_64) on **`emulator-5556`**. When two emulators run, every adb call needs `-s emulator-5556` (the emulator's own post-boot adb calls fail with "more than one emulator" — harmless).

### Boot a second emulator on a fixed port

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_7a -no-snapshot-load -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect -no-metrics -port 5556
adb -s emulator-5556 wait-for-device
adb -s emulator-5556 shell getprop sys.boot_completed     # poll until 1
```

If `adb devices` keeps saying `emulator-5556 unauthorized` after boot (the pubkey handshake did not take), a device-scoped reconnect fixes it without touching the other emulator: `adb -s emulator-5556 reconnect`.

### Seed today's data

Alphanumeric SMS senders work on this emulator version (so `senderTrusted` can be true on the SMS path):

```powershell
adb -s emulator-5556 emu sms send VM-SBIINB "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI"
adb -s emulator-5556 shell "cmd notification post -S bigtext -t 'HDFC Bank' tag1 'Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345678 from KUMAR'"
```

A **NoMatch claim** (so the digest / flagged export have a mismatch): open the Voice screen (mic button on the Log screen) and tap **"Seed a NoMatch claim"** in the debug panel — debug builds only; inserts a ₹500 / RAVI / `NOTHING_RECEIVED` receipt with `extractorBackend=debug-seed`.

### Voice query without a microphone (debug builds)

The Voice screen's debug panel ("Type a query" + **Run**) sends typed text through the same `VoiceQueryService` pipeline as the microphone. From adb:

```powershell
adb -s emulator-5556 shell input tap 975 2305                     # mic button on the Log screen (1080x2400)
adb -s emulator-5556 shell input tap 540 1075                     # the text field
adb -s emulator-5556 shell input text "did%sMurugan%spay%s500%stoday"   # %s = space
adb -s emulator-5556 shell input keyevent 4                       # close the keyboard (Run moves when it is open)
adb -s emulator-5556 shell input tap 158 1970                     # Run
adb -s emulator-5556 logcat -d -s CrossCheckSTT CrossCheckTTS
```

Coordinates drift with content; when in doubt `adb -s emulator-5556 shell uiautomator dump /sdcard/ui.xml` and read the `bounds` of the node whose `text` is `Run`. Evidence lines: `CrossCheckSTT: query transcript="…" parsed=DidPay(name=Murugan, amountPaise=50000) answer="…"` then `CrossCheckTTS: onStart/onDone id=cc-N text="…"`. Try `how was today`, `Murugan 500 vandhucha`, `murugan ne 500 diya kya`, `இன்று எப்படி`.

### On-device STT status

Open Settings (gear) — the "On-device speech recognition" section runs `checkRecognitionSupport` for ta-IN / en-IN / hi-IN and logs one line per locale:

```powershell
adb -s emulator-5556 logcat -d -s CrossCheckSTT | Select-String checkRecognitionSupport
```

On the API 36 Play image: `installed=[en-US]`, `en-IN`/`hi-IN` downloadable, **`ta-IN` not listed at all**. Tapping the real mic with `ta-IN` therefore logs `onError code=12 (LANGUAGE_NOT_SUPPORTED)` and the screen shows the "download it in Settings or switch to English (India)" card. Tamil **TTS**, by contrast, was available (`CrossCheckTTS: locale requested=ta-IN using=ta-IN`).

### Office Kit export

Tap the share icon ("Export today") in the Log top bar. Files land in the app's external files dir and the system share sheet opens (~2 s on the emulator with swiftshader; `ActivityTaskManager: Displayed com.android.intentresolver/.ChooserActivityLauncher … +1s896ms`):

```powershell
adb -s emulator-5556 logcat -d -s CrossCheckExport
adb -s emulator-5556 shell ls -l /sdcard/Android/data/com.crosscheck.app/files/reports/
adb -s emulator-5556 shell cat /sdcard/Android/data/com.crosscheck.app/files/reports/*-summary.txt
adb -s emulator-5556 pull /sdcard/Android/data/com.crosscheck.app/files/reports/ "C:\path\to\reports"
```

Screenshot the share sheet while it is up (`adb -s emulator-5556 shell "dumpsys activity activities | grep topResumedActivity"` shows `com.android.intentresolver/.ChooserActivityLauncher`), then `input keyevent 4` to dismiss it.
