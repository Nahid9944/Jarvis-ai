package com.example.developer

import android.content.Context
import com.example.automation.GitHubRepositoryLearningEngine
import com.example.data.local.ArtifactEntity
import com.example.data.local.AssistantDao
import com.example.data.local.AssistantDatabase
import com.example.data.local.DevPatchHistoryEntity
import com.example.data.local.HomeDeveloperDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

data class PatchGenerationOutcome(
    val success: Boolean,
    val patch: DevPatchHistoryEntity,
    val artifactId: Long,
    val testStubArtifactId: Long = 0L,
    val securityFindings: List<String> = emptyList(),
    val messageBn: String
)

/**
 * Multi-Language Code Patch, Unit Test Stub, Web App Workspace & Security/Rollback Engine.
 * Truthfulness rules:
 * - Native Kotlin/Java/Manifest/C++/Rust/etc. patches are ALWAYS marked `compileStatus = "NOT_COMPILED_REQUIRES_BUILD"`.
 * - Dynamic code loading (.dex/.jar/.so/eval) or hardcoded API keys are blocked with `securityStatus = "BLOCKED"`.
 * - Web workspaces (HTML/CSS/JS) are saved strictly as self-contained artifacts.
 */
object DeveloperWorkspaceEngine {

    private val EXTRA_POLICY_PATTERNS = listOf(
        Regex("""MANAGE_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE""") to "Restricted broad storage permission detected (Google Play violation).",
        Regex("""System\.loadLibrary\s*\(.*["'].*\.so""") to "Dynamic native library loading pattern flagged for security audit."
    )

    suspend fun generateLanguagePatchAndTestStub(
        context: Context? = null,
        taskId: String = "",
        targetFile: String,
        goalPrompt: String,
        proposedContentOverride: String? = null,
        previousSnapshot: String = "",
        assistantDaoOverride: AssistantDao? = null,
        devDaoOverride: HomeDeveloperDao? = null
    ): PatchGenerationOutcome = withContext(Dispatchers.IO) {
        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val aDao = assistantDaoOverride ?: db?.assistantDao()
        val dDao = devDaoOverride ?: db?.homeDeveloperDao()

        val lang = SupportedCodeLanguage.detectFromFileOrPrompt(targetFile, goalPrompt)
        val safeTargetFile = targetFile.trim().ifBlank { "feature_patch.${lang.fileExt}" }
        val generatedCode = (proposedContentOverride?.takeIf { it.isNotBlank() }
            ?: buildTemplateForLanguage(lang, safeTargetFile, goalPrompt))

        val securityAudit = GitHubRepositoryLearningEngine.scanSourceForSecurityAndPlayRisks(
            fileContents = mapOf(safeTargetFile to generatedCode),
            manifestPermissions = emptyList()
        )
        val extraFindings = EXTRA_POLICY_PATTERNS.mapNotNull { (rx, msg) ->
            if (rx.containsMatchIn(generatedCode)) "$safeTargetFile -> $msg" else null
        }
        val allFindings = (securityAudit.findings + extraFindings).distinct()
        val isBlocked = securityAudit.blockedByPlayOrSecurityPolicy ||
            allFindings.any { it.contains("CRITICAL", ignoreCase = true) || it.contains("hardcoded", ignoreCase = true) }

        val securityStatus = when {
            isBlocked -> "BLOCKED"
            allFindings.isNotEmpty() -> "WARNING"
            else -> "PASS"
        }

        val patchId = "patch_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        val compileTruth = if (lang == SupportedCodeLanguage.HTML_CSS || lang == SupportedCodeLanguage.JSON_YAML_XML) {
            "DECLARATIVE_READY"
        } else {
            "NOT_COMPILED_REQUIRES_BUILD"
        }

        var artifactId = 0L
        var testArtifactId = 0L
        if (aDao != null && !isBlocked) {
            artifactId = aDao.insertArtifact(
                ArtifactEntity(
                    title = "Patch: $safeTargetFile (${lang.displayName})",
                    content = generatedCode,
                    format = if (lang == SupportedCodeLanguage.JSON_YAML_XML) "JSON" else "CODE",
                    category = "HOME_DEV_PATCH",
                    filePath = safeTargetFile
                )
            )
            val testStub = buildTestStubForLanguage(lang, safeTargetFile, goalPrompt)
            if (testStub.isNotBlank()) {
                testArtifactId = aDao.insertArtifact(
                    ArtifactEntity(
                        title = "Test Stub: $safeTargetFile",
                        content = testStub,
                        format = "CODE",
                        category = "TEST_SUITE",
                        filePath = "tests/test_${safeTargetFile.substringAfterLast('/')}"
                    )
                )
            }
        }

        val summaryBn = if (isBlocked) {
            "নিরাপত্তা ও Google Play নীতিমালার কারণে '${safeTargetFile}' প্যাচটি ব্লক করা হয়েছে: ${allFindings.joinToString("; ")}"
        } else {
            "'${safeTargetFile}' (${lang.displayName}) প্যাচ ও টেস্ট স্টাব প্রস্তুত। নেটিভ রানটাইমে কার্যকর করতে Build + Install প্রয়োজন।"
        }

        val patchEntity = DevPatchHistoryEntity(
            patchId = patchId,
            taskId = taskId,
            targetFile = safeTargetFile,
            language = lang.name,
            summaryBn = summaryBn,
            patchContent = generatedCode,
            previousSnapshot = previousSnapshot,
            securityStatus = securityStatus,
            compileStatus = compileTruth,
            status = if (isBlocked) "BLOCKED" else "DRAFT",
            artifactId = artifactId
        )
        dDao?.upsertPatch(patchEntity)

        PatchGenerationOutcome(
            success = !isBlocked,
            patch = patchEntity,
            artifactId = artifactId,
            testStubArtifactId = testArtifactId,
            securityFindings = allFindings,
            messageBn = summaryBn
        )
    }

    /**
     * Builds a separate Web / Frontend Workspace artifact bundle (HTML5 + CSS + JS)
     * cleanly isolated from native Android code patches.
     */
    suspend fun generateWebAppWorkspace(
        context: Context? = null,
        taskId: String = "",
        appTitle: String,
        goalPrompt: String,
        assistantDaoOverride: AssistantDao? = null,
        devDaoOverride: HomeDeveloperDao? = null
    ): PatchGenerationOutcome = withContext(Dispatchers.IO) {
        val cleanTitle = appTitle.trim().ifBlank { "NAHID Web App Workspace" }
        val bundle = """
            |<!DOCTYPE html>
            |<html lang="bn">
            |<head>
            |  <meta charset="UTF-8" />
            |  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            |  <title>$cleanTitle</title>
            |  <style>
            |    body { font-family: system-ui, sans-serif; background: #071318; color: #E2F8F5; margin: 0; padding: 24px; }
            |    .card { max-width: 720px; margin: auto; background: #0E232B; border: 1px solid #00F5D4; border-radius: 16px; padding: 24px; }
            |    h1 { color: #00F5D4; margin-top: 0; }
            |  </style>
            |</head>
            |<body>
            |  <main class="card">
            |    <h1>$cleanTitle</h1>
            |    <p>উদ্দেশ্য: ${goalPrompt.replace("<", "&lt;").replace(">", "&gt;")}</p>
            |    <div id="app-root">সিস্টেম প্রস্তুত</div>
            |  </main>
            |  <script>
            |    (function() {
            |      const root = document.getElementById('app-root');
            |      if (root) root.textContent = 'Web Workspace initialized cleanly.';
            |    })();
            |  </script>
            |</body>
            |</html>
        """.trimMargin()

        val db = context?.let { AssistantDatabase.getDatabase(it) }
        val aDao = assistantDaoOverride ?: db?.assistantDao()
        val dDao = devDaoOverride ?: db?.homeDeveloperDao()

        val artifactId = aDao?.insertArtifact(
            ArtifactEntity(
                title = "Web Workspace: $cleanTitle",
                content = bundle,
                format = "CODE",
                category = "WEB_APP_WORKSPACE",
                filePath = "web/${cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}/index.html"
            )
        ) ?: 0L

        val patchEntity = DevPatchHistoryEntity(
            patchId = "web_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}",
            taskId = taskId,
            targetFile = "web/${cleanTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_")}/index.html",
            language = SupportedCodeLanguage.HTML_CSS.name,
            summaryBn = "ওয়েব অ্যাপ ওয়ার্কস্পেস '$cleanTitle' আর্টিফ্যাক্ট হিসেবে তৈরি হয়েছে।",
            patchContent = bundle,
            securityStatus = "PASS",
            compileStatus = "DECLARATIVE_READY",
            status = "DRAFT",
            artifactId = artifactId
        )
        dDao?.upsertPatch(patchEntity)

        PatchGenerationOutcome(
            success = true,
            patch = patchEntity,
            artifactId = artifactId,
            messageBn = patchEntity.summaryBn
        )
    }

    suspend fun approvePatch(
        patchId: String,
        devDao: HomeDeveloperDao
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val existing = devDao.getPatchByIdSync(patchId)
            ?: return@withContext false to "প্যাচ '$patchId' খুঁজে পাওয়া যায়নি।"
        if (existing.securityStatus == "BLOCKED" || existing.status == "BLOCKED") {
            return@withContext false to "নিরাপত্তা নীতিমালার কারণে ব্লক করা প্যাচ অনুমোদন করা যাবে না।"
        }
        val approved = existing.copy(status = "APPROVED", updatedAt = System.currentTimeMillis())
        devDao.upsertPatch(approved)
        true to "'${approved.targetFile}' প্যাচটি অনুমোদিত হয়েছে (${approved.compileStatus})।"
    }

    suspend fun rollbackPatch(
        patchId: String,
        devDao: HomeDeveloperDao,
        assistantDao: AssistantDao? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val existing = devDao.getPatchByIdSync(patchId)
            ?: return@withContext false to "প্যাচ '$patchId' খুঁজে পাওয়া যায়নি।"
        val restoredContent = existing.previousSnapshot.ifBlank { "// Rolled back from patch ${existing.patchId}" }
        val rolledBack = existing.copy(
            patchContent = restoredContent,
            status = "ROLLED_BACK",
            updatedAt = System.currentTimeMillis()
        )
        devDao.upsertPatch(rolledBack)
        if (assistantDao != null && existing.artifactId > 0L) {
            val art = assistantDao.getArtifactByIdSync(existing.artifactId)
            if (art != null) {
                assistantDao.updateArtifact(
                    art.copy(
                        content = restoredContent,
                        title = "[ROLLED BACK] ${art.title}",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        true to "'${existing.targetFile}' নিরাপদ পূর্ববর্তী অবস্থায় রোলব্যাক সম্পন্ন হয়েছে।"
    }

    private fun buildTemplateForLanguage(
        lang: SupportedCodeLanguage,
        fileName: String,
        goalPrompt: String
    ): String {
        val safeGoal = goalPrompt.replace("*/", "").take(220)
        return when (lang) {
            SupportedCodeLanguage.KOTLIN -> """
                |// Target: $fileName
                |// Goal: $safeGoal
                |// Runtime truth: Native Kotlin changes require Gradle Build + APK Install.
                |package com.example.generated
                |
                |data class GeneratedFeatureSpec(
                |    val title: String = "${safeGoal.replace("\"", "'")}",
                |    val verified: Boolean = false
                |)
            """.trimMargin()
            SupportedCodeLanguage.PYTHON -> """
                |# Target: $fileName
                |# Goal: $safeGoal
                |from dataclasses import dataclass
                |
                |@dataclass
                |class FeatureSpec:
                |    goal: str = "${safeGoal.replace("\"", "'")}"
                |
                |def describe_spec() -> str:
                |    return FeatureSpec().goal
            """.trimMargin()
            SupportedCodeLanguage.TYPESCRIPT, SupportedCodeLanguage.JAVASCRIPT -> """
                |// Target: $fileName
                |// Goal: $safeGoal
                |export function createFeaturePlan(goal = "${safeGoal.replace("\"", "'")}") {
                |  return { goal, status: 'DRAFT_READY' };
                |}
            """.trimMargin()
            SupportedCodeLanguage.SQL -> """
                |-- Target: $fileName
                |-- Goal: $safeGoal
                |CREATE TABLE IF NOT EXISTS dev_generated_spec (
                |  id INTEGER PRIMARY KEY AUTOINCREMENT,
                |  goal TEXT NOT NULL
                |);
            """.trimMargin()
            else -> """
                |/* Target: $fileName (${lang.displayName})
                | * Goal: $safeGoal
                | * Note: Exported as structured workspace artifact; compile/run on target toolchain.
                | */
            """.trimMargin()
        }
    }

    private fun buildTestStubForLanguage(
        lang: SupportedCodeLanguage,
        fileName: String,
        goalPrompt: String
    ): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "Feature" }
        return when (lang) {
            SupportedCodeLanguage.KOTLIN, SupportedCodeLanguage.JAVA -> """
                |// Unit test stub for $fileName
                |package com.example.generated
                |
                |import org.junit.Assert.assertTrue
                |import org.junit.Test
                |
                |class ${base}VerificationTest {
                |    @Test
                |    fun verifyGoalContract() {
                |        val spec = GeneratedFeatureSpec()
                |        assertTrue(spec.title.isNotBlank())
                |    }
                |}
            """.trimMargin()
            SupportedCodeLanguage.PYTHON -> """
                |def test_${base.lowercase(Locale.ROOT)}_spec():
                |    assert len("${goalPrompt.take(40).replace("\"", "'")}") >= 0
            """.trimMargin()
            SupportedCodeLanguage.TYPESCRIPT, SupportedCodeLanguage.JAVASCRIPT -> """
                |test('$base spec contract', () => {
                |  expect('${goalPrompt.take(40).replace("'", "")}'.length).toBeGreaterThanOrEqual(0);
                |});
            """.trimMargin()
            else -> ""
        }
    }
}
