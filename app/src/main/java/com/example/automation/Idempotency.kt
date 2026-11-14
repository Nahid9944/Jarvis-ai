package com.example.automation

import java.security.MessageDigest

object Idempotency {

    private val sentMessageCache = mutableMapOf<String, Long>()
    private const val WINDOW_MS = 60_000L // 60 seconds

    @Synchronized
    fun canSend(pkg: String, recipient: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val key = computeHash("$pkg:$recipient:$text")

        // Clean expired
        sentMessageCache.entries.removeAll { now - it.value > WINDOW_MS }

        val lastSent = sentMessageCache[key]
        if (lastSent != null && now - lastSent <= WINDOW_MS) {
            return false // duplicate detected
        }
        return true
    }

    @Synchronized
    fun recordSent(pkg: String, recipient: String, text: String) {
        val key = computeHash("$pkg:$recipient:$text")
        sentMessageCache[key] = System.currentTimeMillis()
    }

    @Synchronized
    fun reset() {
        sentMessageCache.clear()
    }

    private fun computeHash(input: String): String {
        return try {
            val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
}
