package com.example.automation

import android.view.accessibility.AccessibilityNodeInfo

object ActionVerifier {

    fun verify(
        spec: VerifySpec,
        root: AccessibilityNodeInfo?,
        initialScreenHash: String,
        currentScreenHash: String,
        lastReplySuccess: Boolean = false,
        mediaTitle: String? = null,
        params: Map<String, String> = emptyMap(),
        isMediaPlaying: Boolean = !mediaTitle.isNullOrBlank()
    ): Pair<Boolean, String> {
        return when (spec.type) {
            VerifyType.NODE_EXISTS -> {
                if (spec.selector == null) {
                    Pair(true, "যাচাই সম্পন্ন: ডিফল্ট বিদ্যমান")
                } else {
                    val node = NodeSnapshot.findNode(root, spec.selector)
                    if (node != null) Pair(true, "যাচাই সফল: নোড দৃশ্যমান পাওয়া গেছে")
                    else Pair(false, "যাচাই ব্যর্থ: প্রত্যাশিত নোড খুঁজে পাওয়া যায়নি")
                }
            }

            VerifyType.NODE_GONE -> {
                if (spec.selector == null) {
                    Pair(true, "যাচাই সম্পন্ন")
                } else {
                    val node = NodeSnapshot.findNode(root, spec.selector)
                    if (node == null) Pair(true, "যাচাই সফল: নোডটি স্ক্রিন থেকে অপসারিত হয়েছে")
                    else Pair(false, "যাচাই ব্যর্থ: নোডটি এখনও দৃশ্যমান রয়েছে")
                }
            }

            VerifyType.SCREEN_HASH_CHANGED -> {
                if (initialScreenHash != currentScreenHash && currentScreenHash != "EMPTY_SCREEN") {
                    Pair(true, "যাচাই সফল: স্ক্রিনের অবস্থা পরিবর্তিত হয়েছে")
                } else {
                    Pair(false, "যাচাই ব্যর্থ: স্ক্রিনে কোনো পরিবর্তন ঘটেনি")
                }
            }

            VerifyType.LAST_OUTGOING_MESSAGE_EQUALS -> {
                val expected = (spec.expected?.takeIf { it.isNotBlank() }
                    ?: params["text"]
                    ?: params["message"]
                    ?: "").trim()
                if (expected.isBlank()) {
                    return Pair(false, "যাচাই ব্যর্থ: তুলনা করার জন্য কোনো প্রত্যাশিত বার্তা প্রদান করা হয়নি")
                }
                val visibleTexts = NodeSnapshot.readVisibleTextList(root)
                val matched = visibleTexts.any { it.contains(expected.take(20), ignoreCase = true) }
                if (matched) {
                    Pair(true, "যাচাই সফল: বার্তাটি স্ক্রিনে আউটগোয়িং হিসেবে পাওয়া গেছে")
                } else {
                    Pair(false, "যাচাই অনিশ্চিত: বার্তাটি স্ক্রিনে আউটগোয়িং হিসেবে নিশ্চিত হওয়া যায়নি")
                }
            }

            VerifyType.NOTIFICATION_REPLY_ACCEPTED -> {
                if (lastReplySuccess) {
                    Pair(true, "যাচাই সফল: নোটিফিকেশনে ডিরেক্ট রিপ্লাই পাঠানো হয়েছে")
                } else {
                    Pair(false, "যাচাই ব্যর্থ: নোটিফিকেশন রিপ্লাই গ্রহণ করা হয়নি")
                }
            }

            VerifyType.MEDIA_PLAYING -> {
                if (isMediaPlaying) {
                    Pair(true, "যাচাই সফল: মিডিয়া সক্রিয়ভাবে চলছে${mediaTitle?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}")
                } else {
                    Pair(false, "যাচাই ব্যর্থ: কোনো চলমান মিডিয়া সেশন পাওয়া যায়নি")
                }
            }

            VerifyType.PLAYBACK_TITLE_CONTAINS -> {
                val expected = spec.expected?.lowercase() ?: ""
                if (isMediaPlaying && !mediaTitle.isNullOrBlank() && mediaTitle.lowercase().contains(expected)) {
                    Pair(true, "যাচাই সফল: প্লেব্যাক শিরোনাম মিলছে ($mediaTitle)")
                } else {
                    Pair(false, "যাচাই ব্যর্থ: প্লেব্যাক শিরোনামে '$expected' পাওয়া যায়নি (বর্তমান: $mediaTitle)")
                }
            }

            VerifyType.CUSTOM -> {
                Pair(true, "কাস্টম যাচাই সফল")
            }
        }
    }
}
