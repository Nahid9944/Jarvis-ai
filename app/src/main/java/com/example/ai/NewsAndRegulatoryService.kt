package com.example.ai

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class NewsItem(
    val title: String,
    val summary: String,
    val category: String,
    val source: String,
    val date: String,
    val url: String = ""
)

data class WikipediaResult(
    val title: String,
    val extract: String,
    val url: String,
    val found: Boolean
)

object NewsAndRegulatoryService {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    internal var httpTextFetcher: (String) -> String? = { targetUrl ->
        try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 Nahid-Jarvis-AI")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) res.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private val cacheLock = Any()
    private val cachedByCategory = mutableMapOf<String, Pair<Long, List<NewsItem>>>()

    suspend fun searchWikipedia(query: String): WikipediaResult = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) {
            return@withContext WikipediaResult("", "অনুসন্ধানের বিষয় উল্লেখ করুন।", "", false)
        }

        val bnResult = fetchWikiSummary("https://bn.wikipedia.org/api/rest_v1/page/summary/", clean)
        if (bnResult != null) return@withContext bnResult

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
            val body = httpTextFetcher("$endpoint$encoded") ?: return null
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
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synchronous snapshot of cached news only (never returns fake static placeholder items).
     */
    fun getRecentNews(category: String = "সব"): List<NewsItem> {
        val key = category.trim().ifBlank { "সব" }
        synchronized(cacheLock) {
            val entry = cachedByCategory[key]
            if (entry != null && (System.currentTimeMillis() - entry.first) <= CACHE_TTL_MS) {
                return entry.second
            }
        }
        return emptyList()
    }

    /**
     * Fetches real public headlines from BBC Bangla RSS, Google News BD RSS,
     * and Hacker News JSON API (fallback / Tech-AI category).
     * Caches for 10 minutes and returns emptyList() honestly when offline.
     */
    suspend fun getLiveNews(
        category: String = "সব",
        forceRefresh: Boolean = false
    ): List<NewsItem> = withContext(Dispatchers.IO) {
        val key = category.trim().ifBlank { "সব" }
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(cacheLock) {
                val entry = cachedByCategory[key]
                if (entry != null && (now - entry.first) <= CACHE_TTL_MS && entry.second.isNotEmpty()) {
                    return@withContext entry.second
                }
            }
        }

        val collected = mutableListOf<NewsItem>()
        val lowerCat = key.lowercase(Locale.ROOT)
        val wantTech = lowerCat.contains("tech") || lowerCat.contains("ai") || lowerCat.contains("প্রযুক্তি")

        if (!wantTech) {
            httpTextFetcher("https://feeds.bbci.co.uk/bengali/rss.xml")?.let { xml ->
                collected.addAll(parseRssXml(xml, defaultSource = "BBC বাংলা", defaultCategory = "বাংলাদেশ ও বিশ্ব"))
            }
            if (collected.size < 4) {
                httpTextFetcher("https://news.google.com/rss?hl=bn&gl=BD&ceid=BD:bn")?.let { xml ->
                    collected.addAll(parseRssXml(xml, defaultSource = "Google News BD", defaultCategory = "জাতীয় ও বিশ্ব"))
                }
            }
        }

        if (wantTech || collected.isEmpty()) {
            collected.addAll(fetchHackerNewsTopStories(limit = 5))
        }

        val finalItems = collected
            .distinctBy { it.title.trim().lowercase(Locale.ROOT) }
            .take(10)

        synchronized(cacheLock) {
            if (finalItems.isNotEmpty()) {
                cachedByCategory[key] = Pair(now, finalItems)
            }
        }
        finalItems
    }

    internal fun parseRssXml(
        xmlContent: String,
        defaultSource: String,
        defaultCategory: String
    ): List<NewsItem> {
        if (xmlContent.isBlank()) return emptyList()
        val items = mutableListOf<NewsItem>()
        try {
            val parser: XmlPullParser = try {
                Xml.newPullParser()
            } catch (_: Exception) {
                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = false
                factory.newPullParser()
            }
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var insideItem = false
            var currentTag = ""
            var title = ""
            var description = ""
            var link = ""
            var pubDate = ""
            var source = defaultSource

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name?.lowercase(Locale.ROOT) ?: ""
                        currentTag = tag
                        if (tag == "item" || tag == "entry") {
                            insideItem = true
                            title = ""
                            description = ""
                            link = ""
                            pubDate = ""
                            source = defaultSource
                        } else if (insideItem && tag == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank()) {
                                link = href.trim()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideItem) {
                            val text = parser.text ?: ""
                            when (currentTag) {
                                "title" -> title += text
                                "description", "summary" -> description += text
                                "link" -> if (link.isBlank()) link += text
                                "pubdate", "published", "updated" -> pubDate += text
                                "source" -> source += if (source == defaultSource) "" else text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name?.lowercase(Locale.ROOT) ?: ""
                        if (tag == "item" || tag == "entry") {
                            val cleanTitle = cleanHtmlAndEntities(title).trim()
                            val cleanDesc = cleanHtmlAndEntities(description).trim()
                                .ifBlank { cleanTitle }
                                .take(240)
                            val cleanDate = pubDate.trim().take(25).ifBlank { "সাম্প্রতিক" }
                            if (cleanTitle.isNotBlank()) {
                                items.add(
                                    NewsItem(
                                        title = cleanTitle,
                                        summary = cleanDesc,
                                        category = defaultCategory,
                                        source = defaultSource,
                                        date = cleanDate,
                                        url = link.trim()
                                    )
                                )
                            }
                            insideItem = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
        }
        return items
    }

    private fun fetchHackerNewsTopStories(limit: Int): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        return try {
            val idsBody = httpTextFetcher("https://hacker-news.firebaseio.com/v0/topstories.json") ?: return emptyList()
            val ids = JSONArray(idsBody)
            val count = ids.length().coerceAtMost(limit)
            for (i in 0 until count) {
                val id = ids.optLong(i, -1L)
                if (id <= 0L) continue
                val itemBody = httpTextFetcher("https://hacker-news.firebaseio.com/v0/item/$id.json") ?: continue
                val obj = JSONObject(itemBody)
                val title = obj.optString("title", "").trim()
                val storyUrl = obj.optString("url", "https://news.ycombinator.com/item?id=$id").trim()
                val score = obj.optInt("score", 0)
                if (title.isNotBlank()) {
                    out.add(
                        NewsItem(
                            title = title,
                            summary = "Hacker News শীর্ষ প্রযুক্তি সংবাদ (স্কোর: $score)",
                            category = "প্রযুক্তি ও এআই",
                            source = "Hacker News",
                            date = "সর্বশেষ",
                            url = storyUrl
                        )
                    )
                }
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    private fun cleanHtmlAndEntities(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    internal fun clearCacheForTest() {
        synchronized(cacheLock) {
            cachedByCategory.clear()
        }
    }
}

/**
 * Lightweight thread-safe event bus connecting voice/orchestrator tool results
 * (such as opening the Information/Headlines Panel or toggling inline HUD camera)
 * cleanly to the Jetpack Compose HUD.
 */
object JarvisHudEventBus {
    private val _openHeadlinesEvents = kotlinx.coroutines.flow.MutableSharedFlow<List<NewsItem>>(extraBufferCapacity = 4)
    val openHeadlinesEvents: kotlinx.coroutines.flow.SharedFlow<List<NewsItem>> = _openHeadlinesEvents

    private val _closeHeadlinesEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val closeHeadlinesEvents: kotlinx.coroutines.flow.SharedFlow<Unit> = _closeHeadlinesEvents

    private val _inlineCameraToggleEvents = kotlinx.coroutines.flow.MutableSharedFlow<Boolean?>(extraBufferCapacity = 4)
    val inlineCameraToggleEvents: kotlinx.coroutines.flow.SharedFlow<Boolean?> = _inlineCameraToggleEvents

    private val _cameraLensChangedEvents = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val cameraLensChangedEvents: kotlinx.coroutines.flow.SharedFlow<Int> = _cameraLensChangedEvents

    fun emitOpenHeadlines(items: List<NewsItem>) {
        _openHeadlinesEvents.tryEmit(items)
    }

    fun emitCloseHeadlines() {
        _closeHeadlinesEvents.tryEmit(Unit)
    }

    fun emitToggleInlineCamera(enabled: Boolean?) {
        _inlineCameraToggleEvents.tryEmit(enabled)
    }

    fun emitCameraLensChanged(lensFacing: Int) {
        _cameraLensChangedEvents.tryEmit(lensFacing)
    }
}
