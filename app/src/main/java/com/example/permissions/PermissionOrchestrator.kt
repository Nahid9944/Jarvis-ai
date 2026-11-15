package com.example.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PermissionOrchestrator {

    private val _currentRequest = MutableStateFlow<PermissionRequestEvent?>(null)
    val currentRequest: StateFlow<PermissionRequestEvent?> = _currentRequest.asStateFlow()

    private val postponedIds = mutableSetOf<String>()
    private val permanentlyDeniedIds = mutableSetOf<String>()

    private val _grantedMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val grantedMap: StateFlow<Map<String, Boolean>> = _grantedMap.asStateFlow()

    @Volatile
    var sessionPermissionHandler: ((Context, PermissionSpec) -> Unit)? = null
        private set

    fun setSessionPermissionHandler(handler: ((Context, PermissionSpec) -> Unit)?) {
        sessionPermissionHandler = handler
    }

    fun request(
        spec: PermissionSpec,
        reason: String? = null,
        onResolved: ((Boolean) -> Unit)? = null
    ) {
        _currentRequest.value = PermissionRequestEvent(
            spec = spec,
            reason = reason,
            onResolved = onResolved
        )
    }

    fun dismiss() {
        _currentRequest.value?.onResolved?.invoke(false)
        _currentRequest.value = null
    }

    fun notifyResolved(granted: Boolean) {
        val event = _currentRequest.value
        event?.onResolved?.invoke(granted)
        _currentRequest.value = null
    }

    fun markPostponed(specId: String) {
        postponedIds.add(specId)
        dismiss()
    }

    fun isPostponed(specId: String): Boolean = postponedIds.contains(specId)

    fun markPermanentlyDenied(specId: String) {
        permanentlyDeniedIds.add(specId)
        dismiss()
    }

    fun isPermanentlyDenied(specId: String): Boolean = permanentlyDeniedIds.contains(specId)

    fun clearPostponed(specId: String) {
        postponedIds.remove(specId)
    }

    fun resetAllSuppression() {
        postponedIds.clear()
        permanentlyDeniedIds.clear()
    }

    fun checkStatus(context: Context, id: String): PermissionOutcome {
        val spec = PermissionRegistry.get(id) ?: return PermissionOutcome.Granted
        val granted = spec.isGranted(context)
        if (granted) return PermissionOutcome.Granted
        if (isPermanentlyDenied(id)) return PermissionOutcome.PermanentlyDenied(spec)
        if (isPostponed(id)) return PermissionOutcome.UserPostponed
        return PermissionOutcome.Missing(spec)
    }

    fun ensure(context: Context, vararg permissionIds: String): PermissionOutcome {
        for (id in permissionIds) {
            val status = checkStatus(context, id)
            if (status !is PermissionOutcome.Granted) {
                val spec = PermissionRegistry.get(id)
                if (spec != null) {
                    request(spec)
                }
                return status
            }
        }
        return PermissionOutcome.Granted
    }

    fun recheckAll(context: Context): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for (spec in PermissionRegistry.getAll()) {
            val isG = try {
                spec.isGranted(context)
            } catch (e: Exception) {
                false
            }
            result[spec.id] = isG
        }
        _grantedMap.value = result
        return result
    }

    fun getCapabilityScore(context: Context): Pair<Int, Int> {
        val total = PermissionRegistry.getAll().size
        val granted = PermissionRegistry.getGrantedCount(context)
        return Pair(granted, total)
    }

    fun openPermissionSettings(context: Context, spec: PermissionSpec) {
        try {
            val customIntent = spec.settingsIntent?.invoke(context)
            if (customIntent != null) {
                context.startActivity(customIntent)
            } else {
                // Fallback to app details settings
                val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(appSettingsIntent)
            }
        } catch (e: Exception) {
            // General settings fallback
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            } catch (e2: Exception) {
                // Ignore
            }
        }
    }
}
