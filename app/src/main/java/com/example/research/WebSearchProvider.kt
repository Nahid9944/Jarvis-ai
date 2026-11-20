package com.example.research

import com.example.ai.NewsAndRegulatoryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun OkHttpClient.executeCancellable(request: Request): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val body = try { res.body?.string().orEmpty() } catch (_: Exception) { "" }
                    if (cont.isActive) cont.resume(Pair(res.code, body))
                }
            }
        })
    }

data class SearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceDomain: String,
    val publishedDate: String = "",
    val providerName: String,
    val verified: Boolean = true,
    val snippetOnly: Boolean = true,
    val contentExtracted: Boolean = false
)

data class VerifiedSearchResponse(
    val success: Boolean,
    val query: String,
    val results: List<SearchResultItem>,
    val summaryBengali: String,
    val providerUsed: String,
    val statusCodeLabel: String, // VERIFIED, NO_RESULTS, OFFLINE_LIMITED, FAILED
    val errorCode: String = ""
)

interface WebSearchProvider {
    val providerId: String
    val requiresApiKey: Boolean
    fun isConfigured(): Boolean
    suspend fun search(query: String, maxResults: Int = 5): Result<List<SearchResultItem>>
}

internal fun extractDomainFromUrl(url: String): String {
    return try {
        val host = java.net.URI(url).host.orEmpty()
        host.removePrefix("www.")
    } catch (_: Exception) {
        ""
    }
}

class BraveSearchProvider(
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = defaultSearchHttpClient,
    private val endpointUrl: String = "https://api.search.brave.com/res/v1/web/search"
) : WebSearchProvider {

    override val providerId: String = "BraveSearch"
    override val requiresApiKey: Boolean = true

    override fun isConfigured(): Boolean = apiKeyProvider().trim().isNotBlank()

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("BRAVE_API_KEY_NOT_CONFIGURED"))
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext Result.success(emptyList())
        }

        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val count = maxResults.coerceIn(1, 10)
            val req = Request.Builder()
                .url("$endpointUrl?q=$encoded&count=$count")
                .header("Accept", "application/json")
                .header("X-Subscription-Token", key)
                .get()
                .build()

            val (code, body) = httpClient.executeCancellable(req)
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Brave HTTP $code"))
            }
            val root = JSONObject(body)
            val webResults = root.optJSONObject("web")?.optJSONArray("results")
                ?: return@withContext Result.success(emptyList())
            val items = mutableListOf<SearchResultItem>()
            for (i in 0 until webResults.length().coerceAtMost(count)) {
                val obj = webResults.optJSONObject(i) ?: continue
                val title = obj.optString("title", "").trim()
                val url = obj.optString("url", "").trim()
                val desc = obj.optString("description", "").trim()
                val age = obj.optString("age", obj.optString("page_age", "")).trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    items.add(
                        SearchResultItem(
                            title = title,
                            url = url,
                            snippet = desc,
                            sourceDomain = extractDomainFromUrl(url),
                            publishedDate = age,
                            providerName = providerId,
                            verified = true
                        )
                    )
                }
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class TavilySearchProvider(
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = defaultSearchHttpClient,
    private val endpointUrl: String = "https://api.tavily.com/search"
) : WebSearchProvider {

    override val providerId: String = "TavilySearch"
    override val requiresApiKey: Boolean = true

    override fun isConfigured(): Boolean = apiKeyProvider().trim().isNotBlank()

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("TAVILY_API_KEY_NOT_CONFIGURED"))
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return@withContext Result.success(emptyList())
        }

        try {
            val payload = JSONObject().apply {
                put("api_key", key)
                put("query", cleanQuery)
                put("max_results", maxResults.coerceIn(1, 10))
                put("search_depth", "basic")
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url(endpointUrl)
                .post(payload)
                .build()

            val (code, body) = httpClient.executeCancellable(req)
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Tavily HTTP $code"))
            }
            val root = JSONObject(body)
            val arr = root.optJSONArray("results") ?: return@withContext Result.success(emptyList())
            val items = mutableListOf<SearchResultItem>()
            for (i in 0 until arr.length().coerceAtMost(maxResults)) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title", "").trim()
                val url = obj.optString("url", "").trim()
                val content = obj.optString("content", "").trim()
                val pubDate = obj.optString("published_date", "").trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    items.add(
                        SearchResultItem(
                            title = title,
                            url = url,
                            snippet = content.take(360),
                            sourceDomain = extractDomainFromUrl(url),
                            publishedDate = pubDate,
                            providerName = providerId,
                            verified = true
                        )
                    )
                }
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class PublicWikiAndRssProvider(
    private val httpClient: OkHttpClient = defaultSearchHttpClient
) : WebSearchProvider {

    override val providerId: String = "PublicWikiAndRss"
    override val requiresApiKey: Boolean = false

    override fun isConfigured(): Boolean = true

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchResultItem>> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext Result.success(emptyList())

        val collected = mutableListOf<SearchResultItem>()
        var anyNetworkAttemptSucceeded = false

        // 1. Wikipedia REST summary (bn + en)
        try {
            val wiki = NewsAndRegulatoryService.searchWikipedia(clean)
            if (wiki.found && wiki.extract.isNotBlank() && wiki.url.startsWith("http")) {
                anyNetworkAttemptSucceeded = true
                collected.add(
                    SearchResultItem(
                        title = wiki.title,
                        url = wiki.url,
                        snippet = wiki.extract.take(380),
                        sourceDomain = extractDomainFromUrl(wiki.url).ifBlank { "wikipedia.org" },
                        publishedDate = "",
                        providerName = "Wikipedia",
                        verified = true
                    )
                )
            }
        } catch (_: Exception) {
        }

        // 2. DuckDuckGo Instant Answer API (keyless JSON public API)
        if (collected.size < maxResults) {
            try {
                val encoded = URLEncoder.encode(clean, "UTF-8")
                val req = Request.Builder()
                    .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                    .header("User-Agent", "Nahid-Jarvis-AI/1.0")
                    .get()
                    .build()
                val (ddgCode, body) = httpClient.executeCancellable(req)
                if (ddgCode in 200..299) {
                    anyNetworkAttemptSucceeded = true
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val abstractText = json.optString("AbstractText", "").trim()
                        val abstractUrl = json.optString("AbstractURL", "").trim()
                        val heading = json.optString("Heading", clean).trim()
                        if (abstractText.isNotBlank() && abstractUrl.startsWith("http") &&
                            collected.none { it.url == abstractUrl }
                        ) {
                            collected.add(
                                SearchResultItem(
                                    title = heading.ifBlank { clean },
                                    url = abstractUrl,
                                    snippet = abstractText.take(360),
                                    sourceDomain = extractDomainFromUrl(abstractUrl),
                                    providerName = "DuckDuckGoIA",
                                    verified = true
                                )
                            )
                        }
                        val topics = json.optJSONArray("RelatedTopics")
                        if (topics != null) {
                            for (i in 0 until topics.length()) {
                                if (collected.size >= maxResults) break
                                val t = topics.optJSONObject(i) ?: continue
                                val firstUrl = t.optString("FirstURL", "").trim()
                                val text = t.optString("Text", "").trim()
                                if (firstUrl.startsWith("http") && text.isNotBlank() &&
                                    collected.none { it.url == firstUrl }
                                ) {
                                    collected.add(
                                        SearchResultItem(
                                            title = text.substringBefore(" - ").take(80),
                                            url = firstUrl,
                                            snippet = text.take(280),
                                            sourceDomain = extractDomainFromUrl(firstUrl),
                                            providerName = "DuckDuckGoIA",
                                            verified = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Matching live RSS news if query seems news/current-events related or collected is empty
        if (collected.size < maxResults) {
            try {
                val rss = NewsAndRegulatoryService.getLiveNews(category = "সব", forceRefresh = false)
                if (rss.isNotEmpty()) anyNetworkAttemptSucceeded = true
                val terms = clean.lowercase().split(" ").filter { it.length >= 3 }
                rss.filter { item ->
                    terms.any { term ->
                        item.title.lowercase().contains(term) || item.summary.lowercase().contains(term)
                    }
                }.take((maxResults - collected.size).coerceAtLeast(0)).forEach { n ->
                    if (n.url.startsWith("http")) {
                        collected.add(
                            SearchResultItem(
                                title = n.title,
                                url = n.url,
                                snippet = n.summary,
                                sourceDomain = extractDomainFromUrl(n.url).ifBlank { n.source },
                                publishedDate = n.date,
                                providerName = n.source,
                                verified = true
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (collected.isEmpty() && !anyNetworkAttemptSucceeded) {
            Result.failure(IllegalStateException("OFFLINE_OR_PUBLIC_ENDPOINTS_UNREACHABLE"))
        } else {
            Result.success(collected.take(maxResults))
        }
    }
}

internal val defaultSearchHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()
}
