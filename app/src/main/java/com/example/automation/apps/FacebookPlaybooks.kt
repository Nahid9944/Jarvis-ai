package com.example.automation.apps

import com.example.automation.*

object FacebookPlaybooks {

    val OPEN_FEED = Playbook(
        id = "fb.open_feed",
        titleBn = "ফেসবুক ফিড ওপেন করা",
        appPackage = "com.facebook.katana",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.Launch("fb://feed")
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    val OPEN_PROFILE = Playbook(
        id = "fb.open_profile",
        titleBn = "ফেসবুক প্রোফাইল ওপেন করা",
        appPackage = "com.facebook.katana",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.Launch("fb://profile")
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    val COMPOSE_POST = Playbook(
        id = "fb.compose_post",
        titleBn = "ফেসবুক পোস্ট কম্পোজার খোলা",
        appPackage = "com.facebook.katana",
        risk = RiskLevel.SAFE_NAVIGATION,
        params = listOf(
            ParamSpec("text", ParamType.STRING, required = true)
        ),
        steps = listOf(
            Step.Launch("fb://composer?text={text}")
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    val POST_TEXT = Playbook(
        id = "fb.post_text",
        titleBn = "ফেসবুকে স্ট্যাটাস পোস্ট করা",
        appPackage = "com.facebook.katana",
        risk = RiskLevel.EXTERNAL_COMM,
        params = listOf(
            ParamSpec("text", ParamType.STRING, required = true)
        ),
        steps = listOf(
            Step.Launch("fb://composer?text={text}"),
            Step.WaitFor(
                Selector(
                    by = By.TEXT,
                    value = "Post",
                    alts = listOf("POST", "পোস্ট", "Share", "শেয়ার")
                ),
                timeoutMs = 5000
            ),
            Step.Tap(
                Selector(
                    by = By.TEXT,
                    value = "Post",
                    alts = listOf("POST", "পোস্ট", "Share", "শেয়ার")
                ),
                dangerous = true
            )
        ),
        verify = VerifySpec(
            type = VerifyType.NODE_GONE,
            selector = Selector(
                by = By.TEXT,
                value = "Post",
                alts = listOf("POST", "পোস্ট", "Share", "শেয়ার")
            )
        )
    )

    val LIKE_POST = Playbook(
        id = "fb.like_post",
        titleBn = "ফেসবুক পোস্টে লাইক দেওয়া",
        appPackage = "com.facebook.katana",
        risk = RiskLevel.INTERACTION,
        steps = listOf(
            Step.Tap(
                Selector(
                    by = By.TEXT,
                    value = "Like",
                    alts = listOf("লাইক", "React", "com.facebook.katana:id/feed_feedback_like")
                )
            )
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    val COMMENT_POST = Playbook(
        id = "fb.comment_post",
        titleBn = "ফেসবুক পোস্টে কমেন্ট করা",
        appPackage = "com.facebook.katana",
        risk = RiskLevel.EXTERNAL_COMM,
        params = listOf(
            ParamSpec("text", ParamType.STRING, required = true)
        ),
        steps = listOf(
            Step.Tap(
                Selector(
                    by = By.TEXT,
                    value = "Comment",
                    alts = listOf("কমেন্ট", "মন্তব্য")
                )
            ),
            Step.WaitFor(
                Selector(
                    by = By.TEXT,
                    value = "Write a comment",
                    alts = listOf("Write a comment...", "একটি মন্তব্য লিখুন", "android.widget.EditText")
                ),
                timeoutMs = 4000
            ),
            Step.SetText(
                Selector(
                    by = By.TEXT,
                    value = "Write a comment",
                    alts = listOf("Write a comment...", "একটি মন্তব্য লিখুন", "android.widget.EditText")
                ),
                text = "{text}"
            ),
            Step.Tap(
                Selector(
                    by = By.DESC,
                    value = "Send",
                    alts = listOf("Post", "পাঠান", "পোস্ট")
                ),
                dangerous = true
            )
        ),
        verify = VerifySpec(type = VerifyType.LAST_OUTGOING_MESSAGE_EQUALS)
    )

    fun getAll(): List<Playbook> = listOf(OPEN_FEED, OPEN_PROFILE, COMPOSE_POST, POST_TEXT, LIKE_POST, COMMENT_POST)
}
