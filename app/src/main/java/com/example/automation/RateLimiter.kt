package com.example.automation

data class Limits(
    val perMinute: Int,
    val perHour: Int
)

object RateLimiter {

    val limits = mapOf(
        "com.whatsapp" to Limits(perMinute = 8, perHour = 60),
        "com.whatsapp:contact" to Limits(perMinute = 3, perHour = 15),
        "com.facebook.katana" to Limits(perMinute = 6, perHour = 30),
        "com.google.android.youtube" to Limits(perMinute = 20, perHour = 200)
    )

    private val timestamps = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun canExecute(key: String): Pair<Boolean, String?> {
        val now = System.currentTimeMillis()
        val limit = limits[key] ?: return Pair(true, null)

        val history = timestamps.getOrPut(key) { mutableListOf() }
        // Clean history older than 1 hour
        history.removeAll { now - it > 3600_000L }

        val countLastMinute = history.count { now - it <= 60_000L }
        if (countLastMinute >= limit.perMinute) {
            val waitSec = 60 - ((now - history.last()) / 1000).coerceAtLeast(1)
            return Pair(false, "স্প্যাম প্রতিরোধে রেট লিমিট সক্রিয়। $waitSec সেকেন্ড পর চেষ্টা করুন।")
        }

        if (history.size >= limit.perHour) {
            return Pair(false, "ঘণ্টার সর্বোচ্চ সীমাতে পৌঁছে গেছেন ($key)। অনুগ্রহ করে পরে চেষ্টা করুন।")
        }

        return Pair(true, null)
    }

    @Synchronized
    fun recordExecution(key: String) {
        val history = timestamps.getOrPut(key) { mutableListOf() }
        history.add(System.currentTimeMillis())
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
    }
}
