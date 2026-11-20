package com.example.data.repository

import android.content.Context
import com.example.data.local.*
import com.example.security.SecureKeystoreVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CloudBackupInfo(
    val hasBackup: Boolean,
    val lastUpdatedFormatted: String,
    val memoryCount: Int,
    val taskCount: Int,
    val noteCount: Int,
    val chatCount: Int,
    val systemName: String,
    val backupLocationLabel: String = "লোকাল এনক্রিপ্টেড ভল্ট (AES-256-GCM)"
)

/**
 * Local Personal AI Vault Backup Repository.
 * Note: All legacy Pharmacy Firebase RTDB endpoints have been permanently removed.
 * Data is encrypted via Android Keystore AES-256-GCM (SecureKeystoreVault) and stored
 * inside app-private storage (nahid_ai_vault/nahid_ai_backup.json) with read-back verification.
 */
class FirebaseBackupRepository(
    private val dao: AssistantDao,
    private val context: Context
) {
    companion object {
        private const val VAULT_KEY_ALIAS = "nahid_ai_vault_backup_key"
    }

    private val vaultFile: File
        get() {
            val dir = File(context.filesDir, "nahid_ai_vault")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "nahid_ai_backup.json")
        }

    private fun readDecryptedVaultJson(): JSONObject {
        val raw = vaultFile.readText(Charsets.UTF_8).trim()
        val plaintext = if (raw.startsWith("{")) {
            raw
        } else {
            SecureKeystoreVault.decryptString(context, VAULT_KEY_ALIAS, raw)
        }
        if (plaintext.isBlank()) {
            throw IllegalStateException("ভল্ট ব্যাকআপ ডিক্রিপ্ট করা যায়নি।")
        }
        return JSONObject(plaintext)
    }

    suspend fun checkCloudBackupInfo(): Result<CloudBackupInfo> = withContext(Dispatchers.IO) {
        try {
            if (!vaultFile.exists() || vaultFile.length() == 0L) {
                return@withContext Result.success(
                    CloudBackupInfo(
                        hasBackup = false,
                        lastUpdatedFormatted = "লোকাল ভল্টে এখনও কোনো ব্যাকআপ তৈরি হয়নি",
                        memoryCount = 0,
                        taskCount = 0,
                        noteCount = 0,
                        chatCount = 0,
                        systemName = "NAHID AI"
                    )
                )
            }

            val json = readDecryptedVaultJson()
            val meta = json.optJSONObject("metadata")
            val lastUpdated = meta?.optLong("lastUpdated", 0L) ?: vaultFile.lastModified()
            val formattedDate = if (lastUpdated > 0L) {
                SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(lastUpdated))
            } else {
                "অজ্ঞাত সময়"
            }

            Result.success(
                CloudBackupInfo(
                    hasBackup = true,
                    lastUpdatedFormatted = formattedDate,
                    memoryCount = json.optJSONArray("memories")?.length() ?: 0,
                    taskCount = json.optJSONArray("tasks")?.length() ?: 0,
                    noteCount = json.optJSONArray("notes")?.length() ?: 0,
                    chatCount = json.optJSONArray("chats")?.length() ?: 0,
                    systemName = meta?.optString("systemName", "NAHID AI") ?: "NAHID AI"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadToCloud(progressCallback: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            progressCallback("NAHID AI মেমোরি, নোট ও টাস্ক সংগ্রহ করা হচ্ছে...")

            val memories = dao.getAllActiveMemoriesSync()
            val tasks = dao.getAllAiTasksSync()
            val notes = dao.getAllNotesSync()
            val chats = dao.getAllJarvisChatHistorySync()
            val profile = dao.getProfileSync()

            val rootJson = JSONObject()
            val metaJson = JSONObject().apply {
                put("lastUpdated", System.currentTimeMillis())
                put("systemName", profile?.systemName?.ifBlank { "NAHID AI" } ?: "NAHID AI")
                put("ownerName", profile?.ownerName ?: "Masum Billah Nahid")
                put("version", "4.0-local-vault")
            }
            rootJson.put("metadata", metaJson)

            val memoriesArray = JSONArray()
            for (m in memories) {
                memoriesArray.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("key", m.key)
                        put("content", m.content)
                        put("category", m.category)
                        put("importance", m.importance)
                        put("isPinned", m.isPinned)
                        put("createdAt", m.createdAt)
                        put("updatedAt", m.updatedAt)
                    }
                )
            }
            rootJson.put("memories", memoriesArray)

            val tasksArray = JSONArray()
            for (t in tasks) {
                tasksArray.put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("description", t.description)
                        put("scheduledTime", t.scheduledTime)
                        put("scheduledTimeString", t.scheduledTimeString)
                        put("status", t.status)
                        put("actionType", t.actionType)
                        put("targetCommand", t.targetCommand)
                        put("isExecuted", t.isExecuted)
                    }
                )
            }
            rootJson.put("tasks", tasksArray)

            val notesArray = JSONArray()
            for (n in notes) {
                notesArray.put(
                    JSONObject().apply {
                        put("id", n.id)
                        put("title", n.title)
                        put("content", n.content)
                        put("timestamp", n.timestamp)
                    }
                )
            }
            rootJson.put("notes", notesArray)

            val chatsArray = JSONArray()
            for (c in chats.takeLast(150)) {
                chatsArray.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("userPrompt", c.userPrompt)
                        put("aiResponse", c.aiResponse)
                        put("timestamp", c.timestamp)
                        put("commandType", c.commandType)
                        put("status", c.status)
                    }
                )
            }
            rootJson.put("chats", chatsArray)

            progressCallback("Android Keystore AES-256-GCM এনক্রিপ্ট করে ভল্টে সংরক্ষণ করা হচ্ছে...")
            val encryptedPayload = SecureKeystoreVault.encryptString(context, VAULT_KEY_ALIAS, rootJson.toString())
            if (encryptedPayload.isBlank()) {
                return@withContext Result.failure(Exception("ভল্ট এনক্রিপশন সম্পন্ন করা যায়নি।"))
            }
            vaultFile.writeText(encryptedPayload, Charsets.UTF_8)

            // Verify written encrypted vault cleanly on read-back
            val verified = readDecryptedVaultJson()
            val countTotal = (verified.optJSONArray("memories")?.length() ?: 0) +
                (verified.optJSONArray("tasks")?.length() ?: 0) +
                (verified.optJSONArray("notes")?.length() ?: 0)

            Result.success("এনক্রিপ্টেড লোকাল ভল্টে ব্যাকআপ সম্পন্ন ও যাচাইকৃত ($countTotal আইটেম)।")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFromCloud(progressCallback: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            progressCallback("এনক্রিপ্টেড লোকাল ভল্ট থেকে ব্যাকআপ পড়া হচ্ছে...")
            if (!vaultFile.exists() || vaultFile.length() == 0L) {
                return@withContext Result.failure(Exception("লোকাল ভল্টে কোনো ব্যাকআপ ফাইল পাওয়া যায়নি।"))
            }

            val rootJson = readDecryptedVaultJson()

            val existingMemKeys = dao.getAllActiveMemoriesSync().map { it.key.lowercase() }.toSet()
            val memoriesArray = rootJson.optJSONArray("memories")
            if (memoriesArray != null) {
                for (i in 0 until memoriesArray.length()) {
                    val obj = memoriesArray.getJSONObject(i)
                    val key = obj.optString("key", "")
                    if (key.isNotBlank() && key.lowercase() !in existingMemKeys) {
                        dao.insertMemory(
                            AiMemoryEntity(
                                key = key,
                                content = obj.optString("content", ""),
                                category = obj.optString("category", "USER_PREFERENCE"),
                                importance = obj.optInt("importance", 1),
                                isPinned = obj.optBoolean("isPinned", false),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }

            val tasksArray = rootJson.optJSONArray("tasks")
            if (tasksArray != null) {
                for (i in 0 until tasksArray.length()) {
                    val obj = tasksArray.getJSONObject(i)
                    val actType = obj.optString("actionType", "REMINDER").let {
                        if (it == "MEDICINE_DOSE") "REMINDER" else it
                    }
                    dao.insertAiTask(
                        AiTaskEntity(
                            title = obj.optString("title", ""),
                            description = obj.optString("description", ""),
                            scheduledTime = obj.optLong("scheduledTime", System.currentTimeMillis()),
                            scheduledTimeString = obj.optString("scheduledTimeString", ""),
                            status = obj.optString("status", "PENDING"),
                            actionType = actType,
                            targetCommand = obj.optString("targetCommand", ""),
                            isExecuted = obj.optBoolean("isExecuted", false)
                        )
                    )
                }
            }

            val notesArray = rootJson.optJSONArray("notes")
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val obj = notesArray.getJSONObject(i)
                    dao.insertNote(
                        NoteEntity(
                            title = obj.optString("title", ""),
                            content = obj.optString("content", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }

            Result.success("লোকাল ভল্ট থেকে সফলভাবে মেমোরি, টাস্ক ও নোট রিস্টোর হয়েছে!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
