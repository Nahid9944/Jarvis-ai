package com.example.automation.apps

import com.example.automation.*

object YouTubePlaybooks {

    val SEARCH_PLAY = Playbook(
        id = "yt.search_play",
        titleBn = "ইউটিউবে গান/ভিডিও সার্চ করে চালানো",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.SAFE_NAVIGATION,
        params = listOf(
            ParamSpec("query", ParamType.STRING, required = true)
        ),
        steps = listOf(
            Step.Launch("vnd.youtube://results?search_query={query}"),
            Step.WaitFor(
                Selector(
                    by = By.ID,
                    value = "com.google.android.youtube:id/results",
                    alts = listOf("results", "com.google.android.youtube:id/video_list", "android.widget.RecyclerView")
                ),
                timeoutMs = 6000
            ),
            Step.Tap(
                Selector(
                    by = By.ID,
                    value = "com.google.android.youtube:id/thumbnail",
                    alts = listOf("com.google.android.youtube:id/video_info_view", "com.google.android.youtube:id/title", "android.view.ViewGroup")
                )
            ),
            Step.Sleep(900)
        ),
        verify = VerifySpec(type = VerifyType.MEDIA_PLAYING)
    )

    val PLAY_VIDEO = Playbook(
        id = "yt.play_video",
        titleBn = "ইউটিউব ভিডিও চালানো",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.SAFE_NAVIGATION,
        params = listOf(
            ParamSpec("videoId", ParamType.STRING, required = true)
        ),
        steps = listOf(
            Step.Launch("vnd.youtube://watch?v={videoId}"),
            Step.Sleep(900)
        ),
        verify = VerifySpec(type = VerifyType.MEDIA_PLAYING)
    )

    val PAUSE = Playbook(
        id = "yt.pause",
        titleBn = "ইউটিউব ভিডিও পজ করা",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.MediaAction("PAUSE")
        ),
        verify = VerifySpec(type = VerifyType.CUSTOM)
    )

    val RESUME = Playbook(
        id = "yt.resume",
        titleBn = "ইউটিউব ভিডিও রিজ্যুম করা",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.MediaAction("PLAY")
        ),
        verify = VerifySpec(type = VerifyType.MEDIA_PLAYING)
    )

    val NEXT = Playbook(
        id = "yt.next",
        titleBn = "ইউটিউব পরবর্তী ভিডিও",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.MediaAction("NEXT")
        ),
        verify = VerifySpec(type = VerifyType.CUSTOM)
    )

    val PREVIOUS = Playbook(
        id = "yt.previous",
        titleBn = "ইউটিউব পূর্ববর্তী ভিডিও",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.SAFE_NAVIGATION,
        steps = listOf(
            Step.MediaAction("PREVIOUS")
        ),
        verify = VerifySpec(type = VerifyType.CUSTOM)
    )

    val FULLSCREEN = Playbook(
        id = "yt.fullscreen",
        titleBn = "ইউটিউব ফুলস্ক্রিন টগল",
        appPackage = "com.google.android.youtube",
        risk = RiskLevel.INTERACTION,
        steps = listOf(
            Step.Tap(
                Selector(
                    by = By.DESC,
                    value = "Fullscreen",
                    alts = listOf("Full screen", "Enter fullscreen", "পূর্ণ স্ক্রিন", "com.google.android.youtube:id/fullscreen_button")
                )
            )
        ),
        verify = VerifySpec(type = VerifyType.SCREEN_HASH_CHANGED)
    )

    fun getAll(): List<Playbook> = listOf(SEARCH_PLAY, PLAY_VIDEO, PAUSE, RESUME, NEXT, PREVIOUS, FULLSCREEN)
}
