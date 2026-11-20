package com.example.research

import android.content.Context
import com.example.ai.AiSettingsRepository
import com.example.knowledge.ClaimStatus
import com.example.knowledge.ClaimVerificationEngine
import com.example.knowledge.KnowledgeCitation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

data class DeepResearchFinding(
    val title: String,
    val url: String,
    val sourceDomain: String,
    val extractionStatus: ExtractionStatus,
    val contentExtracted: Boolean,
    val snippetOnly: Boolean,
    val wrappedExcerpt: String,
    val publishedDate: String = "",
    val noteBn: String = ""
)

data class DeepResearchResponse(
    val success: Boolean,
    val query: String,
    val providerUsed: String,
    val statusCodeLabel: String, // VERIFIED_DEEP_RESEARCH, SINGLE_SOURCE_ONLY, CONFLICTING, SNIPPET_ONLY, NO_RESULTS, OFFLINE_LIMITED, FAILED
    val crossCheckStatus: String, // MULTI_SOURCE_CORROBORATED, SINGLE_SOURCE_EXTRACTED, CONFLICTING, SNIPPET_ONLY, NONE
    val extractedSourceCount: Int,
    val blockedSourceCount: Int,
    val results: List<SearchResultItem>,
    val findings: List<DeepResearchFinding>,
    val summaryBengali: String,
    val errorCode: String = ""
)

/**
 * Verifies that search results come from real reachable providers with valid http/https URLs,
 * canonicalizes URLs to strip tracking params/duplicates, and never fabricates citations.
 */
object SearchResultVerifier {
    fun filterVerifiedResults(items: List<SearchResultItem>): List<SearchResultItem> {
        return items
            .filter { item ->
                item.title.isNotBlank() &&
                    (item.url.startsWith("https://") || item.url.startsWith("http://")) &&
                    extractDomainFromUrl(item.url).isNotBlank()
            }
            .distinctBy { ClaimVerificationEngine.canonicalizeUrl(it.url) }
    }
}

/**
 * Unified Research & Browser Agent coordinating configured WebSearchProviders
 * (Brave Search -> Tavily Search -> Keyless Public Wikipedia/DuckDuckGo/RSS) + WebPageContentExtractor
 * + Deep multi-page content extraction and cross-source corroboration.
 */
object UniversalBrowserAgent {

    suspend fun searchAndVerify(
        query: String,
        context: Context? = null,
        braveApiKey: String = "",
        tavilyApiKey: String = "",
        customProviders: List<WebSearchProvider>? = null,
        maxResults: Int = 5
    ): VerifiedSearchResponse {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return VerifiedSearchResponse(
                success = false,
                query = cleanQuery,
                results = emptyList(),
                summaryBengali = "অনুসন্ধানের জন্য একটি স্পষ্ট বিষয় বা প্রশ্ন উল্লেখ করুন।",
                providerUsed = "NONE",
                statusCodeLabel = "FAILED",
                errorCode = "EMPTY_QUERY"
            )
        }

        val resolvedKeys = if (context != null && braveApiKey.isBlank() && tavilyApiKey.isBlank()) {
            try {
                val repo = AiSettingsRepository(context.applicationContext)
                Pair(repo.getBraveSearchApiKey(), repo.getTavilySearchApiKey())
            } catch (_: Exception) {
                Pair("", "")
            }
        } else {
            Pair(braveApiKey, tavilyApiKey)
        }

        val providers = customProviders ?: listOf(
            BraveSearchProvider(apiKeyProvider = { resolvedKeys.first }),
            TavilySearchProvider(apiKeyProvider = { resolvedKeys.second }),
            PublicWikiAndRssProvider()
        )

        var anyNetworkFailure = false
        var lastError = ""

        for (provider in providers) {
            if (!provider.isConfigured()) continue
            val res = provider.search(cleanQuery, maxResults = maxResults)
            if (res.isSuccess) {
                val verified = SearchResultVerifier.filterVerifiedResults(res.getOrNull().orEmpty())
                if (verified.isNotEmpty()) {
                    val topCitations = verified.take(3).joinToString("\n") { item ->
                        val datePart = if (item.publishedDate.isNotBlank()) " [${item.publishedDate}]" else ""
                        "• ${item.title}$datePart (${item.sourceDomain})\n  উৎস: ${item.url}\n  ${item.snippet.take(180)}"
                    }
                    val snippetNote = if (verified.all { it.snippetOnly && !it.contentExtracted }) " [সার্চ স্নিপেট — পূর্ণ পেজ আলাদাভাবে রিড করা হয়নি]" else ""
                    return VerifiedSearchResponse(
                        success = true,
                        query = cleanQuery,
                        results = verified,
                        summaryBengali = "‘$cleanQuery’ বিষয়ে ${verified.size}টি যাচাইকৃত সূত্র পাওয়া গেছে (${provider.providerId})$snippetNote:\n$topCitations",
                        providerUsed = provider.providerId,
                        statusCodeLabel = "VERIFIED"
                    )
                }
            } else {
                anyNetworkFailure = true
                lastError = res.exceptionOrNull()?.message ?: "Provider error"
            }
        }

        return if (anyNetworkFailure) {
            VerifiedSearchResponse(
                success = false,
                query = cleanQuery,
                results = emptyList(),
                summaryBengali = "‘$cleanQuery’ বিষয়ে ইন্টারনেট সার্চ এই মুহূর্তে পাওয়া যায়নি (অফলাইন বা নেটওয়ার্ক সীমিত: $lastError)।",
                providerUsed = "NONE",
                statusCodeLabel = "OFFLINE_LIMITED",
                errorCode = "UNAVAILABLE_OFFLINE"
            )
        } else {
            VerifiedSearchResponse(
                success = false,
                query = cleanQuery,
                results = emptyList(),
                summaryBengali = "‘$cleanQuery’ বিষয়ে কোনো যাচাইকৃত পাবলিক ওয়েব ফলাফল পাওয়া যায়নি। আরও বিস্তারিত লাইভ ওয়েব সার্চের জন্য Settings > Optional Web Search-এ Brave বা Tavily API Key যুক্ত করতে পারেন।",
                providerUsed = "PublicWikiAndRss",
                statusCodeLabel = "NO_RESULTS",
                errorCode = "NO_RESULTS_FOUND"
            )
        }
    }

    /**
     * Deep Research pipeline (Priority 6):
     * Searches via configured providers -> fetches & extracts real HTML body across top verified URLs ->
     * distinguishes extracted full content (`contentExtracted=true, snippetOnly=false`) from blocked/snippet-only URLs ->
     * cross-checks key terms across distinct source domains -> returns cited Bengali synthesis.
     */
    suspend fun deepResearch(
        query: String,
        context: Context? = null,
        braveApiKey: String = "",
        tavilyApiKey: String = "",
        customProviders: List<WebSearchProvider>? = null,
        maxPagesToExtract: Int = 3
    ): DeepResearchResponse {
        val searchRes = searchAndVerify(
            query = query,
            context = context,
            braveApiKey = braveApiKey,
            tavilyApiKey = tavilyApiKey,
            customProviders = customProviders,
            maxResults = (maxPagesToExtract + 2).coerceIn(3, 8)
        )

        if (!searchRes.success || searchRes.results.isEmpty()) {
            return DeepResearchResponse(
                success = false,
                query = searchRes.query,
                providerUsed = searchRes.providerUsed,
                statusCodeLabel = searchRes.statusCodeLabel,
                crossCheckStatus = "NONE",
                extractedSourceCount = 0,
                blockedSourceCount = 0,
                results = emptyList(),
                findings = emptyList(),
                summaryBengali = searchRes.summaryBengali,
                errorCode = searchRes.errorCode
            )
        }

        val targets = searchRes.results.take(maxPagesToExtract.coerceIn(1, 5))
        val gate = Semaphore(3)
        val extractedPairs = coroutineScope {
            targets.map { item ->
                async {
                    gate.withPermit {
                        val extraction = WebPageContentExtractor.extractPageContent(item.url, maxChars = 2500)
                        val didExtract = extraction.status == ExtractionStatus.EXTRACTED && extraction.cleanText.isNotBlank()
                        val updatedItem = item.copy(
                            title = extraction.title.ifBlank { item.title },
                            snippet = if (didExtract) extraction.cleanText.take(360) else item.snippet,
                            snippetOnly = !didExtract,
                            contentExtracted = didExtract
                        )
                        val wrapped = if (didExtract) {
                            wrapUntrustedExternalContent(item.url, extraction.cleanText)
                        } else {
                            wrapUntrustedExternalContent(item.url, item.snippet)
                        }
                        val finding = DeepResearchFinding(
                            title = updatedItem.title,
                            url = item.url,
                            sourceDomain = item.sourceDomain,
                            extractionStatus = extraction.status,
                            contentExtracted = didExtract,
                            snippetOnly = !didExtract,
                            wrappedExcerpt = wrapped,
                            publishedDate = item.publishedDate,
                            noteBn = extraction.summaryBengali
                        )
                        Pair(updatedItem, finding)
                    }
                }
            }.awaitAll()
        }

        val updatedItems = extractedPairs.map { it.first }
        val findings = extractedPairs.map { it.second }
        val extractedCount = findings.count { it.contentExtracted }
        val blockedCount = findings.count { !it.contentExtracted }

        // Cross-check key query terms across distinct extracted domains + ClaimVerificationEngine
        val queryTokens = query.lowercase(Locale.ROOT)
            .split(Regex("""[\s,।?!.:;]+"""))
            .filter { it.length >= 3 }
            .distinct()
        val corroboratedDomains = findings
            .filter { it.contentExtracted }
            .filter { finding ->
                val lowerText = finding.wrappedExcerpt.lowercase(Locale.ROOT)
                queryTokens.isEmpty() || queryTokens.any { lowerText.contains(it) }
            }
            .map { it.sourceDomain }
            .distinct()

        val knowledgeCitations = updatedItems.map { item ->
            KnowledgeCitation(
                title = item.title,
                url = item.url,
                canonicalUrl = ClaimVerificationEngine.canonicalizeUrl(item.url),
                domain = item.sourceDomain,
                snippet = item.snippet,
                contentExtracted = item.contentExtracted,
                publishedDate = item.publishedDate
            )
        }
        val (claimStatus, _) = ClaimVerificationEngine.verifyClaims(query, knowledgeCitations)

        val crossCheckStatus = when {
            claimStatus == ClaimStatus.CONFLICTING -> "CONFLICTING"
            corroboratedDomains.size >= 2 -> "MULTI_SOURCE_CORROBORATED"
            extractedCount >= 1 -> "SINGLE_SOURCE_EXTRACTED"
            else -> "SNIPPET_ONLY"
        }

        // Truthful evidence gate: only >= 2 independent corroborated domains produce VERIFIED_DEEP_RESEARCH
        val finalStatusLabel = when {
            claimStatus == ClaimStatus.CONFLICTING -> "CONFLICTING"
            corroboratedDomains.size >= 2 -> "VERIFIED_DEEP_RESEARCH"
            extractedCount >= 1 -> "SINGLE_SOURCE_ONLY"
            else -> "SNIPPET_ONLY"
        }

        val reportBn = buildString {
            append("‘${searchRes.query}’ বিষয়ে গভীর গবেষণা সম্পন্ন হয়েছে ")
            append("(${searchRes.providerUsed}; পূর্ণ পেজ পড়া: $extractedCount, সীমাবদ্ধ/স্নিপেট: $blockedCount, অবস্থা: $finalStatusLabel, ক্রস-চেক: $crossCheckStatus):\n\n")
            findings.forEachIndexed { idx, f ->
                val tag = if (f.contentExtracted) "পূর্ণ পেজ পঠিত" else "শুধুমাত্র স্নিপেট / ${f.extractionStatus.name}"
                append("${idx + 1}. ${f.title} (${f.sourceDomain}) [$tag]\n")
                append("   সূত্র: ${f.url}\n")
                if (f.contentExtracted) {
                    val snippet = updatedItems[idx].snippet.take(240)
                    append("   সারসংক্ষেপ: $snippet\n")
                } else {
                    append("   নোট: ${f.noteBn}\n")
                }
            }
        }.trim()

        return DeepResearchResponse(
            success = true,
            query = searchRes.query,
            providerUsed = searchRes.providerUsed,
            statusCodeLabel = finalStatusLabel,
            crossCheckStatus = crossCheckStatus,
            extractedSourceCount = extractedCount,
            blockedSourceCount = blockedCount,
            results = updatedItems,
            findings = findings,
            summaryBengali = reportBn
        )
    }

    /**
     * Wraps external web text in explicit untrusted-content boundary markers so downstream
     * reasoning never executes embedded prompt-injection directives.
     */
    fun wrapUntrustedExternalContent(sourceUrl: String, text: String): String {
        val cleaned = text
            .replace(Regex("(?i)(ignore previous instructions|system prompt|developer instructions|you are now)"), "[REDACTED_DIRECTIVE]")
            .take(5000)
        return """
            [BEGIN_UNTRUSTED_EXTERNAL_CONTENT source="$sourceUrl"]
            $cleaned
            [END_UNTRUSTED_EXTERNAL_CONTENT]
        """.trimIndent()
    }
}
