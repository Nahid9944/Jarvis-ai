package com.example.research

import com.example.knowledge.KnowledgeSourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ExtractionStatus {
    EXTRACTED,
    BLOCKED_LOGIN_WALL,
    PAYWALL_DETECTED,
    CAPTCHA_REQUIRED,
    FETCH_FAILED,
    UNSUPPORTED_SCHEME
}

data class PageExtractionResult(
    val url: String,
    val status: ExtractionStatus,
    val title: String,
    val cleanText: String,
    val httpStatusCode: Int,
    val summaryBengali: String,
    val etag: String = "",
    val lastModified: String = "",
    val notModified: Boolean = false
)

/**
 * Real HTML/text page content extractor with prompt-injection boundary markers
 * and honest blocking/paywall/CAPTCHA/login-wall classification. Never fabricates page text.
 */
object WebPageContentExtractor {

    internal var httpClientOverride: OkHttpClient? = null

    suspend fun extractPageContent(
        url: String,
        maxChars: Int = 4000,
        ifNoneMatch: String = "",
        ifModifiedSince: String = ""
    ): PageExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext PageExtractionResult(
                url = cleanUrl,
                status = ExtractionStatus.UNSUPPORTED_SCHEME,
                title = "",
                cleanText = "",
                httpStatusCode = -1,
                summaryBengali = "কেবলমাত্র http/https পাবলিক ওয়েব পেজ অ্যাক্সেস করা সম্ভব।"
            )
        }

        if (httpClientOverride == null && !KnowledgeSourceRegistry.isPublicSafeHttpUrl(cleanUrl)) {
            return@withContext PageExtractionResult(
                url = cleanUrl,
                status = ExtractionStatus.UNSUPPORTED_SCHEME,
                title = "",
                cleanText = "",
                httpStatusCode = -1,
                summaryBengali = "নিরাপত্তার স্বার্থে প্রাইভেট বা লোকালহোস্ট ঠিকানা থেকে কনটেন্ট সংগ্রহ ব্লক করা হয়েছে।"
            )
        }

        val client = httpClientOverride ?: defaultSearchHttpClient
        try {
            val reqBuilder = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 Nahid-Jarvis-AI/1.0")
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
            if (ifNoneMatch.isNotBlank()) reqBuilder.header("If-None-Match", ifNoneMatch)
            if (ifModifiedSince.isNotBlank()) reqBuilder.header("If-Modified-Since", ifModifiedSince)
            val req = reqBuilder.get().build()

            val (code, rawBody, respEtag, respLastMod) = suspendCancellableCoroutine<QuadResponse> { cont ->
                val call = client.newCall(req)
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
                            val body = if (res.code == 304) "" else (res.body?.string().orEmpty())
                            val etag = res.header("ETag").orEmpty()
                            val lm = res.header("Last-Modified").orEmpty()
                            if (cont.isActive) cont.resume(QuadResponse(res.code, body, etag, lm))
                        }
                    }
                })
            }

            if (code == 304) {
                return@withContext PageExtractionResult(
                    url = cleanUrl,
                    status = ExtractionStatus.EXTRACTED,
                    title = "",
                    cleanText = "",
                    httpStatusCode = 304,
                    summaryBengali = "পূর্বের ক্যাশকৃত তথ্যই সর্বশেষ (HTTP 304 Not Modified)।",
                    etag = respEtag.ifBlank { ifNoneMatch },
                    lastModified = respLastMod.ifBlank { ifModifiedSince },
                    notModified = true
                )
            }
            if (code == 401 || code == 403) {
                return@withContext PageExtractionResult(
                    url = cleanUrl,
                    status = ExtractionStatus.BLOCKED_LOGIN_WALL,
                    title = "",
                    cleanText = "",
                    httpStatusCode = code,
                    summaryBengali = "এই ওয়েব পেজটি লগইন বা অ্যাক্সেস অনুমতির কারণে বন্ধ (HTTP $code)।"
                )
            }
            if (code == 402) {
                return@withContext PageExtractionResult(
                    url = cleanUrl,
                    status = ExtractionStatus.PAYWALL_DETECTED,
                    title = "",
                    cleanText = "",
                    httpStatusCode = code,
                    summaryBengali = "এই কনটেন্টটি পেইড সাবস্ক্রিপশন (Paywall)-এর ভেতরে রয়েছে (HTTP 402)।"
                )
            }
            if (code == 429) {
                return@withContext PageExtractionResult(
                    url = cleanUrl,
                    status = ExtractionStatus.CAPTCHA_REQUIRED,
                    title = "",
                    cleanText = "",
                    httpStatusCode = code,
                    summaryBengali = "ওয়েবসাইটটি রেট-লিমিট বা CAPTCHA যাচাই চাইছে (HTTP 429)।"
                )
            }
            if (code !in 200..299) {
                return@withContext PageExtractionResult(
                    url = cleanUrl,
                    status = ExtractionStatus.FETCH_FAILED,
                    title = "",
                    cleanText = "",
                    httpStatusCode = code,
                    summaryBengali = "ওয়েব পেজটি লোড করা যায়নি (HTTP $code)।"
                )
            }

            classifyAndExtractHtml(cleanUrl, code, rawBody, maxChars).copy(
                etag = respEtag,
                lastModified = respLastMod
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            PageExtractionResult(
                url = cleanUrl,
                status = ExtractionStatus.FETCH_FAILED,
                title = "",
                cleanText = "",
                httpStatusCode = -1,
                summaryBengali = "নেটওয়ার্ক ত্রুটির কারণে পেজ পড়া যায়নি: ${e.localizedMessage ?: "Connection failed"}"
            )
        }
    }

    private data class QuadResponse(
        val code: Int,
        val body: String,
        val etag: String,
        val lastModified: String
    )

    internal fun classifyAndExtractHtml(
        url: String,
        httpStatusCode: Int,
        rawHtml: String,
        maxChars: Int = 4000
    ): PageExtractionResult {
        val lowerHtml = rawHtml.lowercase(Locale.ROOT)
        val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(rawHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { stripTagsAndEntities(it) }
            .orEmpty()

        val strippedText = cleanHtmlToReadableText(rawHtml).take(maxChars)
        val lowerText = strippedText.lowercase(Locale.ROOT)

        val isCaptcha = lowerHtml.contains("cf-chl-") ||
            lowerHtml.contains("g-recaptcha") ||
            lowerText.contains("verify you are human") ||
            lowerText.contains("complete the captcha")
        if (isCaptcha) {
            return PageExtractionResult(
                url = url,
                status = ExtractionStatus.CAPTCHA_REQUIRED,
                title = title,
                cleanText = "",
                httpStatusCode = httpStatusCode,
                summaryBengali = "সাইটটিতে CAPTCHA বা মানব-যাচাই (Human Verification) থাকায় অটোমেটিক রিডিং বন্ধ রয়েছে।"
            )
        }

        val isPaywall = lowerText.contains("subscribe to continue reading") ||
            lowerText.contains("subscription required") ||
            lowerHtml.contains("paywall-gate")
        if (isPaywall && strippedText.length < 700) {
            return PageExtractionResult(
                url = url,
                status = ExtractionStatus.PAYWALL_DETECTED,
                title = title,
                cleanText = strippedText,
                httpStatusCode = httpStatusCode,
                summaryBengali = "এই আর্টিকেলটি পেওয়াল (Paywall) দ্বারা সুরক্ষিত — সম্পূর্ণ লেখা পাবলিক নয়।"
            )
        }

        val isLoginWall = (lowerHtml.contains("type=\"password\"") || lowerText.contains("sign in to continue") ||
            lowerText.contains("log in to view")) && strippedText.length < 700
        if (isLoginWall) {
            return PageExtractionResult(
                url = url,
                status = ExtractionStatus.BLOCKED_LOGIN_WALL,
                title = title,
                cleanText = "",
                httpStatusCode = httpStatusCode,
                summaryBengali = "এই পেজটি দেখতে অ্যাকাউন্ট লগইন প্রয়োজন (Login Wall); NAHID AI অননুমোদিত লগইন বাইপাস করে না।"
            )
        }

        return PageExtractionResult(
            url = url,
            status = ExtractionStatus.EXTRACTED,
            title = title,
            cleanText = strippedText,
            httpStatusCode = httpStatusCode,
            summaryBengali = if (strippedText.isBlank()) {
                "পেজ থেকে কোনো পঠনযোগ্য টেক্সট পাওয়া যায়নি।"
            } else {
                "পাবলিক পেজ '$title' সফলভাবে বিশ্লেষণ করা হয়েছে (${strippedText.length} অক্ষর)।"
            }
        )
    }

    internal fun cleanHtmlToReadableText(rawHtml: String): String {
        val withoutScripts = rawHtml
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<noscript\b[^>]*>.*?</noscript>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        return stripTagsAndEntities(withoutScripts)
    }

    private fun stripTagsAndEntities(raw: String): String {
        return raw.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
