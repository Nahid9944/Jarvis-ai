# NAHID AI (মায়া / Maya) — Personal Android AI Operating Assistant

**NAHID AI** is a voice-first, multimodal, Bengali/Banglish/English-fluent Personal AI Operating Assistant for Android built with Jetpack Compose, Room, CameraX, Accessibility Services, Notification Intelligence, and Gemini Live Audio-to-Audio streaming.

---

## Core Architecture

- **Personal AI Home HUD (`AiAssistantScreen`)**: Live Arc Reactor & voice waveform visualizer, companion personality chips, real-time OS/vision telemetry, quick-access Personal Hub pills (`Profile`, `Notifications`, `Notes`, `Backup Vault`), and global Emergency Stop.
- **Voice Core (`GeminiLiveSessionManager`, `AudioInputStreamer`, `AudioOutputPlayer`)**: Low-latency 16 kHz PCM capture + 24 kHz PCM playback over `gemini-3.1-flash-live-preview`, with real-time barge-in and offline command fallback.
- **Multi-Agent Orchestrator (`JarvisMasterOrchestrator`)**: Intent classification, multi-step planning, specialized agent delegation (`AiAgentRegistry`), bounded retries, and verification.
- **Tool Pipeline & Approval Security (`ToolRegistry`, `ToolExecutionPipeline`, `JarvisPermissionManager`, `ActionApprovalRegistry`, `ConsentManager`)**: Single source of truth separating OS runtime/special permissions from SHA-256 parameter-bound one-time user confirmations.
- **Emotional Companion (`ControlCenterScreen`, `JarvisPersonalityManager`, `EmotionContextEngine`)**: 5 switchable companion modes, feedback versioning/rollback, and read-only voice RMS emotion cues.
- **Database (`AssistantDatabase` — `nahid_personal_ai.db` v19)**: Room SQLite database with non-destructive `DatabaseMigrationHelper` snapshots in `filesDir/ai_db_backup/` and migrations `16 -> 17 -> 18 -> 19`.
- **Owner Profile & Local Vault (`ProfileScreen`, `FirebaseBackupScreen`)**: Device-local personal owner profile (zero hardcoded backdoors) and verified JSON export/restore vault.

---

## Configuration & Secrets

1. Configure your Gemini API key securely via the **Secrets panel in AI Studio** (or `.env` locally matching `.env.example`, never committed to Git) — or set it directly in the in-app **AI Settings** dialog.
2. See `docs/IMPLEMENTATION_STATUS.md` and `docs/PHARMACY_REMOVAL_AUDIT.md` for full capability matrices, migration details, and verification status.

---

## Build & Verification Commands

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
