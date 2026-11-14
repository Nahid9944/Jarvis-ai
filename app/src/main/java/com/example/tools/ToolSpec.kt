package com.example.tools

data class ToolParam(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
    val default: String? = null
)

data class ToolSpec(
    val name: String,
    val titleBn: String,
    val description: String,
    val category: ToolCategory,
    val parameters: Map<String, String> = emptyMap(),
    val requiredPermissionId: String? = null,
    val confirmationLevel: ConfirmationLevel = ConfirmationLevel.NONE,
    val isMutating: Boolean = false,
    val riskScore: Int = 10, // 0 - 100
    val auditRequired: Boolean = true,
    val enabled: Boolean = true
)
