# NAHID AI (মায়া / Maya) — Implementation Status, Capability Matrix & Dependency Map (v9)

**App Name:** NAHID AI  
**Application ID:** `com.jannani.pharmacy.torwmx` (preserved for Android build & keystore continuity)  
**Compile / Target SDK:** Android 36 (minorApiLevel 1) | **Min SDK:** 24  
**Room Database:** `AssistantDatabase` (`nahid_personal_ai.db`, Version **19**) with `DatabaseMigrationHelper` + non-destructive `MIGRATION_16_17`, `MIGRATION_17_18`, `MIGRATION_18_19`, zero destructive fallback  
**Test Verification:** Clean Build (`compile_applet`) + **99/99 JVM & Robolectric Unit/E2E Tests Passed across 14 Suites** (`gradle :app:testDebugUnitTest`)

---

## 1. Voice Core Preservation Guarantee (Frozen & Verified)

- **Endpoint:** `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent` (`GeminiLiveSessionManager.kt`)
- **Default Live Model:** `gemini-3.1-flash-live-preview` (`AiSettingsRepository.kt`)
- **Audio Input:** 16,000 Hz PCM Mono via `AudioRecord` (`AudioInputStreamer.kt`)
- **Audio Output:** 24,000 Hz PCM Mono via `AudioTrack` (`AudioOutputPlayer.kt`)
- **Android Native TTS:** **ZERO** `android.speech.tts.TextToSpeech` references in source code (`GeminiLiveAnnouncementAdapter` routes all spoken feedback into the Gemini Live session and queues safely offline/in-call). Zero duplicate `AudioRecord` streams (`EmotionContextEngine` consumes read-only energy levels from the active voice mic).

---

## 2. Truthful Capability Matrix

| Subsystem / Capability | Status Classification | Verification Method | Notes / Device Limitations |
|---|---|---|---|
| **Legacy Pharmacy Removal & Branding (`NAHID AI`)** | **REAL (Audited & Verified)** | Source grep audit + `AssistantDatabaseMigrationTest` + `ExampleRobolectricTest` | All pharmacy tools, `Pharmacy*` classes, `LoginScreen`/`BlockedUserScreen`/`UserManagementScreen`/`CloudBackupPasswordDialog`, hardcoded PINs (`994400`/`taki`/`2020`), and old RTDB (`jannai-c78c5`) endpoints removed cleanly |
| **Non-Destructive DB Backup & Migration (`MIGRATION_18_19`)** | **REAL (Robolectric Verified)** | `AssistantDatabaseMigrationTest` | Copies legacy `nahid_jarvis_ai_db` / `pharmacy_db` to `filesDir/ai_db_backup/` before opening `nahid_personal_ai.db`, removes `pharmacyName`/`pharmacyLocation` columns, preserves 100% AI memories, tasks, notes, chats, contacts, skills, and companion profiles |
| **Master Orchestrator (`JarvisMasterOrchestrator`)** | **REAL (Unit + Robolectric Verified)** | `JarvisV9MasterOsUnitTest`, `JarvisPermissionAndApprovalTest` | Multi-step Bengali/English decomposition, specialist routing across 12 agents, Bengali/Banglish confirmation intercept, partial-completion reporting |
| **OS Permission vs. One-Time Approval Separation (`JarvisPermissionManager`, `ActionApprovalRegistry`, `ConsentManager`)** | **REAL (Robolectric Verified)** | `JarvisPermissionAndApprovalTest` (T01–T14) | Single central gatekeeper; zero re-prompt on granted runtime permissions; SHA-256 parameter & message binding; single-use `PENDING -> APPROVED -> DISPATCHED -> CONSUMED`; `executionId` scoped continuation; WhatsApp != `SEND_SMS` isolation; 3-tab Permission Center UI |
| **Zero-Fake-Success Tool Pipeline (134 Tools)** | **REAL (Unit + Robolectric Verified)** | `ToolRegistryUnitTest`, `JarvisRobolectricEndToEndTest`, `JarvisPermissionAndApprovalTest` | Permission pre-check, one-time `ActionApprovalRegistry` gate, atomic `ExecutionCommitController`, `ToolResultVerifier`, `ToolAuditLogger` |
| **5-Personality Emotional Companion & Adaptive Engine (`JarvisPersonalityManager`, `AdaptivePersonalityEngine`, `EmotionContextEngine`)** | **REAL (Unit + Robolectric Verified)** | `JarvisV9MasterOsUnitTest`, `JarvisEmotionalCompanionTest` (C01–C08) | `ROMANTIC`, `BEST_FRIEND`, `EMOTIONAL_COMFORT`, `PROFESSIONAL`, `PLAYFUL` + voice revert (`previousMode`), feedback versioning & rollback, strict 4-tier priority hierarchy, 12-emotion conflict/uncertainty flag, read-only voice RMS observer, privacy toggles, `ControlCenterScreen` UI |
| **Live Conversation Context Bridge (`LiveConversationContextBridge`)** | **REAL (Robolectric Verified)** | `JarvisEmotionalCompanionTest` (C06) | Thread-safe queue, duplicate suppression, offline queuing and auto-flush on WebSocket `CONNECTED` hook |
| **Full-Duplex Interruption & Commit Boundaries** | **REAL (Unit Verified)** | `JarvisV9MasterOsUnitTest` | `PAUSE`, `RESUME`, `CANCEL`, `REPLACE`, `STOP_SPEECH_ONLY`, pre-dispatch block & `TOO_LATE_TO_CANCEL` detection |
| **Room Database Persistence (`MIGRATION_16_17`, `17_18`, `18_19`)** | **REAL (Robolectric Verified)** | `JarvisRobolectricEndToEndTest`, `JarvisEmotionalCompanionTest`, `AssistantDatabaseMigrationTest` | Verified v16->v17, v17->v18, and v18->v19 non-destructive migrations preserving legacy rows and personal AI schemas |
| **Notification Intelligence & OTP Redaction** | **REAL (Unit + Robolectric Verified)** | `JarvisV9MasterOsUnitTest`, `JarvisRobolectricEndToEndTest` | Room persistence, routed dark HUD `NotificationCenterScreen`, OTP isolation, 4–8 digit voice redaction (`[গোপন-কোড]`), in-call deferral & auto-flush |
| **Local Encrypted Backup Vault & Owner Profile** | **REAL (Robolectric Verified)** | `ExampleRobolectricTest`, `FirebaseBackupRepository`, `ProfileManagementComponent` | Private on-device JSON export/restore under `filesDir/nahid_ai_vault/`, zero old pharmacy RTDB traffic, optional user-set local PIN (empty by default), `PickVisualMedia` avatar picker, DB migration telemetry card |
| **SMS Carrier Telemetry (`SmsStatusReceiver`)** | **REAL (Robolectric Verified)** | `JarvisRobolectricEndToEndTest` | PendingIntent `SEND_CONFIRMED` / `SEND_FAILED` / `DELIVERY_CONFIRMED` callbacks verified via Robolectric broadcast |
| **GitHub & YouTube Learning Engines** | **REAL (Unit Verified w/ Offline HTTP Stub)** | `JarvisV9MasterOsUnitTest`, `JarvisPhase2LearningAndNotificationUnitTest` | Real REST API + git tree inspection, 404/429/offline reporting, Swift/iOS rejection; YouTube oEmbed + caption XML parsing enforcing `TRANSCRIPT_REQUIRED` |
| **Teach Mode, Parameterized Skills & Versioning/Rollback** | **REAL (Robolectric Verified)** | `JarvisRobolectricEndToEndTest`, `JarvisV9MasterOsUnitTest` | Natural-language Bengali instruction parsing, `{param}` extraction, v1->v2->rollback, `PARAMETERS_REQUIRED` enforcement |
| **Boot Alarm Rescheduling (`JarvisBootReceiver`)** | **REAL (Robolectric Verified)** | `JarvisRobolectricEndToEndTest` | Reschedules future `AiTaskEntity` alarms on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `TIMEZONE_CHANGED` |
| **Global Emergency Stop Coordinator** | **REAL (Unit + Robolectric Verified)** | `JarvisV9MasterOsUnitTest`, `ScreenAndContactPerceptionUnitTest`, `JarvisPermissionAndApprovalTest` | Halts active task, autonomous loop, pending/approved action tokens, playback/speaker queues, camera vision stream, skill recorder, wake service & spotter |
| **Gemini Live Audio-to-Audio Streaming** | **IMPLEMENTED - DEVICE TEST REQUIRED** | Code & Manifest Verified | Real-device mic/speaker & API key required (no ADB/emulator in build container) |
| **Live CameraX Vision, Accessibility, NotificationListener, SIM Calls/SMS & Wake Word** | **IMPLEMENTED - DEVICE TEST REQUIRED** | Manifest, Service & Coordinator Verified | Hardware sensors, cellular SIM, and Android OS special permissions require testing on physical Android device |

---

## 3. Build Configuration & Navigation Architecture

- **`app/build.gradle.kts`**: Verified `compileSdk = 36` (`minorApiLevel = 1`), `targetSdk = 36`, `minSdk = 24`, Java 11 compatibility, `applicationId = "com.jannani.pharmacy.torwmx"`, and preserved `signingConfigs` (`release` and `debugConfig`).
- **Active Compose Navigation Routes (`MainActivity.kt`)**:
  - `hud` (`AiAssistantScreen` — NAHID AI Live Neural HUD, Quick Hub to Profile/Notifications/Notes/Vault, Agent Monitor, Tool Matrix, Core Settings)
  - `camera` (`CameraScreen` — CameraX & OCR/Vision)
  - `notepad` (`NotepadScreen` — NAHID AI Neural Notes)
  - `permissions` (`PermissionCenterScreen` — 3-tab Smart Permission Center)
  - `control_center` (`ControlCenterScreen` — 5-Personality Character, Emotion & Privacy Hub)
  - `notifications` (`NotificationCenterScreen` — Personal Notification Intelligence & OTP/Task alerts)
  - `firebase_backup` (`FirebaseBackupScreen` — Local Personal Backup Vault)
  - `profile` (`ProfileScreen` / `ProfileManagementComponent` — Owner Profile, Optional Lock PIN & Local DB Telemetry)

---

## 4. Dependency & Schema Audit

1. **Active Runtime Dependencies**: Jetpack Compose Material 3, Icons Extended, Room (`2.7.0` + KSP), OkHttp (`4.10.0`), Coil (`2.7.0`), Moshi (`1.15.2`), CameraX (`1.5.0`), ML Kit Text Recognition (`16.0.1`), Play Services Location (`21.3.0`), Firebase App Check, Coroutines (`1.10.2`), Robolectric (`4.16.1`).
2. **Firebase Blueprint & Rules (`firebase-blueprint.json`, `firestore.rules`, `firestore.test.js`)**: Clean Personal AI schemas (`users`, `memories`, `tasks`, `notes`, `character_profiles`) enforcing strict owner UID access; legacy `jannai-c78c5` RTDB project URL completely disconnected in client code (can be manually removed from Firebase Console if no longer needed).
3. **Removed Legacy Files**: `PharmacyDatabase.kt`, `PharmacyDao.kt`, `PharmacyRepository.kt`, `PharmacyViewModel.kt`, `LoginScreen.kt`, `BlockedUserScreen.kt`, `UserManagementScreen.kt`, `CloudBackupPasswordDialog.kt`.

---

## 5. Acceptance Gates Summary

- **Gate A (Clean APK Build):** PASS — `compile_applet` succeeds with 0 errors.
- **Gate B (Unit & Robolectric Tests):** **PASS — 99 / 99 PASSED across 14 Suites** (`AssistantDatabaseMigrationTest` [1], `AutomationEngineUnitTest` [8], `ExampleRobolectricTest` [6], `ExampleUnitTest` [1], `JarvisEmotionalCompanionTest` [8], `JarvisMemoryUnitTest` [7], `JarvisPermissionAndApprovalTest` [14], `JarvisPhase2LearningAndNotificationUnitTest` [10], `JarvisRobolectricEndToEndTest` [5], `JarvisSkillRecorderUnitTest` [6], `JarvisV9MasterOsUnitTest` [9], `PermissionOrchestratorTest` [5], `ScreenAndContactPerceptionUnitTest` [10], `ToolRegistryUnitTest` [9]).
- **Gate C (Security & Truthfulness):** PASS — Zero `TextToSpeech`, zero static PIN backdoors (`994400`/`taki`/`2020`), zero secret leaks in logs/UI, SHA-256 bound single-use confirmations, OTP digit voice redaction, and honest hardware/permission states.
