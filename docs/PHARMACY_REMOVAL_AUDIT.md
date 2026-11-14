# NAHID AI — Pharmacy Removal & Personal AI OS Audit (Phase 0)

## 1. Inventory of Legacy Janani Pharmacy Artifacts Found

### A. Branding, Theme, & Preferences
- `metadata.json`, `settings.gradle.kts`, `app/src/main/res/values/strings.xml` — update app name cleanly to `NAHID AI` (keep `applicationId = "com.jannani.pharmacy.torwmx"` and `namespace = "com.example"` unchanged per Android project integrity rules).
- `app/src/main/java/com/example/ui/theme/Theme.kt` — `JananiPharmacyTheme` -> rename to `NahidAiTheme`.
- `app/src/main/java/com/example/ui/theme/Color.kt` — `Medical*`, `Pharmacy*`, `GradientTealEmerald`, `CardTint*`, `SoftGold*`, `ExpiredRed`/`NearExpiryOrange` unused pharmacy color tokens.
- `app/src/main/java/com/example/ai/AiSettingsRepository.kt` — `SharedPreferences("janani_ai_settings")` -> migrate automatically to `"nahid_ai_settings"` preserving any saved API key/model/voice.
- `app/src/main/java/com/example/service/LiveLocationService.kt` — `CHANNEL_ID = "janani_live_location_channel"` -> `"nahid_live_location_channel"`.
- `app/src/main/java/com/example/service/JarvisBackgroundService.kt` — KDoc pharmacy wording.
- `app/src/main/java/com/example/ai/JarvisCinematicConfig.kt` — `setMedicineDoseAlarm` prompt mention -> `setExactScheduleAlarm`.

### B. Legacy Firebase RTDB & Firestore Schemas
- `app/src/main/java/com/example/data/repository/FirebaseBackupRepository.kt` — hardcoded `https://jannai-c78c5-default-rtdb.asia-southeast1.firebasedatabase.app` -> disconnect completely and convert to verified local JSON backup vault (`context.filesDir/nahid_ai_vault/`).
- `app/src/main/java/com/example/data/repository/UserManagementRepository.kt` — hardcoded `https://jannai-c78c5-default-rtdb.asia-southeast1.firebasedatabase.app` endpoints, polling loop, `janani_user_mgmt` prefs, `pharmacyName`/`pharmacyLocation` -> disconnect completely and convert to local single-owner device/session telemetry backed by Room.
- `firebase-blueprint.json`, `firestore.rules`, `firestore.test.js` — legacy `products`, `customer_dues`, `companies`, `daily_accounts`, `order_items`, `surgical_rates`, `pharmacyName` -> replace with clean Personal AI collections (`users`, `memories`, `tasks`, `notes`, `character_profiles`).

### C. Pharmacy Tools, Confirmation Rules & Unused Screens
- `ToolRegistry.kt`: `setMedicineDoseAlarm` -> remove medicine dose tool (retain `setExactScheduleAlarm`).
- `PermissionRegistry.kt`: `scanPrescription`, `recognizeMedicineByCamera`, `sendDosageAlert`, `sendLowStockWarning`, `analyzeGalleryPrescription`, `pickProductImage`, `setMedicineDoseAlarm` -> clean personal AI capabilities & wording.
- `ToolExecutionPipeline.kt` & `JarvisActionState.kt` & `ToolResultVerifier.kt`: delete references to `deleteProduct`, `deleteSale`, `deleteCustomerDue`, `deleteCustomerDueRecord`, `deleteCompany`, `deleteDailyExpense`, `cancelSaleInvoice`, `recordCustomerPayment`, `payCustomerDueDirect`, `payCompanyDirect`, `recordCompanyPayment`.
- `AssistantToolExecutor.kt`: `"setMedicineDoseAlarm"` / `"MEDICINE_DOSE"` / `"মেডিসিন ডোজ অ্যালার্ম"` -> `"setExactScheduleAlarm"` / `"REMINDER"` / `"ব্যক্তিগত অ্যালার্ম"`.
- `PharmacyViewModel.kt`, `Entities.kt`, `PharmacyDatabase.kt`: hardcoded `"994400"` PIN backdoor & `"taki"` password gate -> remove static backdoors; keep optional user-configured local lock PIN only if user explicitly sets one.
- `LoginScreen.kt`, `BlockedUserScreen.kt`, `UserManagementScreen.kt`: unreferenced legacy staff/pharmacy lockout & `"taki"` admin screens -> remove completely.

### D. Room Database & Data Preservation Plan
- Current DB: `PharmacyDatabase` (`"nahid_jarvis_ai_db"`, version 18).
- Target DB: `AssistantDatabase` (`"nahid_personal_ai.db"`, version 19), `AssistantDao`, `AssistantRepository`, `AssistantViewModel`.
- Backup Helper: `DatabaseMigrationHelper` safely copies existing `"nahid_jarvis_ai_db"` / `"pharmacy_db"` (`+ -wal / -shm`) to `context.filesDir/ai_db_backup/<timestamp>/` before opening `"nahid_personal_ai.db"`, copies over preserved SQLite file cleanly when upgrading, executes `MIGRATION_18_19` (drops `pharmacyName`/`pharmacyLocation` from `app_users`, normalizes `"JANANI PHARMACY"` -> `"NAHID AI"`, cleans `"MEDICINE_DOSE"`), and verifies row counts on read-back.

## 2. Preserved Modules (Keep Intact)
- **Frozen Gemini Live Voice Core**: `GeminiLiveSessionManager`, `AudioInputStreamer`, `AudioOutputPlayer`, `AiSettingsRepository` (`gemini-3.1-flash-live-preview`), `GeminiLiveAnnouncementAdapter`.
- **Voice / Turn / Companion Core**: `JarvisPersonalityManager`, `EmotionContextEngine`, `ActiveTaskInterruptionManager`, `VoiceTurnManager`, `PlaybackInterruptionBridge`, `JarvisMasterOrchestrator`, `JarvisOfflineKeywordSpotter`, `JarvisSpeechSynthesizer` (if any live helpers), `ControlCenterScreen`.
- **Permissions / Approvals**: `JarvisPermissionManager`, `ConsentManager`, `ActionApprovalRegistry`, `PermissionRegistry`.
- **Android Bridge & Automation**: `JarvisAccessibilityService`, `JarvisNotificationListenerService`, `JarvisScreenCaptureManager`, `JarvisSkillRecorder`, `GitHubRepositoryLearningEngine`, `YouTubeTutorialImporter`, `JarvisAlarmReceiver`, `JarvisBootReceiver`.
