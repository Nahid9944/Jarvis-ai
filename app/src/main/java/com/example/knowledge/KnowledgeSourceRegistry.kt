package com.example.knowledge

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Curated trustworthy public knowledge source registry with authority weighting,
 * per-domain crawl cooldown tracking, and blocked private/unsafe host guards.
 */
object KnowledgeSourceRegistry {

    private val lastDomainAccessMs = ConcurrentHashMap<String, Long>()

    val CURATED_SOURCES: List<KnowledgeSourceSpec> = listOf(
        KnowledgeSourceSpec("bd_portal", "Bangladesh National Portal", "bangladesh.gov.bd", "GOV_BD", 0.96f, 2000L),
        KnowledgeSourceSpec("bb_org", "Bangladesh Bank", "bb.org.bd", "FINANCE_GOV_BD", 0.96f, 2000L),
        KnowledgeSourceSpec("bbs_gov", "Bangladesh Bureau of Statistics", "bbs.gov.bd", "DATA_GOV_BD", 0.95f, 2000L),
        KnowledgeSourceSpec("pubmed", "PubMed / NCBI", "pubmed.ncbi.nlm.nih.gov", "MEDICAL_SCIENCE", 0.95f, 1500L),
        KnowledgeSourceSpec("who", "World Health Organization", "who.int", "HEALTH_GLOBAL", 0.95f, 1500L),
        KnowledgeSourceSpec("crossref", "Crossref Scholarly Metadata", "api.crossref.org", "RESEARCH_PAPERS", 0.92f, 1200L),
        KnowledgeSourceSpec("openalex", "OpenAlex Research Index", "api.openalex.org", "RESEARCH_PAPERS", 0.92f, 1200L),
        KnowledgeSourceSpec("wiki_bn", "Bangla Wikipedia", "bn.wikipedia.org", "ENCYCLOPEDIA", 0.88f, 1000L),
        KnowledgeSourceSpec("wiki_en", "English Wikipedia", "en.wikipedia.org", "ENCYCLOPEDIA", 0.88f, 1000L),
        KnowledgeSourceSpec("bbc_bn", "BBC News Bangla", "bbc.com", "NEWS_GLOBAL", 0.87f, 1500L),
        KnowledgeSourceSpec("github", "GitHub Official Docs & Repos", "github.com", "DEVELOPER_TECH", 0.86f, 1000L),
        KnowledgeSourceSpec("android_dev", "Android Developers", "developer.android.com", "DEVELOPER_TECH", 0.94f, 1200L),
        KnowledgeSourceSpec("prothomalo", "Prothom Alo", "prothomalo.com", "NEWS_BD", 0.84f, 1500L),
        KnowledgeSourceSpec("bdnews24", "bdnews24.com", "bdnews24.com", "NEWS_BD", 0.82f, 1500L)
    )

    fun getAuthorityWeight(domain: String): Float {
        val clean = domain.lowercase(Locale.ROOT).removePrefix("www.")
        if (clean.isBlank()) return 0.35f
        val exact = CURATED_SOURCES.firstOrNull { clean == it.domain || clean.endsWith(".${it.domain}") }
        if (exact != null) return exact.authorityWeight
        return when {
            clean.endsWith(".gov.bd") || clean.endsWith(".gov") || clean.endsWith(".edu") || clean.endsWith(".edu.bd") -> 0.90f
            clean.endsWith(".org") -> 0.72f
            else -> 0.55f
        }
    }

    fun isPublicSafeHttpUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            !isBlockedPrivateHost(host)
        } catch (_: Exception) {
            false
        }
    }

    fun isBlockedPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase(Locale.ROOT).removeSurrounding("[", "]")
        if (h.isBlank()) return true
        if (h == "localhost" || h == "::1" || h == "0.0.0.0" || h.endsWith(".local") || h.endsWith(".internal")) return true
        if (h == "169.254.169.254" || h.startsWith("169.254.")) return true
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")) return true
        val match172 = Regex("""^172\.(\d{1,3})\.""").find(h)
        if (match172 != null) {
            val secondOctet = match172.groupValues[1].toIntOrNull() ?: 0
            if (secondOctet in 16..31) return true
        }
        return false
    }

    fun tryAcquireDomainSlot(domain: String): Boolean {
        val clean = domain.lowercase(Locale.ROOT).removePrefix("www.")
        val minInterval = CURATED_SOURCES.firstOrNull { clean.endsWith(it.domain) }?.minIntervalMs ?: 800L
        val now = System.currentTimeMillis()
        val prev = lastDomainAccessMs[clean] ?: 0L
        if ((now - prev) < minInterval) return false
        lastDomainAccessMs[clean] = now
        return true
    }

    fun resetRateLimiterForTest() {
        lastDomainAccessMs.clear()
    }
}
