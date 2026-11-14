package com.example.automation.apps

import com.example.automation.*

object DemoPlaybooks {

    val OPEN_SETTINGS_BATTERY = Playbook(
        id = "demo.open_settings_battery",
        titleBn = "সিস্টেম সেটিংস ও ব্যাটারি স্ট্যাটাস খোলা",
        appPackage = "com.android.settings",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.Launch("android.settings.SETTINGS"),
            Step.WaitFor(
                Selector(
                    by = By.CLASS_INDEX,
                    value = "android.widget.ScrollView",
                    alts = listOf("android.widget.ListView", "android.widget.RecyclerView", "Settings", "সেটিংস")
                ),
                timeoutMs = 4000
            ),
            Step.Scroll(
                direction = Dir.DOWN,
                until = Selector(
                    by = By.TEXT,
                    value = "Battery",
                    alts = listOf("ব্যাটারি", "Power", "Battery & performance")
                ),
                maxSwipes = 6
            ),
            Step.Tap(
                Selector(
                    by = By.TEXT,
                    value = "Battery",
                    alts = listOf("ব্যাটারি", "Power")
                )
            )
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    fun getAll(): List<Playbook> = listOf(OPEN_SETTINGS_BATTERY)
}
