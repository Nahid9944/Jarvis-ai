# NAHID AI — Verification & Test Plan (Phase 0)

**Execution Constraints:** Build environment provides JVM + Robolectric (`sdk = [34]`), Room in-memory/temp-file SQLite, and `compile_applet`. No Android Emulator, ADB, physical SIM, Camera hardware, or real Accessibility window tree is attached. All hardware-dependent features must be honestly tested up to their OS boundary using Robolectric/unit mocks and documented clearly as device-verification required for hardware end-to-end.

---

## 1. Existing Passing Baseline (14 Suites, 99 Tests)
1. `AssistantDatabaseMigrationTest` (1) — Legacy DB v16–v18 -> v19 migration preservation
2. `AutomationEngineUnitTest` (8) — Selectors, idempotency, rate limiter, playbooks
3. `ExampleRobolectricTest` (6) — App context, legacy string cleanup, Robolectric boot
4. `ExampleUnitTest` (1) — JVM arithmetic sanity
5. `JarvisEmotionalCompanionTest` (8) — Personality modes, feedback versioning, rollback, priority
6. `JarvisMemoryUnitTest` (7) — Preferences, contacts, snippets, context enrichment
7. `JarvisPermissionAndApprovalTest` (14) — Approval registry, consent scoping, WhatsApp != SMS, fail-open bugs to harden
8. `JarvisPhase2LearningAndNotificationUnitTest` (10) — YouTube/GitHub learning, notification OTP redaction
9. `JarvisRobolectricEndToEndTest` (5) — Full pipeline, SMS carrier telemetry, boot alarm rescheduling
10. `JarvisSkillRecorderUnitTest` (6) — Skill recording, parameters, rollback
11. `JarvisV9MasterOsUnitTest` (9) — Master orchestrator, interruption, emergency stop
12. `PermissionOrchestratorTest` (5) — Single active request queue, postpone, revoke detection
13. `ScreenAndContactPerceptionUnitTest` (10) — Disambiguation, OTP sanitization
14. `ToolRegistryUnitTest` (9) — 135 tool schema generation & registry lookup

---

## 2. Planned New & Expanded Tests Across Phases 1–11

| Phase | Target Component | Planned Test Coverage | Test Environment |
|---|---|---|---|
| **Phase 1** | `SecureKeystoreVault`, `AssistantViewModel` Lock, `AiSettingsRepository`, `ToolExecutionPipeline`, `AssistantDatabase` | 1) Keystore/fallback encryption roundtrip; 2) User-set PIN locks `_isLoggedIn` & wrong PIN fails while blank PIN unlocks; 3) `ToolExecutionPipeline` FAILS CLOSED on `context == null` for protected tools; 4) Clean `AssistantDatabase.onCreate` has 0 dummy contacts & 0 fake `"SUCCESS"` init tasks; 5) Auto-listening disabled by default unless opted in. | JVM + Robolectric |
| **Phase 2** | `GeminiLiveSessionManager`, `AiSettingsRepository` | 1) `v1beta` URL & ephemeral `access_token` URL construction; 2) Setup JSON includes `inputAudioTranscription`, `outputAudioTranscription`, and valid model (`gemini-3.8-live` / `gemini-3.1-flash-live-preview`); 3) Server transcript events parsed without dropping PCM playback; 4) Exponential backoff reconnect schedule on socket drop. | JVM Unit + Mock WebSocket JSON |
| **Phase 3 & 4** | `ToolRegistry`, `AssistantToolExecutor`, `ToolResultVerifier` | 1) All 135 registered tools execute cleanly with explicit `"success"` boolean and valid JSON Schema parameter types; 2) `setVolume`, `getDeviceInfo`, `openDeepLink` safety (`javascript:` blocked), `getLiveWeather` failure truth; 3) `REQUEST_ACCEPTED` never promoted to `VERIFIED`. | JVM + Robolectric |
| **Phase 5, 6, 7** | `JarvisCameraVisionCoordinator`, `JarvisScreenCaptureManager`, `JarvisAccessibilityService` | 1) Sensitive screen filter blocks banking/password capture telemetry; 2) Camera/Screen pause & emergency stop transitions; 3) `GestureDispatchResult` awaits callback rather than returning immediate `true` on dispatch timeout/failure. | Robolectric |
| **Phase 8, 9, 10** | `JarvisOfflineKeywordSpotter`, `JarvisAutonomousLoopOrchestrator`, `FirebaseBackupRepository` | 1) Wake word configurable triggers (`jarvis`, `hey jarvis`, `জারভিস`) & opt-in enforcement; 2) Weak screen verifier fixed (`REPLAN` failure not fake `true`); 3) Encrypted vault backup write/readback + Room `exportSchema = true` schema validation. | Robolectric |
| **Phase 12 & 13** | Release Readiness & Regression | Full `gradle :app:testDebugUnitTest --rerun` + `compile_applet` clean APK build. | Full JVM/Robolectric & Gradle |
