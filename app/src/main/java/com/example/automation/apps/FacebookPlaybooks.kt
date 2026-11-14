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
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    fun getAll(): List<Playbook> = listOf(OPEN_FEED, OPEN_PROFILE, COMPOSE_POST, POST_TEXT)
}
