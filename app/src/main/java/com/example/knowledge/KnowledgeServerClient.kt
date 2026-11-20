package com.example.knowledge

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class KnowledgeServerSearchResponse(
    val reachable: Boolean,
    val configured: Boolean,
    val statusCode: Int,
    val hitFound: Boolean,
    val summaryBn: String = "",
    val citations: List<KnowledgeCitation> = emptyList(),
    val verificationStatus: ClaimStatus = ClaimStatus.UNVERIFIED,
    val bytesTransferred: Long = 0L,
    val errorReason: String = ""
)

data class KnowledgeServerIngestResponse(
    val success: Boolean,
    val httpStatus: Int,
    val errorReason: String = ""
)

/**
 * Independent Firebase-free REST client for L3 Shared Knowledge Server.
 * Uses cancellable OkHttp calls so user cancel / barge-in propagates immediately to Call.cancel().
 */
class KnowledgeServerClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String = { "" },
    private val httpClient: OkHttpClient = defaultClient
) {

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .build()
        }
    }

    fun isConfigured(): Boolean = baseUrlProvider().trim().startsWith("http")

    suspend fun search(query: String, mode: RetrievalMode): KnowledgeServerSearchResponse {
        val base = baseUrlProvider().trim().trimEnd('/')
        if (!base.startsWith("http")) {
            return KnowledgeServerSearchResponse(
                reachable = false,
                configured = false,
                statusCode = -1,
                hitFound = false,
                errorReason = "SERVER_UNCONFIGURED"
            )
        }
        val encodedQ = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$base/v1/knowledge/search?q=$encodedQ&mode=${mode.name}"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
        val token = tokenProvider().trim()
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        return executeCancellable(reqBuilder.build()) { code, body, bytes ->
            if (code !in 200..299 || body.isBlank()) {
                return@executeCancellable KnowledgeServerSearchResponse(
                    reachable = code > 0,
                    configured = true,
                    statusCode = code,
                    hitFound = false,
                    bytesTransferred = bytes,
                    errorReason = "HTTP_$code"
                )
            }
            val root = JSONObject(body)
            val hits = root.optJSONArray("results") ?: JSONArray()
            val citations = mutableListOf<KnowledgeCitation>()
            for (i in 0 until hits.length()) {
                val obj = hits.optJSONObject(i) ?: continue
                val docUrl = obj.optString("url", "").trim()
                val title = obj.optString("title", "").trim()
                val snippet = obj.optString("summary", obj.optString("snippet", "")).trim()
                val domain = obj.optString("domain", "").ifBlank {
                    try { java.net.URI(docUrl).host.orEmpty().removePrefix("www.") } catch (_: Exception) { "" }
                }
                if (docUrl.startsWith("http") && title.isNotBlank()) {
                    citations.add(
                        KnowledgeCitation(
                            title = title,
                            url = docUrl,
                            canonicalUrl = ClaimVerificationEngine.canonicalizeUrl(docUrl),
                            domain = domain,
                            snippet = snippet,
                            contentExtracted = obj.optBoolean("contentExtracted", false),
                            contentHash = obj.optString("contentHash", ""),
                            publishedDate = obj.optString("publishedAt", "")
                        )
                    )
                }
            }
            val summary = root.optString("answerSummaryBn", "").trim()
            val statusStr = root.optString("verificationStatus", "UNVERIFIED")
            val parsedStatus = try {
                ClaimStatus.valueOf(statusStr)
            } catch (_: Exception) {
                ClaimStatus.PARTIALLY_SUPPORTED
            }
            KnowledgeServerSearchResponse(
                reachable = true,
                configured = true,
                statusCode = code,
                hitFound = citations.isNotEmpty() || summary.isNotBlank(),
                summaryBn = summary,
                citations = citations,
                verificationStatus = parsedStatus,
                bytesTransferred = bytes
            )
        } ?: KnowledgeServerSearchResponse(
            reachable = false,
            configured = true,
            statusCode = -1,
            hitFound = false,
            errorReason = "UNREACHABLE_OR_CANCELLED"
        )
    }

    suspend fun ingest(
        idempotencyKey: String,
        query: String,
        topicCategory: TopicCategory,
        summaryBn: String,
        verificationStatus: ClaimStatus,
        citations: List<KnowledgeCitation>
    ): KnowledgeServerIngestResponse {
        val base = baseUrlProvider().trim().trimEnd('/')
        if (!base.startsWith("http")) {
            return KnowledgeServerIngestResponse(success = false, httpStatus = -1, errorReason = "SERVER_UNCONFIGURED")
        }
        val arr = JSONArray()
        citations.forEach { c ->
            arr.put(JSONObject().apply {
                put("title", c.title)
                put("url", c.canonicalUrl.ifBlank { c.url })
                put("domain", c.domain)
                put("snippet", c.snippet.take(600))
                put("contentExtracted", c.contentExtracted)
                put("contentHash", c.contentHash)
            })
        }
        val payload = JSONObject().apply {
            put("idempotencyKey", idempotencyKey)
            put("query", query)
            put("topicCategory", topicCategory.name)
            put("answerSummaryBn", summaryBn)
            put("verificationStatus", verificationStatus.name)
            put("citations", arr)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url("$base/v1/knowledge/ingest")
            .header("X-Idempotency-Key", idempotencyKey)
            .post(payload)
        val token = tokenProvider().trim()
        if (token.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        return executeCancellable(reqBuilder.build()) { code, _, _ ->
            KnowledgeServerIngestResponse(
                success = code in 200..299,
                httpStatus = code,
                errorReason = if (code in 200..299) "" else "HTTP_$code"
            )
        } ?: KnowledgeServerIngestResponse(success = false, httpStatus = -1, errorReason = "NETWORK_OR_CANCELLED")
    }

    private suspend fun <T> executeCancellable(
        request: Request,
        parser: (code: Int, body: String, bytes: Long) -> T
    ): T? = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Exception) {
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val raw = try { res.body?.string().orEmpty() } catch (_: Exception) { "" }
                    val bytes = raw.toByteArray(Charsets.UTF_8).size.toLong()
                    val parsed = try {
                        parser(res.code, raw, bytes)
                    } catch (_: Exception) {
                        null
                    }
                    if (cont.isActive) cont.resume(parsed)
                }
            }
        })
    }
}
