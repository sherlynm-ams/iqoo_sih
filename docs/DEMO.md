# CrossCheck — demo run-book (city battle, 12–13 Sept 2026)

Target: **45–60 seconds**, three beats, entirely on the iQOO 15, no network. Rehearse the whole thing twice on the loaner before each eval round.

## 0. Setup on the iQOO 15 (do this in the first hour after check-in)

| Step | Command / action | Pass signal |
|---|---|---|
| Identify device | `adb shell getprop ro.soc.model` → `SM8850`; `getprop ro.build.version.release` → `16` | matches vlm-runtime.md §1 |
| Install (adb, not by copying the APK — sideloading triggers "Restricted settings") | `adb install -r app-debug.apk` | `Success` |
| Permissions | `adb shell pm grant com.crosscheck.app android.permission.RECEIVE_SMS` / `POST_NOTIFICATIONS` / `CAMERA` / `RECORD_AUDIO` | no prompts on stage |
| Notification access | `adb shell cmd notification allow_listener com.crosscheck.app/.signal.NotificationListenerSource` → confirm in app Settings: "Notification access: granted" | green text |
| Tamil TTS voice | Settings → "Test speech" — if it speaks English, download Tamil (India) once: `adb shell am start -n com.google.android.tts/com.google.android.apps.speech.tts.googletts.local.voicepack.ui.VoiceDataInstallActivity` → Tamil (India) → Download | `CrossCheckTTS: using=ta-IN` |
| Tamil STT check | Settings → "On-device speech recognition" section | if `ta-IN` is *Installed* → demo the voice beat in Tamil; if *Not supported* → switch speech language to **English (India)** for the voice beat (answers still spoken in Tamil? No — answers follow the speech locale; decide one locale for the whole demo and stick to it) |
| Gemma model | `adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma-4-E2B-it.litertlm; adb shell chmod 644 /data/local/tmp/llm/gemma-4-E2B-it.litertlm` → Settings shows "Model file found" | `CrossCheckRouter: … -> vlm-gpu` on the first verify |
| Benchmark | vlm-runtime.md §4 (≤ 2 h). Decide: VLM AUTO (if p95 < 5 s and UTR ≥ 8/10) or force **OCR** in Settings | write the decision on the whiteboard |
| Seed the log | Make ONE real UPI payment of a known amount (e.g. ₹10) to the demo phone's personal UPI ID from a teammate's phone ~15 min before the round, so Mode 1 has already fired and a row exists. Note its UTR from the bank SMS / app notification. | Log screen shows the row |
| Fake claim | On a teammate's phone, open a *screenshot* of a different/edited payment (wrong UTR or wrong amount) — or the `fixtures/phonepe_wrong_utr.png` image, full-screen, brightness max | ready in the gallery |

## 1. The script (≈ 50 s)

**Beat 1 — Mode 1 live (≈ 12 s).** Teammate sends a real ₹10 UPI payment to the phone on stage. Say nothing. The phone speaks: *"முருகன் அனுப்பிய ₹10 பெறப்பட்டது."* Point at the Log screen: the row appears with UTR and source. Line: *"No soundbox, no merchant account — just the phone listening to its own bank alert."*
- Insurance: if the bank/app is slow (SMS delay is the #1 risk — see SPEC risk 2), move on and come back; if it never arrives, show the pre-seeded row from step 0 and play the 10-second backup clip of Beat 1 recorded during rehearsal.

**Beat 2 — Mode 2 dispute (≈ 25 s).** Teammate holds up the fake "payment successful" screen and says "I paid you 500." Tap **Verify a claim** → point the camera → it auto-captures (or tap Capture). The verdict card goes red; the phone speaks: *"பொருந்தவில்லை — UTR பொருந்தவில்லை."* If this payer has failed before, it prefixes *"குறிப்பு: இந்த நபர் இதற்கு முன் N முறை சரிபார்ப்பில் தோல்வியடைந்துள்ளார்."* Tap **View receipt** — show the UTR that was claimed vs "nothing received", the payer key, and the extractor backend line. Line: *"It doesn't judge the picture. It checks whether the money is actually in this phone's own bank record — the only proof NPCI says counts."*
- Insurance: Settings → Claim extractor → **OCR** if the VLM is slow that day; both produce the same verdict. Rehearse the failed-before prefix by running the same fake claim once during setup (that increments the payer's fail count).

**Beat 3 — voice digest (≈ 10 s).** Tap the mic, ask *"how was today?"* (or *"இன்று எப்படி?"* if Tamil STT is installed). The phone answers: *"இன்று 1 பணம் உறுதி செய்யப்பட்டது, 1 பொருத்தமின்மை, ₹500 குறியிடப்பட்டது."* Optional: tap **Export today** → share sheet → Office Kit transfer to the laptop, open the CSV. Line: *"End of day, the seller knows exactly what came in and who tried it on."*

## 2. The two questions to have ready

- **"Isn't this a soundbox?"** — Soundboxes need a merchant account and only announce real payments. This runs on a personal UPI ID with nothing but the phone, and it actively refutes a fake claim being pushed on you right now, with the reason.
- **"Is it on the NPU?"** — *"Fully on-device: the vision model runs on the Adreno GPU via LiteRT-LM; nothing leaves the phone. NPU builds for a vision model on this chip don't exist yet in Google's stack."* Don't claim NPU.

## 3. Things that break demos (checked list)

- Bank credit SMS can be minutes late, or absent for small amounts — the UPI *app notification* is what Mode 1 relies on; make sure GPay/PhonePe notifications are enabled on the demo phone and the app is in the trusted list.
- Restricted settings: install with `adb install`, never by tapping an APK.
- Auto-brightness: set the teammate's phone to max brightness; tilt ~10° to kill glare.
- Camera permission prompt on stage: pre-granted via `pm grant`.
- Don't leave the app force-stopped — Android blocks receivers until relaunch. Open it before walking on.
- Speech locale is global: pick Tamil or English (India) for the whole run; don't switch mid-demo.
