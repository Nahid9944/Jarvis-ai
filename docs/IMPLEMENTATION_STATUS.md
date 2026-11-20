# NAHID AI (মায়া / Maya & JARVIS OS) — Implementation Truth & Status Matrix

This document records the **exact, honest implementation status** of NAHID AI across all core layers and capabilities as verified in code and automated tests. No capability is marked ready without backing implementation.

---

## 1. Core Architecture & Control Plane

| Layer / Module | Key Files | Verified Status |
|---|---|---|
| **Master Orchestrator (Control Loop)** | `JarvisMasterOrchestrator.kt`, `UniversalIntentRouter.kt` | **IMPLEMENTED** — Unifies voice, text, camera, notification, and autonomous intents through intent routing -> permission gating -> risk/confirmation -> tool execution -> post-action verification. |
| **Security, Policy & Risk Engine** | `ActionRiskPolicyEngine.kt`, `PendingActionConfirmationStore.kt`, `PromptInjectionSanitizer.kt` | **IMPLEMENTED** — Enforces risk levels (`LOW`–`CRITICAL`), single-shot human confirmation for sensitive actions, emergency stop (`JarvisEmergencyStopCoordinator`), and untrusted external content boundaries. |
| **Universal Permission Broker** | `JarvisPermissionManager.kt`, `PermissionOrchestrator.kt`, `SmartPermissionPopup.kt` | **IMPLEMENTED** — Audits Android runtime/special permissions (`RECORD_AUDIO`, `CAMERA`, `ACCESS_FINE_LOCATION`, `CALL_PHONE`, `AccessibilityService`, `NotificationListener`, `Overlay`, `WRITE_SETTINGS`) and auto-resumes pending tasks after user grant. |
| **Tool Registry & Verification** | `ToolRegistry.kt` (**152 tools**), `AssistantToolExecutor.kt`, `ToolResultVerifier.kt` | **IMPLEMENTED** — Zero orphan or `"Unknown tool"` routes; explicit `success`, `summaryBengali`, and post-action verification status (`PERMISSION_REQUIRED`, `TRANSCRIPT_REQUIRED`, `UNSUPPORTED`, `LOCATION_UNRESOLVED`, etc.). |
| **Encrypted Secrets & Room DB** | `AiSettingsRepository.kt`, `AssistantDatabase.kt` (v21) | **IMPLEMENTED** — Stores user-provided API keys in `EncryptedSharedPreferences` / Android Keystore backed storage; local memories, tasks, notes, skills, HOME DEVELOPER checkpoints/patches/gaps/evidence, and audit trails in Room DB v21. |

---

## 2. Voice, Wake Word & Gemini Live Multimodal

| Capability | Key Files | Verified Status & Platform Truth |
|---|---|---|
| **Gemini Live Audio-to-Audio Streaming** | `GeminiLiveVoiceClient.kt`, `AudioInputStreamer.kt`, `MicrophoneCoordinator.kt` | **CONFIG_DEPENDENT** — Real WebSocket PCM16 streaming (16kHz in / 24kHz out), barge-in interruption, and single-owner mic arbitration (`WAKE_WORD_SERVICE` vs `GEMINI_LIVE_AUDIO` vs `ON_DEVICE_VOICE`). Requires active user Gemini API key in Settings / Secrets. |
| **Foreground Wake-Word Service** | `JarvisWakeWordService.kt`, `OfflineWakeWordEngine.kt`, `WakeWordModelManager.kt` | **ON_DEVICE_OR_HYBRID** — Runs foreground `microphone` service (`Android 14+` typed), uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` on Android 13+ when supported by the OEM/Google ASR pack, falls back to standard `SpeechRecognizer` with Bengali/Banglish/English matching. *Truth note: No proprietary `.tflite` / `.ppn` keyword neural binary is bundled in APK assets; `WakeWordModelManager.inspectCapabilities()` reports this honestly.* |
| **Live Camera & Screen Understanding** | `JarvisCameraManager.kt`, `JarvisCameraVisionCoordinator.kt`, `JarvisScreenCaptureService.kt` | **PERMISSION_DEPENDENT** — Front/Back CameraX + MediaProjection Foreground Service with `STOP` emergency controls and wired `LIVE CAMERA`, `FRONT`, `SHARE SCREEN` HUD shortcuts. Requires user runtime consent per session. |

---

## 3. Autonomous Phone Control & Device Tools

| Capability | Key Files | Verified Status & Platform Truth |
|---|---|---|
| **App Discovery & Multilingual Launch** | `JarvisDeviceAutomationHelper.kt`, `ToolRegistry.kt` (`openApp`, `listInstalledApps`) | **IMPLEMENTED** — Queries `PackageManager` launcher intents & maps Bengali/Banglish aliases (`বিকাশ`, `নগদ`, `ইমো`, `স্পটিফাই`, `হোয়াটসঅ্যাপ`, etc.). Verified with `<queries>` visibility in Manifest. |
| **Accessibility UI Automation** | `JarvisAccessibilityService.kt`, `AutomationToolHandler.kt`, `ActionVerifier.kt` | **PERMISSION_DEPENDENT** — Performs semantic node clicks, scrolling, typing, YouTube search/play verification (`MediaSessionManager` playback state check), and WhatsApp tick classification (`SENT`/`DELIVERED`/`READ`). Requires user enabling Accessibility Service in Android Settings. |
| **Notification Intelligence & Reply** | `JarvisNotificationListenerService.kt`, `JarvisNotificationRepository.kt` | **PERMISSION_DEPENDENT** — Captures active notifications when granted, classifies OTP/urgent notifications, provides Bengali smart replies, and executes `RemoteInput` direct replies without claiming reply delivery unless `RemoteInput` fires. |
| **Hardware & Settings** | `JarvisDeviceAutomationHelper.kt` | **IMPLEMENTED** — Real `CameraManager` torch, `AudioManager` stream volume with read-back verification, `Settings.System` brightness (requires `WRITE_SETTINGS`), `ACTION_DIAL` / `ACTION_CALL` (requires `CALL_PHONE`). |

---

## 4. Learning Engines, Deep Research & Bangladesh Utilities

| Capability | Key Files | Verified Status |
|---|---|---|
| **YouTube Tutorial & Multimodal Learning** | `YouTubeTutorialImporter.kt` | **IMPLEMENTED** — Parses oEmbed + timed `<text start="...">` XML captions with `[MM:SS]` labels, analyzes videos via Gemini multimodal (`file_data.file_uri`) when API key is set, supports `DRAFT` validation (`validateImportedSkillDraft`), provides `vnd.youtube:<id>` playback intent, and enforces `TRANSCRIPT_REQUIRED` / `UNSUPPORTED` truthfulness gate. |
| **GitHub Full Project Intelligence** | `GitHubRepositoryLearningEngine.kt` | **IMPLEMENTED** — Inspects metadata, recursive file tree, `README`, `LICENSE`, `AndroidManifest.xml`, `build.gradle*`, `package.json`, and up to 5 source files; blocks Dynamic Code Loading (`DexClassLoader`) per Google Play policy; compares capabilities against all 152 `ToolRegistry` tools; generates integration proposals and declarative skill imports. |
| **Text-Based Learning Workspace** | `TextLearningEngine.kt`, `JarvisToolsTab.kt` | **IMPLEMENTED** — Classifies natural Bengali/Banglish/English instructions into `MEMORY`, `ROUTINE`, or `EXECUTABLE_SKILL_DRAFT` with Dry-Run preview and Room persistence, accessible both by tool (`learnFromText`) and dedicated UI dialog. |
| **Deep Web Research & Cross-Checking** | `UniversalBrowserAgent.kt`, `WebPageContentExtractor.kt` | **IMPLEMENTED** — Uses `Brave` / `Tavily` / public `Wikipedia + DuckDuckGo + RSS`, fetches & cleans HTML body across top results, marks paywalls/login walls (`BLOCKED_LOGIN_WALL`, `CAPTCHA_REQUIRED`) honestly as snippet-only, wraps external text against prompt injection, and computes `MULTI_SOURCE_CORROBORATED` / `SINGLE_SOURCE_EXTRACTED`. |
| **Bangladesh Revised Calendar & Geo Routing** | `BangladeshCalendarAndGeoEngine.kt`, `JarvisLiveLocationTracker.kt` | **IMPLEMENTED** — Implements official **Bangla Academy 2019** calendar rules (Pohela Boishakh = 14 Apr, months 1–6 = 31 days, Falgun = 29 / 30 leap days), `Asia/Dhaka` clock, Haversine distance + driving/walking ETA, and Google Maps route intents. |

---

## 5. Google Play & Security Compliance

- **No Dynamic Code Loading**: No runtime `.dex`/`.jar`/`.so` loading; GitHub DCL repositories are explicitly blocked (`SECURITY_POLICY_BLOCKED`).
- **Media & Contacts Minimal Permissions**: Photo selection uses zero-permission system Photo Picker (`PickVisualMedia`); runtime hardware permissions are requested via Compose `ActivityResultContracts`.
- **Foreground Services Typed Explicitly**: `microphone`, `camera`, `mediaProjection`, `location`, and `dataSync` declared on respective services in `AndroidManifest.xml`.

---

## 6. HOME DEVELOPER Agent (Autonomous Skill Factory & Developer Center)

| Capability | Key Files | Verified Status & Platform Truth |
|---|---|---|
| **13th Specialist Agent & Orchestration** | `AiAgentModel.kt` (`HOME_DEVELOPER`), `HomeDeveloperOrchestrator.kt`, `JarvisMasterOrchestrator.kt`, `VoiceCommandUnderstandingEngine.kt` | **IMPLEMENTED** — 13 specialist agents (`ALL_AGENTS`), Autonomy Levels `L1`–`L4`, cooperative checkpoint pause/resume persisted in `dev_tasks`, cancellation quarantine, emergency stop integration, and Bengali/Banglish voice status/control commands. Reports real `0%` progress when idle. |
| **Capability Intelligence Graph & 4-Way Routing** | `CapabilityIntelligenceGraph.kt` | **IMPLEMENTED** — Computes live health & tool coverage from `AiAgentRegistry`, `ToolRegistry`, `PlaybookStore`, `JarvisSkillRepository`, and `PermissionRegistry`; persists `CapabilityGapEntity`; routes requests 4-way (`EXISTING_SKILL_AVAILABLE`, `EXISTING_TOOLS_CAN_PERFORM`, `NEW_SKILL_CAN_BE_CREATED`, `NATIVE_CAPABILITY_OR_CODE_REQUIRED`). |
| **Universal Skill Factory** | `UniversalSkillFactory.kt` | **IMPLEMENTED** — Unifies YouTube, GitHub (`ALREADY_AVAILABLE` / `IMPROVEMENT_REQUIRED` / `MISSING_CAPABILITY` + proposals), Text, Document, Screenshot/OCR, Composite multi-skill combination, and `SkillEvidenceEntity` failure/review tracking. |
| **Multi-Language Patch & Web Workspace** | `DeveloperWorkspaceEngine.kt` | **IMPLEMENTED** — Supports 17 languages + standalone `WEB_APP_WORKSPACE` HTML/CSS/JS bundles, unit test stub generation, security/Play policy blocking (DCL & hardcoded API key rejection), and `previousSnapshot` rollback. *Truth note: Native Kotlin/Java/Manifest patches are explicitly marked `NOT_COMPILED_REQUIRES_BUILD` (require Gradle Build + APK Install).* |
| **Constraint-Aware Scheduler & Developer Center UI** | `HomeDeveloperScheduler.kt` (`JobScheduler`), `HomeDeveloperCenterScreen.kt`, `AiAssistantScreen.kt` | **IMPLEMENTED** — `HomeDevBackgroundJobService` enforces `NETWORK_TYPE_UNMETERED` (`Wi-Fi`), battery, charging, idle, and storage constraints; live status indicator dot on HUD dock (`HOME DEV`) + full 8-section Developer Center screen. |
