package com.example

import com.example.automation.GitHubRepositoryLearningEngine
import com.example.automation.YouTubeTutorialImporter
import com.example.data.repository.JarvisNotificationRepository
import com.example.service.EnhancedNotification
import com.example.tools.ExecutionStatus
import com.example.tools.ToolAuditLogger
import com.example.tools.ToolCategory
import com.example.tools.ToolExecutionPipeline
import com.example.tools.ToolRegistry
import com.example.ai.JarvisNlpCommandParser
import com.example.ai.JarvisParsedCommand
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JarvisPhase2LearningAndNotificationUnitTest {

    @Before
    fun setUp() {
        ToolAuditLogger.clear()
        JarvisNotificationRepository.clear()
    }

    @Test
    fun youtubeTutorialImporter_validatesUrlAndExtractsVideoId() {
        val validUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val validShortsUrl = "https://youtu.be/dQw4w9WgXcQ"
        val invalidUrl = "https://example.com/not-youtube"

        assertTrue(YouTubeTutorialImporter.isValidYouTubeUrl(validUrl))
        assertTrue(YouTubeTutorialImporter.isValidYouTubeUrl(validShortsUrl))
        assertFalse(YouTubeTutorialImporter.isValidYouTubeUrl(invalidUrl))

        assertEquals("dQw4w9WgXcQ", YouTubeTutorialImporter.extractVideoId(validUrl))
        assertEquals("dQw4w9WgXcQ", YouTubeTutorialImporter.extractVideoId(validShortsUrl))
        assertNull(YouTubeTutorialImporter.extractVideoId(invalidUrl))
    }

    @Test
    fun youtubeTutorialImporter_synthesizesSkillWithSteps() = runBlocking {
        val url = "https://www.youtube.com/watch?v=abcdefghijk"
        val transcript = "Click Search\nType developer options\nClick Developer Options\nScroll down"

        val result = YouTubeTutorialImporter.importFromTutorial(
            url = url,
            userProvidedTitle = "Developer Options Tutorial",
            userProvidedTranscript = transcript
        )

        assertTrue(result.getBoolean("success"))
        assertEquals("abcdefghijk", result.getString("videoId"))
        assertTrue(result.getInt("stepCount") >= 3)
        assertTrue(result.getString("summaryBengali").contains("সফলভাবে"))
    }

    @Test
    fun youtubeTutorialImporter_rejectsInvalidUrlGracefully() = runBlocking {
        val result = YouTubeTutorialImporter.importFromTutorial(
            url = "invalid-url"
        )
        assertFalse(result.getBoolean("success"))
        assertEquals("INVALID_YOUTUBE_URL", result.getString("error"))
    }

    @Test
    fun gitHubLearningEngine_validatesUrlAndParsesRepo() = runBlocking {
        val validUrl = "https://github.com/google/jetpack-compose"
        val invalidUrl = "https://gitlab.com/test/repo"

        assertTrue(GitHubRepositoryLearningEngine.isValidGitHubUrl(validUrl))
        assertFalse(GitHubRepositoryLearningEngine.isValidGitHubUrl(invalidUrl))

        val (owner, repo) = GitHubRepositoryLearningEngine.extractOwnerAndRepo(validUrl)!!
        assertEquals("google", owner)
        assertEquals("jetpack-compose", repo)

        val originalFetcher = GitHubRepositoryLearningEngine.httpFetcher
        try {
            GitHubRepositoryLearningEngine.httpFetcher = { url ->
                when {
                    url.endsWith("/repos/google/jetpack-compose") -> com.example.automation.HttpFetchResult(
                        statusCode = 200,
                        body = """{"default_branch":"main","description":"Jetpack Compose Automation Samples","language":"Kotlin"}"""
                    )
                    url.contains("/git/trees/main") -> com.example.automation.HttpFetchResult(
                        statusCode = 200,
                        body = """{"tree":[{"path":"app/src/main/AndroidManifest.xml"},{"path":"build.gradle.kts"},{"path":"jarvis-skill.json"},{"path":"README.md"}]}"""
                    )
                    url.endsWith("/jarvis-skill.json") -> com.example.automation.HttpFetchResult(
                        statusCode = 200,
                        body = """{"name":"Compose Sample","nameBengali":"কম্পোজ স্যাম্পল","targetPackageName":"com.example.compose","steps":[{"actionType":"OPEN_APP","targetViewId":"com.example.compose","targetText":"Compose","descriptionBengali":"অ্যাপ চালু করা"},{"actionType":"CLICK","targetText":"Run","descriptionBengali":"চালু করা"}]}"""
                    )
                    url.endsWith("/README.md") -> com.example.automation.HttpFetchResult(
                        statusCode = 200,
                        body = "# Jetpack Compose\nClick Run\nScroll list"
                    )
                    else -> com.example.automation.HttpFetchResult(statusCode = 404, body = "")
                }
            }
            val result = GitHubRepositoryLearningEngine.importFromRepository(
                repoUrl = validUrl
            )
            assertTrue(result.getBoolean("success"))
            assertEquals("google", result.getString("owner"))
            assertEquals("jetpack-compose", result.getString("repo"))
            assertTrue(result.getInt("stepCount") >= 2)
        } finally {
            GitHubRepositoryLearningEngine.httpFetcher = originalFetcher
        }
    }

    @Test
    fun notificationRepository_storesAndRetrievesNotifications() {
        val notif1 = EnhancedNotification(
            key = "key_1",
            id = 1,
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            sender = "Rahim",
            title = "Rahim",
            text = "Are you coming today?",
            bigText = "Are you coming today?",
            timestamp = System.currentTimeMillis(),
            category = "msg",
            isSensitiveOtp = false,
            hasReplyAction = true,
            actionsSummary = listOf("Reply")
        )

        val notif2 = EnhancedNotification(
            key = "key_2",
            id = 2,
            packageName = "com.bKash.customerapp",
            appName = "bKash",
            sender = "bKash",
            title = "OTP Alert",
            text = "Your bKash verification code is 492018",
            bigText = "Your bKash verification code is 492018",
            timestamp = System.currentTimeMillis() + 100,
            category = "msg",
            isSensitiveOtp = true,
            hasReplyAction = false,
            actionsSummary = emptyList()
        )

        JarvisNotificationRepository.addNotification(notif1)
        JarvisNotificationRepository.addNotification(notif2)

        assertEquals(2, JarvisNotificationRepository.getRecent().size)

        val otps = JarvisNotificationRepository.getOtps()
        assertEquals(1, otps.size)
        assertEquals("bKash", otps[0].appName)

        val searchRes = JarvisNotificationRepository.searchNotifications("Rahim")
        assertEquals(1, searchRes.size)
        assertEquals("WhatsApp", searchRes[0].appName)
    }

    @Test
    fun toolRegistry_recognizesNewYouTubeAndGitHubTools() {
        val ytTool = ToolRegistry.get("importSkillFromYouTube")
        val ghTool = ToolRegistry.get("importSkillFromGitHub")

        assertNotNull(ytTool)
        assertNotNull(ghTool)
        assertEquals(ToolCategory.APP_AUTOMATION, ytTool?.category)
        assertEquals(ToolCategory.APP_AUTOMATION, ghTool?.category)
    }

    @Test
    fun toolExecutionPipeline_truthfulStatusWhenSuccess() = runBlocking {
        val args = JSONObject()
        val res = ToolExecutionPipeline.execute(
            toolName = "getDeviceInfo",
            args = args,
            context = null,
            caller = "test"
        ) { _, _ ->
            JSONObject().apply {
                put("success", true)
                put("model", "Pixel 8")
                put("batteryPercent", 85)
            }
        }

        val auditLogs = ToolAuditLogger.getRecent()
        assertTrue(auditLogs.isNotEmpty())
        assertEquals(ExecutionStatus.SUCCESS, auditLogs[0].status)
    }

    @Test
    fun toolExecutionPipeline_truthfulStatusWhenFailed() = runBlocking {
        val args = JSONObject()
        val res = ToolExecutionPipeline.execute(
            toolName = "getDeviceInfo",
            args = args,
            context = null,
            caller = "test"
        ) { _, _ ->
            JSONObject().apply {
                put("success", false)
                put("error", "Hardware sensor unavailable")
            }
        }

        val auditLogs = ToolAuditLogger.getRecent()
        assertTrue(auditLogs.isNotEmpty())
        assertEquals(ExecutionStatus.FAILED, auditLogs[0].status)
    }

    @Test
    fun nlpParser_distinguishesWhatsAppCallIntents() {
        val videoCallCmd = JarvisNlpCommandParser.parse("হোয়াটসঅ্যাপে বাবাকে ভিডিও কল দাও")
        assertTrue(videoCallCmd is JarvisParsedCommand.WhatsAppVideoCall)
        assertEquals("বাবা", (videoCallCmd as JarvisParsedCommand.WhatsAppVideoCall).contactOrPhone)

        val voiceCallCmd = JarvisNlpCommandParser.parse("হোয়াটসঅ্যাপে বাবাকে কল দাও")
        assertTrue(voiceCallCmd is JarvisParsedCommand.WhatsAppVoiceCall)
        assertEquals("বাবা", (voiceCallCmd as JarvisParsedCommand.WhatsAppVoiceCall).contactOrPhone)

        val openChatCmd = JarvisNlpCommandParser.parse("হোয়াটসঅ্যাপে তানভিরের চ্যাট খোলো")
        assertTrue(openChatCmd is JarvisParsedCommand.WhatsAppOpenChat)
        assertEquals("তানভির", (openChatCmd as JarvisParsedCommand.WhatsAppOpenChat).contactOrPhone)

        val msgCmd = JarvisNlpCommandParser.parse("হোয়াটসঅ্যাপে তানভিরকে মেসেজ পাঠাও যে আমি ৫ মিনিট পর পৌঁছাচ্ছি")
        assertTrue(msgCmd is JarvisParsedCommand.SendWhatsApp)
        val wa = msgCmd as JarvisParsedCommand.SendWhatsApp
        assertEquals("তানভির", wa.contactOrPhone)
        assertTrue(wa.message.contains("পৌঁছাচ্ছি"))
    }

    @Test
    fun nlpParser_parsesSmsAndInCallCommands() {
        val smsCmd = JarvisNlpCommandParser.parse("01712345678 নম্বরে এসএমএস পাঠাও: আমি ক্লাসে আছি")
        assertTrue(smsCmd is JarvisParsedCommand.SendSms)
        val sms = smsCmd as JarvisParsedCommand.SendSms
        assertEquals("01712345678", sms.phoneNumber)
        assertEquals("আমি ক্লাসে আছি", sms.message)

        val answerCmd = JarvisNlpCommandParser.parse("কল রিসিভ করো")
        assertTrue(answerCmd is JarvisParsedCommand.AnswerCall)

        val rejectCmd = JarvisNlpCommandParser.parse("কল কেটে দাও")
        assertTrue(rejectCmd is JarvisParsedCommand.RejectCall)

        val silenceCmd = JarvisNlpCommandParser.parse("রিংটোন মিউট করো")
        assertTrue(silenceCmd is JarvisParsedCommand.SilenceCall)

        val endCmd = JarvisNlpCommandParser.parse("চলতি কল কেটে দাও")
        assertTrue(endCmd is JarvisParsedCommand.EndActiveCall)

        val callerCmd = JarvisNlpCommandParser.parse("কে কল করেছে")
        assertTrue(callerCmd is JarvisParsedCommand.IdentifyCaller)

        val notifCmd = JarvisNlpCommandParser.parse("সর্বশেষ নোটিফিকেশনটি পড়ে শোনাও")
        assertTrue(notifCmd is JarvisParsedCommand.ReadLatestNotification)
    }
}
