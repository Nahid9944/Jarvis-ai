# NAHID AI — Unified Source Audit & Gap Matrix

**Audit Scope:** Full inspection of Native Android Kotlin + Jetpack Compose project (`com.example` / `com.jannani.pharmacy.torwmx`) prior to Master JARVIS Central Brain & Intelligent Knowledge Engine upgrade.
**Audit Method:** Direct line-by-line source inspection + `grep` reference tracing across `app/src/main/java`, `app/src/test/java`, and `gradle/libs.versions.toml`.

---

## 1. Executive Summary & Dependency Graph

The existing codebase already has strong foundational components:
- `AssistantDatabase` (Room v21, 25 entities, non-destructive migrations 16→21).
- `ToolRegistry` (155 registered built-in tools) + `ToolExecutionPipeline` + `ToolAuditLogger`.
- `JarvisMasterOrchestrator` + `AiAgentRegistry` (13 agents currently).
- `GeminiLiveSessionManager` + `LiveConversationContextBridge` + `AudioInputStreamer` / `AudioOutputPlayer` + `VoiceTurnManager`.
- `JarvisAutonomousLoopOrchestrator` + `ScreenPerceptionEngine` + `ScreenVisualAnalyzer`.
- `UniversalBrowserAgent` + `WebSearchProvider` (`BraveSearchProvider`, `TavilySearchProvider`, `PublicWikiAndRssProvider`) + `WebPageContentExtractor`.
- `HomeDeveloperOrchestrator` + `HomeDeveloperScheduler` (JobScheduler with battery/Wi-Fi/charging/idle constraints).

---

## 2. Verified Defects & Gaps (Exact Code Audit)

| # | Area | Exact Location | Verified Issue | Severity | Planned Resolution |
|---|------|---------------|----------------|----------|--------------------|
| 1 | Vision -> Autonomous Loop | `JarvisAutonomousLoopOrchestrator.kt` | Never calls `ScreenVisualAnalyzer.captureAndAnalyze` or reads fresh `JarvisMasterOrchestrator.latestVisionAnalysis` during autonomous execution or A11y-lookup failure recovery; only triggers `executeVisualSuggestion` if `planSteps` explicitly sets `action="vision_click"`. | CRITICAL | Wire `ScreenVisualAnalyzer` directly into `JarvisAutonomousLoopOrchestrator` (proactive discovery + vision-assisted `REPLAN` + single-goal vision fallback + post-click `ScreenPerceptionEngine.computeScreenHash` verification + test hook). |
| 2 | Live Voice Setup Blocking | `GeminiLiveSessionManager.kt:438` | `sendSetupMessage()` calls `runBlocking { toolExecutor.getTopMemoriesForContext() }` directly inside OkHttp `WebSocketListener.onOpen`, blocking the WebSocket I/O callback thread. | HIGH | Replace `runBlocking` with non-blocking `scope.launch(Dispatchers.IO)` loading enriched context (`JarvisMemoryRepository.getEnrichedContextForAi`) and sending setup JSON cleanly while keeping `isSetupReady=false` until ack. |
| 3 | Context Bridge Unwired & Packet Loss | `LiveConversationContextBridge.kt:84-90` & `GeminiLiveSessionManager.kt` | `LiveConversationContextBridge.setFlushListener(...)` is never registered anywhere; worse, `flushIfReady()` calls `drainPendingContexts()` (clearing `pendingQueue`) *before* verifying `flushListener != null` or send success, silently dropping packets. | CRITICAL | Check `val listener = flushListener ?: return emptyList()` before draining, only commit removal after successful delivery, and register/unregister WebSocket `clientContent` injector in `GeminiLiveSessionManager`. |
| 4 | 13 Agents (Missing Knowledge Specialist) | `AiAgentModel.kt` & `JarvisMasterOrchestrator.kt` | `AiAgentRegistry.ALL_AGENTS` contains 13 agents; lacks dedicated 14th `INTELLIGENT_AGENT` Knowledge & Deep Research specialist. `MasterExecutionPlan` lacks `controllerAgent`, `primarySpecialistAgent`, `supportingAgents`, dependency graph, and retry/timeout policies. `GeminiLiveSessionManager.buildSetupJson` does not enforce `JARVIS_MASTER > Specialist` authority. | HIGH | Add `INTELLIGENT_AGENT` (14th agent), extend `MasterExecutionPlan` cleanly (backward-compatible), add `AgentSelectionResult`, update `selectSpecialistAgent`, and enforce Master-over-Specialist prompt governance. |
| 5 | `OpenApp` Hijack & Language Gaps | `JarvisNlpCommandParser.kt:703,733` | `parseWhatsAppCommand` and `parseFacebookCommand` blindly return `OpenApp("whatsapp")` / `OpenApp("facebook")` whenever those app names appear anywhere in informational/question utterances (e.g., `"WhatsApp এ রহিমের শেষ মেসেজটার মানে কী?"`). No canonical `StructuredUtterance` / pronoun follow-up resolver. | HIGH | Add `UnifiedBrainLanguageEngine` (`StructuredUtterance`, `CanonicalIntent`, Bengali/Banglish normalization preserving entities/payloads, compound vs single splitter, pronoun/follow-up resolution) + fix `OpenApp` false positives on questions/analysis. |
| 6 | Sequential Search & Uncancellable Calls | `UniversalBrowserAgent.kt:97-222`, `WebSearchProvider.kt`, `WebPageContentExtractor.kt` | Search providers and page extractions execute sequentially; `.execute()` blocks without `suspendCancellableCoroutine` + `call.cancel()`. Missing URL canonicalization (tracking params) and syndicated/content-hash deduplication. | HIGH | Upgrade to bounded parallel coroutines (`Semaphore`), `suspendCancellableCoroutine` with OkHttp `Call.cancel()`, canonical URL + hash dedup, and `ETag`/`Last-Modified` conditional support. |
| 7 | False `VERIFIED_DEEP_RESEARCH` Status | `UniversalBrowserAgent.kt:244` | `if (extractedCount > 0) "VERIFIED_DEEP_RESEARCH"` marks a single extracted page as verified deep research without multi-source claim corroboration. | CRITICAL | Implement `ClaimVerificationEngine` enforcing `VERIFIED` (>=2 independent corroborated domains), `SINGLE_SOURCE_ONLY`, `PARTIALLY_SUPPORTED`, `CONFLICTING`, `UNVERIFIED`, `OUTDATED`, `REJECTED`. |
| 8 | Missing 4-Tier Fast-First Cache & L3 Server | `AssistantDatabase.kt` (v21), new `knowledge/` & `knowledge-server/` | No L1 in-memory LRU, no Room L2 `knowledge_cache` (`MIGRATION_21_22`), no L3 Firebase-free Knowledge Server REST client/server, no progressive live voice delivery or monotonic telemetry tracker. | CRITICAL | Implement `KnowledgeL1Cache` + Room v22 (`MIGRATION_21_22`) + `KnowledgeServerClient` + `/app/knowledge-server` (Node REST + PostgreSQL schema) + `IntelligentKnowledgeRouter` + `AsyncKnowledgeStorageManager` + `KnowledgePerformanceTracker`. |

---

## 3. Capability Truth Matrix (Baseline)

| Subsystem | Status | Honest Assessment |
|-----------|--------|-------------------|
| Existing Room DB (v21, 25 entities) | **Existing** | Operational; migrations 16→21 verified in `AssistantDatabaseMigrationTest`. |
| 155 Built-in Tools & Permission/Approval Gate | **Existing** | Operational via `ToolRegistry` & `ToolExecutionPipeline`. |
| Vision-to-AutonomousLoop Execution | **Partial** | `ScreenVisualAnalyzer.executeVisualSuggestion` exists, but `JarvisAutonomousLoopOrchestrator` does not invoke vision discovery or vision replanning directly. |
| Live Voice & Context Bridge | **Partial (Buggy)** | `runBlocking` in `sendSetupMessage`, `flushListener` never wired, `drainPendingContexts` drops queue when listener absent. |
| Canonical Brain Language / Context Resolver | **Missing** | Needed to prevent `OpenApp` hijack, resolve Banglish/Bengali pronouns/follow-ups, and wire `getEnrichedContextForAi`. |
| 14-Agent Hierarchy (`JARVIS_MASTER` + `INTELLIGENT_AGENT`) | **Partial** | 13 agents exist; missing 14th `INTELLIGENT_AGENT` and explicit Master > Specialist authority contract. |
| Fast-First Knowledge Router (L1/L2/L3/L4) | **Missing** | Needs L1 LRU, Room L2 v22, L3 REST Client, bounded parallel L4 search & claim verification. |
| Independent Knowledge API Server | **Missing -> To Build (NOT_DEPLOYED)** | Server source, PostgreSQL DDL, security guards, and tests to be created in `/app/knowledge-server`; cloud DB host remains unprovisioned in sandbox (`NOT_DEPLOYED`). |
