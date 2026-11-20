package com.example.developer

import java.util.Locale

enum class AutonomyLevel(
    val levelNumber: Int,
    val titleBn: String,
    val descriptionBn: String
) {
    L1_ADVISORY(
        1,
        "L1: Advisory Only",
        "বিশ্লেষণ, প্ল্যান ও পরামর্শ দেয়; কোনো পরিবর্তন করে না।"
    ),
    L2_SAFE_AUTONOMOUS(
        2,
        "L2: Safe Autonomous (ডিফল্ট)",
        "নিরাপদ রিসার্চ, গ্যাপ ডিটেকশন, টেস্ট, ড্রাফট ও নোট-আর্টিফ্যাক্ট স্বয়ংক্রিয়ভাবে করে।"
    ),
    L3_FEATURE_BUILDER(
        3,
        "L3: Feature Builder",
        "কোড প্যাচ, টুল স্পেক, স্কিল কম্বিনেশন ও টেস্ট-স্যুট তৈরি করে (অনুমোদন সাপেক্ষে সেভ)।"
    ),
    L4_SCHEDULED_BACKGROUND(
        4,
        "L4: Scheduled Background",
        "Wi-Fi, ব্যাটারি ও আইডল শর্ত মেনে ব্যাকগ্রাউন্ডে নিরাপদ সেল্ফ-ইম্প্রুভমেন্ট ও অডিট চালায়।"
    );

    companion object {
        fun fromString(raw: String?): AutonomyLevel {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: L2_SAFE_AUTONOMOUS
        }
    }
}

enum class DevWorkStatus {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_APPROVAL,
    WAITING_PERMISSION,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

enum class DevPhase {
    IDLE,
    PLANNING,
    LEARNING,
    BUILDING,
    TESTING,
    SECURITY_CHECK,
    AWAITING_APPROVAL,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class DevIndicatorState(val labelBn: String, val colorHex: Long) {
    GREEN_WORKING("কাজ চলছে", 0xFF00FF9D),
    CYAN_LEARNING("নতুন কিছু শিখছে", 0xFF00E5FF),
    YELLOW_WAITING("অনুমতি/অনুমোদন অপেক্ষা", 0xFFFEE440),
    RED_ERROR("ত্রুটি", 0xFFFF476F),
    GREY_IDLE("বন্ধ / অপেক্ষমাণ", 0xFF93B9AA)
}

enum class CapabilityDecisionType {
    EXISTING_SKILL_AVAILABLE,
    EXISTING_TOOLS_CAN_PERFORM,
    NEW_SKILL_CAN_BE_CREATED,
    NATIVE_CAPABILITY_OR_CODE_REQUIRED
}

enum class SkillLifecycleStage {
    LEARN,
    UNDERSTAND,
    CREATE_DRAFT,
    MAP_TO_TOOLS,
    VALIDATE,
    DRY_RUN,
    APPROVAL,
    SAVE,
    ACTIVATE,
    EXECUTE,
    VERIFY,
    IMPROVE
}

enum class SupportedCodeLanguage(
    val displayName: String,
    val fileExt: String,
    val runnableInAndroidRuntime: Boolean
) {
    KOTLIN("Kotlin", "kt", false),
    JAVA("Java", "java", false),
    JAVASCRIPT("JavaScript", "js", false),
    TYPESCRIPT("TypeScript", "ts", false),
    PYTHON("Python", "py", false),
    C("C", "c", false),
    CPP("C++", "cpp", false),
    CSHARP("C#", "cs", false),
    GO("Go", "go", false),
    RUST("Rust", "rs", false),
    SWIFT("Swift", "swift", false),
    DART("Dart / Flutter", "dart", false),
    PHP("PHP", "php", false),
    SQL("SQL", "sql", false),
    HTML_CSS("HTML / CSS", "html", false),
    JSON_YAML_XML("JSON / YAML / XML", "json", false),
    BASH("Shell / Bash", "sh", false);

    companion object {
        fun detectFromFileOrPrompt(fileName: String, contentOrPrompt: String): SupportedCodeLanguage {
            val f = fileName.trim().lowercase(Locale.ROOT)
            val p = contentOrPrompt.lowercase(Locale.ROOT)
            return when {
                f.endsWith(".kt") || f.endsWith(".kts") -> KOTLIN
                f.endsWith(".java") -> JAVA
                f.endsWith(".tsx") || f.endsWith(".ts") -> TYPESCRIPT
                f.endsWith(".js") || f.endsWith(".jsx") -> JAVASCRIPT
                f.endsWith(".py") -> PYTHON
                f.endsWith(".cpp") || f.endsWith(".cc") || f.endsWith(".hpp") -> CPP
                f.endsWith(".c") || f.endsWith(".h") -> C
                f.endsWith(".cs") -> CSHARP
                f.endsWith(".go") -> GO
                f.endsWith(".rs") -> RUST
                f.endsWith(".swift") -> SWIFT
                f.endsWith(".dart") -> DART
                f.endsWith(".php") -> PHP
                f.endsWith(".sql") -> SQL
                f.endsWith(".html") || f.endsWith(".css") -> HTML_CSS
                f.endsWith(".json") || f.endsWith(".yaml") || f.endsWith(".yml") || f.endsWith(".xml") -> JSON_YAML_XML
                f.endsWith(".sh") || f.endsWith(".bash") -> BASH
                p.contains("kotlin") || p.contains("compose") || p.contains("android") -> KOTLIN
                p.contains("typescript") -> TYPESCRIPT
                p.contains("javascript") || p.contains("react") || p.contains("node") -> JAVASCRIPT
                p.contains("python") || p.contains("fastapi") || p.contains("django") -> PYTHON
                p.contains("flutter") || p.contains("dart") -> DART
                p.contains("rust") -> RUST
                p.contains("golang") || p.contains(" go ") -> GO
                p.contains("swift") || p.contains("ios") -> SWIFT
                p.contains("c++") || p.contains("cpp") -> CPP
                p.contains("c#") || p.contains("csharp") || p.contains("dotnet") -> CSHARP
                p.contains("php") || p.contains("laravel") -> PHP
                p.contains("sql") || p.contains("postgres") || p.contains("sqlite") -> SQL
                p.contains("html") || p.contains("css") || p.contains("website") || p.contains("ওয়েব") -> HTML_CSS
                p.contains("bash") || p.contains("shell") -> BASH
                p.contains("json") || p.contains("yaml") || p.contains("xml") -> JSON_YAML_XML
                else -> KOTLIN
            }
        }
    }
}

data class HomeDeveloperLiveState(
    val selfDevelopmentEnabled: Boolean = false,
    val status: DevWorkStatus = DevWorkStatus.IDLE,
    val phase: DevPhase = DevPhase.IDLE,
    val indicatorState: DevIndicatorState = DevIndicatorState.GREY_IDLE,
    val activeTaskId: String = "",
    val activeTaskGoal: String = "",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val progressPercent: Int = 0, // Always 0 when idle or no real completed steps
    val currentFileOrTarget: String = "",
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
    val failedSteps: List<String> = emptyList(),
    val pauseReason: String = "",
    val lastError: String = "",
    val testStatusSummary: String = "কোনো ডেভেলপমেন্ট টেস্ট এখনো চলেনি",
    val securityStatusSummary: String = "সিকিউরিটি ও Google Play নীতিমালা সক্রিয়",
    val buildStatusSummary: String = "নেটিভ কোড প্যাচ চালু করতে Build + Install প্রয়োজন",
    val capabilityCoveragePercent: Int = 0,
    val projectHealthScore: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
