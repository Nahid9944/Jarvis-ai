package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.SkillRecorderState
import com.example.developer.AutonomyLevel
import com.example.developer.DevWorkStatus
import com.example.service.JarvisAccessibilityService
import com.example.viewmodel.AssistantViewModel
import kotlinx.coroutines.launch

private val DevBg = Color(0xFF020806)
private val DevSurface = Color(0xFF0A2420)
private val DevCard = Color(0xFF061B16)
private val DevEmerald = Color(0xFF00FF9D)
private val DevCyan = Color(0xFF00E5FF)
private val DevAmber = Color(0xFFFEE440)
private val DevDanger = Color(0xFFFF476F)
private val DevText = Color(0xFFF5FFF9)
private val DevDim = Color(0xFF93B9AA)
private val DevBorder = Color(0xFF144D3D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDeveloperCenterScreen(
    viewModel: AssistantViewModel,
    onOpenArtifacts: () -> Unit,
    onOpenSkills: () -> Unit,
    onBack: () -> Unit
) {
    val devState by viewModel.homeDevState.collectAsState()
    val settings by viewModel.homeDevSettings.collectAsState()
    val devTasks by viewModel.devTasks.collectAsState()
    val devLogs by viewModel.devActivityLogs.collectAsState()
    val devPatches by viewModel.devPatches.collectAsState()
    val gaps by viewModel.capabilityGaps.collectAsState()
    val skills by viewModel.allLearnedSkills.collectAsState()
    val evidences by viewModel.skillEvidences.collectAsState()

    val scope = rememberCoroutineScope()
    val accessibilityActive by JarvisAccessibilityService.isServiceActive.collectAsState()
    val recorderState by viewModel.skillRecorder.recorderState.collectAsState()
    val recordedSteps by viewModel.skillRecorder.capturedSteps.collectAsState()
    var teachMeFeedback by remember { mutableStateOf("") }

    var selectedSection by remember { mutableIntStateOf(0) } // 0: Dashboard, 1: Learning, 2: Skill Factory, 3: Code/Web, 4: Logs
    var goalInput by remember { mutableStateOf("") }
    var urlOrFileInput by remember { mutableStateOf("") }
    var selectedLearnSource by remember { mutableStateOf("TEXT") } // YOUTUBE, GITHUB, TEXT, DOCUMENT, SCREENSHOT
    var selectedPatchFile by remember { mutableStateOf("FeatureModule.kt") }
    var isWebWorkspaceMode by remember { mutableStateOf(false) }
    var combinedIdsInput by remember { mutableStateOf("") }
    var combinedNameInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshHomeDevTelemetry()
    }

    val indicatorColor = Color(devState.indicatorState.colorHex)

    Scaffold(
        containerColor = DevBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DevSurface),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home_dev_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "হোমে ফিরুন",
                            tint = DevText
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                        )
                        Column {
                            Text(
                                text = "HOME DEVELOPER",
                                color = DevEmerald,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "${devState.indicatorState.labelBn} • ${devState.progressPercent}%",
                                color = DevDim,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.runCapabilityGapScan() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home_dev_audit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "সেল্ফ-রিপেয়ার অডিট",
                            tint = DevCyan
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DevBg),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Compact Top Control & Live Progress Strip (keeps LazyColumn generous on all screen heights)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = DevCard,
                    border = BorderStroke(1.dp, indicatorColor.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "অটোনোমাস সেল্ফ-ডেভেলপমেন্ট • ${devState.progressPercent}%",
                                    color = DevText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${settings.autonomyLevel.titleBn} | ফেজ: ${devState.phase.name} | হেলথ: ${devState.projectHealthScore}%",
                                    color = indicatorColor,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Switch(
                                checked = settings.selfDevelopmentEnabled,
                                onCheckedChange = { viewModel.toggleSelfDevelopment(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DevBg,
                                    checkedTrackColor = DevEmerald
                                ),
                                modifier = Modifier.testTag("home_dev_self_toggle")
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (devState.progressPercent / 100f).coerceIn(0f, 1f) },
                            color = indicatorColor,
                            trackColor = DevSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        if (devState.activeTaskGoal.isNotBlank()) {
                            Text(
                                text = "সক্রিয় লক্ষ্য: ${devState.activeTaskGoal}",
                                color = DevCyan,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Sub-section Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("ড্যাশবোর্ড", "লার্নিং সেন্টার", "স্কিল ফ্যাক্টরি (${skills.size})", "কোড ও ওয়েব (${devPatches.size})", "লাইভ লগ (${devLogs.size})").forEachIndexed { idx, title ->
                        FilterChip(
                            selected = selectedSection == idx,
                            onClick = { selectedSection = idx },
                            label = { Text(title, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DevCyan.copy(alpha = 0.22f),
                                selectedLabelColor = DevCyan,
                                labelColor = DevDim
                            ),
                            modifier = Modifier.testTag("home_dev_section_$idx")
                        )
                    }
                }

                // Scrollable Body
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("home_dev_lazy_list"),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (selectedSection) {
                        0 -> {
                            // Autonomy L1-L4 + Resource Constraints + Development Priority + Full Telemetry
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("স্বয়ংক্রিয়তা ও রিসোর্স নীতি (L1–L4)", color = DevEmerald, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            AutonomyLevel.entries.forEach { level ->
                                                val selected = settings.autonomyLevel == level
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = { viewModel.setHomeDevAutonomyLevel(level) },
                                                    label = { Text("L${level.levelNumber}", fontSize = 11.sp) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = DevEmerald.copy(alpha = 0.25f),
                                                        selectedLabelColor = DevEmerald,
                                                        labelColor = DevDim
                                                    ),
                                                    modifier = Modifier.testTag("home_dev_autonomy_l${level.levelNumber}")
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            ConstraintBadge(
                                                label = if (settings.wifiOnly) "Wi-Fi Only" else "Any Net",
                                                active = settings.wifiOnly,
                                                onClick = { viewModel.updateHomeDevSettings { it.copy(wifiOnly = !it.wifiOnly) } }
                                            )
                                            ConstraintBadge(
                                                label = "Battery OK",
                                                active = settings.batteryNotLow,
                                                onClick = { viewModel.updateHomeDevSettings { it.copy(batteryNotLow = !it.batteryNotLow) } }
                                            )
                                            ConstraintBadge(
                                                label = "Charging",
                                                active = settings.requireCharging,
                                                onClick = { viewModel.updateHomeDevSettings { it.copy(requireCharging = !it.requireCharging) } }
                                            )
                                            ConstraintBadge(
                                                label = "Device Idle",
                                                active = settings.requireDeviceIdle,
                                                onClick = { viewModel.updateHomeDevSettings { it.copy(requireDeviceIdle = !it.requireDeviceIdle) } }
                                            )
                                            ConstraintBadge(
                                                label = "Storage OK",
                                                active = settings.requireStorageNotLow,
                                                onClick = { viewModel.updateHomeDevSettings { it.copy(requireStorageNotLow = !it.requireStorageNotLow) } }
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Priority:", color = DevDim, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                            listOf(
                                                "BUG_FIX_AND_SECURITY" to "Bug & Security",
                                                "SKILL_EXPANSION" to "Skills",
                                                "SELF_REPAIR" to "Self-Repair",
                                                "RESEARCH_AND_DOCS" to "Research"
                                            ).forEach { (key, label) ->
                                                FilterChip(
                                                    selected = settings.developmentPriority == key,
                                                    onClick = { viewModel.updateHomeDevSettings { it.copy(developmentPriority = key) } },
                                                    label = { Text(label, fontSize = 10.sp) }
                                                )
                                            }
                                        }
                                        Text(
                                            text = "কভারেজ: ${devState.capabilityCoveragePercent}% • ধাপ: মোট ${devState.totalSteps} | সম্পন্ন ${devState.completedSteps.size} | বাকি ${devState.pendingSteps.size} | ব্যর্থ ${devState.failedSteps.size}",
                                            color = DevCyan,
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "স্কিল: ${skills.size} (Active ${skills.count { it.status == "ACTIVE" }}, Draft ${skills.count { it.status == "DRAFT" }}, Review ${evidences.count { it.needsReview }}) • প্যাচ: ${devPatches.size}",
                                            color = DevDim,
                                            fontSize = 10.5.sp
                                        )
                                        Text(
                                            text = "${devState.securityStatusSummary} | ${devState.buildStatusSummary}",
                                            color = DevDim,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                            // Capability Gaps & Tracked Checkpointed Tasks
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = DevAmber, modifier = Modifier.size(16.dp))
                                            Text("শনাক্তকৃত সক্ষমতার ঘাটতি (${gaps.size})", color = DevText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                        if (gaps.isEmpty()) {
                                            Text("অডিট বাটন চেপে বর্তমান ডিভাইস ও টুল-কভারেজ স্ক্যান করুন।", color = DevDim, fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                            items(gaps, key = { it.gapId }) { gap ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DevSurface,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(gap.titleBn, color = DevText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${gap.category} • ${gap.severity} • সমাধান: ${gap.resolutionType}", color = DevCyan, fontSize = 10.5.sp)
                                        Text(gap.sourceEvidence, color = DevDim, fontSize = 10.sp)
                                    }
                                }
                            }
                            items(devTasks.take(6), key = { it.taskId }) { task ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${task.goal} [${task.status}]", color = DevText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("ফেজ: ${task.phase} • অগ্রগতি: ${task.computeProgressPercent()}% (${task.currentStepIndex}/${task.totalSteps})", color = DevDim, fontSize = 10.5.sp)
                                        if (task.status == DevWorkStatus.WAITING_APPROVAL.name) {
                                            Button(
                                                onClick = { viewModel.approveHomeDevTask(task.taskId) },
                                                colors = ButtonDefaults.buttonColors(containerColor = DevEmerald, contentColor = DevBg),
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("home_dev_approve_task_${task.taskId}")
                                            ) {
                                                Text("অনুমোদন দিন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            // SECTION 3: Multi-Source Learning Center
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("মাল্টি-সোর্স লার্নিং ওয়ার্কস্পেস", color = DevEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf("TEXT", "YOUTUBE", "GITHUB", "DOCUMENT", "SCREENSHOT").forEach { src ->
                                                FilterChip(
                                                    selected = selectedLearnSource == src,
                                                    onClick = { selectedLearnSource = src },
                                                    label = { Text(src, fontSize = 10.5.sp) }
                                                )
                                            }
                                        }
                                        OutlinedTextField(
                                            value = urlOrFileInput,
                                            onValueChange = { urlOrFileInput = it },
                                            label = { Text("URL / ফাইলের নাম / টার্গেট অ্যাপ", fontSize = 11.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = DevCyan,
                                                unfocusedBorderColor = DevBorder,
                                                focusedTextColor = DevText,
                                                unfocusedTextColor = DevText
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = goalInput,
                                            onValueChange = { goalInput = it },
                                            label = { Text("নির্দেশনা, ডকুমেন্ট কনটেন্ট বা স্ক্রিনশট টেক্সট", fontSize = 11.sp) },
                                            minLines = 3,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = DevEmerald,
                                                unfocusedBorderColor = DevBorder,
                                                focusedTextColor = DevText,
                                                unfocusedTextColor = DevText
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("home_dev_goal_input")
                                        )
                                            Button(
                                            onClick = {
                                                val txt = goalInput.ifBlank { urlOrFileInput }
                                                if (txt.isNotBlank()) {
                                                    when (selectedLearnSource) {
                                                        "DOCUMENT" -> viewModel.learnDocumentWorkspace(urlOrFileInput.ifBlank { "guide.md" }, txt)
                                                        "SCREENSHOT" -> viewModel.learnScreenshotWorkspace(txt, urlOrFileInput)
                                                        else -> viewModel.startHomeDevGoal(
                                                            goal = txt,
                                                            category = "SKILL_LEARN",
                                                            sourceType = selectedLearnSource,
                                                            sourceUrl = urlOrFileInput
                                                        )
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DevCyan, contentColor = DevBg),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("home_dev_learn_submit")
                                        ) {
                                            Text("শেখা ও যাচাই শুরু করুন", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = DevSurface,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("লার্নিং সোর্স অনুমতি (৭টি মাধ্যম)", color = DevCyan, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            FilterChip(selected = settings.allowYoutubeLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowYoutubeLearning = !it.allowYoutubeLearning) } }, label = { Text("YouTube", fontSize = 10.5.sp) })
                                            FilterChip(selected = settings.allowGithubLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowGithubLearning = !it.allowGithubLearning) } }, label = { Text("GitHub", fontSize = 10.5.sp) })
                                            FilterChip(selected = settings.allowTextLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowTextLearning = !it.allowTextLearning) } }, label = { Text("Text", fontSize = 10.5.sp) })
                                            FilterChip(selected = settings.allowVoiceLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowVoiceLearning = !it.allowVoiceLearning) } }, label = { Text("Voice", fontSize = 10.5.sp) })
                                            FilterChip(selected = settings.allowLiveDemoLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowLiveDemoLearning = !it.allowLiveDemoLearning) } }, label = { Text("Live Demo", fontSize = 10.5.sp) })
                                            FilterChip(selected = settings.allowScreenshotLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowScreenshotLearning = !it.allowScreenshotLearning) } }, label = { Text("Screenshot", fontSize = 10.5.sp) })
                                            FilterChip(selected = settings.allowDocumentLearning, onClick = { viewModel.updateHomeDevSettings { it.copy(allowDocumentLearning = !it.allowDocumentLearning) } }, label = { Text("Document", fontSize = 10.5.sp) })
                                        }
                                        val isRecording = recorderState == SkillRecorderState.RECORDING || recorderState == SkillRecorderState.PAUSED
                                        Text("TEACH ME (লাইভ ডেমো রেকর্ডিং) • ${recordedSteps.size} ধাপ", color = DevEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            Button(
                                                onClick = {
                                                    if (!settings.allowLiveDemoLearning) {
                                                        teachMeFeedback = "সেটিংস থেকে Live Demo শেখা বন্ধ রাখা হয়েছে।"
                                                    } else if (!accessibilityActive) {
                                                        teachMeFeedback = "Live Demonstration রেকর্ড করতে Android Accessibility Service চালু থাকা প্রয়োজন।"
                                                    } else if (!isRecording) {
                                                        val title = goalInput.ifBlank { "Custom Live Demo Skill" }
                                                        viewModel.skillRecorder.startRecording(
                                                            skillName = title,
                                                            skillNameBengali = title,
                                                            triggerPhrase = title,
                                                            targetAppPackage = urlOrFileInput,
                                                            repository = viewModel.jarvisSkillRepo
                                                        )
                                                        teachMeFeedback = "লাইভ ডেমো রেকর্ডিং শুরু হয়েছে — এখন ফোনে কাজটি করে দেখান।"
                                                    } else {
                                                        scope.launch {
                                                            val saved = viewModel.skillRecorder.stopAndSaveSkill(
                                                                description = goalInput.ifBlank { "Recorded via TEACH ME Mode" },
                                                                repository = viewModel.jarvisSkillRepo
                                                            )
                                                            teachMeFeedback = "আপনার Demonstration থেকে '${saved.nameBengali}' (${saved.stepCount} ধাপ, ${saved.status}) তৈরি হয়েছে।"
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isRecording) DevAmber else DevEmerald,
                                                    contentColor = DevBg
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 48.dp)
                                                    .testTag("home_dev_teach_me_btn")
                                            ) {
                                                Text(if (isRecording) "সেভ ও অনুমোদন নিন" else "TEACH ME শুরু করুন", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                            }
                                            if (isRecording) {
                                                OutlinedButton(
                                                    onClick = {
                                                        viewModel.skillRecorder.discardRecording(viewModel.jarvisSkillRepo)
                                                        teachMeFeedback = "লাইভ ডেমো রেকর্ডিং বাতিল করা হয়েছে।"
                                                    },
                                                    modifier = Modifier.heightIn(min = 48.dp)
                                                ) {
                                                    Text("বাতিল", color = DevDanger, fontSize = 11.5.sp)
                                                }
                                            }
                                        }
                                        if (teachMeFeedback.isNotBlank()) {
                                            Text(teachMeFeedback, color = DevAmber, fontSize = 11.sp)
                                        } else if (!accessibilityActive) {
                                            Text("নোট: লাইভ ডেমোর জন্য Android Accessibility চালু না থাকলে পারমিশন সেন্টার থেকে অনুমোদন দিন (OTP/Password কখনোই রেকর্ড হয় না)।", color = DevDim, fontSize = 10.5.sp)
                                        }
                                    }
                                }
                            }
                        }
                        2 -> {
                            // SECTION 4: Skill Factory & Combination
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("স্কিল কম্বাইন ফ্যাক্টরি", color = DevCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        OutlinedTextField(
                                            value = combinedIdsInput,
                                            onValueChange = { combinedIdsInput = it },
                                            label = { Text("কমা দিয়ে ২+ স্কিল আইডি লিখুন", fontSize = 11.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DevText, unfocusedTextColor = DevText),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = combinedNameInput,
                                            onValueChange = { combinedNameInput = it },
                                            label = { Text("নতুন কম্বাইনড স্কিলের নাম", fontSize = 11.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DevText, unfocusedTextColor = DevText),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(
                                            onClick = {
                                                val ids = combinedIdsInput.split(',').map { it.trim() }.filter { it.isNotBlank() }
                                                if (ids.size >= 2) {
                                                    viewModel.combineSkillsInFactory(ids, combinedNameInput.ifBlank { "Combined Workflow" })
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DevEmerald, contentColor = DevBg),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("home_dev_combine_skills")
                                        ) {
                                            Text("নতুন কম্বাইনড স্কিল তৈরি করুন", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            items(skills, key = { it.skillId }) { skill ->
                                val ev = evidences.firstOrNull { it.skillId == skill.skillId }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DevSurface,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${skill.nameBengali} [${skill.status}] v${skill.version}", color = DevText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("ID: ${skill.skillId} • ধাপ: ${skill.stepCount} • সোর্স: ${skill.sourceType} • হেলথ: ${ev?.healthScore ?: 100}%", color = DevDim, fontSize = 10.5.sp)
                                        if (skill.status == "DRAFT") {
                                            Button(
                                                onClick = { viewModel.approveDraftSkillInFactory(skill.skillId) },
                                                colors = ButtonDefaults.buttonColors(containerColor = DevEmerald, contentColor = DevBg),
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .testTag("home_dev_activate_skill_${skill.skillId}")
                                            ) {
                                                Text("ড্রাফট অনুমোদন ও সক্রিয় করুন", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        3 -> {
                            // SECTION 5: Multi-Language Code Patch, Web App & Rollback Center
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("মাল্টি-ল্যাঙ্গুয়েজ কোড প্যাচ ও ওয়েব অ্যাপ জেনারেটর", color = DevAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        OutlinedTextField(
                                            value = selectedPatchFile,
                                            onValueChange = { selectedPatchFile = it },
                                            label = { Text("টার্গেট ফাইল (যেমন: Helper.kt / app.py / index.html)", fontSize = 11.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DevText, unfocusedTextColor = DevText),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text("আলাদা HTML/CSS/JS ওয়েব অ্যাপ ওয়ার্কস্পেস", color = DevText, fontSize = 12.sp)
                                            Switch(checked = isWebWorkspaceMode, onCheckedChange = { isWebWorkspaceMode = it })
                                        }
                                        OutlinedTextField(
                                            value = goalInput,
                                            onValueChange = { goalInput = it },
                                            label = { Text("ফিচার বা ওয়েব অ্যাপ স্পেসিফিকেশন", fontSize = 11.sp) },
                                            minLines = 2,
                                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = DevText, unfocusedTextColor = DevText),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(
                                            onClick = {
                                                val spec = goalInput.ifBlank { "Custom module spec for $selectedPatchFile" }
                                                viewModel.startHomeDevGoal(
                                                    goal = spec,
                                                    category = if (isWebWorkspaceMode) "WEB_APP" else "CODE_PATCH",
                                                    sourceType = "TEXT",
                                                    sourceUrl = selectedPatchFile
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DevEmerald, contentColor = DevBg),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("home_dev_generate_patch")
                                        ) {
                                            Text("কোড প্যাচ ও টেস্ট স্টাব তৈরি করুন", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            items(devPatches, key = { it.patchId }) { patch ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DevSurface,
                                    border = BorderStroke(1.dp, if (patch.securityStatus == "BLOCKED") DevDanger else DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("${patch.targetFile} (${patch.language}) • ${patch.status}", color = DevText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("সিকিউরিটি: ${patch.securityStatus} • রানটাইম: ${patch.compileStatus}", color = if (patch.securityStatus == "BLOCKED") DevDanger else DevCyan, fontSize = 10.5.sp)
                                        Text(patch.summaryBn, color = DevDim, fontSize = 11.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (patch.status == "DRAFT") {
                                                OutlinedButton(
                                                    onClick = { viewModel.approveHomeDevPatch(patch.patchId) },
                                                    modifier = Modifier.heightIn(min = 48.dp)
                                                ) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text("Approve", fontSize = 11.sp)
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = { viewModel.rollbackHomeDevPatch(patch.patchId) },
                                                modifier = Modifier.heightIn(min = 48.dp)
                                            ) {
                                                Text("Rollback", color = DevAmber, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        4 -> {
                            // SECTION 6: Live Activity Log
                            items(devLogs, key = { it.id }) { log ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = DevCard,
                                    border = BorderStroke(1.dp, DevBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("[${log.phase}] ${log.eventType}", color = if (log.severity == "ERROR") DevDanger else DevCyan, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                                        Text(log.messageBn, color = DevText, fontSize = 11.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // SECTION 8: Bottom Action Dock (All 7 requested action controls)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ActionPillButton("START DEVELOPMENT", DevEmerald, DevBg, Icons.Default.Build, "home_dev_start_btn") {
                        val goal = goalInput.ifBlank { "সিস্টেম সক্ষমতা ও গ্যাপ অডিট" }
                        viewModel.startHomeDevGoal(goal = goal, category = "SELF_REPAIR", sourceType = "GAP_DETECTOR")
                    }
                    ActionPillButton("PAUSE / STOP", DevAmber, DevBg, Icons.Default.Pause, "home_dev_pause_btn") {
                        viewModel.pauseHomeDev("USER_BUTTON_PAUSE")
                    }
                    ActionPillButton("RESUME", DevCyan, DevBg, Icons.Default.PlayArrow, "home_dev_resume_btn") {
                        viewModel.resumeHomeDev(devState.activeTaskId)
                    }
                    ActionPillButton("CANCEL TASK", DevDanger, Color.White, Icons.Default.Cancel, "home_dev_cancel_btn") {
                        viewModel.cancelHomeDev(devState.activeTaskId)
                    }
                    ActionPillButton("OPEN ARTIFACTS", DevSurface, DevText, Icons.Default.Description, "home_dev_open_artifacts_btn") {
                        onOpenArtifacts()
                    }
                    ActionPillButton("VIEW SKILLS", DevSurface, DevEmerald, Icons.Default.Security, "home_dev_view_skills_btn") {
                        onOpenSkills()
                    }
                    ActionPillButton("VIEW CODE CHANGES", DevSurface, DevCyan, Icons.Default.Code, "home_dev_view_code_btn") {
                        selectedSection = 3
                    }
                }
            }
        }
    }
}

@Composable
private fun ConstraintBadge(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) DevEmerald.copy(alpha = 0.16f) else DevSurface,
        border = BorderStroke(1.dp, if (active) DevEmerald else DevBorder),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
            Text(
                text = label,
                color = if (active) DevEmerald else DevDim,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActionPillButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Black)
    }
}
