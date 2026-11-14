package com.example

import com.example.tools.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
class ToolRegistryUnitTest {

    @Before
    fun setUp() {
        ToolAuditLogger.clear()
        com.example.util.JarvisEmergencyStopCoordinator.resetEmergencyStop()
    }

    @Test
    fun toolRegistry_hasComprehensiveCatalog() {
        val total = ToolRegistry.totalCount
        assertTrue("Tool registry should have extensive tools (found $total)", total >= 40)

        val systemTools = ToolRegistry.getByCategory(ToolCategory.SYSTEM_ACTIONS)
        assertTrue("System actions tools should be populated", systemTools.isNotEmpty())

        val communicationTools = ToolRegistry.getByCategory(ToolCategory.COMMUNICATION)
        assertTrue("Communication tools should be populated", communicationTools.isNotEmpty())

        val automationTools = ToolRegistry.getByCategory(ToolCategory.APP_AUTOMATION)
        assertTrue("Automation tools should be populated", automationTools.isNotEmpty())

        val memoryTools = ToolRegistry.getByCategory(ToolCategory.AI_MEMORY)
        assertTrue("AI memory tools should be populated", memoryTools.isNotEmpty())
    }

    @Test
    fun toolRegistry_mapsSensitiveToolsToPermissionSpecs() {
        val callTool = ToolRegistry.get("makePhoneCall")
        assertNotNull(callTool)
        assertEquals("call_phone", callTool?.requiredPermissionId)

        val smsTool = ToolRegistry.get("sendSmsMessage")
        assertNotNull(smsTool)
        assertEquals("send_sms", smsTool?.requiredPermissionId)

        val accessibilityTool = ToolRegistry.get("performSystemAction")
        assertNotNull(accessibilityTool)
        assertEquals("accessibility", accessibilityTool?.requiredPermissionId)

        val locationTool = ToolRegistry.get("getCurrentGpsLocation")
        assertNotNull(locationTool)
        assertEquals("location_fine", locationTool?.requiredPermissionId)
    }

    @Test
    fun toolRegistry_enforcesConfirmationLevels() {
        val deleteMem = ToolRegistry.get("deleteMemory")
        assertNotNull(deleteMem)
        assertEquals(ConfirmationLevel.HUMAN_GATE, deleteMem?.confirmationLevel)

        val callTool = ToolRegistry.get("makePhoneCall")
        assertEquals(ConfirmationLevel.HUMAN_GATE, callTool?.confirmationLevel)

        val noteTool = ToolRegistry.get("createNote")
        assertEquals(ConfirmationLevel.EXPLICIT_CONFIRM, noteTool?.confirmationLevel)

        val infoTool = ToolRegistry.get("getDeviceInfo")
        assertEquals(ConfirmationLevel.NONE, infoTool?.confirmationLevel)
    }

    @Test
    fun toolRegistry_generatesValidJsonDeclarations() {
        val json = ToolRegistry.getDeclarationsJson()
        assertTrue("Declarations array must not be empty", json.length() > 0)

        val first = json.getJSONObject(0)
        assertTrue("Declaration must have name", first.has("name"))
        assertTrue("Declaration must have description", first.has("description"))
        assertTrue("Declaration must have parameters", first.has("parameters"))
    }

    @Test
    fun toolAuditLogger_recordsAndFiltersLogs() {
        assertEquals(0, ToolAuditLogger.getCount())

        ToolAuditLogger.log(
            ToolAuditRecord(
                toolName = "getDeviceInfo",
                category = ToolCategory.SYSTEM_ACTIONS,
                paramsJson = "{}",
                caller = "voice",
                status = ExecutionStatus.SUCCESS,
                durationMs = 25,
                resultPreview = "{\"model\":\"Pixel\"}"
            )
        )

        assertEquals(1, ToolAuditLogger.getCount())
        val recent = ToolAuditLogger.getRecent()
        assertEquals(1, recent.size)
        assertEquals("getDeviceInfo", recent[0].toolName)
        assertEquals(ExecutionStatus.SUCCESS, recent[0].status)

        // Add a failed record
        ToolAuditLogger.log(
            ToolAuditRecord(
                toolName = "makePhoneCall",
                category = ToolCategory.COMMUNICATION,
                paramsJson = "{\"phoneNumber\":\"01700000000\"}",
                caller = "voice",
                status = ExecutionStatus.FAILED,
                durationMs = 120,
                errorMessage = "Network unavailable"
            )
        )

        assertEquals(2, ToolAuditLogger.getCount())
        val failures = ToolAuditLogger.getFailures()
        assertEquals(1, failures.size)
        assertEquals("makePhoneCall", failures[0].toolName)
    }

    @Test
    fun toolRouter_routesSafeCallsAndLogsAudit() = kotlinx.coroutines.test.runTest {
        val args = JSONObject().apply { put("query", "Dhaka") }
        val result = ToolRouter.routeAndExecute(
            toolName = "getLiveWeather",
            args = args,
            context = null,
            caller = "voice"
        ) { name, jsonArgs ->
            JSONObject().apply {
                put("success", true)
                put("temp", 28)
            }
        }

        assertTrue(result.optBoolean("success"))
        assertEquals(28, result.optInt("temp"))

        val auditLogs = ToolAuditLogger.getByTool("getLiveWeather")
        assertEquals(1, auditLogs.size)
        assertEquals(ExecutionStatus.SUCCESS, auditLogs[0].status)
    }

    @Test
    fun toolExecutionPipeline_enforcesUserConfirmationForDestructiveTools() = kotlinx.coroutines.test.runTest {
        val deleteArgs = JSONObject().apply {
            put("keyOrId", "user_secret_fact")
        }

        // 1. Without confirmation, should be blocked by confirmation gate
        val blockedResult = ToolExecutionPipeline.execute(
            toolName = "deleteMemory",
            args = deleteArgs,
            context = null,
            caller = "voice"
        ) { _, _ ->
            JSONObject().apply { put("success", true) }
        }

        assertEquals("CONFIRMATION_REQUIRED", blockedResult.optString("status"))
        assertFalse(blockedResult.optBoolean("success"))
        assertTrue(blockedResult.has("promptBn"))
        assertFalse(blockedResult.optBoolean("verified"))

        // 2. With explicit user confirmation, execution proceeds and is verified
        deleteArgs.put("_confirmed", true)
        val confirmedResult = ToolExecutionPipeline.execute(
            toolName = "deleteMemory",
            args = deleteArgs,
            context = null,
            caller = "test"
        ) { _, _ ->
            JSONObject().apply {
                put("success", true)
                put("deleted", true)
            }
        }

        assertTrue(confirmedResult.optBoolean("success"))
        assertTrue(confirmedResult.optBoolean("verified"))
        assertEquals("VERIFIED", confirmedResult.optString("verificationStatus"))
    }

    @Test
    fun toolExecutionPipeline_performsResultVerification() = kotlinx.coroutines.test.runTest {
        val searchArgs = JSONObject().apply { put("query", "quantum") }
        val result = ToolExecutionPipeline.execute(
            toolName = "searchWikipedia",
            args = searchArgs,
            context = null,
            caller = "voice"
        ) { _, _ ->
            JSONObject().apply {
                put("found", true)
                put("title", "Quantum Computing")
            }
        }

        assertTrue(result.optBoolean("verified"))
        assertEquals("VERIFIED", result.optString("verificationStatus"))
        assertTrue(result.has("verificationDetails"))
    }

    @Test
    fun toolExecutionPipeline_handlesVerificationFailureOnExecutionError() = kotlinx.coroutines.test.runTest {
        val result = ToolExecutionPipeline.execute(
            toolName = "getLiveWeather",
            args = JSONObject(),
            context = null,
            caller = "voice"
        ) { _, _ ->
            JSONObject().apply {
                put("error", true)
                put("status", "FAILED")
                put("message", "Network connection lost")
            }
        }

        assertFalse(result.optBoolean("verified"))
        assertEquals("VERIFICATION_FAILED", result.optString("verificationStatus"))
    }
}
