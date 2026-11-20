package com.example.automation.apps

import com.example.automation.*
import java.net.URLEncoder

object WhatsAppPlaybooks {

    val OPEN_CHAT = Playbook(
        id = "wa.open_chat",
        titleBn = "হোয়াটসঅ্যাপে চ্যাট ওপেন করা",
        appPackage = "com.whatsapp",
        risk = RiskLevel.SAFE_NAVIGATION,
        params = listOf(
            ParamSpec("phone", ParamType.STRING, required = true)
        ),
        steps = listOf(
            Step.Launch("https://wa.me/{phone}"),
            Step.WaitFor(
                Selector(
                    by = By.ID,
                    value = "com.whatsapp:id/entry",
                    alts = listOf("Type a message", "একটি বার্তা লিখুন", "com.whatsapp:id/conversation_entry")
                ),
                timeoutMs = 6000
            )
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    val SEND_TEXT = Playbook(
        id = "wa.send_text",
        titleBn = "হোয়াটসঅ্যাপে মেসেজ পাঠানো",
        appPackage = "com.whatsapp",
        risk = RiskLevel.EXTERNAL_COMM,
        params = listOf(
            ParamSpec("phone", ParamType.STRING, required = true),
            ParamSpec("text", ParamType.STRING, required = true),
            ParamSpec("contactName", ParamType.STRING, required = false)
        ),
        steps = listOf(
            // 1. Deep link prefill
            Step.Launch("https://wa.me/{phone}?text={text}"),
            // 2. Wait for message entry field
            Step.WaitFor(
                Selector(
                    by = By.ID,
                    value = "com.whatsapp:id/entry",
                    alts = listOf("Type a message", "একটি বার্তা লিখুন", "com.whatsapp:id/conversation_entry")
                ),
                timeoutMs = 6000
            ),
            // 3. Assert prefilled text is present
            Step.Assert(
                Selector(
                    by = By.ID,
                    value = "com.whatsapp:id/entry",
                    alts = listOf("com.whatsapp:id/conversation_entry")
                ),
                NodeState.EXISTS
            ),
            // 4. Tap Send button (dangerous = true triggers HumanGate)
            Step.Tap(
                Selector(
                    by = By.ID,
                    value = "com.whatsapp:id/send",
                    alts = listOf("Send", "পাঠান", "পাঠিয়ে দিন", "com.whatsapp:id/send_container")
                ),
                dangerous = true
            )
        ),
        verify = VerifySpec(type = VerifyType.LAST_OUTGOING_MESSAGE_EQUALS),
        onFail = listOf(Step.PressKey(Key.BACK))
    )

    val REPLY_LATEST = Playbook(
        id = "wa.reply_latest",
        titleBn = "হোয়াটসঅ্যাপ নোটিফিকেশনে সরাসরি উত্তর দেওয়া",
        appPackage = "com.whatsapp",
        risk = RiskLevel.EXTERNAL_COMM,
        params = listOf(
            ParamSpec("text", ParamType.STRING, required = true),
            ParamSpec("from", ParamType.STRING, required = false)
        ),
        steps = listOf(
            Step.NotificationReply(
                packageFilter = "com.whatsapp",
                sender = "{from}",
                text = "{text}"
            )
        ),
        verify = VerifySpec(type = VerifyType.NOTIFICATION_REPLY_ACCEPTED)
    )

    val READ_LATEST = Playbook(
        id = "wa.read_latest",
        titleBn = "সর্বশেষ হোয়াটসঅ্যাপ নোটিফিকেশন পড়া",
        appPackage = "com.whatsapp",
        risk = RiskLevel.READ_ONLY,
        steps = listOf(
            Step.Sleep(200)
        ),
        verify = VerifySpec(type = VerifyType.CUSTOM)
    )

    val VOICE_CALL = Playbook(
        id = "wa.voice_call",
        titleBn = "হোয়াটসঅ্যাপ ভয়েস কল করা",
        appPackage = "com.whatsapp",
        risk = RiskLevel.EXTERNAL_COMM,
        params = listOf(
            ParamSpec("phone", ParamType.STRING, required = true),
            ParamSpec("contactName", ParamType.STRING, required = false)
        ),
        steps = listOf(
            Step.Launch("https://wa.me/{phone}"),
            Step.WaitFor(
                Selector(
                    by = By.DESC,
                    value = "Voice call",
                    alts = listOf("ভয়েস কল", "কল করুন", "Call", "com.whatsapp:id/voice_call")
                ),
                timeoutMs = 6000
            ),
            Step.Tap(
                Selector(
                    by = By.DESC,
                    value = "Voice call",
                    alts = listOf("ভয়েস কল", "কল করুন", "Call", "com.whatsapp:id/voice_call")
                ),
                dangerous = true
            )
        ),
        verify = VerifySpec(
            type = VerifyType.NODE_EXISTS,
            selector = Selector(
                by = By.ID,
                value = "com.whatsapp:id/end_call_btn",
                alts = listOf("End call", "কল শেষ করুন", "Ringing", "Calling", "Connecting", "Mute", "Speaker")
            )
        )
    )

    val VIDEO_CALL = Playbook(
        id = "wa.video_call",
        titleBn = "হোয়াটসঅ্যাপ ভিডিও কল করা",
        appPackage = "com.whatsapp",
        risk = RiskLevel.EXTERNAL_COMM,
        params = listOf(
            ParamSpec("phone", ParamType.STRING, required = true),
            ParamSpec("contactName", ParamType.STRING, required = false)
        ),
        steps = listOf(
            Step.Launch("https://wa.me/{phone}"),
            Step.WaitFor(
                Selector(
                    by = By.DESC,
                    value = "Video call",
                    alts = listOf("ভিডিও কল", "Video", "com.whatsapp:id/video_call")
                ),
                timeoutMs = 6000
            ),
            Step.Tap(
                Selector(
                    by = By.DESC,
                    value = "Video call",
                    alts = listOf("ভিডিও কল", "Video", "com.whatsapp:id/video_call")
                ),
                dangerous = true
            )
        ),
        verify = VerifySpec(
            type = VerifyType.NODE_EXISTS,
            selector = Selector(
                by = By.ID,
                value = "com.whatsapp:id/end_call_btn",
                alts = listOf("End call", "কল শেষ করুন", "Ringing", "Calling", "Connecting", "Mute", "Speaker")
            )
        )
    )

    val END_CALL = Playbook(
        id = "wa.end_call",
        titleBn = "হোয়াটসঅ্যাপ কল শেষ করা",
        appPackage = "com.whatsapp",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.Tap(
                Selector(
                    by = By.ID,
                    value = "com.whatsapp:id/end_call_btn",
                    alts = listOf("End call", "কল শেষ করুন", "Hang up", "Leave call", "com.whatsapp:id/call_end")
                )
            )
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    fun getAll(): List<Playbook> = listOf(OPEN_CHAT, SEND_TEXT, REPLY_LATEST, READ_LATEST, VOICE_CALL, VIDEO_CALL, END_CALL)
}
