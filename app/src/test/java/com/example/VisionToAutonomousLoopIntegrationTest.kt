package com.example

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.JarvisMasterOrchestrator
import com.example.ai.ScreenActionSuggestion
import com.example.ai.ScreenVisualAnalysis
import com.example.automation.JarvisAutonomousLoopOrchestrator
import com.example.service.JarvisAccessibilityService
import com.example.util.JarvisEmergencyStopCoordinator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisionToAutonomousLoopIntegrationTest {

    private lateinit var app: Application
    private var a11yService: JarvisAccessibilityService? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        JarvisAutonomousLoopOrchestrator.resetForTest()
        JarvisMasterOrchestrator.resetForTest()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @After
    fun tearDown() {
        JarvisAccessibilityService.setInstanceForTest(null)
        a11yService = null
        JarvisAutonomousLoopOrchestrator.resetForTest()
        JarvisMasterOrchestrator.resetForTest()
        JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    private fun attachMockAccessibility(rootProvider: () -> AccessibilityNodeInfo?) {
        val svc = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        a11yService = svc
        JarvisAccessibilityService.setInstanceForTest(svc)
        JarvisAccessibilityService.rootWindowOverrideForTest = rootProvider
    }

    private fun buildScreenRoot(
        pkg: String,
        buttonText: String,
        buttonViewId: String,
        onButtonClick: () -> Unit
    ): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = "android.widget.FrameLayout"
        }
        val child = AccessibilityNodeInfo.obtain().apply {
            packageName = pkg
            className = "android.widget.Button"
            text = buttonText
            viewIdResourceName = buttonViewId
            isClickable = true
            isEnabled = true
            setBoundsInScreen(Rect(100, 400, 500, 520))
        }
        shadowOf(child).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                onButtonClick()
                true
            } else {
                false
            }
        }
        shadowOf(root).addChild(child)
        return root
    }

    @Test
    fun selectBestVisualSuggestion_ranksSemanticAndConfidenceMatches() {
        val analysis = ScreenVisualAnalysis(
            summaryBengali = "স্ক্রিন বিশ্লেষণ",
            detectedApp = "com.example.shop",
            screenDescription = "Cart screen",
            suggestions = listOf(
                ScreenActionSuggestion(
                    actionType = "click",
                    title = "Cancel",
                    description = "Cancel cart",
                    targetTextOrId = "Cancel",
                    confidence = 0.85f
                ),
                ScreenActionSuggestion(
                    actionType = "click",
                    title = "Place Order",
                    elementName = "Checkout Button",
                    description = "Submit order",
                    targetTextOrId = "Place Order",
                    viewId = "com.example.shop:id/btn_place_order",
                    confidence = 0.91f
                )
            ),
            rawAiResponse = "{}"
        )

        val picked = JarvisAutonomousLoopOrchestrator.selectBestVisualSuggestion(
            analysis = analysis,
            targetHint = "Place Order",
            expectedAction = "vision_click"
        )
        assertNotNull(picked)
        assertEquals("com.example.shop:id/btn_place_order", picked!!.viewId)
    }

    @Test
    fun autonomousLoop_visionDiscoverStepTriggersAccessibilityClickAndVerifiesUiDelta() = runBlocking {
        var screenClicked = false
        attachMockAccessibility {
            if (!screenClicked) {
                buildScreenRoot(
                    pkg = "com.example.shop",
                    buttonText = "Confirm Pay",
                    buttonViewId = "com.example.shop:id/btn_confirm_pay",
                    onButtonClick = { screenClicked = true }
                )
            } else {
                buildScreenRoot(
                    pkg = "com.example.shop",
                    buttonText = "Order Confirmed Receipt",
                    buttonViewId = "com.example.shop:id/txt_receipt",
                    onButtonClick = {}
                )
            }
        }

        var analyzerCalled = 0
        JarvisAutonomousLoopOrchestrator.visualAnalyzerRunner = { _, query ->
            analyzerCalled++
            ScreenVisualAnalysis(
                summaryBengali = "স্ক্রিনে Confirm Pay বাটন পাওয়া গেছে ($query)।",
                detectedApp = "com.example.shop",
                screenDescription = "Payment confirmation",
                suggestions = listOf(
                    ScreenActionSuggestion(
                        actionType = "click",
                        title = "Confirm Pay",
                        description = "Tap confirm",
                        targetTextOrId = "Confirm Pay",
                        viewId = "com.example.shop:id/btn_confirm_pay",
                        centerNormX = 0.3f,
                        centerNormY = 0.25f,
                        confidence = 0.94f,
                        screenshotTimestamp = System.currentTimeMillis()
                    )
                ),
                rawAiResponse = "{}"
            )
        }

        val plan = JSONArray().put(
            JSONObject()
                .put("action", "vision_discover")
                .put("target", "Confirm Pay")
        )
        val exec = JarvisAutonomousLoopOrchestrator.executeTask(
            context = app,
            goal = "Confirm Pay বাটনে ক্লিক করো",
            planSteps = plan
        )

        assertEquals(1, analyzerCalled)
        assertTrue("Accessibility click should have toggled UI state", screenClicked)
        assertTrue("Autonomous loop must succeed when UI delta is observed", exec.success)
        assertTrue("Autonomous loop must verify genuine state transition", exec.verified)
        assertEquals("SUCCEEDED", exec.finalState)
        assertTrue(exec.stepsTaken.any { it.phase == "OBSERVE_VISION" && it.success })
        assertTrue(exec.stepsTaken.any { it.phase == "VERIFY" && it.success })
    }

    @Test
    fun autonomousLoop_failedDirectClickTriggersReplanVisionFallbackAndVerifiesStateChange() = runBlocking {
        var darkModeEnabled = false
        attachMockAccessibility {
            if (!darkModeEnabled) {
                buildScreenRoot(
                    pkg = "com.android.settings",
                    buttonText = "Appearance Toggle",
                    buttonViewId = "com.android.settings:id/switch_appearance",
                    onButtonClick = { darkModeEnabled = true }
                )
            } else {
                buildScreenRoot(
                    pkg = "com.android.settings",
                    buttonText = "Dark Theme Active",
                    buttonViewId = "com.android.settings:id/status_dark_on",
                    onButtonClick = {}
                )
            }
        }

        // Standard "click" asks for localized Bangla label "ডার্ক থিম" which does not match "Appearance Toggle" in UI tree,
        // forcing Strategy A.5 (REPLAN_VISION) via ScreenVisualAnalyzer!
        JarvisAutonomousLoopOrchestrator.visualAnalyzerRunner = { _, _ ->
            ScreenVisualAnalysis(
                summaryBengali = "সেটিংস স্ক্রিনে Appearance Toggle শনাক্ত হয়েছে।",
                detectedApp = "com.android.settings",
                screenDescription = "Display settings",
                suggestions = listOf(
                    ScreenActionSuggestion(
                        actionType = "click",
                        title = "ডার্ক থিম চালু",
                        elementName = "Appearance Toggle",
                        description = "Toggle appearance",
                        targetTextOrId = "Appearance Toggle",
                        viewId = "com.android.settings:id/switch_appearance",
                        confidence = 0.92f,
                        screenshotTimestamp = System.currentTimeMillis()
                    )
                ),
                rawAiResponse = "{}"
            )
        }

        val plan = JSONArray().put(
            JSONObject()
                .put("action", "click")
                .put("target", "ডার্ক থিম")
        )
        val exec = JarvisAutonomousLoopOrchestrator.executeTask(
            context = app,
            goal = "ডার্ক থিম চালু করো",
            planSteps = plan
        )

        assertTrue("REPLAN_VISION must click real accessibility element", darkModeEnabled)
        assertTrue(exec.success)
        assertTrue(exec.verified)
        assertEquals("SUCCEEDED", exec.finalState)
        assertTrue(
            "Execution trace must record REPLAN_VISION step",
            exec.stepsTaken.any { it.phase == "REPLAN_VISION" && it.success }
        )
    }

    @Test
    fun autonomousLoop_refusesVerifiedWhenUiHashDoesNotChangeAfterClick() = runBlocking {
        // Button accepts click, but screen hash never changes -> must NOT claim verified=true
        var clickCount = 0
        attachMockAccessibility {
            buildScreenRoot(
                pkg = "com.example.staticapp",
                buttonText = "Static Button",
                buttonViewId = "com.example.staticapp:id/btn_static",
                onButtonClick = { clickCount++ }
            )
        }

        JarvisAutonomousLoopOrchestrator.visualAnalyzerRunner = { _, _ ->
            ScreenVisualAnalysis(
                summaryBengali = "Static screen",
                detectedApp = "com.example.staticapp",
                screenDescription = "Static screen",
                suggestions = listOf(
                    ScreenActionSuggestion(
                        actionType = "click",
                        title = "Static Button",
                        description = "Static button",
                        targetTextOrId = "Static Button",
                        viewId = "com.example.staticapp:id/btn_static",
                        confidence = 0.9f,
                        screenshotTimestamp = System.currentTimeMillis()
                    )
                ),
                rawAiResponse = "{}"
            )
        }

        val plan = JSONArray().put(
            JSONObject()
                .put("action", "vision_discover")
                .put("target", "Static Button")
        )
        val exec = JarvisAutonomousLoopOrchestrator.executeTask(
            context = app,
            goal = "Static Button ক্লিক করো",
            planSteps = plan
        )

        assertEquals(1, clickCount)
        assertFalse("Unchanged UI hash must NEVER set verified=true", exec.verified)
        assertFalse(exec.success)
        assertEquals("PARTIALLY_COMPLETED", exec.finalState)
    }
}
