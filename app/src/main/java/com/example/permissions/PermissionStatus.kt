package com.example.permissions

sealed class PermissionOutcome {
    data object Granted : PermissionOutcome()
    data class Missing(val spec: PermissionSpec) : PermissionOutcome()
    data class PermanentlyDenied(val spec: PermissionSpec) : PermissionOutcome()
    data object UserPostponed : PermissionOutcome()
}

data class PermissionState(
    val spec: PermissionSpec,
    val isGranted: Boolean,
    val isDeniedPermanently: Boolean = false,
    val isPostponed: Boolean = false
)

data class PermissionRequestEvent(
    val spec: PermissionSpec,
    val reason: String? = null,
    val onResolved: ((Boolean) -> Unit)? = null
)
