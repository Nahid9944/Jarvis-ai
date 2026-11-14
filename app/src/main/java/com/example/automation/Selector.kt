package com.example.automation

enum class By {
    ID,          // e.g. "com.whatsapp:id/send"
    TEXT,        // exact or case-insensitive text
    DESC,        // contentDescription
    CLASS_INDEX, // e.g. "android.widget.Button:0"
    REGEX        // regex matching on text or desc
}

data class Selector(
    val by: By,
    val value: String,
    val alts: List<String> = emptyList(),   // mandatory alternatives for multilingual / version robustness
    val within: Selector? = null,           // container scope
    val index: Int? = null,
    val requireEnabled: Boolean = true,
    val requireVisible: Boolean = true
)
