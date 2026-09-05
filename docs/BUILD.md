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

Inject a bank SMS (numeric sender only — the console cannot spoof alphanumeric IDs):

```powershell
adb emu sms send 5551234 "Rs.500.00 credited to A/c XX1234 on 06-09-26 by UPI Ref No 426112345678 from MURUGAN. -SBI"
```

Post a fake bank notification. **Quoting matters**: in PowerShell wrap the whole remote command in one string and use single quotes inside, otherwise the device shell re-splits `"HDFC Bank"` into title/tag/text and the body is lost:

```powershell
adb shell "cmd notification post -S bigtext -t 'HDFC Bank' tag1 'Rs.750.00 credited to a/c XX1234 via UPI Ref 624912345678 from KUMAR'"
```

Read the evidence:

```powershell
adb logcat -d -s CrossCheckTTS CrossCheckSMS CrossCheckNotif CrossCheckDB
```

- `CrossCheckSMS` / `CrossCheckNotif`: `result=Inserted | Duplicate | NotACredit` per signal.
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
