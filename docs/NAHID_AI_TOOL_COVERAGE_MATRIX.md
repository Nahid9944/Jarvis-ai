# NAHID AI — Tool Coverage & Verification Matrix (Phase 0)

**Registered Tools in `ToolRegistry.kt`:** **135** (Note: `ToolResultVerifier.kt` KDoc erroneously references 153 from prior pharmacy version; must be updated to 135).  
**Executor Mapping (`AssistantToolExecutor.kt`):** **135 / 135 tool names matched in `when` branches (`0` missing)**, **HOWEVER** static audit found contract & verification bugs across individual branches detailed below.

---

## 1. Audited Bugs Across Registered Tools

1. **Missing Explicit `"success"` Flag on Read/Query Branches (`BROKEN / INCOMPLETE`)**:
   - `getDeviceInfo` (no `success` or `status`), `getLiveWeather`, `getLiveNews`, `getNetworkStatus`, `getForegroundApp`, `getNotificationAccessStatus`, `classifyNotification`, `suggestSmartReplies`, `getActiveCallState`, `getIncomingCallDetails`, `identifyIncomingCaller`, `announceIncomingCaller`, `getCallDuration`, `findUiElement`, `listPlaybooks`, `validatePlaybook`, `getAppVersion`, `getPlaybookHealth`, `readVisibleList`, `verifyWhatsAppCallState`, `searchContacts`, `getContactDetails`.
   - Risk: `ToolResultVerifier` falls back to heuristic key presence (`has("summaryBengali")` etc.), and callers reading `result.optBoolean("success")` directly get `false` even when data succeeded!
2. **Weather API Network Failure Fallback (`WeatherService.kt` vs `getLiveWeather`)**:
   - Must verify weather tool explicitly sets `success = false` if live HTTP fetch fails (zero silent stale weather presented as live!).
3. **`performSwipe` / `clickUiElement` / `longPressUiElement` Async Lies (`JarvisAccessibilityService.kt` + `JarvisDeviceAutomationHelper.kt`)**:
   - `clickCoordinates`, `longPressCoordinates`, and `swipeDirection` fire-and-forget async `dispatchGesture`, immediately returning `true`.
4. **Weak Post-Action Verification (`AssistantToolExecutor.kt`, `ToolResultVerifier.kt`)**:
   - `setVolume`: does not read back `audioManager.getStreamVolume(stream)` to confirm actual OS change (e.g., Do Not Disturb can block ring volume changes!).
   - `startCameraVision` / `stopCameraVision`: returns `success = true` immediately after calling `onNavigate(...)` without checking camera readiness/cleanup.
   - `openDeepLink`: accepts arbitrary URIs without validating scheme or verifying `resolveActivity`.
5. **Typed Tool Schema Gaps (`ToolRegistry.kt`)**:
   - `toggleFlashlight`, `toggleCameraTorch`, `setVolume`, `setBrightness`, `muteActiveCall`, `toggleCallSpeaker`, `setCameraZoom`, `getRecentNotifications`, `completeAiTask` currently register some boolean/number params as `"string: ..."` instead of proper JSON Schema types (`boolean`, `integer`, `number`) + `enum` values.

---

## 2. Category-by-Category Matrix (135 Tools)

| Category | Count | Executor Wired | Verification Status | Current Defects / Phase Fix |
|---|---|---|---|---|
| **1. System Actions & Navigation** | 10 | 10 / 10 | **7 WORKING / 3 INCOMPLETE** | `openDeepLink` needs scheme validation; `getDeviceInfo` & `getForegroundApp` & `getDeviceState` need explicit `success`/`verified` fields (Phase 3 & 4). |
| **2. Device & Media Control** | 5 | 5 / 5 | **3 WORKING / 2 INCOMPLETE** | `setVolume` needs post-set OS readback verification; `searchAndPlayYouTube` Accessibility click uses fire-and-forget delay; `toggleFlashlight` boolean typing in schema (Phase 3 & 4). |
| **3. Communication (Call, SMS, WhatsApp)** | 10 | 10 / 10 | **9 WORKING / 1 BROKEN** | `makePhoneCall`, `sendSmsMessage`, `sendWhatsAppMessage` correctly return `REQUEST_ACCEPTED` + await `SmsStatusReceiver`; `startWhatsAppVoiceCall`/`VideoCall` launch async UI click without result verification; `ToolExecutionPipeline` `context == null` bypasses permission check (Phase 1, 4, 7). |
| **4. Screen Perception & Accessibility** | 16 | 16 / 16 | **8 WORKING / 8 BROKEN** | `clickScreen`, `longPressOnScreen`, `clearAndTypeOnScreen`, `scrollToText` rely on non-awaited `dispatchGesture` or unverified `ACTION_SET_TEXT` in `JarvisAccessibilityService`; `captureAndAnalyzeScreen` needs foreground/OTP privacy guard (Phase 4, 6, 7). |
| **5. Playbooks, Workflows & Human Gate** | 9 | 9 / 9 | **8 WORKING / 1 INCOMPLETE** | `JarvisAutonomousLoopOrchestrator` weak UI verification (`allElements.isNotEmpty()`) & trivial `REPLAN` success (Phase 9). |
| **6. AI Long-Term Memory** | 4 | 4 / 4 | **4 WORKING** | Backed by real Room `AssistantDao` queries & delete checks; add duplicate-key update hygiene (Phase 8). |
| **7. Autonomous AI Tasks & Reminders** | 5 | 5 / 5 | **5 WORKING** | Real Room CRUD + exact alarm / `JarvisBootReceiver` rescheduling; verify `completeAiTask` & `deleteAiTask` Room readback (Phase 4). |
| **8. Personal Notepad / Voice Logs** | 5 | 5 / 5 | **5 WORKING** | Real Room CRUD + delete gate verified in E2E suite. |
| **9. Intelligence, GPS, Weather, Web** | 6 | 6 / 6 | **4 WORKING / 2 INCOMPLETE** | `getLiveWeather` / `getLiveNews` / `searchWikipedia` / `searchExternalKnowledge` need explicit `success`/`verified` & offline truth flags (Phase 4). |
| **10. Notification Intelligence** | 11 | 11 / 11 | **9 WORKING / 2 INCOMPLETE** | OTP voice redaction & Room repo work; `getNotificationAccessStatus`, `classifyNotification`, `suggestSmartReplies` omit explicit `success` (Phase 4). |
| **11. Incoming Call & Telephony** | 11 | 11 / 11 | **8 WORKING / 3 INCOMPLETE** | Real `JarvisInCallService` telecom APIs; status read tools omit `success` flag; requires real device telephony verification (Phase 4). |
| **12. Camera Vision & Multimodal** | 10 | 10 / 10 | **7 WORKING / 3 INCOMPLETE** | Real CameraX + ML Kit OCR + Gemini Vision; `startCameraVision` needs honest readiness notice; `ScreenVisualAnalyzer` needs redaction/size guard & proxy auth option (Phase 5). |
| **13. Screen UI Automation Shortcuts** | 7 | 7 / 7 | **2 WORKING / 5 BROKEN** | `clickUiElement`, `longPressUiElement`, `swipeScreen`, `typeIntoFocusedField` suffer from async `dispatchGesture` returning immediate `true` (Phase 7). |
| **14. Social, Messaging & Contacts** | 8 | 8 / 8 | **7 WORKING / 1 INCOMPLETE** | Contact search disambiguation works; `openWhatsAppConversation` & `reactToCurrentPost` depend on async Accessibility fix (Phase 7). |
| **15. Device Telemetry & Settings** | 7 | 7 / 7 | **5 WORKING / 2 INCOMPLETE** | `setBrightness` already reads back `SCREEN_BRIGHTNESS`; `getNetworkStatus`, `getForegroundApp`, `getBatteryHealthInfo` need uniform `success` contract (Phase 4). |
| **16. Routines & Emergency Stop** | 4 | 4 / 4 | **4 WORKING** | `JarvisRoutineEngine` + `JarvisEmergencyStopCoordinator` verified in unit tests. |
| **17. Skill Library & Learning** | 7 | 7 / 7 | **7 WORKING** | Teach Mode, Skill Recording, GitHub & YouTube importers verified in `JarvisSkillRecorderUnitTest`, `JarvisV9MasterOsUnitTest`, `JarvisPhase2LearningAndNotificationUnitTest`. |

**Total Initial Audit Tally:** **95 WORKING / 25 INCOMPLETE / 15 BROKEN (fixing across Phases 1–10)**.
