package com.example.data.repository

import android.content.Context
import android.os.Build
import com.example.data.local.AppUserEntity
import com.example.data.local.AssistantDao
import com.example.service.LiveLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class NameValidationResult {
    object Available : NameValidationResult()
    object Empty : NameValidationResult()
    object AlreadyTaken : NameValidationResult()
    object Blocked : NameValidationResult()
}

/**
 * Pure local Single-Owner Device & Telemetry Repository for NAHID AI.
 * Legacy Pharmacy Firebase RTDB remote lockouts and client polling have been completely removed.
 */
class UserManagementRepository(
    private val dao: AssistantDao,
    private val context: Context
) {
    private val prefs = context.getSharedPreferences("nahid_owner_mgmt", Context.MODE_PRIVATE).also { target ->
        val legacy = context.getSharedPreferences("janani_user_mgmt", Context.MODE_PRIVATE)
        if (target.all.isEmpty() && legacy.all.isNotEmpty()) {
            val ed = target.edit()
            legacy.all.forEach { (k, v) ->
                when (v) {
                    is String -> ed.putString(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                }
            }
            ed.apply()
            legacy.edit().clear().apply()
        }
    }

    val isMasterApp: Boolean = true

    val persistentDeviceId: String = run {
        val ssaid = try {
            android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        if (!ssaid.isNullOrBlank() && ssaid != "9774d56d682e549c") {
            "dev_$ssaid"
        } else {
            prefs.getString("installation_id", null) ?: run {
                val newId = "dev_" + UUID.randomUUID().toString().replace("-", "").take(12)
                prefs.edit().putString("installation_id", newId).apply()
                newId
            }
        }
    }

    val installationId: String = persistentDeviceId

    val currentUserId: String = run {
        val hash = persistentDeviceId.removePrefix("dev_").take(10)
        "owner_$hash"
    }

    fun getOrGenerateUserNumber(): String {
        val existing = prefs.getString("user_number", null)
        if (!existing.isNullOrBlank()) return existing
        val hash = Math.abs(persistentDeviceId.hashCode())
        val num = 10000 + (hash % 90000)
        val newNum = "UID-$num"
        prefs.edit().putString("user_number", newNum).apply()
        return newNum
    }

    val userNumber: String = getOrGenerateUserNumber()

    val manufacturer: String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model: String = Build.MODEL
    val currentDeviceModel: String = "$manufacturer $model"
    val currentDeviceName: String = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"

    val androidVersion: String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val appVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "4.0"
    } catch (e: Exception) {
        "4.0"
    }

    private val _isCurrentUserBlocked = MutableStateFlow(false)
    val isCurrentUserBlocked: StateFlow<Boolean> = _isCurrentUserBlocked.asStateFlow()

    private val _locationSharingEnabled = MutableStateFlow(prefs.getBoolean("loc_sharing", false))
    val locationSharingEnabled: StateFlow<Boolean> = _locationSharingEnabled.asStateFlow()

    fun getAllUsersFlow(): Flow<List<AppUserEntity>> = dao.getAllUsers()

    fun isUserRegistered(): Boolean = true

    fun getRegisteredUserName(): String {
        return prefs.getString("registered_user_name", "Masum Billah Nahid") ?: "Masum Billah Nahid"
    }

    suspend fun checkNameValidation(name: String): NameValidationResult = withContext(Dispatchers.IO) {
        if (name.trim().isBlank()) NameValidationResult.Empty else NameValidationResult.Available
    }

    suspend fun registerUser(name: String): Result<AppUserEntity> = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifBlank { "Masum Billah Nahid" }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("registered_user_name", cleanName)
            .putBoolean("is_registered", true)
            .apply()

        val prof = dao.getProfileSync()
        val user = AppUserEntity(
            userId = currentUserId,
            userNumber = prof?.mobileNumber?.takeIf { it.isNotBlank() } ?: userNumber,
            profileName = cleanName,
            deviceId = installationId,
            deviceModel = currentDeviceModel,
            deviceName = currentDeviceName,
            manufacturer = manufacturer,
            installationId = installationId,
            appVersion = appVersion,
            androidVersion = androidVersion,
            isOnline = true,
            isMasterApp = true,
            lastLoginAt = now,
            lastLogoutAt = 0L,
            lastSeenAt = now,
            isBlocked = false,
            locationSharingEnabled = _locationSharingEnabled.value,
            latitude = if (_locationSharingEnabled.value) prefs.getFloat("lat", 0f).toDouble().takeIf { it != 0.0 } else null,
            longitude = if (_locationSharingEnabled.value) prefs.getFloat("lng", 0f).toDouble().takeIf { it != 0.0 } else null,
            accuracy = if (_locationSharingEnabled.value) prefs.getFloat("accuracy", 0f).takeIf { it != 0f } else null,
            locationAddress = prefs.getString("loc_address", "") ?: "",
            locationUpdatedAt = prefs.getLong("loc_updated_at", 0L),
            stationName = prof?.systemName?.takeIf { it.isNotBlank() } ?: "NAHID AI CORE",
            stationLocation = prof?.location ?: "",
            photoUri = prof?.photoUri
        )
        dao.insertUser(user)
        Result.success(user)
    }

    suspend fun recordLogin(profileName: String) {
        registerUser(profileName)
    }

    suspend fun recordLogout() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_logout_at", now).apply()
        dao.updateUserPresence(currentUserId, isOnline = false, lastSeenAt = now)
    }

    suspend fun setLocationSharing(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean("loc_sharing", enabled).apply()
        _locationSharingEnabled.value = enabled
        if (enabled) {
            LiveLocationService.start(context)
        } else {
            LiveLocationService.stop(context)
        }
    }

    suspend fun updateLocation(lat: Double, lng: Double, address: String, accuracy: Float?) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putFloat("lat", lat.toFloat())
            .putFloat("lng", lng.toFloat())
            .putFloat("accuracy", accuracy ?: 0f)
            .putString("loc_address", address)
            .putLong("loc_updated_at", now)
            .apply()
        dao.updateUserLocationDetailed(currentUserId, lat, lng, accuracy, address, now)
    }

    suspend fun setBlockUser(
        userId: String,
        block: Boolean,
        deviceModel: String = "",
        deviceId: String = "",
        profileName: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        dao.updateUserBlockStatus(userId, block)
        true
    }

    suspend fun checkBlockedStatusFromCloud(): Boolean = false

    suspend fun fetchAllUsers(): List<AppUserEntity> = withContext(Dispatchers.IO) {
        dao.getAllUsersSync()
    }
}
