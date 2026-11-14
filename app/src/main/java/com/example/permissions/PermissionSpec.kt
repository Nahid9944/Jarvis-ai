package com.example.permissions

import android.content.Context
import android.content.Intent

enum class PermissionKind {
    RUNTIME,          // standard Android runtime permission (ActivityResultLauncher)
    SPECIAL_ACCESS,   // System Settings page (Accessibility, Overlay, Notification Listener, Usage, etc.)
    ROLE,             // Default dialer/SMS role
    PER_SESSION,      // Media projection screen capture token
    MANIFEST_ONLY     // Declared in manifest
}

data class PermissionSpec(
    val id: String,
    val kind: PermissionKind,
    val androidPermissions: List<String> = emptyList(),
    val titleBn: String,
    val whyBn: String,
    val whatItEnablesBn: String,
    val privacyNoteBn: String,
    val settingsIntent: ((Context) -> Intent)? = null,
    val isGranted: (Context) -> Boolean,
    val requiresRestart: Boolean = false,
    val toolIds: List<String> = emptyList(),
    val iconKey: String = "security"
)
