# NAHID AI — Dual-Path Voice AI & Offline TTS Report (REQ 5)

## 1. Dual-Path Speech Architecture (`JarvisVoiceSpeaker.kt`, `AndroidManifest.xml`)
- **Manifest Declaration**: Added `<intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>` inside `<queries>` in `AndroidManifest.xml` for Android 11+ TTS engine discovery.
- **Primary Path (`GEMINI_LIVE`)**: When a live audio dispatcher is registered via `GeminiLiveAnnouncementAdapter`, announcements stream through Gemini Live.
- **Offline Fallback Path (`ANDROID_OFFLINE_TTS`)**: When Gemini Live is disconnected/offline and `offlineTtsEnabled == true`, initializes Android `TextToSpeech` with locale priority `bn-BD` -> `bn` -> `en-US` and tracks live status via `OfflineTtsStatus` (`UNINITIALIZED`, `INITIALIZING`, `READY`, `SPEAKING`, `UNAVAILABLE_NO_ENGINE`, `MUTED`) and `lastSpeakPath` `StateFlow`.
- **Safety & Privacy Enforcement**:
  - Every utterance passes through `GeminiLiveAnnouncementAdapter.sanitizeForVoice(...)` to redact raw OTP digits before TTS or Live dispatch.
  - Active telephony calls automatically defer speech (`MUTED_OR_IN_CALL`).
  - `silentMode == true` logs UI announcement text without emitting speaker audio (`MUTED_SILENT_MODE`).

## 2. Persistent Preferences & Settings UI (`AiSettingsRepository.kt`, `AssistantViewModel.kt`, `AiSettingsDialog.kt`)
- Added `offlineTtsEnabled: Boolean = true`, `ttsSpeechRate: Float = 1.0f`, and `silentMode: Boolean = false` to `AiSettingsState`, persisted in `SharedPreferences` (`nahid_ai_settings`) and automatically synchronized to `JarvisVoiceSpeaker.configureVoiceSettings(...)` on load and save.
- Added interactive Material 3 controls in `AiSettingsDialog.kt`:
  - `offline_tts_switch`: Offline Bengali/English TTS fallback toggle
  - `silent_mode_switch`: Silent / Text-Only Mode toggle
  - `tts_speech_rate_slider`: Speech rate slider (`0.70x` to `1.50x`)

## 3. Verification Status
- Live vs Offline routing, OTP digit sanitization, call deferral, Silent Mode suppression, and persistent speech-rate settings sync: **Verified via Robolectric JVM tests**.
- Physical phone audio output via Android `TextToSpeech` Bengali voice pack & Gemini Live WebSocket audio roundtrip: **`DEVICE TEST PENDING`**.
