package com.example

import com.example.ai.JarvisNlpCommandParser
import com.example.ai.JarvisParsedCommand
import com.example.automation.CapturedStep
import com.example.automation.JarvisSkillRecorder
import com.example.automation.SkillRecorderState
import com.example.data.local.JarvisLearningSessionEntity
import com.example.data.local.JarvisSkillEntity
import com.example.data.local.JarvisSkillStepEntity
import com.example.tools.ToolRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class JarvisSkillRecorderUnitTest {

    @Test
    fun verifySkillEntityCreationAndSerialization() {
        val skill = JarvisSkillEntity(
            skillId = "skill_send_status_update",
            name = "Send Daily Report",
            nameBengali = "দৈনিক রিপোর্ট পাঠান",
            description = "Sends daily summary to supervisor via WhatsApp",
            triggerPhrasesJson = "[\"রিপোর্ট পাঠাও\", \"send report\", \"daily update\"]",
            targetPackageName = "com.whatsapp",
            targetAppName = "WhatsApp",
            category = "COMMUNICATION",
            stepCount = 3
        )

        assertEquals("skill_send_status_update", skill.skillId)
        assertEquals("Send Daily Report", skill.name)
        assertEquals("দৈনিক রিপোর্ট পাঠান", skill.nameBengali)
        assertEquals("com.whatsapp", skill.targetPackageName)
        assertEquals(3, skill.stepCount)
        assertEquals("ACTIVE", skill.status)

        val triggers = JSONArray(skill.triggerPhrasesJson)
        assertEquals(3, triggers.length())
        assertEquals("রিপোর্ট পাঠাও", triggers.getString(0))
    }

    @Test
    fun verifySkillStepEntityMapping() {
        val step = CapturedStep(
            stepIndex = 1,
            actionType = "CLICK",
            targetViewId = "com.whatsapp:id/send_button",
            targetText = "Send",
            targetContentDescription = "Send message",
            descriptionBengali = "‘Send’ বাটনে ক্লিক করা হয়েছে"
        )

        val entity = step.toStepEntity("skill_test_123")
        assertEquals("skill_test_123", entity.skillId)
        assertEquals(1, entity.stepIndex)
        assertEquals("CLICK", entity.actionType)
        assertEquals("com.whatsapp:id/send_button", entity.targetViewId)
        assertEquals("Send", entity.targetText)
        assertEquals("‘Send’ বাটনে ক্লিক করা হয়েছে", entity.descriptionBengali)

        val json = step.toJson()
        assertEquals("CLICK", json.getString("actionType"))
        assertEquals("com.whatsapp:id/send_button", json.getString("targetViewId"))
    }

    @Test
    fun verifyLearningSessionTracking() {
        val session = JarvisLearningSessionEntity(
            sessionId = "sess_001",
            skillName = "Order Food",
            skillNameBengali = "খাবার অর্ডার",
            triggerPhrase = "খাবার আনাও",
            targetAppPackage = "com.pathao.food",
            mode = "DEMONSTRATION",
            status = "RECORDING"
        )

        assertEquals("sess_001", session.sessionId)
        assertEquals("Order Food", session.skillName)
        assertEquals("DEMONSTRATION", session.mode)
        assertEquals("RECORDING", session.status)
        assertTrue(session.startedAt > 0L)
    }

    @Test
    fun verifySkillRecorderManualStepAddition() {
        val recorder = JarvisSkillRecorder()
        recorder.startRecording(
            skillName = "Facebook Post",
            skillNameBengali = "ফেসবুক পোস্ট",
            triggerPhrase = "পোস্ট করো",
            targetAppPackage = "com.facebook.katana",
            targetAppName = "Facebook"
        )

        assertEquals(SkillRecorderState.RECORDING, recorder.recorderState.value)
        assertEquals(1, recorder.capturedSteps.value.size) // OPEN_APP step
        assertEquals("OPEN_APP", recorder.capturedSteps.value[0].actionType)

        recorder.addManualStep(
            actionType = "CLICK",
            targetText = "What's on your mind?",
            descriptionBn = "স্ট্যাটাস বক্সে ক্লিক"
        )
        recorder.addManualStep(
            actionType = "INPUT_TEXT",
            payload = "Hello World",
            descriptionBn = "হ্যালো ওয়ার্ল্ড টাইপ করা হয়েছে"
        )

        val steps = recorder.capturedSteps.value
        assertEquals(3, steps.size)
        assertEquals("CLICK", steps[1].actionType)
        assertEquals("What's on your mind?", steps[1].targetText)
        assertEquals("INPUT_TEXT", steps[2].actionType)
        assertEquals("Hello World", steps[2].inputPayload)

        recorder.discardRecording()
        assertEquals(SkillRecorderState.IDLE, recorder.recorderState.value)
        assertTrue(recorder.capturedSteps.value.isEmpty())
    }

    @Test
    fun verifyNlpLearningIntentParsing() {
        val startCommand = JarvisNlpCommandParser.parse("Jarvis, নতুন কাজ শেখো ফেসবুক পোস্ট")
        assertTrue(startCommand is JarvisParsedCommand.StartSkillRecording)
        val skillName = (startCommand as JarvisParsedCommand.StartSkillRecording).skillName
        assertTrue("Extracted skill name should be non-empty", skillName.isNotBlank())

        val stopCommand = JarvisNlpCommandParser.parse("Jarvis, কাজ শেখা শেষ করো")
        assertTrue(stopCommand is JarvisParsedCommand.StopSkillRecording)

        val runCommand = JarvisNlpCommandParser.parse("Jarvis, শেখা কাজ চালাও ফেসবুক পোস্ট")
        assertTrue(runCommand is JarvisParsedCommand.RunLearnedSkill)
    }

    @Test
    fun verifyToolRegistryContainsSkillTools() {
        val startTool = ToolRegistry.get("startSkillRecording")
        assertNotNull("startSkillRecording must be registered in ToolRegistry", startTool)
        assertEquals("accessibility", startTool?.requiredPermissionId)

        val saveTool = ToolRegistry.get("stopAndSaveSkill")
        assertNotNull("stopAndSaveSkill must be registered in ToolRegistry", saveTool)

        val listTool = ToolRegistry.get("listLearnedSkills")
        assertNotNull("listLearnedSkills must be registered in ToolRegistry", listTool)

        val executeTool = ToolRegistry.get("executeLearnedSkill")
        assertNotNull("executeLearnedSkill must be registered in ToolRegistry", executeTool)

        val deleteTool = ToolRegistry.get("deleteLearnedSkill")
        assertNotNull("deleteLearnedSkill must be registered in ToolRegistry", deleteTool)
    }
}
