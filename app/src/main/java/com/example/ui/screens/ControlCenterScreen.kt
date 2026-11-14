package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AdaptivePersonalityEngine
import com.example.ai.EmotionControlConfig
import com.example.ai.JarvisCameraVisionCoordinator
import com.example.ai.JarvisPersonalityManager
import com.example.ai.JarvisPersonalityMode
import com.example.ai.UserEmotion
import com.example.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CcSpaceDark = Color(0xFF040814)
private val CcSurface = Color(0xFF0A1226)
private val CcCardBg = Color(0xFF0F1A36)
private val CcCyan = Color(0xFF00F5D4)
private val CcBlue = Color(0xFF00BBF9)
private val CcAmber = Color(0xFFFEE440)
private val CcPink = Color(0xFFF15BB5)
private val CcEmerald = Color(0xFF10B981)
private val CcTextWhite = Color(0xFFF8FAFC)
private val CcTextDim = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCenterScreen(
    viewModel: AssistantViewModel,
    onOpenPermissions: () -> Unit,
    onOpenCamera: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activeMode by viewModel.activePersonality.collectAsState()
    val previousMode by viewModel.previousPersonalityMode.collectAsState()
    val activeProfile by viewModel.activeCharacterProfile.collectAsState()
    val characterVersions by viewModel.characterVersions.collectAsState()

    val currentEmotion by viewModel.currentEmotion.collectAsState()
    val emotionConfig by viewModel.emotionConfig.collectAsState()
    val emotionHistory by viewModel.emotionHistory.collectAsState()
    val visualObservation by viewModel.visualObservationState.collectAsState()

    val adaptiveDirective = remember(activeMode, currentEmotion, emotionConfig) {
        AdaptivePersonalityEngine.computeDirective()
    }

    var feedbackInput by remember { mutableStateOf("") }
    var selectedSection by remember { mutableIntStateOf(0) } // 0: Character, 1: Emotion, 2: Visual & Privacy

    Scaffold(
        containerColor = CcSpaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "মায়া (Maya) কন্ট্রোল সেন্টার",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = CcCyan
                        )
                        Text(
                            text = "পার্সোনালিটি স্টুডিও, ইমোশন ও ভিশন প্রাইভেসি",
                            fontSize = 11.sp,
                            color = CcTextDim
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("control_center_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "ফিরে যান",
                            tint = CcCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CcSpaceDark)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Brush.verticalGradient(listOf(CcSpaceDark, CcSurface))),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
            ) {
            // Section Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    0 to "চরিত্র ও টোন",
                    1 to "ইমোশন",
                    2 to "ভিশন ও প্রাইভেসি"
                ).forEach { (idx, label) ->
                    val selected = selectedSection == idx
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) CcCyan.copy(alpha = 0.2f) else CcCardBg,
                        border = BorderStroke(1.dp, if (selected) CcCyan else Color(0xFF1E3A8A)),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable { selectedSection = idx }
                            .testTag("control_center_tab_$idx")
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(
                                text = label,
                                color = if (selected) CcCyan else CcTextDim,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedSection) {
                    0 -> {
                        item {
                            // Mode Picker
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, CcCyan.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "সক্রিয় চরিত্র (v${activeProfile.version})",
                                            color = CcCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        if (previousMode != null && previousMode != activeMode) {
                                            TextButton(
                                                onClick = { viewModel.revertToPreviousPersonality() },
                                                modifier = Modifier.testTag("revert_previous_mode_btn")
                                            ) {
                                                Text(
                                                    text = "আগের মোডে ফিরুন",
                                                    color = CcAmber,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    JarvisPersonalityMode.entries.forEach { mode ->
                                        val isSelected = mode == activeMode
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSelected) CcCyan.copy(alpha = 0.16f) else CcSurface,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) CcCyan else Color(0xFF1E293B)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable { viewModel.setPersonalityMode(mode) }
                                                .testTag("cc_mode_${mode.id}")
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        text = mode.titleBengali,
                                                        color = if (isSelected) CcCyan else CcTextWhite,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.5.sp
                                                    )
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = "সক্রিয়",
                                                            tint = CcCyan,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = mode.shortDescriptionBn,
                                                    color = CcTextDim,
                                                    fontSize = 11.5.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Active Profile Traits + Priority Summary
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, CcBlue.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("প্রোফাইল বৈশিষ্ট্য ও প্রায়োরিটি", color = CcBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("কথার ধরন: ${activeProfile.speakingStyleBn}", color = CcTextWhite, fontSize = 12.sp)
                                    Text("টোন: ${activeProfile.toneDescriptionBn}", color = CcTextDim, fontSize = 12.sp)
                                    Text(
                                        text = "হাস্যরস: ${activeProfile.humorLevel}/10 • আনুষ্ঠানিকতা: ${activeProfile.formalityLevel}/10 • স্নেহ: ${activeProfile.affectionLevel}/10",
                                        color = CcAmber,
                                        fontSize = 11.5.sp
                                    )
                                    Text(
                                        text = "অ্যাডাপ্টিভ নির্দেশনা: ${adaptiveDirective.subToneBn}",
                                        color = CcEmerald,
                                        fontSize = 11.5.sp
                                    )
                                    if (activeProfile.customRules.isNotEmpty()) {
                                        HorizontalDivider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 4.dp))
                                        Text("শেখা কাস্টম নিয়ম:", color = CcCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                        activeProfile.customRules.forEach { rule ->
                                            Text("• $rule", color = CcTextWhite, fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Feedback Learning & Version History
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, Color(0xFF1E3A8A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ফিডব্যাক দিয়ে চরিত্র কাস্টমাইজ করুন", color = CcTextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    OutlinedTextField(
                                        value = feedbackInput,
                                        onValueChange = { feedbackInput = it },
                                        placeholder = { Text("যেমন: আরও ছোট ও মিষ্টি করে উত্তর দাও", fontSize = 12.sp) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("character_feedback_input"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CcCyan,
                                            unfocusedBorderColor = Color(0xFF334155),
                                            focusedTextColor = CcTextWhite,
                                            unfocusedTextColor = CcTextWhite
                                        )
                                    )
                                    Button(
                                        onClick = {
                                            if (feedbackInput.isNotBlank()) {
                                                viewModel.recordCharacterFeedback(feedbackInput)
                                                feedbackInput = ""
                                                Toast.makeText(context, "নতুন চরিত্র ভার্সন সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CcCyan),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("save_character_feedback_btn")
                                    ) {
                                        Text("নতুন ভার্সন হিসেবে সেভ করুন", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }

                                    if (characterVersions.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("পূর্ববর্তী ভার্সন ইতিহাস (রোলব্যাকযোগ্য)", color = CcTextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        characterVersions.take(5).forEach { ver ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text("v${ver.version}: ${ver.customRuleText}", color = CcTextWhite, fontSize = 12.sp)
                                                    Text(formatTs(ver.createdAt), color = CcTextDim, fontSize = 10.sp)
                                                }
                                                TextButton(
                                                    onClick = {
                                                        val ok = viewModel.revertCharacterToVersion(ver.version)
                                                        if (ok) Toast.makeText(context, "ভার্সন v${ver.version} ফিরিয়ে আনা হয়েছে", Toast.LENGTH_SHORT).show()
                                                    }
                                                ) {
                                                    Text("ফিরুন", color = CcAmber, fontSize = 11.5.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        item {
                            // Current Observed Emotion & Uncertainty
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, if (currentEmotion.isUncertain) CcAmber else CcBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "বর্তমান অনুভূতি: ${currentEmotion.emotion.labelBengali}",
                                        color = if (currentEmotion.isUncertain) CcAmber else CcCyan,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "উৎস: ${currentEmotion.source} • কনফিডেন্স: ${(currentEmotion.confidence * 100).toInt()}%",
                                        color = CcTextDim,
                                        fontSize = 12.sp
                                    )
                                    Text(text = currentEmotion.contextSummaryBn, color = CcTextWhite, fontSize = 12.sp)
                                    if (currentEmotion.isUncertain && currentEmotion.conflictReasonBn.isNotBlank()) {
                                        Text(
                                            text = "অনিশ্চয়তা সতর্কতা: ${currentEmotion.conflictReasonBn}",
                                            color = CcAmber,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Toggles & Threshold
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, Color(0xFF1E3A8A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ইমোশন প্রাইভেসি ও সিগন্যাল কন্ট্রোল", color = CcTextWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    EmotionToggleRow("টেক্সট থেকে আবেগ বোঝা", emotionConfig.textAnalysisEnabled) {
                                        viewModel.updateEmotionConfig(emotionConfig.copy(textAnalysisEnabled = it))
                                    }
                                    EmotionToggleRow("ভয়েস এনার্জি পর্যবেক্ষণ (শুধু ক্যাডেন্স)", emotionConfig.voiceEnergyAnalysisEnabled) {
                                        viewModel.updateEmotionConfig(emotionConfig.copy(voiceEnergyAnalysisEnabled = it))
                                    }
                                    EmotionToggleRow("ক্যামেরা মুখভঙ্গি পর্যবেক্ষণ", emotionConfig.cameraExpressionEnabled) {
                                        viewModel.updateEmotionConfig(emotionConfig.copy(cameraExpressionEnabled = it))
                                    }
                                    EmotionToggleRow("অ্যাডাপ্টিভ টোন সমন্বয়", emotionConfig.adaptiveToneEnabled) {
                                        viewModel.updateEmotionConfig(emotionConfig.copy(adaptiveToneEnabled = it))
                                    }

                                    Text(
                                        text = "কনফিডেন্স থ্রেশহোল্ড: ${(emotionConfig.confidenceThreshold * 100).toInt()}%",
                                        color = CcCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Slider(
                                        value = emotionConfig.confidenceThreshold,
                                        onValueChange = {
                                            viewModel.updateEmotionConfig(emotionConfig.copy(confidenceThreshold = it))
                                        },
                                        valueRange = 0.4f..0.95f
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.resetEmotionBaseline()
                                                Toast.makeText(context, "ইমোশন বেসলাইন রিসেট হয়েছে", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("reset_emotion_baseline_btn")
                                        ) {
                                            Text("রিসেট", color = CcAmber, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.clearEmotionHistory()
                                                Toast.makeText(context, "ইমোশন ইতিহাস মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("clear_emotion_history_btn")
                                        ) {
                                            Text("ইতিহাস মুছুন", color = CcPink, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Manual Emotion Override
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, Color(0xFF1E3A8A)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("সরাসরি অনুভূতি সংশোধন করুন", color = CcTextWhite, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(
                                            listOf(
                                                UserEmotion.HAPPY,
                                                UserEmotion.SAD,
                                                UserEmotion.STRESSED,
                                                UserEmotion.TIRED,
                                                UserEmotion.CALM,
                                                UserEmotion.NEUTRAL
                                            )
                                        ) { emotion ->
                                            FilterChip(
                                                selected = currentEmotion.emotion == emotion,
                                                onClick = { viewModel.correctUserEmotion(emotion) },
                                                label = { Text(emotion.labelBengali, fontSize = 11.5.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (emotionHistory.isNotEmpty()) {
                            item {
                                Text("সাম্প্রতিক ইমোশন ইতিহাস", color = CcTextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            items(emotionHistory.take(10)) { itemEval ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = CcCardBg,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(itemEval.emotion.labelBengali, color = CcTextWhite, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                            Text(itemEval.contextSummaryBn, color = CcTextDim, fontSize = 11.sp)
                                        }
                                        Text("${(itemEval.confidence * 100).toInt()}%", color = CcCyan, fontSize = 11.5.sp)
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CcCardBg,
                                border = BorderStroke(1.dp, CcPink.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(if (visualObservation.privacyIndicatorActive) CcPink else CcTextDim)
                                        )
                                        Text(
                                            text = if (visualObservation.privacyIndicatorActive) "ক্যামেরা সক্রিয় (গোপন রেকর্ড নিষিদ্ধ)" else "ক্যামেরা স্ট্যান্ডবাই",
                                            color = CcTextWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    Text(
                                        text = "সর্বশেষ ভিশন অবস্থা: ${visualObservation.statusNoteBn}",
                                        color = CcTextDim,
                                        fontSize = 12.sp
                                    )
                                    Text("ফ্রেম স্যাম্পলিং ইন্টারভাল:", color = CcCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(1500L to "1.5 সেকেন্ড", 3000L to "3 সেকেন্ড", 5000L to "5 সেকেন্ড").forEach { (ms, label) ->
                                            FilterChip(
                                                selected = visualObservation.samplingIntervalMs == ms,
                                                onClick = { JarvisCameraVisionCoordinator.setSamplingInterval(ms) },
                                                label = { Text(label, fontSize = 11.5.sp) }
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = onOpenCamera,
                                            colors = ButtonDefaults.buttonColors(containerColor = CcCyan),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("cc_open_camera_btn")
                                        ) {
                                            Icon(Icons.Default.Videocam, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("ভিজ্যুয়াল হাব", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        OutlinedButton(
                                            onClick = onOpenPermissions,
                                            border = BorderStroke(1.dp, CcBlue),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("cc_open_permissions_btn")
                                        ) {
                                            Icon(Icons.Default.Shield, contentDescription = null, tint = CcBlue, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("পারমিশন সেন্টার", color = CcBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
        }
    }
}

@Composable
private fun EmotionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = CcTextWhite, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun formatTs(ts: Long): String {
    return try {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ts))
    } catch (_: Exception) {
        ""
    }
}
