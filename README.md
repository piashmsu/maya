<div align="center">

# 🌹 MYRA — Android AI Voice Assistant

### *Your Hinglish • বাংলা • English AI Girlfriend / Assistant — Powered by Gemini Live*

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#)
[![Gemini Live](https://img.shields.io/badge/Gemini-Live%20WebSocket-FF6F00?style=for-the-badge&logo=google&logoColor=white)](#)
[![Root](https://img.shields.io/badge/Root-One%20Click%20Setup-FF1744?style=for-the-badge&logo=android&logoColor=white)](#)
[![Languages](https://img.shields.io/badge/Lang-Hinglish%20%E2%80%A2%20%E0%A6%AC%E0%A6%BE%E0%A6%82%E0%A6%B2%E0%A6%BE%20%E2%80%A2%20EN-D500F9?style=for-the-badge)](#)

```
┌────────────────────────────────────────────────────────────┐
│   "Hey MYRA..."                                            │
│        ↓                                                   │
│   [ 🎙️  AudioRecord 16kHz ] → WebSocket → Gemini Live      │
│        ↓                                                   │
│   [ 🔊  AudioTrack 24kHz  ] ← native audio reply ← Gemini  │
│        ↓                                                   │
│   📞 SMS • 💬 WhatsApp • 📷 Vision • 🔔 Notifications      │
└────────────────────────────────────────────────────────────┘
```

</div>

---

## 🎯 What is MYRA?

MYRA is a fully voice-driven Android assistant that *actually* talks back in a natural human voice (not robotic TTS) by streaming PCM audio over Gemini's `BidiGenerateContent` WebSocket. She can:

- 💬 **Talk** — full-duplex voice convo in **Hinglish, বাংলা, Benglish, or English**
- 📞 **Run your phone** — calls, SMS, WhatsApp, open / close apps, volume, flashlight, WiFi, Bluetooth
- 🎙️ **Wake up on call** — say **"Hey MYRA"** with screen off, she opens
- 🔔 **Read notifications aloud** — WhatsApp / SMS / Telegram / Instagram / Gmail / Messenger
- 📸 **See** — point camera at anything, she describes it (Gemini Vision)
- 💾 **Remember** — chat history persists across restarts (500-message rolling buffer)
- ⚡ **Auto-setup on rooted devices** — one tap grants every permission silently
- 🫧 **Float** — overlay orb appears anywhere with double power-button press

---

## 📦 Latest Build

> **📥 Download:** See the latest GitHub Actions artifact or the APK attached to the most recent PR.
> Both `myra-debug.apk` (~7.2 MB) and `myra-release.apk` (~2.4 MB, R8-minified) are produced on every push.

| File | Size | Notes |
|------|------|-------|
| `app-debug.apk` | 7.2 MB | Installs as `com.myra.assistant.debug` — keeps your release install separate |
| `app-release.apk` | 2.4 MB | R8-minified, signed with debug key (replace with your keystore for Play Store) |

---

## 🚀 Quick Start

### 1. Install
```bash
adb install myra-debug.apk
# or just transfer to phone and tap the APK
```

### 2. First Launch
1. Open MYRA → **Settings**
2. Paste your **Gemini API key** ([get one here](https://aistudio.google.com/apikey))
3. Pick a **Language** — Hinglish / বাংলা (Bengali) / Benglish / English
4. Pick a **Voice** — 8 options including Aoede (F), Charon (M), Kore (F) …
5. Pick a **Personality** — 💖 GF / 💼 Professional / 🤖 Assistant
6. Save → app restarts

### 3. Permissions (rooted device — one tap)
1. Settings → **⚡ AUTO SETUP (ROOT)** → grant `su` → done
2. All 17 permissions + accessibility + notification listener + battery whitelist + assistant role registered silently

### 3b. Permissions (non-root)
| Permission | Where |
|------------|-------|
| Microphone, Contacts, Phone, SMS, Camera, Notifications | first launch prompt |
| Display over other apps | Settings → Apps → MYRA → "Display over other apps" |
| Accessibility | Settings → Accessibility → MYRA Helper → On |
| Notification access | Settings → Apps & notifications → Special app access → Notification access → MYRA On |
| Battery optimisation | Settings → Battery → MYRA → "Don't optimise" |

### 4. Talk to MYRA
- Press the mic button → talk → release → MYRA replies in her natural voice
- **Long press mic** = interrupt while she's speaking
- Say **"YouTube kholo"** / **"Priya ke call koro"** / **"flashlight on"** etc.
- Enable **wake word** in Settings → say **"Hey MYRA"** anywhere → app pops up

---

## 🧬 Feature Matrix

### 🗣️ Voice & AI
| Feature | Status | File |
|---------|--------|------|
| Gemini Live WebSocket (real-time voice) | ✅ | `ai/GeminiLiveClient.kt` |
| Native PCM audio (16k mic / 24k speaker) | ✅ | `ai/AudioEngine.kt` |
| Hinglish + বাংলা + Benglish + English | ✅ | `ai/SystemPrompts.kt` + `data/Prefs.kt` |
| Voice picker — 8 voices (Aoede, Charon, Kore …) | ✅ | Settings |
| Personality modes — GF / Pro / Assistant | ✅ | `ai/SystemPrompts.kt` |
| Native voice speech (no robotic TTS) | ✅ | Gemini Live native audio model |
| Auto-reconnect on disconnect | ✅ | `GeminiLiveClient` |
| 9-minute session renewal | ✅ | `SESSION_RENEW_AFTER` |
| 8-second keepalive | ✅ | `KEEPALIVE_INTERVAL` |
| Interrupt while MYRA is speaking | ✅ | long-press mic |
| Vision mode (camera → describe) | ✅ | `ai/GeminiVisionClient.kt` + camera button |

### 🎙️ Wake Word
| Feature | Status | File |
|---------|--------|------|
| **"Hey MYRA"** / **"Hi MYRA"** / **"OK MYRA"** | ✅ | `service/WakeWordService.kt` |
| Continuous SpeechRecognizer loop | ✅ | restarts on error with backoff |
| Foreground microphone service | ✅ | survives screen-off (with battery whitelist) |
| Settings toggle | ✅ | Settings → Wake word switch |
| Cooldown after trigger (5s) | ✅ | prevents re-trigger spam |

### 📞 Phone Actions
| Feature | Status | File |
|---------|--------|------|
| Open / close apps (30+ presets + label scan) | ✅ | `viewmodel/MainViewModel.kt` |
| Make calls (with contact lookup) | ✅ | `MainViewModel.callContact` |
| Send SMS | ✅ | `MainViewModel.sendSms` |
| WhatsApp message + call | ✅ | `wa.me` deep link |
| Prime contacts (multi, JSON-stored) | ✅ | `data/Prefs.kt` + Settings RecyclerView |
| Volume up / down | ✅ | `AudioManager` |
| Flashlight on / off | ✅ | `CameraManager.torch` |
| WiFi / Bluetooth toggles | ✅ | `WifiManager`, `BluetoothAdapter` |
| Incoming call announcement | ✅ | `service/CallMonitorService.kt` |
| Voice-controlled call accept / reject | ✅ | `MainActivity` post-announcement STT |

### 🔔 Notifications
| Feature | Status | File |
|---------|--------|------|
| Read notifications aloud | ✅ | `service/MyraNotificationListener.kt` |
| WhatsApp / SMS / Telegram / Instagram / Gmail / Messenger | ✅ | package filter list |
| Language-aware spoken phrasing | ✅ | uses `prefs.language` |
| Settings toggle | ✅ | Settings → Read notifications aloud |
| Voice reply via `RemoteInput` | ⏳ | future (Phase 3) |

### 💾 Persistence
| Feature | Status | File |
|---------|--------|------|
| Chat history across restarts | ✅ | `data/ChatHistory.kt` (JSON @ `filesDir/chat_history.json`) |
| 500-message rolling cap | ✅ | auto-trim oldest |
| Clear history button | ✅ | Settings → Clear chat history |
| All prefs in SharedPreferences | ✅ | `data/Prefs.kt` |
| Prime contacts JSON migration | ✅ | legacy `prime_name` / `prime_number` keys read on first launch |

### 🫧 UI
| Feature | Status | File |
|---------|--------|------|
| Animated orb (Canvas, 7 layers, 4 states) | ✅ | `ui/main/OrbAnimationView.kt` |
| 20-bar amplitude waveform | ✅ | `ui/main/UiComponents.kt` |
| Dark / red / purple theme | ✅ | `res/values/themes.xml` |
| Chat bubbles (user red / MYRA dark) | ✅ | `ui/main/UiComponents.kt` |
| Floating overlay orb (draggable) | ✅ | `service/MyraOverlayService.kt` |
| Double power-press → overlay | ✅ | `service/PowerButtonReceiver.kt` |
| Red screen-flash when MYRA active | ✅ | `MainActivity.redOverlay` |
| Camera icon in top bar | ✅ | `res/drawable/ic_camera.xml` |

### 🔐 Root / Permissions
| Feature | Status | File |
|---------|--------|------|
| One-tap root auto-setup | ✅ | `data/RootSetup.kt` |
| `su` availability check | ✅ | `which su` |
| 11 runtime permissions via `pm grant` | ✅ | RECORD_AUDIO, READ_CONTACTS, CALL_PHONE … |
| Overlay via `appops set SYSTEM_ALERT_WINDOW allow` | ✅ | |
| Write settings via `appops set WRITE_SETTINGS allow` | ✅ | |
| Accessibility via `settings put secure enabled_accessibility_services` (append-safe) | ✅ | |
| Notification listener via `enabled_notification_listeners` (append-safe) | ✅ | |
| Battery whitelist via `dumpsys deviceidle whitelist` | ✅ | |
| Assistant role via `cmd role add-role-holder` | ✅ | |
| Per-command success / fail report in UI | ✅ | `SettingsActivity.runRootSetup` |

---

## 🏗️ Architecture

```
app/src/main/java/com/myra/assistant/
├── ai/
│   ├── GeminiLiveClient.kt     WebSocket BidiGenerateContent + keepalive + reconnect
│   ├── GeminiVisionClient.kt   REST generateContent for image description
│   ├── AudioEngine.kt          AudioRecord 16k + AudioTrack 24k + RMS + mute / interrupt
│   ├── CommandParser.kt        Voice text → AppCommand (Hinglish + English + বাংলা)
│   └── SystemPrompts.kt        Personality × language matrix
│
├── data/
│   ├── Prefs.kt                SharedPreferences wrapper (API key, name, model, voice,
│   │                           personality, language, wake word, notif reader, prime contacts)
│   ├── RootSetup.kt            One-tap `su -c` batch executor
│   └── ChatHistory.kt          JSON-backed message persistence (500 cap)
│
├── model/
│   └── AppCommand.kt           data class for parsed commands
│
├── service/
│   ├── AccessibilityHelperService.kt   open / close / click / type / scroll
│   ├── CallMonitorService.kt           PhoneStateListener → MainActivity broadcast
│   ├── MyraOverlayService.kt           draggable floating orb
│   ├── MyraNotificationListener.kt     WhatsApp/SMS/etc → broadcast → speak
│   ├── WakeWordService.kt              continuous SpeechRecognizer loop
│   ├── PowerButtonReceiver.kt          double-press detection
│   └── BootReceiver.kt                 auto-start on boot
│
├── ui/
│   ├── main/
│   │   ├── MainActivity.kt     orchestrates WebSocket + audio + chat + commands
│   │   ├── OrbAnimationView.kt 7-layer Canvas orb
│   │   └── UiComponents.kt     WaveformView + ChatMessage + ChatAdapter
│   └── settings/
│       ├── SettingsActivity.kt all toggles, spinners, root setup button
│       └── PrimeContactAdapter.kt
│
└── viewmodel/
    └── MainViewModel.kt        all phone actions on Dispatchers.IO
```

### Data flow on a single voice turn

```
User taps mic
   │
   ▼
AudioEngine.startRecording()  ──── PCM 16k mono ───▶  GeminiLiveClient.sendAudio()
                                                              │
                                                              ▼
                                            WebSocket realtime_input.media_chunks
                                                              │
                                                              ▼
                                            Gemini Live (native-audio model)
                                                              │
                              ┌───────────────────────────────┼───────────────────────────────┐
                              ▼                               ▼                               ▼
                  inlineData.data (PCM 24k)       outputTranscription.text       turnComplete=true
                              │                               │                               │
                              ▼                               ▼                               ▼
                  AudioEngine.queueAudio()      onOutputTranscript buffer        flush → ChatAdapter
                              │                               │                               │
                              ▼                               ▼                               ▼
                       AudioTrack 24k                    chat bubble               ChatHistory.append()
```

---

## ⚙️ Settings Reference

| Setting | Default | Notes |
|---------|---------|-------|
| API key | (empty) | Required. Get from [Google AI Studio](https://aistudio.google.com/apikey) |
| Your name | (empty) | MYRA calls you by this |
| AI model | `gemini-2.5-flash-native-audio-preview-12-2025` | 3 options |
| Voice | `Aoede` | 8 prebuilt voices |
| **Language** | `Hinglish` | **Hinglish / বাংলা / Benglish / English** |
| Personality | `GF 💖` | GF / Professional / Assistant |
| **Wake word** | off | toggle on after AUTO SETUP / battery whitelist |
| **Read notifications aloud** | off | enable via notification-listener system setting too |
| Prime contacts | (empty) | unlimited, JSON-stored |
| **Clear chat history** | — | wipes `filesDir/chat_history.json` |
| **⚡ AUTO SETUP (ROOT)** | — | requires Magisk / KernelSU `su` |

---

## 🛠️ Build

```bash
git clone https://github.com/piashmsu/maya.git
cd maya
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

Requirements: JDK 17, Android SDK 34, Gradle 8.7 (wrapper included).

CI: every push to `main` and every PR runs `.github/workflows/build-apk.yml` and uploads both APKs as 30-day artifacts.

---

## 📜 Version History / Changelog

> Every fix and feature update gets a row here so the user (or any AI agent picking this up later) can see exactly what's changed at a glance.

### v2.0.1 — 🩹 Root Setup Hotfix *(2026-05-12)*
- **Fix:** `RootSetup.kt` now uses `context.packageName` instead of hardcoded `com.myra.assistant`. Debug builds (`com.myra.assistant.debug`) were silently failing to enable accessibility because the component string pointed at a non-existent package.
- **Fix:** `enabled_accessibility_services` and `enabled_notification_listeners` are now **append-safe** — they preserve any other services the user already has on (TalkBack, Switch Access, etc.) instead of overwriting the whole list.

### v2.0.0 — 🚀 Phase 1 + Phase 2 ("Boro Update") *(2026-05-11)*
Six features in one PR:
- ⚡ **Root auto-setup** — one tap → 17 commands → all permissions granted silently (`data/RootSetup.kt`)
- 🎙️ **Wake word "Hey MYRA"** — continuous `SpeechRecognizer` loop, foreground mic service (`service/WakeWordService.kt`)
- 🇧🇩 **Bengali / Benglish / English language support** — system prompts + greetings + BCP-47 `language_code` for native-audio voice
- 🔔 **Notification reader** — `NotificationListenerService` filters WhatsApp / SMS / Telegram / Instagram / Gmail / Messenger and speaks aloud in chosen language
- 📸 **Vision mode** — camera button → Gemini REST `generateContent` with inline JPEG → MYRA describes what she sees
- 💾 **Chat history** — JSON persistence (`filesDir/chat_history.json`), 500-message rolling cap, "Clear history" button

### v1.0.1 — 🧰 APK Build Fixes *(2026-05-11)*
- Removed duplicate unprefixed `colorBackground` from `themes.xml`
- Fixed CardView `app:` namespace in `activity_settings.xml` (9 attrs)
- Renamed `startForeground` shadow helper to `startMyraForeground` to avoid weakening visibility
- Fixed `OrbAnimationView` JVM signature clash on `setAmplitude`
- Corrected SDK version gate (`Q` not `UpsideDownCake`)

### v1.0.0 — 🌹 Initial Scaffold *(2026-05-11)*
Full MYRA spec implemented:
- Gemini Live WebSocket (`BidiGenerateContent`) with keepalive + 9-min session renewal + auto-reconnect
- Native PCM audio engine (16 kHz mic / 24 kHz speaker, RMS, mute, interrupt)
- 7-layer animated Canvas orb (idle / listening / speaking / thinking)
- 20-bar amplitude waveform
- 3 personality modes × 8 voices × 3 models
- Hinglish + English command parser (open app, call, SMS, WhatsApp, prime contacts, volume, flashlight, WiFi, Bluetooth)
- Accessibility helper service (click / type / scroll / close app)
- Floating overlay orb + double-power-press trigger
- Incoming call announcement + voice-controlled accept / reject
- Multi prime contacts with RecyclerView UI

### GitHub Actions + Gradle wrapper *(2026-05-11)*
- `.github/workflows/build-apk.yml` — builds debug + release on every push / PR, uploads as artifacts (30-day retention)
- `gradlew` / `gradlew.bat` / `gradle-wrapper.jar` — Gradle 8.7 wrapper for CI + contributors without a local Gradle install

---

## 🔭 Roadmap (Phase 3 ideas)

| # | Idea | Notes |
|---|------|-------|
| 1 | Voice reply to notifications via `RemoteInput` | "Reply Priya: aa rahi hoon" |
| 2 | Screenshot OCR | "ye text translate koro" |
| 3 | Reminders & alarms | "kal 7 baje yaad dilana" |
| 4 | VAD (voice activity detection) | save bandwidth + battery |
| 5 | Full-duplex echo cancellation | interrupt by voice (no long-press) |
| 6 | Lock-screen widget / quick tile | one-tap from notification panel |
| 7 | Custom voice clone (ElevenLabs) | speak in your GF / wife's voice |
| 8 | Spotify / YouTube playback control | "play Arijit Singh" |
| 9 | Smart home (MQTT / Tuya) | bulb / AC voice control |
| 10 | Gmail read + reply | language-aware summarisation |
| 11 | Calendar integration | "Sunday er meeting set kor" |
| 12 | Real-time translation mode | English ↔ Hindi ↔ বাংলা |
| 13 | WhatsApp auto-reply | busy-mode AI reply |
| 14 | Picovoice Porcupine wake word | offline + lower latency |
| 15 | Personality editor | write your own system prompt |
| 16 | Theme picker | red / blue / green / purple |

---

## 🧑‍💻 For AI Agents Continuing This Work

If you (another AI or future-me) are picking this up:

1. **Repo:** `piashmsu/maya` — pre-cloned at `/home/ubuntu/maya` in standard Devin sessions.
2. **Branch convention:** `devin/<timestamp>-<short-name>`. Base off `main`.
3. **CI:** GitHub Actions builds APKs automatically. If Actions is disabled on the repo, build locally with `./gradlew assembleDebug` (needs `$ANDROID_HOME/cmdline-tools/latest/bin` on PATH + JDK 17 + SDK 34).
4. **Package id quirk:** debug builds install as `com.myra.assistant.debug` (`applicationIdSuffix=".debug"` in `app/build.gradle`). Anywhere you reference the package id at runtime, use `context.packageName` — **never** hardcode `com.myra.assistant`. The Kotlin namespace stays `com.myra.assistant` regardless, so fully-qualified class names don't change.
5. **PR base gotcha:** Devin's `git_pr` defaults sometimes pick a previous feature branch as the base. Always explicitly set `base_branch="main"` on `git_pr(action="create")`.
6. **Cognition guardrail:** agents cannot merge PRs into `main` directly. Always ask the user to click "Squash and merge" in the GitHub UI.
7. **README is the source of truth for "what's done":** append a new entry to `## Version History / Changelog` for every fix or feature. The user explicitly asked for this so they (and future agents) can audit what changed without reading every commit.
8. **APIs to know:**
   - `GeminiLiveClient` — WebSocket `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=…`. Setup → `realtime_input.media_chunks` for mic → `serverContent.modelTurn.parts.inlineData.data` for audio reply.
   - `GeminiVisionClient` — REST `generateContent` (not WebSocket). Inline image bytes + text prompt.
   - `RootSetup.runAll(context)` — call from `Dispatchers.IO`. Returns `List<StepResult>` with per-command ok/fail.
9. **Settings clash:** writing to `enabled_accessibility_services` or `enabled_notification_listeners` overwrites the whole list. Always read-then-append. The current `RootSetup.kt` already does this.

---

## 🙏 Credits

- **Concept & spec:** [@piashmsu](https://github.com/piashmsu)
- **Engine:** Google Gemini Live (`BidiGenerateContent` WebSocket)
- **Built by:** [Devin AI](https://devin.ai)

---

<div align="center">

**Made with 💖 in Hinglish • বাংলা • English**

*"Hey MYRA, kemon achho?"*

</div>
