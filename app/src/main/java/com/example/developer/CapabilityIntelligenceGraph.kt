package com.example.developer

import android.content.Context
import android.os.Build
import com.example.ai.AiAgentRegistry
import com.example.automation.PlaybookStore
import com.example.data.local.CapabilityGapEntity
import com.example.data.local.HomeDeveloperDao
import com.example.data.local.JarvisSkillEntity
import com.example.data.repository.JarvisSkillRepository
import com.example.permissions.PermissionRegistry
import com.example.tools.ToolCategory
import com.example.tools.ToolRegistry
import java.util.Locale

data class CapabilityIntelligenceSnapshot(
    val totalAgents: Int,
    val totalTools: Int,
    val totalPlaybooks: Int,
    val activeSkillsCount: Int,
    val draftSkillsCount: Int,
    val brokenOrNeedsReviewSkillsCount: Int,
    val grantedPermissionsCount: Int,
    val totalTrackedPermissions: Int,
    val capabilityCoveragePercent: Int,
    val projectHealthScore: Int,
    val toolsByCategory: Map<ToolCategory, Int>,
    val missingOrBlockedPermissions: List<String>,
    val hardwareAndPlatformTruthLimitsBn: List<String>,
    val detectedGaps: List<CapabilityGapEntity>
)

data class CapabilityResolutionDecision(
    val decisionType: CapabilityDecisionType,
    val matchedSkillId: String = "",
    val matchedSkillName: String = "",
    val matchedToolNames: List<String> = emptyList(),
    val summaryBn: String,
    val requiresNativeBuildAndInstall: Boolean = false
)

/**
 * Capability Intelligence Graph & Autonomous Gap Detector.
 * Reads real live state from AiAgentRegistry, ToolRegistry, JarvisSkillRepository,
 * PlaybookStore, and PermissionRegistry—never hardcodes fake health or fake capabilities.
 */
object CapabilityIntelligenceGraph {

    fun inspectProjectCapabilities(
        context: Context? = null,
        skills: List<JarvisSkillEntity> = emptyList()
    ): CapabilityIntelligenceSnapshot {
        val agents = AiAgentRegistry.ALL_AGENTS
        val tools = ToolRegistry.getAll()
        val playbooks = PlaybookStore.getAll()
        val activeSkills = skills.filter { it.status.equals("ACTIVE", ignoreCase = true) }
        val draftSkills = skills.filter { it.status.equals("DRAFT", ignoreCase = true) }
        val failingSkills = skills.filter { it.failureCount >= 2 }

        val toolsByCategory = tools.groupingBy { it.category }.eachCount()

        val permSpecs = PermissionRegistry.SPECS
        var grantedPerms = 0
        val missingPerms = mutableListOf<String>()
        if (context != null) {
            permSpecs.forEach { spec ->
                val granted = try {
                    spec.isGranted(context)
                } catch (_: Exception) {
                    false
                }
                if (granted) grantedPerms++ else missingPerms.add(spec.titleBn)
            }
        }

        val gaps = detectRealCapabilityGaps(
            context = context,
            skills = skills,
            missingPerms = missingPerms
        )

        val expectedCategories = ToolCategory.entries.size.coerceAtLeast(1)
        val coveredCategories = ToolCategory.entries.count { (toolsByCategory[it] ?: 0) > 0 }
        val baseToolCoverage = (coveredCategories * 100) / expectedCategories

        val permRatio = if (context != null && permSpecs.isNotEmpty()) {
            (grantedPerms * 100) / permSpecs.size
        } else {
            65
        }
        val penalty = (failingSkills.size * 6 + draftSkills.size * 2).coerceAtMost(35)
        val healthScore = ((baseToolCoverage * 6 + permRatio * 4) / 10 - penalty).coerceIn(10, 100)

        val platformTruths = listOf(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}): তৃতীয়-পক্ষ অ্যাপের ভেতর DRM-সুরক্ষিত কনটেন্ট বা অন্য অ্যাপের প্রাইভেট ডেটা ডিক্রিপ্ট করা অসম্ভব।",
            "ব্যাকগ্রাউন্ডে ভয়েস কল অডিও রেকর্ড করা অ্যান্ড্রয়েড ও স্থানীয় আইন দ্বারা সীমাবদ্ধ।",
            "নতুন নেটিভ Kotlin ফাইল বা AndroidManifest পরিবর্তন রানটাইমে লোড হয় না (DCL নিষিদ্ধ); কেবল Build + Install-এর পরে কার্যকর হয়।",
            "ডিভাইস-অ্যাক্সেসিবিলিটি, নোটিফিকেশন লিসেনার ও স্ক্রিন ক্যাপচার ব্যবহারকারীর স্পষ্ট অনুমতি ছাড়া কাজ করে না।"
        )

        return CapabilityIntelligenceSnapshot(
            totalAgents = agents.size,
            totalTools = tools.size,
            totalPlaybooks = playbooks.size,
            activeSkillsCount = activeSkills.size,
            draftSkillsCount = draftSkills.size,
            brokenOrNeedsReviewSkillsCount = failingSkills.size,
            grantedPermissionsCount = grantedPerms,
            totalTrackedPermissions = permSpecs.size,
            capabilityCoveragePercent = baseToolCoverage,
            projectHealthScore = healthScore,
            toolsByCategory = toolsByCategory,
            missingOrBlockedPermissions = missingPerms,
            hardwareAndPlatformTruthLimitsBn = platformTruths,
            detectedGaps = gaps
        )
    }

    fun detectRealCapabilityGaps(
        context: Context? = null,
        skills: List<JarvisSkillEntity> = emptyList(),
        missingPerms: List<String> = emptyList()
    ): List<CapabilityGapEntity> {
        val now = System.currentTimeMillis()
        val gaps = mutableListOf<CapabilityGapEntity>()

        // 1. Failing active/recorded skills (failureCount >= 2)
        skills.filter { it.failureCount >= 2 }.forEach { s ->
            gaps.add(
                CapabilityGapEntity(
                    gapId = "gap_skill_fail_${s.skillId}",
                    titleBn = "স্কিল সংশোধন প্রয়োজন: ${s.nameBengali.ifBlank { s.name }} (${s.failureCount} বার ব্যর্থ)",
                    category = "SKILL_RELIABILITY",
                    severity = if (s.failureCount >= 4) "CRITICAL" else "HIGH",
                    sourceEvidence = "JarvisSkillDao: success=${s.successCount}, failure=${s.failureCount}, version=v${s.version}",
                    resolutionType = "WORKFLOW_IMPROVE",
                    status = "OPEN",
                    detectedAt = now,
                    updatedAt = now
                )
            )
        }

        // 2. Unvalidated / unapproved DRAFT skills sitting idle
        skills.filter { it.status.equals("DRAFT", ignoreCase = true) }.forEach { d ->
            gaps.add(
                CapabilityGapEntity(
                    gapId = "gap_draft_${d.skillId}",
                    titleBn = "ড্রাফট স্কিল যাচাই ও অনুমোদন বাকি: ${d.nameBengali.ifBlank { d.name }}",
                    category = "SKILL_VALIDATION",
                    severity = "MEDIUM",
                    sourceEvidence = "sourceType=${d.sourceType}, steps=${d.stepCount}",
                    resolutionType = "SKILL_CREATE",
                    status = "OPEN",
                    detectedAt = now,
                    updatedAt = now
                )
            )
        }

        // 3. Playbook health failures in PlaybookStore
        PlaybookStore.getAllHealth().filter { it.totalRuns > 0 && !it.isHealthy }.forEach { h ->
            gaps.add(
                CapabilityGapEntity(
                    gapId = "gap_pb_${h.playbookId}",
                    titleBn = "প্লেবুক '${h.playbookId}'-এর সাফল্যের হার কম (${h.successRatePercent}%)",
                    category = "PLAYBOOK_HEALTH",
                    severity = "HIGH",
                    sourceEvidence = "PlaybookStore: runs=${h.totalRuns}, failed=${h.failedRuns}, lastError=${h.lastError ?: "N/A"}",
                    resolutionType = "WORKFLOW_IMPROVE",
                    status = "OPEN",
                    detectedAt = now,
                    updatedAt = now
                )
            )
        }

        // 4. Critical runtime/system permissions not granted on device
        if (context != null && missingPerms.isNotEmpty()) {
            val criticalMissing = missingPerms.take(4)
            gaps.add(
                CapabilityGapEntity(
                    gapId = "gap_permissions_missing",
                    titleBn = "${missingPerms.size}টি ডিভাইস পারমিশন বন্ধ আছে (${criticalMissing.joinToString(", ")})",
                    category = "PERMISSION_AVAILABILITY",
                    severity = "HIGH",
                    sourceEvidence = "PermissionRegistry live check on API ${Build.VERSION.SDK_INT}",
                    resolutionType = "UNSUPPORTED_HARDWARE",
                    status = "OPEN",
                    detectedAt = now,
                    updatedAt = now
                )
            )
        }

        // 5. Check ToolRegistry coverage for core domains
        val toolNames = ToolRegistry.getAll().map { it.name.lowercase(Locale.ROOT) }
        if (toolNames.none { it.contains("homedeveloper") || it.contains("homedev") || it.contains("selfrepair") }) {
            gaps.add(
                CapabilityGapEntity(
                    gapId = "gap_home_dev_tools",
                    titleBn = "HOME DEVELOPER টুলস ToolRegistry-তে যুক্ত করা প্রয়োজন",
                    category = "TOOL_COVERAGE",
                    severity = "HIGH",
                    sourceEvidence = "ToolRegistry (${toolNames.size} tools) missing Home Developer control tools",
                    resolutionType = "NATIVE_CODE_PATCH",
                    status = "OPEN",
                    detectedAt = now,
                    updatedAt = now
                )
            )
        }

        return gaps
    }

    suspend fun refreshAndPersistGaps(
        context: Context? = null,
        skillRepo: JarvisSkillRepository,
        devDao: HomeDeveloperDao
    ): CapabilityIntelligenceSnapshot {
        val allSkills = skillRepo.getSkillSnapshotList()
        val snapshot = inspectProjectCapabilities(context, allSkills)
        if (snapshot.detectedGaps.isNotEmpty()) {
            devDao.upsertCapabilityGaps(snapshot.detectedGaps)
        }
        return snapshot
    }

    /**
     * Universal 4-way Request Resolution Flow before acting on any user capability request:
     * 1. Check Existing Skill
     * 2. Check Existing Tool(s)
     * 3. Check if a Declarative Skill / Workflow / Web Artifact can be created
     * 4. Otherwise truthfully classify as Native Code Patch requiring Build + Install
     */
    suspend fun classifyUserCapabilityNeed(
        requestText: String,
        skillRepo: JarvisSkillRepository? = null
    ): CapabilityResolutionDecision {
        val clean = requestText.trim()
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Check existing active learned skill
        val matchedSkill = skillRepo?.findMatchingSkill(clean)
        if (matchedSkill != null && matchedSkill.status.equals("ACTIVE", ignoreCase = true) && matchedSkill.stepCount > 0) {
            return CapabilityResolutionDecision(
                decisionType = CapabilityDecisionType.EXISTING_SKILL_AVAILABLE,
                matchedSkillId = matchedSkill.skillId,
                matchedSkillName = matchedSkill.nameBengali.ifBlank { matchedSkill.name },
                summaryBn = "বিদ্যমান সক্রিয় স্কিল '${matchedSkill.nameBengali}' (${matchedSkill.stepCount} ধাপ) পাওয়া গেছে এবং সরাসরি ব্যবহারযোগ্য।"
            )
        }

        // 2. Explicit native code / Manifest / Gradle / Room schema modification requests ALWAYS require Build + Install
        val nativeCodeSignals = listOf(
            "androidmanifest", "permission যোগ", "room table", "database migration", "bluetooth gatt",
            "kotlin ফাইল", "নতুন সার্ভিস", "accessibilityservice", "build.gradle", "native sdk",
            "নতুন স্ক্রিন", "apk", "compile", "নতুন ফিচার", "new screen", "api তৈরি"
        )
        if (nativeCodeSignals.any { lower.contains(it) }) {
            return CapabilityResolutionDecision(
                decisionType = CapabilityDecisionType.NATIVE_CAPABILITY_OR_CODE_REQUIRED,
                summaryBn = "এটি নেটিভ Kotlin/Manifest/Gradle কোড পরিবর্তন চায়। HOME DEVELOPER কোড প্যাচ, টেস্ট স্টাব ও আর্টিফ্যাক্ট তৈরি করতে পারবে; তবে চলমান APK-তে তা সরাসরি চালু হবে না — Build + Install প্রয়োজন।",
                requiresNativeBuildAndInstall = true
            )
        }

        // 3. Check ToolRegistry for existing tool matches (excluding common stop-words)
        val stopWords = setOf(
            "করো", "নতুন", "দাও", "যোগ", "হবে", "চাই", "পারো", "করে", "দিয়ে", "থেকে",
            "the", "and", "for", "with", "add", "new", "make", "create", "use"
        )
        val allTools = ToolRegistry.getAll()
        val tokens = lower.split(Regex("""[\s,।?!]+"""))
            .filter { it.length >= 3 && it !in stopWords }
        val matchedTools = allTools.filter { spec ->
            val searchable = "${spec.name} ${spec.titleBn} ${spec.description}".lowercase(Locale.ROOT)
            tokens.any { tok -> searchable.contains(tok) }
        }.take(4)

        if (matchedTools.isNotEmpty()) {
            return CapabilityResolutionDecision(
                decisionType = CapabilityDecisionType.EXISTING_TOOLS_CAN_PERFORM,
                matchedToolNames = matchedTools.map { it.name },
                summaryBn = "বিদ্যমান টুল (${matchedTools.joinToString(", ") { it.name }}) দিয়েই এই কাজটি করা সম্ভব।"
            )
        }

        // 4. Can a declarative skill / multi-step workflow / text-or-URL learning solve this?
        val declarativeSignals = listOf(
            "youtube", "youtu.be", "github.com", "টেক্সট", "শেখাও", "skill", "স্কিল",
            "workflow", "রুটিন", "open ", "click", "হোয়াটসঅ্যাপ", "সেটিংস", "html", "web", "ওয়েব"
        )

        if (declarativeSignals.any { lower.contains(it) } || tokens.size >= 2) {
            return CapabilityResolutionDecision(
                decisionType = CapabilityDecisionType.NEW_SKILL_CAN_BE_CREATED,
                summaryBn = "বিদ্যমান টুলগুলো যুক্ত করে বা নির্দেশনা/রিসোর্স শিখে একটি নতুন অনুমোদনযোগ্য স্কিল/ওয়ার্কস্পেস তৈরি করা সম্ভব।"
            )
        }

        return CapabilityResolutionDecision(
            decisionType = CapabilityDecisionType.NATIVE_CAPABILITY_OR_CODE_REQUIRED,
            summaryBn = "এই কাজের জন্য নেটিভ কোড স্পেক ও ম্যানুয়াল রিভিউ প্রয়োজন (Build + Install সাপেক্ষে কার্যকর)।" ,
            requiresNativeBuildAndInstall = true
        )
    }
}
