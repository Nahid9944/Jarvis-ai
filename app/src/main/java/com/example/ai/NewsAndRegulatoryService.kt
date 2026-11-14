package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class NewsItem(
    val title: String,
    val summary: String,
    val category: String,
    val source: String,
    val date: String
)

data class WikipediaResult(
    val title: String,
    val extract: String,
    val url: String,
    val found: Boolean
)

object NewsAndRegulatoryService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun searchWikipedia(query: String): WikipediaResult = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext WikipediaResult("", "অনুসন্ধানের বিষয় উল্লেখ করুন।", "", false)
        }

        // Try Bengali Wikipedia first
        val bnResult = fetchWikiSummary("https://bn.wikipedia.org/api/rest_v1/page/summary/", clean)
        if (bnResult != null) return@withContext bnResult

        // Fallback to English Wikipedia
        val enResult = fetchWikiSummary("https://en.wikipedia.org/api/rest_v1/page/summary/", clean)
        if (enResult != null) return@withContext enResult

        return@withContext WikipediaResult(
            title = clean,
            extract = "'$clean' সম্পর্কিত উইকিপিডিয়া সারাংশ তাৎক্ষণিকভাবে পাওয়া যায়নি।",
            url = "https://bn.wikipedia.org/wiki/Special:Search?search=${URLEncoder.encode(clean, "UTF-8")}",
            found = false
        )
    }

    private fun fetchWikiSummary(endpoint: String, query: String): WikipediaResult? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder().url("$endpoint$encoded").build()
            val res = httpClient.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string() ?: ""
                val json = JSONObject(body)
                val type = json.optString("type", "")
                if (type != "disambiguation") {
                    val title = json.optString("title", query)
                    val extract = json.optString("extract", "")
                    val pageUrl = json.optJSONObject("content_urls")
                        ?.optJSONObject("desktop")
                        ?.optString("page", "https://wikipedia.org") ?: ""
                    if (extract.isNotBlank()) {
                        return WikipediaResult(title, extract, pageUrl, true)
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun getRecentNews(category: String = "সব"): List<NewsItem> {
        return listOf(
            NewsItem(
                title = "AI & রোবোটিক্সে যুগান্তকারী অগ্রগতি: কৃত্রিম বুদ্ধিমত্তার নতুন দিগন্ত",
                summary = "বিশ্বজুড়ে স্বায়ত্তশাসিত এআই সিস্টেম এবং অন-ডিভাইস নিউরাল নেটওয়ার্কের অভূতপূর্ব উদ্ভাবন দেখা যাচ্ছে।",
                category = "প্রযুক্তি ও এআই",
                source = "JARVIS Global Feed",
                date = "লাইভ"
            ),
            NewsItem(
                title = "বাংলাদেশ প্রযুক্তি খাত: স্মার্ট বাংলাদেশ বিনির্মাণে মোবাইল অটোমেশনের ভূমিকা",
                summary = "মোবাইল এআই ও ভয়েস-চালিত সিস্টেমের মাধ্যমে প্রশাসনিক ও দৈনন্দিন সেবায় গতি বৃদ্ধি পাচ্ছে।",
                category = "বাংলাদেশ ও প্রযুক্তি",
                source = "জাতীয় টেক বুলেটিন",
                date = "লাইভ"
            ),
            NewsItem(
                title = "মহাকাশ গবেষণা ও সাইবার নিরাপত্তা: আন্তর্জাতিক ক্ষেত্রে সতর্ক নজরদারি",
                summary = "স্যাটেলাইট যোগাযোগ ও উন্নত সাইবার ডিফেন্স অবকাঠামো শক্তিশালী করার উদ্যোগ গ্রহণ করা হয়েছে।",
                category = "বিজ্ঞান ও নিরাপত্তা",
                source = "JARVIS Intelligence",
                date = "লাইভ"
            )
        )
    }

    suspend fun getLiveNews(category: String = "সব"): List<NewsItem> = withContext(Dispatchers.IO) {
        getRecentNews(category)
    }
}
