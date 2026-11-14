package com.example.automation

enum class RiskLevel {
    READ_ONLY,
    SAFE_NAVIGATION,
    INTERACTION,
    EXTERNAL_COMM,   // sending message, making call, public post -> HumanGate required
    DESTRUCTIVE      // delete, block, clear data -> double confirmation
}

enum class ParamType {
    STRING,
    NUMBER,
    BOOLEAN,
    URI
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
    val default: String? = null
)

enum class Key {
    BACK,
    HOME,
    ENTER,
    DELETE,
    TAB
}

enum class Dir {
    UP,
    DOWN,
    LEFT,
    RIGHT
}

enum class NodeState {
    EXISTS,
    GONE,
    CHECKED,
    UNCHECKED,
    FOCUSED,
    ENABLED
}

enum class VerifyType {
    NODE_EXISTS,
    NODE_GONE,
    SCREEN_HASH_CHANGED,
    LAST_OUTGOING_MESSAGE_EQUALS,
    NOTIFICATION_REPLY_ACCEPTED,
    MEDIA_PLAYING,
    PLAYBACK_TITLE_CONTAINS,
    CUSTOM
}

data class VerifySpec(
    val type: VerifyType,
    val expected: String? = null,
    val selector: Selector? = null,
    val timeoutMs: Long = 3000
)

sealed class Step {
    data class Launch(val uri: String) : Step()
    data class WaitFor(val selector: Selector, val timeoutMs: Long = 4000) : Step()
    data class Tap(val selector: Selector, val dangerous: Boolean = false) : Step()
    data class LongPress(val selector: Selector, val ms: Long = 700) : Step()
    data class SetText(val selector: Selector, val text: String, val clearFirst: Boolean = true) : Step()
    data class PressKey(val key: Key) : Step()
    data class Scroll(val direction: Dir, val until: Selector? = null, val maxSwipes: Int = 8) : Step()
    data class Assert(val selector: Selector, val state: NodeState) : Step()
    data class ReadText(val selector: Selector, val into: String) : Step()
    data class Sleep(val ms: Long) : Step()
    data class AskUser(val questionKey: String) : Step()
    data class VisionCheck(val question: String) : Step()
    data class NotificationReply(val packageFilter: String, val sender: String? = null, val text: String) : Step()
    data class MediaAction(val action: String, val param: Long? = null) : Step()
}

data class Playbook(
    val id: String,                         // e.g. "wa.send_text", "yt.search_play"
    val titleBn: String,                    // e.g. "হোয়াটসঅ্যাপে টেক্সট মেসেজ পাঠানো"
    val appPackage: String,
    val minVersionCode: Long = 0,
    val maxTestedVersionCode: Long = Long.MAX_VALUE,
    val risk: RiskLevel,
    val params: List<ParamSpec> = emptyList(),
    val steps: List<Step>,
    val verify: VerifySpec,
    val onFail: List<Step> = emptyList()
)

data class StepExecutionRecord(
    val stepIndex: Int,
    val stepDescription: String,
    val success: Boolean,
    val errorMessage: String? = null,
    val executionTimeMs: Long
)

data class PlaybookRunResult(
    val playbookId: String,
    val success: Boolean,
    val code: String,                       // "SUCCESS", "NODE_NOT_FOUND", "CONFIRMATION_DECLINED", etc.
    val messageBn: String,
    val stepRecords: List<StepExecutionRecord> = emptyList(),
    val verified: Boolean = false,
    val executionTimeMs: Long = 0
)
