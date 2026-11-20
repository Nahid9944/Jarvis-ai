package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.AiSettingsRepository
import com.example.ai.AiSettingsState
import com.example.audio.WakeWordModelManager
import com.example.service.JarvisAssistantRoleManager
import com.example.service.JarvisWakeWordService
import com.example.viewmodel.AssistantViewModel

@Composable
fun AiSettingsDialog(
    viewModel: AssistantViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentSettings by viewModel.aiSettings.collectAsState()
    val wakeWordOptIn by viewModel.wakeWordOptIn.collectAsState()

    var apiKeyInput by remember { mutableStateOf(currentSettings.apiKey.ifBlank { AiSettingsRepository.DEFAULT_API_KEY }) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var selectedModel by remember { mutableStateOf(currentSettings.modelName) }
    var selectedVoice by remember { mutableStateOf(currentSettings.voiceName) }
    var systemPromptInput by remember { mutableStateOf(currentSettings.systemInstruction) }
    var autoConnect by remember { mutableStateOf(currentSettings.autoConnect) }
    var selectedVolumeLevel by remember { mutableStateOf(currentSettings.voiceVolumeLevel) }
    var selectedLanguage by remember { mutableStateOf(currentSettings.preferredLanguage) }
    var offlineTtsEnabled by remember { mutableStateOf(currentSettings.offlineTtsEnabled) }
    var ttsSpeechRate by remember { mutableStateOf(currentSettings.ttsSpeechRate) }
    var silentMode by remember { mutableStateOf(currentSettings.silentMode) }
    var selectedSpeakingStyle by remember { mutableStateOf(currentSettings.speakingStyle) }

    var braveKeyInput by remember { mutableStateOf(currentSettings.braveSearchApiKey) }
    var tavilyKeyInput by remember { mutableStateOf(currentSettings.tavilySearchApiKey) }
    var githubTokenInput by remember { mutableStateOf(currentSettings.githubToken) }
    var showOptionalKeys by remember { mutableStateOf(false) }

    var customWakePhrasesInput by remember {
        mutableStateOf(
            WakeWordModelManager.getCustomWakePhrases(context)
                .filterNot { it in WakeWordModelManager.DEFAULT_WAKE_PHRASES }
                .joinToString(", ")
        )
    }
    val wakeCapabilities = remember { WakeWordModelManager.inspectCapabilities(context) }
    var assistantRoleStatus by remember { mutableStateOf(JarvisAssistantRoleManager.getStatus(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                assistantRoleStatus = JarvisAssistantRoleManager.getStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF334155))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0284C7).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "AI সেটিংস ও স্ট্যাটাস",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Gemini Live Voice Assistant",
                                fontSize = 11.5.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "বন্ধ করুন", tint = Color(0xFF94A3B8))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF1E293B))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Status Overview Card (Non-secret status)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Assistant Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (currentSettings.isApiKeyConfigured) Color(0xFF10B981) else Color(0xFFF59E0B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("AI Assistant", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Surface(
                                    color = if (currentSettings.isApiKeyConfigured) Color(0xFF065F46).copy(alpha = 0.5f) else Color(0xFF78350F).copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (currentSettings.isApiKeyConfigured) "সক্রিয় (Connected)" else "কনফিগার প্রয়োজন",
                                        color = if (currentSettings.isApiKeyConfigured) Color(0xFF6EE7B7) else Color(0xFFFCD34D),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFF334155))

                            // Model Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Model Status", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text(selectedModel, color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // Voice Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Voice Status", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("16kHz In / 24kHz Out PCM Live", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    // 2. Gemini Live API Key Configuration (Custom API Key support)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Gemini API Key (ভয়েস লাইভ)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Surface(
                                    color = if (apiKeyInput.isNotBlank()) Color(0xFF065F46).copy(alpha = 0.6f) else Color(0xFF7F1D1D).copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (apiKeyInput.isNotBlank()) "সক্রিয় (Active)" else "প্রয়োজন",
                                        color = if (apiKeyInput.isNotBlank()) Color(0xFF34D399) else Color(0xFFFCA5A5),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = "এই API Key ব্যবহার করে AI Gemini Live সরাসরি কথা বলে অডিওর মাধ্যমে সকল প্রশ্নের উত্তর দেবে।",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )

                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = {
                                    apiKeyInput = it
                                    testResult = null
                                },
                                placeholder = { Text("Gemini API Key দিন...", color = Color(0xFF64748B), fontSize = 12.sp) },
                                visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                        Icon(
                                            imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (isApiKeyVisible) "লুকান" else "দেখান",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedContainerColor = Color(0xFF0F172A),
                                    unfocusedContainerColor = Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        apiKeyInput = AiSettingsRepository.DEFAULT_API_KEY
                                        testResult = null
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ডিফল্ট Key দিন", fontSize = 11.sp, color = Color(0xFF38BDF8))
                                }

                                Button(
                                    onClick = {
                                        if (apiKeyInput.isNotBlank()) {
                                            isTestingConnection = true
                                            testResult = null
                                            viewModel.testAiConnection(apiKeyInput.trim(), selectedModel) { success, message ->
                                                isTestingConnection = false
                                                testResult = Pair(success, message)
                                            }
                                        }
                                    },
                                    enabled = apiKeyInput.isNotBlank() && !isTestingConnection,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (isTestingConnection) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("পরীক্ষা হচ্ছে...", fontSize = 11.5.sp)
                                    } else {
                                        Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("API টেস্ট করুন", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (testResult != null) {
                                val (ok, msg) = testResult!!
                                Surface(
                                    color = if (ok) Color(0xFF065F46).copy(alpha = 0.4f) else Color(0xFF7F1D1D).copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (ok) Color(0xFF10B981) else Color(0xFFEF4444)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = msg,
                                        color = if (ok) Color(0xFF6EE7B7) else Color(0xFFFCA5A5),
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 2B. Live Voice Model Selector
                    Column {
                        Text(
                            text = "জেমিনি লাইভ মডেল (Live Model Selector)",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AiSettingsRepository.AVAILABLE_MODELS.forEach { model ->
                                val isSelected = selectedModel == model
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0xFF0284C7).copy(alpha = 0.35f) else Color(0xFF1E293B),
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("model_selector_$model")
                                        .clickable { selectedModel = model }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = model,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF38BDF8) else Color.White
                                        )
                                        Text(
                                            text = if (model == AiSettingsRepository.DEFAULT_MODEL) "প্রাইমারি ডিফল্ট" else "প্রিভিউ ফলব্যাক",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Voice Selection
                    Column {
                        Text(
                            text = "সহকারীর ভয়েস (Voice Name)",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AiSettingsRepository.AVAILABLE_VOICES.chunked(4).forEach { voiceRow ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    voiceRow.forEach { voice ->
                                        val isSelected = selectedVoice == voice
                                        val descBn = AiSettingsRepository.VOICE_DESCRIPTIONS_BN[voice].orEmpty()
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) Color(0xFF059669) else Color(0xFF1E293B),
                                            border = BorderStroke(1.dp, if (isSelected) Color(0xFF10B981) else Color(0xFF334155)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag("voice_option_$voice")
                                                .clickable { selectedVoice = voice }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = voice,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else Color(0xFFE2E8F0)
                                                )
                                                if (descBn.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = descBn,
                                                        fontSize = 9.sp,
                                                        color = if (isSelected) Color(0xFFD1FAE5) else Color(0xFF94A3B8)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    repeat((4 - voiceRow.size).coerceAtLeast(0)) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "কথা বলার ধরন (Speaking Style)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AiSettingsRepository.AVAILABLE_SPEAKING_STYLES.forEach { style ->
                                val isStyleSelected = selectedSpeakingStyle == style
                                val styleLabel = when (style) {
                                    "SOFT" -> "কোমল"
                                    "SWEET" -> "মিষ্টি"
                                    "PROFESSIONAL" -> "পেশাদার"
                                    else -> "স্বাভাবিক"
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isStyleSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                    border = BorderStroke(1.dp, if (isStyleSelected) Color(0xFF38BDF8) else Color(0xFF334155)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("speaking_style_$style")
                                        .clickable { selectedSpeakingStyle = style }
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = styleLabel,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isStyleSelected) Color.White else Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. AI Voice Volume / Clarity
                    Column {
                        Text(
                            text = "AI Voice Volume (ভয়েস ভলিউম ও স্পষ্টতা)",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val isNormal = selectedVolumeLevel == "NORMAL"
                            val isLoud = selectedVolumeLevel == "LOUD"

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isNormal) Color(0xFF0284C7).copy(alpha = 0.35f) else Color(0xFF1E293B),
                                border = BorderStroke(1.dp, if (isNormal) Color(0xFF38BDF8) else Color(0xFF334155)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedVolumeLevel = "NORMAL" }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isNormal,
                                        onClick = { selectedVolumeLevel = "NORMAL" },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8), unselectedColor = Color(0xFF64748B))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text("স্বাভাবিক", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                        Text("1.0x সাধারণ মাত্রা", color = Color(0xFF94A3B8), fontSize = 10.5.sp)
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isLoud) Color(0xFF0284C7).copy(alpha = 0.35f) else Color(0xFF1E293B),
                                border = BorderStroke(1.dp, if (isLoud) Color(0xFF38BDF8) else Color(0xFF334155)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedVolumeLevel = "LOUD" }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isLoud,
                                        onClick = { selectedVolumeLevel = "LOUD" },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8), unselectedColor = Color(0xFF64748B))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text("উচ্চ স্পষ্টতা", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                        Text("1.15x স্ফটিক-স্বচ্ছ", color = Color(0xFF38BDF8), fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 4. Auto-Connect & Language
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("স্বয়ংক্রিয় লাইভ সংযোগ (Auto Connect)", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text("স্ক্রিন খোলার সাথে সাথে AI লাইভ চালু হবে", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Switch(
                                    checked = autoConnect,
                                    onCheckedChange = { autoConnect = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF0284C7),
                                        uncheckedThumbColor = Color(0xFF94A3B8),
                                        uncheckedTrackColor = Color(0xFF334155)
                                    )
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("প্রধান ভাষা (Primary Language)", color = Color(0xFFCBD5E1), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val languageChoices = listOf(
                                        "bn" to "বাংলা",
                                        "en" to "English",
                                        "mixed" to "Banglish / মিশ্র"
                                    )
                                    languageChoices.forEach { (code, label) ->
                                        val isLangSelected = selectedLanguage == code
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isLangSelected) Color(0xFF0284C7).copy(alpha = 0.35f) else Color(0xFF0F172A),
                                            border = BorderStroke(1.dp, if (isLangSelected) Color(0xFF38BDF8) else Color(0xFF334155)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("language_option_$code")
                                                .clickable { selectedLanguage = code }
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isLangSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isLangSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("অফলাইন বাংলা/ইংরেজি ভয়েস ফলব্যাক (TTS)", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Gemini Live বিচ্ছিন্ন থাকলে অফলাইন কণ্ঠে উত্তর বলবে", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = offlineTtsEnabled,
                                        onCheckedChange = { offlineTtsEnabled = it },
                                        modifier = Modifier.testTag("offline_tts_switch"),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFF0284C7),
                                            uncheckedThumbColor = Color(0xFF94A3B8),
                                            uncheckedTrackColor = Color(0xFF334155)
                                        )
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("নীরব / টেক্সট-অনলি মোড (Silent Mode)", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text("ভয়েস আউটপুট সাময়িকভাবে মিউট রেখে কেবল টেক্সট দেখাবে", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = silentMode,
                                        onCheckedChange = { silentMode = it },
                                        modifier = Modifier.testTag("silent_mode_switch"),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFFF59E0B),
                                            uncheckedThumbColor = Color(0xFF94A3B8),
                                            uncheckedTrackColor = Color(0xFF334155)
                                        )
                                    )
                                }

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("কথা বলার গতি (Speech Rate)", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                                        Text("${String.format(java.util.Locale.US, "%.2f", ttsSpeechRate)}x", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = ttsSpeechRate,
                                        onValueChange = { ttsSpeechRate = it },
                                        valueRange = 0.7f..1.5f,
                                        modifier = Modifier.testTag("tts_speech_rate_slider"),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF38BDF8),
                                            activeTrackColor = Color(0xFF0284C7)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 4B. Hands-Free Wake Word & Default System Assistant Role
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("হ্যান্ডস-ফ্রি ওয়েক-ওয়ার্ড (Opt-In)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "ইঞ্জিন: ${wakeCapabilities.engineModeLabel}",
                                        color = Color(0xFF34D399),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Switch(
                                    checked = wakeWordOptIn,
                                    onCheckedChange = { enabled ->
                                        viewModel.setWakeWordOptIn(enabled)
                                        if (enabled) {
                                            JarvisWakeWordService.start(context)
                                        } else {
                                            JarvisWakeWordService.stop(context)
                                        }
                                    },
                                    modifier = Modifier.testTag("wake_word_opt_in_switch"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF059669),
                                        uncheckedThumbColor = Color(0xFF94A3B8),
                                        uncheckedTrackColor = Color(0xFF334155)
                                    )
                                )
                            }

                            Text(
                                text = wakeCapabilities.detailBengali,
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )

                            OutlinedTextField(
                                value = customWakePhrasesInput,
                                onValueChange = { customWakePhrasesInput = it },
                                label = { Text("কাস্টম ওয়েক ফ্রেজ (কমা দিয়ে আলাদা)", fontSize = 11.sp) },
                                placeholder = { Text("যেমন: এই নাহিদ, বন্ধু জারভিস, hey maya", fontSize = 11.sp, color = Color(0xFF64748B)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF10B981),
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedContainerColor = Color(0xFF0F172A),
                                    unfocusedContainerColor = Color(0xFF0F172A)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                            )

                            HorizontalDivider(color = Color(0xFF334155))

                            Text(
                                text = assistantRoleStatus.statusBengali,
                                color = if (assistantRoleStatus.isDefaultAssistant) Color(0xFF6EE7B7) else Color(0xFFFCD34D),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )

                            OutlinedButton(
                                onClick = {
                                    val roleIntent = JarvisAssistantRoleManager.createSelectAssistantIntent(context)
                                    try {
                                        context.startActivity(roleIntent)
                                    } catch (_: Exception) {
                                    }
                                    assistantRoleStatus = JarvisAssistantRoleManager.getStatus(context)
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("set_default_assistant_button")
                            ) {
                                Icon(Icons.Default.Assistant, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (assistantRoleStatus.isDefaultAssistant) "ডিফল্ট অ্যাসিস্ট্যান্ট সক্রিয় আছে" else "NAHID AI-কে ডিফল্ট অ্যাসিস্ট্যান্ট করুন",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // 4C. Optional Web Search & GitHub PAT Keys (Encrypted Vault, blank by default)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showOptionalKeys = !showOptionalKeys },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("ঐচ্ছিক ওয়েব সার্চ ও গিটহাব টোকেন", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = if (currentSettings.isWebSearchKeyConfigured) "লাইভ সার্চ API Key সংরক্ষিত" else "ডিফল্ট পাবলিক Wiki/RSS সক্রিয় (Key ঐচ্ছিক)",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.5.sp
                                    )
                                }
                                Icon(
                                    imageVector = if (showOptionalKeys) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (showOptionalKeys) "সঙ্কুচিত করুন" else "বিস্তারিত",
                                    tint = Color(0xFF38BDF8)
                                )
                            }

                            if (showOptionalKeys) {
                                OutlinedTextField(
                                    value = braveKeyInput,
                                    onValueChange = { braveKeyInput = it },
                                    label = { Text("Brave Search API Key (ঐচ্ছিক)", fontSize = 11.sp) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF334155),
                                        focusedContainerColor = Color(0xFF0F172A),
                                        unfocusedContainerColor = Color(0xFF0F172A)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp)
                                )
                                OutlinedTextField(
                                    value = tavilyKeyInput,
                                    onValueChange = { tavilyKeyInput = it },
                                    label = { Text("Tavily Search API Key (ঐচ্ছিক)", fontSize = 11.sp) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF334155),
                                        focusedContainerColor = Color(0xFF0F172A),
                                        unfocusedContainerColor = Color(0xFF0F172A)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp)
                                )
                                OutlinedTextField(
                                    value = githubTokenInput,
                                    onValueChange = { githubTokenInput = it },
                                    label = { Text("GitHub Personal Access Token (ঐচ্ছিক)", fontSize = 11.sp) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF334155),
                                        focusedContainerColor = Color(0xFF0F172A),
                                        unfocusedContainerColor = Color(0xFF0F172A)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp)
                                )
                            }
                        }
                    }

                    // 5. System Prompt
                    Column {
                        Text(
                            text = "সিস্টেম নির্দেশনা (System Prompt)",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = systemPromptInput,
                            onValueChange = { systemPromptInput = it },
                            minLines = 4,
                            maxLines = 6,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF1E293B)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, lineHeight = 16.sp)
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF1E293B))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.resetAiSettings()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF64748B)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("রিসেট", color = Color(0xFFCBD5E1))
                    }

                    Button(
                        onClick = {
                            WakeWordModelManager.saveCustomWakePhrases(context, customWakePhrasesInput)
                            viewModel.updateAiSettings(
                                currentSettings.copy(
                                    apiKey = apiKeyInput.trim().ifBlank { AiSettingsRepository.DEFAULT_API_KEY },
                                    modelName = selectedModel,
                                    voiceName = selectedVoice.trim(),
                                    systemInstruction = systemPromptInput.trim(),
                                    autoConnect = autoConnect,
                                    voiceVolumeLevel = selectedVolumeLevel,
                                    braveSearchApiKey = braveKeyInput.trim(),
                                    tavilySearchApiKey = tavilyKeyInput.trim(),
                                    githubToken = githubTokenInput.trim(),
                                    preferredLanguage = selectedLanguage,
                                    offlineTtsEnabled = offlineTtsEnabled,
                                    ttsSpeechRate = ttsSpeechRate,
                                    silentMode = silentMode,
                                    speakingStyle = selectedSpeakingStyle
                                )
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("সংরক্ষণ করুন", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
