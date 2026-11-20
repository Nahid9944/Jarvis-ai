# NAHID AI — Screen Vision & Dynamic Action Bridge Report (REQ 2)

## 1. Screen Perception & Hashing (`ScreenPerceptionEngine.kt`)
- Combines Android Accessibility UI tree inspection (`NodeSnapshot`) with MediaProjection/Accessibility bitmap capture.
- Added `computeScreenHash(screen)`: deterministic SHA-256 digest over foreground package, title/activity, and up to 60 visible semantic UI elements so state changes before/after an action are verifiable without fragile heuristics.

## 2. Structured Gemini Vision Parsing & Execution Bridge (`ScreenVisualAnalyzer.kt`)
- `ScreenActionSuggestion` carries rich structured metadata:
  - `actionType` (`click`, `type`, `scroll`, `open_app`, `system`), `title`, `description`
  - Semantic identifiers: `targetTextOrId`, `viewId`, `elementType`, `clickable`, `inputPayload`
  - Spatial geometry: pixel bounds (`boundsLeft`, `boundsTop`, `boundsRight`, `boundsBottom`), normalized coordinates (`centerNormX`, `centerNormY`), `confidence`, `screenshotTimestamp`, `screenshotWidth`, `screenshotHeight`.
- `parseGeminiResponse(responseJsonStr, frameWidth, frameHeight)` extracts markdown-stripped JSON payloads from Gemini Vision responses cleanly.
- `executeVisualSuggestion(context, suggestion, allowCoordinateFallback = true, maxFrameAgeMs = 15_000L)`:
  1. Rejects stale frames (`> 15_000 ms` age -> `STALE_FRAME`).
  2. Rejects unclickable decorative elements when no semantic target exists (`NON_CLICKABLE_ELEMENT`).
  3. Verifies orientation consistency between capture frame dimensions and live `DisplayMetrics` (`ORIENTATION_MISMATCH`).
  4. Executes semantic lookup first (`viewId`, then text/`contentDescription`), falling back to scaled normalized `(centerNormX * width, centerNormY * height)` only when `confidence >= 0.45f`.
  5. Recomputes `computeScreenHash` after 350 ms delay and sets `verified = true` only on an actual UI hash or foreground package delta (`VERIFIED_UI_CHANGED`).
- Exposed via `AssistantToolExecutor.kt` both inside `captureAndAnalyzeScreen` (`suggestions` array) and as standalone tool `executeVisualSuggestion`.

## 3. Dynamic Autonomous Replanning (`JarvisAutonomousLoopOrchestrator.kt`)
- Tracks `initialHash`, `lastHash`, and `anyScreenDelta` across loop iterations.
- Supports `vision_click` and `visual_suggestion` steps via `ScreenVisualAnalyzer.executeVisualSuggestion`.
- Multi-strategy `REPLAN`: fuzzy text lookup, structured visual suggestion retry, then bounded `scroll_down` + retry; refuses `verified = true` unless real UI change occurred.

## 4. Verification Status
- Gemini JSON parsing, freshness gating, clickability rejection, orientation checks, and missing-Accessibility failure paths: **Verified via Robolectric JVM tests (`FiveRequirementsAndBugRepairTest`)**.
- Live MediaProjection frame capture & Gemini Vision API roundtrip on device: **`DEVICE TEST PENDING`**.
