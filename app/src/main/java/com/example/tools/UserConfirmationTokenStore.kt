package com.example.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Secure User Confirmation Token Store for J.A.R.V.I.S. OS.
 * Strictly prevents AI-model-supplied confirmation bypass (e.g. confirmed=true or _human_gate_approved=true).
 * Confirmation tokens can ONLY be created by real Android UI button click interactions.
 */
object UserConfirmationTokenStore {

    data class ConfirmationToken(
        val token: String,
        val toolName: String,
        val targetSignature: String,
        val expiresAt: Long,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val isValid: Boolean get() = System.currentTimeMillis() <= expiresAt
    }

    private val approvedTokens = ConcurrentHashMap<String, ConfirmationToken>()

    /**
     * Called EXCLUSIVELY by Compose UI user button click (e.g. JarvisHumanGateDialog or PermissionRationaleSheet).
     */
    fun grantUserApproval(
        toolName: String,
        targetSignature: String = "",
        validDurationMs: Long = 90_000L
    ): String {
        val tokenId = UUID.randomUUID().toString()
        val token = ConfirmationToken(
            token = tokenId,
            toolName = toolName,
            targetSignature = targetSignature,
            expiresAt = System.currentTimeMillis() + validDurationMs
        )
        approvedTokens[buildKey(toolName, targetSignature)] = token
        return tokenId
    }

    /**
     * Validates and consumes an approved user token.
     * Tokens can only be used once (one-time-use).
     */
    fun consumeApproval(toolName: String, targetSignature: String = ""): Boolean {
        cleanupExpired()
        val key = buildKey(toolName, targetSignature)
        val token = approvedTokens.remove(key)
        return token != null && token.isValid
    }

    fun hasPendingApproval(toolName: String, targetSignature: String = ""): Boolean {
        cleanupExpired()
        val key = buildKey(toolName, targetSignature)
        val token = approvedTokens[key]
        return token != null && token.isValid
    }

    fun revokeAll() {
        approvedTokens.clear()
    }

    private fun buildKey(toolName: String, targetSignature: String): String {
        return "$toolName:${targetSignature.trim().lowercase()}"
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        approvedTokens.entries.removeIf { !it.value.isValid || it.value.expiresAt < now }
    }
}
