# MYRA — Full Specification

This document mirrors the master build prompt that drove the initial
implementation. Use it as the source of truth for behaviour and naming.

## AI engine

* **Endpoint:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=<API_KEY>`
* **Session config:**
  * `SESSION_RENEW_AFTER` = 540 s (9 min)
  * `KEEPALIVE_INTERVAL` = 8 s (silent 16 kHz PCM chunk)
  * Auto-reconnect after disconnect with a 3 s delay
* **Setup message:** sends `model`, `system_instruction`, `generation_config.response_modalities=[AUDIO]`, `speech_config.voice_config.prebuilt_voice_config.voice_name`, `temperature=0.9`, `output_audio_transcription` and `input_audio_transcription`
* **Mic chunk:** `realtime_input.media_chunks[0]` with `mime_type=audio/pcm;rate=16000`
* **User text:** `client_content.turns[].parts[].text` with `turn_complete=true`
* **Interrupt:** `client_content.turns=[]` with `turn_complete=true`
* **Server parsing:**
  * `serverContent.modelTurn.parts[].inlineData.data` → base64 PCM @ 24 kHz → play to speaker
  * `serverContent.outputTranscription.text` → MYRA's spoken text
  * `serverContent.inputTranscription.text` → user's transcript
  * `serverContent.turnComplete == true` → flush both transcripts to chat

## Audio engine

| Side | Sample rate | Channels | Encoding | Source / sink |
|------|-------------|----------|----------|----------------|
| Mic  | 16000 Hz    | Mono     | PCM 16-bit | `MediaRecorder.AudioSource.VOICE_RECOGNITION` |
| Spk  | 24000 Hz    | Mono     | PCM 16-bit | `AudioAttributes.USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH` |

Chunk size is 1024 bytes. While MYRA is speaking, mic frames are *not* sent
out (basic echo suppression). A long-press on the mic clears the playback
queue and sends an `interrupt`.

## Voices

`Aoede` (default, female), `Charon` (male), `Kore` (female), `Fenrir` (male),
`Puck` (male), `Leda` (female), `Orus` (male), `Zephyr` (female).

## Personality modes

* **GF (default):** Hinglish, warm, 2–3 sentences, names the user.
* **Professional:** Formal English, ≤ 2 sentences, no emoji.
* **Assistant:** Friendly Hinglish/English, ≤ 3 sentences.

## Phone commands handled by `MainViewModel`

`OPEN_APP`, `CLOSE_APP`, `CALL`, `SMS`, `WHATSAPP_MSG`, `WHATSAPP_CALL`,
`PRIME_CALL`, `PRIME_MSG`, `VOLUME_UP`, `VOLUME_DOWN`, `VOLUME_MUTE`,
`FLASHLIGHT_ON`, `FLASHLIGHT_OFF`, `WIFI_ON`, `WIFI_OFF`, `BLUETOOTH_ON`,
`BLUETOOTH_OFF`.

## Incoming call flow

1. `CallMonitorService` detects `CALL_STATE_RINGING` via `PhoneStateListener`.
2. Caller name is looked up from `ContactsContract`.
3. `MainActivity` is started with `INCOMING_CALL=true`.
4. MYRA sends `"Sir, <name> ka call aa raha hai. Uthau ya reject karu?"` to Gemini.
5. After 4.5 s, a `SpeechRecognizer` listens for the user's decision.
6. Keywords matched: `uthao` / `haan` / `accept` / `answer` → `TelecomManager.acceptRingingCall()`.
7. Keywords matched: `reject` / `nahi` / `mat` / `decline` → `TelecomManager.endCall()`.
8. `CALL_STATE_IDLE` broadcasts `com.myra.CALL_ENDED` so the UI exits in-call mode.

## Prime contacts

Stored as JSON in `SharedPreferences` key `prime_contacts_json`:

```json
[
  {"name": "Priya", "number": "+919876543210"},
  {"name": "Mom", "number": "+919123456789"}
]
```

Legacy single-contact keys (`prime_name` / `prime_number`) are migrated
automatically the first time the JSON is read.

## Files

```
app/src/main/java/com/myra/assistant/
├── ai/
│   ├── GeminiLiveClient.kt
│   ├── AudioEngine.kt
│   ├── CommandParser.kt
│   └── SystemPrompts.kt
├── data/
│   └── Prefs.kt
├── model/
│   └── AppCommand.kt
├── service/
│   ├── AccessibilityHelperService.kt
│   ├── CallMonitorService.kt
│   ├── MyraOverlayService.kt
│   ├── PowerButtonReceiver.kt
│   └── BootReceiver.kt
├── ui/
│   ├── main/
│   │   ├── MainActivity.kt
│   │   ├── OrbAnimationView.kt
│   │   └── UiComponents.kt
│   └── settings/
│       ├── SettingsActivity.kt
│       └── PrimeContactAdapter.kt
├── viewmodel/
│   └── MainViewModel.kt
└── MyraApplication.kt
```
