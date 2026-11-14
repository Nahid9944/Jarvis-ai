package com.example.tools

enum class ConfirmationLevel {
    NONE,               // Safe read-only queries, checks, status
    IMPLICIT_NOTICE,    // Low-risk action, announced after completion
    EXPLICIT_CONFIRM,   // Mutating action requiring user approval
    BIOMETRIC,          // High security or sensitive action
    HUMAN_GATE          // High risk external communication or irreversible deletion
}

enum class ToolCategory {
    SYSTEM_ACTIONS,
    DEVICE_CONTROL,
    COMMUNICATION,
    MEDIA_CONTROL,
    CAMERA_VISION,
    SCREEN_CAPTURE,
    LOCATION,
    APP_AUTOMATION,
    AI_MEMORY,
    PERSONAL_NOTEPAD,
    INTELLIGENCE_REPORTS,
    SECURITY_DIAGNOSTICS
}
