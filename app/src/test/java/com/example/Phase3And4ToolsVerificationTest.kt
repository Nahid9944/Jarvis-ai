package com.example

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.automation.AutomationToolHandler
import com.example.tools.ToolRegistry
import com.example.tools.ToolResultVerifier
import com.example.util.JarvisDeviceAutomationHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase3And4ToolsVerificationTest {

    private fun findToolSchema(name: String): JSONObject {
        val arr = ToolRegistry.getDeclarationsJson()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("name") == name) return obj
        }
        fail("Tool schema not found: $name")
        return JSONObject()
    }

    @Test
    fun toolRegistry_emitsAccurateJsonTypesEnumsAndOptionality() {
        val torchProps = findToolSchema("toggleFlashlight").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("boolean", torchProps.getJSONObject("enabled").getString("type"))

        val volProps = findToolSchema("setVolume").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("integer", volProps.getJSONObject("volumePercent").getString("type"))

        val clickParams = findToolSchema("clickScreen").getJSONObject("parameters")
        val clickProps = clickParams.getJSONObject("properties")
        assertEquals("number", clickProps.getJSONObject("x").getString("type"))
        assertEquals("number", clickProps.getJSONObject("y").getString("type"))
        val reqList = (0 until clickParams.getJSONArray("required").length()).map {
            clickParams.getJSONArray("required").getString(it)
        }
        assertTrue(reqList.contains("targetText"))
        assertFalse(reqList.contains("x"))
        assertFalse(reqList.contains("y"))

        val sysActionProp = findToolSchema("performSystemAction")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("action")
        assertTrue(sysActionProp.has("enum"))
        assertTrue(sysActionProp.getJSONArray("enum").length() >= 5)
    }

    @Test
    fun openDeepLink_blocksUnsafeSchemesAndAllowsSafeOnes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val handler = AutomationToolHandler(context)

        assertFalse(handler.isAllowedDeepLinkScheme("javascript:alert(1)"))
        assertFalse(handler.isAllowedDeepLinkScheme("file:///data/local/tmp"))
        assertFalse(handler.isAllowedDeepLinkScheme("content://contacts/people"))
        assertFalse(handler.isAllowedDeepLinkScheme(""))

        assertTrue(handler.isAllowedDeepLinkScheme("https://example.com"))
        assertTrue(handler.isAllowedDeepLinkScheme("tel:+8801711000000"))
        assertTrue(handler.isAllowedDeepLinkScheme("whatsapp://send?text=hi"))

        val blocked = handler.openDeepLink("javascript:alert(1)")
        assertFalse(blocked.optBoolean("success"))
        assertEquals("BLOCKED_UNSAFE_URI", blocked.optString("status"))
    }

    @Test
    fun toolResultVerifier_rejectsOfflineWeatherAsVerifiedSuccess() {
        val raw = JSONObject().apply {
            put("success", false)
            put("isLiveData", false)
            put("status", "OFFLINE_LIMITED")
            put("location", "ঢাকা")
            put("temperature", 0.0)
            put("summaryBengali", "ইন্টারনেট সংযোগ বিচ্ছিন্ন")
        }
        val verified = ToolResultVerifier.verifyAndEnrich("getLiveWeather", ToolRegistry.get("getLiveWeather"), JSONObject(), raw)
        assertFalse(verified.optBoolean("success"))
        assertFalse(verified.optBoolean("verified"))
        assertEquals("OFFLINE_LIMITED", verified.optString("verificationStatus"))
    }

    @Test
    fun setVolume_verifiesReadBackStreamVolume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (ok, msg) = JarvisDeviceAutomationHelper.setVolume(context, 60, "media")
        assertTrue("Expected setVolume to succeed cleanly: $msg", ok)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val actual = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val expected = ((60 / 100f) * max).toInt()
        assertTrue("Expected stream volume near $expected, was $actual", kotlin.math.abs(actual - expected) <= 1)
    }
}
