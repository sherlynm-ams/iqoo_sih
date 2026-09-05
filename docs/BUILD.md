# CrossCheck — build, run and verify (Windows 11, PowerShell 5.1)

Everything below was executed verbatim on this machine on 2026-09-06 and worked.
Repo root: `C:\Users\Sherlyn\Documents\sherwin claude code\CrossCheck` (the harness cwd is the parent folder, so every command uses absolute paths).

## Toolchain

| Piece | Version / path |
|---|---|
| JDK | 17 — `D:\Android Studio\jbr` (set `JAVA_HOME` on every Gradle call; nothing on PATH) |
| Gradle | 8.11.1 via wrapper (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP | 8.9.1 · Kotlin 2.1.0 · KSP 2.1.0-1.0.29 · Room 2.7.1 (+ `androidx.room` Gradle plugin) |
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

## PowerShell 5.1 gotchas hit during Phase 1

- No `&&` / `||`; use `;` and `if ($?) { … }`.
- `Get-ChildItem … -ErrorAction SilentlyContinue` still makes the tool report exit 1; wrap in `try/catch` if it matters.
- `adb exec-out … > file` and `>` in general re-encode bytes (BOM + UTF-8) — never redirect binary output.
- Git Bash also works for Gradle: `JAVA_HOME="/d/Android Studio/jbr" ./gradlew assembleDebug`.

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
