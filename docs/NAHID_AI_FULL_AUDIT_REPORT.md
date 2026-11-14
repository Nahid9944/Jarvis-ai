# NAHID AI — Full Source Code Audit Report (Phase 0)

**Date:** 2026-11-14  
**App Identity:** NAHID AI (মায়া / Maya)  
**Package / App ID:** `namespace = "com.example"`, `applicationId = "com.jannani.pharmacy.torwmx"` (preserved for keystore & Room upgrade continuity)  
**Build Environment:** Android SDK 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`), Kotlin 2.2.10, Jetpack Compose BOM 2024.09.00, Room 2.7.0, KSP 2.3.10  
**Verified Baseline:** `compile_applet` PASS; `gradle :app:testDebugUnitTest --rerun` **99 / 99 PASSED across 14 Suites**.  
**Legacy Pharmacy Note:** Previously named legacy files (`PharmacyViewModel.kt`, `PharmacyDatabase.kt`, `LoginScreen.kt`, etc.) were already refactored into `AssistantViewModel.kt`, `AssistantDatabase.kt` (`nahid_personal_ai.db` v19), and `ProfileManagementComponent.kt` in the prior migration milestone.

---

## 1. Executive Audit Summary by Status

### WORKING (Real & Verified in JVM / Robolectric)
- **Room Database Migrations (`MIGRATION_16_17`, `MIGRATION_17_18`, `MIGRATION_18_19`)**: Verified non-destructive preservation of AI memories, tasks, notes, chats, skills, and companion profiles via `AssistantDatabaseMigrationTest` & `JarvisRobolectricEndToEndTest`.
- **Unified One-Time Approval & Execution-ID Consent (`JarvisPermissionManager`, `ActionApprovalRegistry`, `ConsentManager`)**: Single approval registry, SHA-256 parameter/message binding, single-use `PENDING -> APPROVED -> DISPATCHED -> CONSUMED`, WhatsApp != `SEND_SMS` separation verified in `JarvisPermissionAndApprovalTest`.
- **5-Personality Emotional Companion & Context Engines (`JarvisPersonalityManager`, `AdaptivePersonalityEngine`, `EmotionContextEngine`, `LiveConversationContextBridge`)**: Race condition previously fixed; offline queuing + flush verified (`C01`–`C08`).
- **Full-Duplex Interruption Engine & Commit Boundary (`ActiveTaskInterruptionManager`, `LiveInterruptionCoordinator`, `ExecutionCommitController`)**: Unit tested in `JarvisV9MasterOsUnitTest`.
- **Global Emergency Stop Coordinator (`JarvisEmergencyStopCoordinator`)**: Stops active jobs, clears pending/approved action tokens, halts camera/recorder/wake services.
- **GitHub/YouTube Skill Learning & Parameterized Execution**: Real HTTP parsers + offline test stubs verified (`JarvisV9MasterOsUnitTest`, `JarvisPhase2LearningAndNotificationUnitTest`, `JarvisSkillRecorderUnitTest`).
- **Local Backup Vault (`FirebaseBackupRepository`)**: Writes and verifies JSON snapshots in `filesDir/nahid_ai_vault/nahid_ai_backup.json` (no legacy RTDB dependency).

### INCOMPLETE (Works Partially / Needs Hardening or Config)
- **Gemini Live Voice Protocol (`GeminiLiveSessionManager.kt`)**: Uses legacy `v1alpha` WebSocket endpoint with default `gemini-3.1-flash-live-preview`; lacks `inputAudioTranscription` / `outputAudioTranscription` in setup handshake and server transcript parsing; lacks ephemeral token (`BidiGenerateContentConstrained?access_token=...`) / proxy auth support; lacks automatic backoff reconnection.
- **Provider & Tool Parameter Typing (`ToolRegistry.kt`)**: Currently derives types heuristically by string-prefix matching in `parseParamSchema`; lacks structured typed schema metadata (`enumValues`, bounds, validation errors) inside `ToolSpec`.
- **Voice/Audio Visual Feedback (`AssistantViewModel.kt:833-835`, `MainActivity.kt:188`)**: Mic/output RMS thresholds set to `> 5f` against `0f..1f` normalized float levels (never triggers); `FloatingJarvisOrb` checks only `LiveSessionState.CONNECTED`, ignoring `LISTENING`/`THINKING`/`SPEAKING`/`TOOL_EXECUTING`.
- **Autonomous Loop Verifier (`JarvisAutonomousLoopOrchestrator.kt:378-382`)**: Treats `finalScreen.allElements.isNotEmpty()` as verification and marks `REPLAN` as `success = true` trivially.
- **Local Backup Vault (`FirebaseBackupRepository.kt`)**: Stored in app-private storage as plaintext JSON rather than AES-256-GCM Keystore-encrypted payload; Room `exportSchema = false` (needs `exportSchema = true` and checked-in schema JSONs).
- **CameraX & MediaProjection Privacy Controls (`CameraScreen.kt`, `JarvisScreenCaptureService.kt`)**: Foreground camera indicator exists, but MediaProjection lacks explicit notification pause/resume/stop PendingIntent actions and password/OTP screen redaction guard before uploading frames.
- **Dependency Surface (`app/build.gradle.kts`)**: Includes unused Firebase Auth/Firestore/AI/Credential libraries and Retrofit deps despite local-vault + OkHttp runtime implementation.

### BROKEN (Verified Bugs / False Positives / Unsafe Defaults)
1. **Fail-Open Permission Bypass (`ToolExecutionPipeline.kt:260`)**: When `context == null`, permission enforcement returns `null` (allowed) instead of failing closed for permission-gated tools.
2. **Dummy Seeded Family Contacts & Fake Init Success (`AssistantDatabase.kt:270-314`)**: `onCreate` inserts placeholder contacts (`01711000000`, `01712000000`, `999`) and pre-inserts a fake `"SUCCESS"` `JarvisTaskHistoryEntity`.
3. **Auto-Starting Microphone on App Launch (`MainActivity.kt:67-69`)**: Starts `offlineKeywordSpotter.startListening()` unconditionally via `LaunchedEffect(Unit)` without explicit user opt-in (`wake_word_opt_in`).
4. **Plaintext Secrets & Auth (`AssistantViewModel.kt:37-64`, `AiSettingsRepository.kt:43,88,147`)**: Stores local password & Gemini API key in plaintext `SharedPreferences`; `_isLoggedIn` always defaults to `true` even when user sets a local lock PIN!
5. **Async Accessibility Gestures Returning Immediate `true` (`JarvisAccessibilityService.kt`)**:
   - `clickElementByText()` (line 236) & `clickElementById()` (line 343) dispatch `clickCoordinates(...)` async and return `true` immediately before gesture outcome is known.
   - `longPressElementByText()` (line 289) returns `true` immediately after async `longPressCoordinates`.
   - `scrollHorizontal()` (line 395) calls async `swipeDirection()` and returns `true` immediately.
   - `clearInputAndType()` (line 312) does not verify updated node text after `ACTION_SET_TEXT`.
6. **Tool Result Contract Gaps (`AssistantToolExecutor.kt`, `ToolResultVerifier.kt`)**:
   - `getDeviceInfo` & several read/status branches omit `"success"` key;
   - `ToolResultVerifier.kt` KDoc claims `153 tools` (actual count in `ToolRegistry`: **135**);
   - Several mutating operations need stricter post-condition verification (`brightness` read-back exists, but volume, contact search, screen click/type need stricter `VERIFIED`/`UNVERIFIED` alignment).
7. **Misleading Offline Keyword Spotter (`JarvisOfflineKeywordSpotter.kt`, `JarvisWakeWordService.kt`)**: `JarvisWakeWordService` omits `EXTRA_PREFER_OFFLINE = true` and treats `SpeechRecognizer` as guaranteed offline wake-word engine even on devices lacking an offline speech pack.

### MISSING (Requested Capabilities Not Yet Implemented)
- Android Keystore AES-256-GCM encryption wrapper (`SecureKeystoreVault`) for local API key, local PIN hash/secret, and vault backups.
- Optional Local PIN Lock Screen / Biometric unlock gate when user enables a custom lock PIN in Profile.
- Configurable Gemini Live Model (`gemini-3.8-live` + `gemini-3.1-flash-live-preview`) with `v1beta` `BidiGenerateContent`, ephemeral token / proxy backend mode, realtime input/output transcripts, and automatic exponential-backoff reconnect.
- Explicit user opt-in toggle + configurable wake words + sensitivity + notification Stop action for `JarvisWakeWordService`.
- Room exported schema files (`app/schemas/`) for migration verification.

---

## 2. Phase Execution Roadmap (Phases 0 -> 13)

- **Phase 0**: Audit & 4 Authoritative Docs (`COMPLETED IN THIS TURN`)
- **Phase 1**: Critical Security & Bug Fixes (`SecureKeystoreVault`, PIN lock enforcement, `AiSettingsRepository` encryption + proxy/ephemeral token model, `ToolExecutionPipeline` fail-closed fix, remove dummy contacts & fake init task in `AssistantDatabase`, stop auto-mic in `MainActivity`, fix `FloatingJarvisOrb` & RMS threshold)
- **Phase 2**: Gemini Live Voice Upgrade (`v1beta`, `gemini-3.8-live` option, `inputAudioTranscription`/`outputAudioTranscription`, auto-reconnect, live transcript UI feed)
- **Phase 3 & 4**: Typed `ToolSpec` Parameter Schema + Complete Tool Verification Audit (`135/135` tools consistent `success`/`verified`/`REQUEST_ACCEPTED`/`UNSUPPORTED`)
- **Phase 5, 6, 7**: Camera Privacy & Frame Guard, Screen Capture Notification Controls & Sensitive Screen Filter, Async Accessibility Result Verification (`suspendCoroutine` / callback bridge + post-action verification)
- **Phase 8, 9, 10**: Honest Wake-Word Opt-In & Notification Controls, Multi-Agent Shared Context & Autonomous Loop Verifier Hardening, Room `exportSchema = true`
- **Phase 11, 12, 13**: Expanded Unit + Robolectric Test Suite across all subsystems, Dependency Audit & ProGuard Verification, Final Clean Build (`compile_applet` + `gradle :app:testDebugUnitTest`).
