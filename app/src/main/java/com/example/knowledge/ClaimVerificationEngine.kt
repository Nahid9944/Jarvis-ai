package com.example.knowledge

import java.net.URI
import java.util.Locale

/**
 * Claim-level Verification, URL Canonicalization, Syndicated Content Deduplication,
 * and Contradiction Detection Engine.
 * Enforces strict platform truth: VERIFIED requires >= 2 independent non-syndicated domains.
 */
object ClaimVerificationEngine {

    private val TRACKING_QUERY_PREFIXES = listOf(
        "utm_", "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source", "igshid"
    )

    fun canonicalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return try {
            val uri = URI(trimmed)
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            val portPart = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            val rawPath = (uri.rawPath ?: "").trimEnd('/')
            val filteredQuery = uri.rawQuery
                ?.split("&")
                ?.filter { pair ->
                    val key = pair.substringBefore("=").lowercase(Locale.ROOT)
                    key.isNotBlank() && TRACKING_QUERY_PREFIXES.none { key.startsWith(it) }
                }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotBlank() }

            buildString {
                append(scheme).append("://").append(host).append(portPart).append(rawPath)
                if (!filteredQuery.isNullOrBlank()) append("?").append(filteredQuery)
            }
        } catch (_: Exception) {
            trimmed.substringBefore("#").trimEnd('/')
        }
    }

    /**
     * Collapses duplicate canonical URLs and detects syndicated/mirror copies sharing identical normalized content hashes.
     */
    fun deduplicateCitations(rawCitations: List<KnowledgeCitation>): List<KnowledgeCitation> {
        val seenUrls = LinkedHashSet<String>()
        val seenHashes = mutableSetOf<String>()
        val deduped = mutableListOf<KnowledgeCitation>()

        for (citation in rawCitations) {
            val canonical = canonicalizeUrl(citation.url)
            if (canonical.isBlank() || !seenUrls.add(canonical)) continue

            val normalizedSnippet = citation.snippet.lowercase(Locale.ROOT)
                .replace(Regex("""\s+"""), " ")
                .trim()
            val hash = citation.contentHash.ifBlank {
                if (normalizedSnippet.length >= 40) KnowledgeL1Cache.sha256Hex(normalizedSnippet).take(24) else ""
            }
            val isSyndicated = hash.isNotBlank() && !seenHashes.add(hash)
            deduped.add(
                citation.copy(
                    canonicalUrl = canonical,
                    contentHash = hash,
                    authorityWeight = KnowledgeSourceRegistry.getAuthorityWeight(citation.domain),
                    isSyndicatedDuplicate = isSyndicated
                )
            )
        }
        return deduped
    }

    /**
     * Evaluates claim support & cross-source corroboration across independent domains.
     */
    fun verifyClaims(
        query: String,
        citations: List<KnowledgeCitation>
    ): Pair<ClaimStatus, List<VerifiedClaim>> {
        val cleanCitations = deduplicateCitations(citations)
        if (cleanCitations.isEmpty()) {
            return Pair(ClaimStatus.UNVERIFIED, emptyList())
        }

        val independentExtracted = cleanCitations
            .filter { it.contentExtracted && !it.isSyndicatedDuplicate }
            .distinctBy { it.domain.lowercase(Locale.ROOT).removePrefix("www.") }

        val independentAll = cleanCitations
            .filter { !it.isSyndicatedDuplicate }
            .distinctBy { it.domain.lowercase(Locale.ROOT).removePrefix("www.") }

        val queryTokens = query.lowercase(Locale.ROOT)
            .split(Regex("""[\s,।?!.:;]+"""))
            .filter { it.length >= 3 }
            .distinct()

        // Extract top salient sentences from extracted pages or snippets
        val candidateStatements = cleanCitations
            .filter { !it.isSyndicatedDuplicate }
            .flatMap { c ->
                c.snippet.split(Regex("""[।\.\n]+"""))
                    .map { it.trim() }
                    .filter { it.length in 24..220 }
                    .take(2)
                    .map { sentence -> c to sentence }
            }
            .take(5)

        val verifiedClaims = mutableListOf<VerifiedClaim>()
        var anyContradiction = false

        for ((originCitation, statement) in candidateStatements) {
            val statementTokens = statement.lowercase(Locale.ROOT)
                .split(Regex("""[\s,।?!.:;]+"""))
                .filter { it.length >= 4 }
                .take(6)

            val supportingDomains = cleanCitations
                .filter { !it.isSyndicatedDuplicate }
                .filter { c ->
                    val bodyLower = (c.title + " " + c.snippet).lowercase(Locale.ROOT)
                    val matchesQuery = queryTokens.isEmpty() || queryTokens.any { bodyLower.contains(it) }
                    val matchesClaim = statementTokens.count { bodyLower.contains(it) } >= 2.coerceAtMost(statementTokens.size)
                    matchesQuery && matchesClaim
                }
                .map { it.domain.lowercase(Locale.ROOT).removePrefix("www.") }
                .distinct()

            val numbersByDomain = cleanCitations
                .filter { !it.isSyndicatedDuplicate }
                .associate { c ->
                    val nums = Regex("""\b\d+(?:\.\d+)?%?\b""").findAll(c.snippet).map { it.value }.toSet()
                    c.domain.lowercase(Locale.ROOT).removePrefix("www.") to nums
                }

            val hasNegationConflict = detectContradictionAcrossSources(cleanCitations, statementTokens)
            val distinctNumberSets = numbersByDomain.values.filter { it.isNotEmpty() }.distinct()
            val numberConflict = distinctNumberSets.size >= 2 &&
                distinctNumberSets.first().intersect(distinctNumberSets.last()).isEmpty() &&
                supportingDomains.size >= 2

            if (hasNegationConflict || numberConflict) {
                anyContradiction = true
            }

            val extractedSupporters = cleanCitations
                .filter { it.contentExtracted && !it.isSyndicatedDuplicate }
                .map { it.domain.lowercase(Locale.ROOT).removePrefix("www.") }
                .filter { it in supportingDomains || supportingDomains.isEmpty() }
                .distinct()

            val status = when {
                hasNegationConflict || numberConflict -> ClaimStatus.CONFLICTING
                extractedSupporters.size >= 2 -> ClaimStatus.VERIFIED
                supportingDomains.size >= 2 && cleanCitations.any { it.contentExtracted } -> ClaimStatus.PARTIALLY_SUPPORTED
                supportingDomains.size == 1 -> ClaimStatus.SINGLE_SOURCE_ONLY
                else -> ClaimStatus.UNVERIFIED
            }

            val maxAuthority = cleanCitations
                .filter { it.domain.lowercase(Locale.ROOT).removePrefix("www.") in supportingDomains }
                .maxOfOrNull { it.authorityWeight } ?: originCitation.authorityWeight

            verifiedClaims.add(
                VerifiedClaim(
                    claimText = statement,
                    status = status,
                    independentDomainCount = supportingDomains.size,
                    supportingDomains = supportingDomains.ifEmpty { listOf(originCitation.domain) },
                    conflictingNotes = if (hasNegationConflict || numberConflict) "ভিন্ন উৎসে পরস্পরবিরোধী তথ্য বা সংখ্যা পাওয়া গেছে" else "",
                    confidenceScore = ((supportingDomains.size * 0.32f) + (maxAuthority * 0.4f)).coerceIn(0.15f, 0.98f)
                )
            )
        }

        val overallStatus = when {
            anyContradiction || verifiedClaims.any { it.status == ClaimStatus.CONFLICTING } -> ClaimStatus.CONFLICTING
            independentExtracted.size >= 2 && verifiedClaims.any { it.status == ClaimStatus.VERIFIED } -> ClaimStatus.VERIFIED
            independentAll.size >= 2 && cleanCitations.any { it.contentExtracted } -> ClaimStatus.PARTIALLY_SUPPORTED
            independentAll.size == 1 -> ClaimStatus.SINGLE_SOURCE_ONLY
            cleanCitations.isNotEmpty() -> ClaimStatus.PARTIALLY_SUPPORTED
            else -> ClaimStatus.UNVERIFIED
        }

        return Pair(overallStatus, verifiedClaims)
    }

    private fun detectContradictionAcrossSources(
        citations: List<KnowledgeCitation>,
        topicTokens: List<String>
    ): Boolean {
        if (citations.size < 2) return false
        val affirmative = listOf("confirmed", "true", "launched", "approved", "নিশ্চিত", "সত্য", "চালু হয়েছে")
        val negative = listOf("false", "hoax", "denied", "cancelled", "not true", "মিথ্যা", "গুজব", "বাতিল", "নয়")

        var hasAff = false
        var hasNeg = false
        for (c in citations) {
            val lower = c.snippet.lowercase(Locale.ROOT)
            val relevant = topicTokens.isEmpty() || topicTokens.any { lower.contains(it) }
            if (!relevant) continue
            if (affirmative.any { lower.contains(it) }) hasAff = true
            if (negative.any { lower.contains(it) }) hasNeg = true
        }
        return hasAff && hasNeg
    }
}
