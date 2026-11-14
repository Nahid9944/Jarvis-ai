# NAHID AI — Security Audit & Risk Report (Phase 0)

**Scope:** Authentication, Secret Storage, API Key Exposure, Permission Enforcement, Microphone/Camera/Screen Capture Privacy, Accessibility Guards, Prompt/Tool Injection, Logging & Data Persistence.

---

## 1. Security Vulnerabilities Found in Source Code

| ID | Severity | Location | Finding | Planned Remediation (Phase) |
|---|---|---|---|---|
| **SEC-01** | **CRITICAL** | `AiSettingsRepository.kt:43,88,147` | Gemini API key stored in plaintext `SharedPreferences("nahid_ai_settings")` and sent directly in URL `?key=$cleanKey`. | Encrypt key at rest using Android Keystore AES-256-GCM (`SecureKeystoreVault`); add Ephemeral Token / Authenticated Backend Proxy mode (`BidiGenerateContentConstrained?access_token=...` + header auth) and keep BYOK explicitly labeled as developer/prototype mode (Phase 1 & 2). |
| **SEC-02** | **CRITICAL** | `AssistantViewModel.kt:37-64` | Owner credentials (`saved_password`) stored in plaintext `SharedPreferences("nahid_auth_prefs")`; `_isLoggedIn` hard-initialized to `true` bypassing any configured PIN lock. | Store local PIN via PBKDF2/SHA-256 salted hash + Keystore AES-GCM secret; auto-unlock ONLY when user has left lock PIN empty; when lock PIN or biometric lock is enabled, gate `MainActivity` behind Unlock Screen / `BiometricPrompt` (Phase 1). |
| **SEC-03** | **CRITICAL** | `ToolExecutionPipeline.kt:260` | When `context == null`, permission gate returns `null` (allowed), allowing background/unit or detached callers to bypass `CALL_PHONE`, `SEND_SMS`, `CAMERA`, `RECORD_AUDIO`, `ACCESSIBILITY`, and `NOTIFICATION_LISTENER` checks. | Fail closed whenever a tool has `requiredPermissionId != null` or non-empty `PermissionRegistry` requirements and `context == null` (unless an explicit test override flag is provided only for unit tests that mock downstream dispatch) (Phase 1). |
| **SEC-04** | **HIGH** | `MainActivity.kt:67-69` | `LaunchedEffect(Unit)` calls `viewModel.startOfflineKeywordSpotting()` immediately on startup, opening microphone recognition without explicit user opt-in. | Require persisted `wake_word_opt_in = true` user toggle + `RECORD_AUDIO` grant before starting `JarvisOfflineKeywordSpotter` or `JarvisWakeWordService` (Phase 1 & 8). |
| **SEC-05** | **HIGH** | `AssistantDatabase.kt:270-314` | `onCreate` seeds fake family contacts (`01711000000`, `01712000000`, `999`) that can pollute real contact/emergency call resolution, plus a fake `"SUCCESS"` init task log. | Remove all dummy contacts and pre-seeded `"SUCCESS"` init history rows from `AssistantDatabase.onCreate` (Phase 1). |
| **SEC-06** | **MEDIUM** | `FirebaseBackupRepository.kt:165` | Local vault backup `nahid_ai_backup.json` is written as unencrypted plain JSON inside `filesDir`. | Encrypt vault contents with `SecureKeystoreVault` AES-256-GCM envelope with backward-compatible read migration for any existing plaintext backup (Phase 10). |
| **SEC-07** | **MEDIUM** | `ScreenPerceptionEngine.kt`, `JarvisScreenCaptureService.kt` | MediaProjection captures full framebuffer without detecting sensitive windows (password managers, bank apps, `FLAG_SECURE` / OTP screens) and lacks quick notification pause/stop actions. | Add package/text sensitivity guard before sending screenshots to cloud Vision + add Pause/Resume/Stop action buttons on MediaProjection foreground notification (Phase 6). |
| **SEC-08** | **MEDIUM** | `JarvisDeviceAutomationHelper.kt`, `AssistantToolExecutor.kt` | Deep links (`openDeepLink`) & intent actions need explicit scheme validation (`javascript:`, `file://` rejection) and sensitive UI click verification before claiming completion. | Validate allowed URI schemes (`https`, `http`, `tel`, `mailto`, WhatsApp/Maps schemes) and route async accessibility gestures through awaited callbacks (Phase 4 & 7). |

---

## 2. Verified Security Controls Already Working

- **Zero Legacy Pharmacy Backdoors**: No `994400`, `taki`, `2020`, `BlockedUserScreen`, or `jannai-c78c5` Firebase RTDB code exists in the codebase.
- **No `TextToSpeech` or Duplicate `AudioRecord` Streams**: Speech synthesis runs solely through `GeminiLiveSessionManager` -> `AudioOutputPlayer`.
- **Notification & Voice OTP Redaction (`JarvisNotificationRepository`, `NotificationCenterScreen`)**: Detects 4–8 digit OTP codes, suppresses raw digit readouts (`[গোপন-কোড]`), and queues sensitive announcements during active calls.
- **SHA-256 Action Approval Binding (`ActionApprovalRegistry`, `ConsentManager`)**: Sensitive external actions (`makePhoneCall`, `sendSmsMessage`, `sendWhatsAppMessage`, deletions) require exact target + message hash match and single-use consumption (`PENDING -> APPROVED -> DISPATCHED -> CONSUMED`).
- **Firestore Zero-Trust Rules (`firestore.rules`)**: Strict `request.auth != null && request.auth.uid == userId` owner-only access with field & timestamp checks.
- **Play Policy Media Selection (`ProfileManagementComponent.kt`)**: Uses Android Photo Picker (`PickVisualMedia`), not broad storage permissions.

---

## 3. Production API Key Warning

Direct client-side `?key=` calls from an Android APK are intended **only for personal prototype / local owner BYOK use**. Any production distribution to third-party users must route Gemini REST/Live calls through an authenticated backend proxy issuing short-lived ephemeral tokens (`BidiGenerateContentConstrained?access_token=...`) verified with Firebase Auth + App Check. NAHID AI v9.1 will support both encrypted local BYOK and authenticated proxy/ephemeral token configuration cleanly.
