package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.data.local.JarvisFrequentContactEntity
import com.example.data.local.AssistantDao
import com.example.data.local.AssistantDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ResolvedContact(
    val contactId: Long = 0L,
    val name: String,
    val primaryPhoneNumber: String,
    val allPhoneNumbers: List<String> = emptyList(),
    val alias: String = "",
    val isFavorite: Boolean = false,
    val source: String = "ANDROID_CONTACTS" // "DATABASE_ALIAS", "ANDROID_CONTACTS", "DIRECT_NUMBER"
)

data class ContactSearchResult(
    val query: String,
    val matches: List<ResolvedContact>,
    val needsDisambiguation: Boolean = false,
    val status: String, // "FOUND", "MULTIPLE_MATCHES", "NOT_FOUND", "PERMISSION_DENIED"
    val summaryBengali: String
)

object JarvisContactSearchEngine {

    private val BENGALI_DIGITS = mapOf(
        '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
        '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
    )

    private val COMMON_RELATIONSHIP_ALIASES = mapOf(
        "বাবা" to listOf("Baba", "Father", "Abbu", "আব্বু"),
        "মা" to listOf("Maa", "Mother", "Ammu", "আম্মু"),
        "আব্বু" to listOf("Baba", "Father", "Abbu", "বাবা"),
        "আম্মু" to listOf("Maa", "Mother", "Ammu", "মা"),
        "ভাই" to listOf("Bhai", "Brother"),
        "বোন" to listOf("Bon", "Sister"),
        "ডাক্তার" to listOf("Doctor", "Dr")
    )

    /**
     * Converts Bengali digits to standard Western digits
     */
    fun convertBengaliDigits(input: String): String {
        return input.map { BENGALI_DIGITS[it] ?: it }.joinToString("")
    }

    /**
     * Normalizes Bangladesh and international telephone numbers
     */
    fun normalizePhoneNumber(input: String): String {
        val converted = convertBengaliDigits(input.trim())
        val digitsAndPlus = converted.filter { it.isDigit() || it == '+' }
        
        return when {
            digitsAndPlus.startsWith("+8801") -> digitsAndPlus
            digitsAndPlus.startsWith("8801") -> "+$digitsAndPlus"
            digitsAndPlus.startsWith("01") && digitsAndPlus.length == 11 -> "+88$digitsAndPlus"
            else -> digitsAndPlus
        }
    }

    /**
     * Checks if a string represents an explicit phone number rather than a contact name
     */
    fun isPurePhoneNumber(input: String): Boolean {
        val converted = convertBengaliDigits(input.trim())
        val cleaned = converted.filter { it.isDigit() || it == '+' }
        val letterCount = input.count { it.isLetter() }
        return letterCount == 0 && cleaned.length >= 3
    }

    /**
     * High-performance, permission-aware contact search querying Room aliases and Android ContactsContract.
     */
    suspend fun searchContact(
        context: Context,
        query: String,
        dao: AssistantDao? = null
    ): ContactSearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext ContactSearchResult(
                query = query,
                matches = emptyList(),
                needsDisambiguation = false,
                status = "NOT_FOUND",
                summaryBengali = "কন্টাক্টের নাম বা ফোন নম্বর খালি।"
            )
        }

        // 1. Direct phone number check
        if (isPurePhoneNumber(trimmed)) {
            val normalized = normalizePhoneNumber(trimmed)
            val direct = ResolvedContact(
                contactId = 0L,
                name = normalized,
                primaryPhoneNumber = normalized,
                allPhoneNumbers = listOf(normalized),
                source = "DIRECT_NUMBER"
            )
            return@withContext ContactSearchResult(
                query = trimmed,
                matches = listOf(direct),
                needsDisambiguation = false,
                status = "FOUND",
                summaryBengali = "সরাসরি ফোন নম্বর সনাক্ত হয়েছে: $normalized"
            )
        }

        val allMatches = mutableListOf<ResolvedContact>()
        val seenPhoneNumbers = mutableSetOf<String>()

        // 2. Query Room frequent contacts / aliases if DAO provided or from getDatabase
        val activeDao = dao ?: try {
            AssistantDatabase.getDatabase(context).assistantDao()
        } catch (_: Exception) {
            null
        }

        if (activeDao != null) {
            try {
                val dbMatch = activeDao.getFrequentContactByAliasOrNameSync(trimmed)
                if (dbMatch != null) {
                    val norm = normalizePhoneNumber(dbMatch.phoneNumber)
                    allMatches.add(
                        ResolvedContact(
                            contactId = dbMatch.id,
                            name = dbMatch.name,
                            primaryPhoneNumber = norm,
                            allPhoneNumbers = listOf(norm),
                            alias = dbMatch.alias,
                            isFavorite = dbMatch.isFavorite,
                            source = "DATABASE_ALIAS"
                        )
                    )
                    seenPhoneNumbers.add(norm)
                }

                // Scan all saved frequent contacts for multiple partial/alias matches (for disambiguation)
                val qLower = trimmed.lowercase()
                activeDao.getAllFrequentContactsSync().forEach { fc ->
                    val nameHit = fc.name.lowercase().contains(qLower)
                    val aliasHit = fc.alias.isNotBlank() && fc.alias.lowercase().contains(qLower)
                    val relHit = fc.relationship.isNotBlank() && fc.relationship.lowercase().contains(qLower)
                    if (nameHit || aliasHit || relHit) {
                        val norm = normalizePhoneNumber(fc.phoneNumber)
                        if (norm.isNotBlank() && !seenPhoneNumbers.contains(norm)) {
                            allMatches.add(
                                ResolvedContact(
                                    contactId = fc.id,
                                    name = fc.name,
                                    primaryPhoneNumber = norm,
                                    allPhoneNumbers = listOf(norm),
                                    alias = fc.alias,
                                    isFavorite = fc.isFavorite,
                                    source = "DATABASE_ALIAS"
                                )
                            )
                            seenPhoneNumbers.add(norm)
                        }
                    }
                }

                // Check relationship aliases (e.g., "বাবা" -> "Baba")
                COMMON_RELATIONSHIP_ALIASES[trimmed.lowercase()]?.forEach { altName ->
                    val altMatch = activeDao.getFrequentContactByAliasOrNameSync(altName)
                    if (altMatch != null) {
                        val norm = normalizePhoneNumber(altMatch.phoneNumber)
                        if (!seenPhoneNumbers.contains(norm)) {
                            allMatches.add(
                                ResolvedContact(
                                    contactId = altMatch.id,
                                    name = altMatch.name,
                                    primaryPhoneNumber = norm,
                                    allPhoneNumbers = listOf(norm),
                                    alias = altMatch.alias.ifBlank { trimmed },
                                    isFavorite = altMatch.isFavorite,
                                    source = "DATABASE_ALIAS"
                                )
                            )
                            seenPhoneNumbers.add(norm)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore room error
            }
        }

        // 3. Check READ_CONTACTS permission
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPerm) {
            return@withContext if (allMatches.isNotEmpty()) {
                ContactSearchResult(
                    query = trimmed,
                    matches = allMatches,
                    needsDisambiguation = allMatches.size > 1,
                    status = if (allMatches.size > 1) "MULTIPLE_MATCHES" else "FOUND",
                    summaryBengali = "জারভিস সংরক্ষিত কন্টাক্ট থেকে পাওয়া গেছে: ${allMatches.first().name} (${allMatches.first().primaryPhoneNumber})"
                )
            } else {
                ContactSearchResult(
                    query = trimmed,
                    matches = emptyList(),
                    needsDisambiguation = false,
                    status = "PERMISSION_DENIED",
                    summaryBengali = "ফোনের কন্টাক্ট লিস্ট পড়তে 'Contacts' পারমিশন প্রয়োজন। দয়া করে সেটিংস থেকে পারমিশন দিন।"
                )
            }
        }

        // 4. Query Android ContactsContract.CommonDataKinds.Phone
        try {
            val queriesToTry = mutableListOf(trimmed)
            COMMON_RELATIONSHIP_ALIASES[trimmed.lowercase()]?.let { queriesToTry.addAll(it) }

            val contactGroups = mutableMapOf<Long, MutableList<Pair<String, String>>>() // contactId -> list of (name, number)

            for (q in queriesToTry.distinct()) {
                val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                val selectionArgs = arrayOf("%$q%", "%$q%")

                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                    ),
                    selection,
                    selectionArgs,
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (cursor.moveToNext()) {
                        val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "অপরিচিত" else "অপরিচিত"
                        val rawNumber = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                        val normalized = normalizePhoneNumber(rawNumber)

                        if (normalized.isNotBlank()) {
                            val list = contactGroups.getOrPut(contactId) { mutableListOf() }
                            if (list.none { it.second == normalized }) {
                                list.add(Pair(name, normalized))
                            }
                        }
                    }
                }
            }

            // Convert grouped contacts
            for ((id, numbers) in contactGroups) {
                if (numbers.isEmpty()) continue
                val primaryName = numbers.first().first
                val phoneList = numbers.map { it.second }.distinct()
                val primaryNum = phoneList.first()

                if (!seenPhoneNumbers.contains(primaryNum)) {
                    allMatches.add(
                        ResolvedContact(
                            contactId = id,
                            name = primaryName,
                            primaryPhoneNumber = primaryNum,
                            allPhoneNumbers = phoneList,
                            source = "ANDROID_CONTACTS"
                        )
                    )
                    seenPhoneNumbers.addAll(phoneList)
                }
            }
        } catch (e: Exception) {
            // ContactsContract query error handling
        }

        if (allMatches.isEmpty()) {
            return@withContext ContactSearchResult(
                query = trimmed,
                matches = emptyList(),
                needsDisambiguation = false,
                status = "NOT_FOUND",
                summaryBengali = "'$trimmed' নামে কোনো কন্টাক্ট পাওয়া যায়নি।"
            )
        }

        val needsDisambig = allMatches.size > 1 || (allMatches.size == 1 && allMatches[0].allPhoneNumbers.size > 1)
        val status = if (allMatches.size > 1) "MULTIPLE_MATCHES" else "FOUND"

        val summary = if (allMatches.size == 1) {
            val contact = allMatches[0]
            if (contact.allPhoneNumbers.size > 1) {
                "${contact.name} এর একাধিক নম্বর পাওয়া গেছে: ${contact.allPhoneNumbers.joinToString(", ")}। কোন নম্বরে সংযোগ করতে চান?"
            } else {
                "${contact.name} এর নম্বর পাওয়া গেছে: ${contact.primaryPhoneNumber}"
            }
        } else {
            "${allMatches.size}টি কন্টাক্ট পাওয়া গেছে: ${allMatches.take(3).joinToString { "${it.name} (${it.primaryPhoneNumber})" }}। আপনি কার সাথে যোগাযোগ করতে চান?"
        }

        ContactSearchResult(
            query = trimmed,
            matches = allMatches,
            needsDisambiguation = needsDisambig,
            status = status,
            summaryBengali = summary
        )
    }

    /**
     * Resolves contact name from an incoming/outgoing phone number using PhoneLookup
     */
    fun resolveContactNameFromNumber(context: Context, phoneNumber: String): String {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) return "অপরিচিত কলার"

        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleanNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) ?: phoneNumber else phoneNumber
                } else {
                    phoneNumber
                }
            } ?: phoneNumber
        } catch (_: Exception) {
            phoneNumber
        }
    }
}
