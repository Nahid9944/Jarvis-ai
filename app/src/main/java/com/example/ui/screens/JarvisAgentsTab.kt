package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiAgent
import com.example.ai.AiAgentRegistry
import com.example.ai.LiveSessionState
import com.example.ai.ToolExecutionTelemetry
import com.example.viewmodel.AssistantViewModel

private val DashBg = Color(0xFF020806)
private val DashSurface = Color(0xFF0A2420)
private val DashCard = Color(0xFF061B16)
private val DashEmerald = Color(0xFF00FF9D)
private val DashEmeraldMid = Color(0xFF00D98B)
private val DashCyan = Color(0xFF00E5FF)
private val DashAmber = Color(0xFFFEE440)
private val DashDanger = Color(0xFFFF476F)
private val DashText = Color(0xFFF5FFF9)
private val DashDim = Color(0xFF93B9AA)

@Composable
fun JarvisAgentsTab(
    viewModel: AssistantViewModel,
    onSelectPrompt: (String) -> Unit
) {
    val context = LocalContext.current
    val activeAgent by viewModel.activeAiAgent.collectAsState()
    val liveState by viewModel.aiLiveState.collectAsState()
    val toolTelemetry by viewModel.toolTelemetry.collectAsState()
    val liveNews by viewModel.liveNews.collectAsState()
    val isNewsLoading by viewModel.isNewsLoading.collectAsState()
    val newsError by viewModel.newsError.collectAsState()
    val screenTelemetry by com.example.util.JarvisScreenCaptureManager.telemetry.collectAsState()
    val liveOutput by viewModel.liveOutputTranscript.collectAsState()
    val activePlan by viewModel.activeOrchestrationPlan.collectAsState()

    var rightPaneMode by remember { mutableStateOf("AGENT") } // VOICE, AGENT, NOTES
    var quickCommand by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (liveNews.isEmpty()) {
            viewModel.refreshLiveNews(category = "সব")
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DashBg)
            .padding(12.dp)
    ) {
        val isWide = maxWidth >= 600.dp

        if (isWide) {
            // 3-Column Desktop/Tablet Archer-Inspired Agent Connection Dashboard
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // LEFT PANE: MEDIA LINK, SAT-LINK FEED, TODAY HEADLINES
                Column(
                    modifier = Modifier
                        .weight(0.28f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MediaAndSatLinkCard(
                        liveState = liveState,
                        screenActive = screenTelemetry.mediaProjectionConsent
                    )
                    TodayHeadlinesDashboardCard(
                        news = liveNews,
                        isLoading = isNewsLoading,
                        error = newsError,
                        onRefresh = { viewModel.refreshLiveNews(forceRefresh = true) },
                        onOpenUrl = { url ->
                            if (url.isNotBlank()) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // CENTER PANE: Top Active Agent + Circuit Traces + Terminate | Bottom 12-Agent Workflow Matrix
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CenterAgentConnectionHeader(
                        activeAgent = activeAgent,
                        liveState = liveState,
                        onTerminate = {
                            val r = viewModel.triggerEmergencyStop()
                            Toast.makeText(context, r.summaryBengali, Toast.LENGTH_SHORT).show()
                        },
                        onToggleMic = { viewModel.toggleLiveSession() }
                    )

                    AgentWorkflowMatrixCard(
                        allAgents = AiAgentRegistry.ALL_AGENTS,
                        activeAgent = activeAgent,
                        activeStepCount = activePlan?.subTasks?.size ?: 0,
                        onActivateAgent = { agent ->
                            viewModel.selectAiAgent(agent)
                            Toast.makeText(context, "${agent.titleBengali} সক্রিয় করা হয়েছে", Toast.LENGTH_SHORT).show()
                        },
                        onSelectPrompt = onSelectPrompt,
                        modifier = Modifier.weight(1f)
                    )
                }

                // RIGHT PANE: VOICE | AGENT | NOTES + Module Telemetry Pills + Quick Input
                RightTelemetryAndPromptsPane(
                    activeAgent = activeAgent,
                    rightPaneMode = rightPaneMode,
                    onModeChange = { rightPaneMode = it },
                    toolTelemetry = toolTelemetry,
                    liveOutput = liveOutput,
                    quickCommand = quickCommand,
                    onQuickCommandChange = { quickCommand = it },
                    onSendQuickCommand = {
                        if (quickCommand.isNotBlank()) {
                            onSelectPrompt(quickCommand.trim())
                            quickCommand = ""
                        }
                    },
                    onSelectPrompt = onSelectPrompt,
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Compact Phone Stacked Agent Connection Dashboard
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    CenterAgentConnectionHeader(
                        activeAgent = activeAgent,
                        liveState = liveState,
                        onTerminate = {
                            val r = viewModel.triggerEmergencyStop()
                            Toast.makeText(context, r.summaryBengali, Toast.LENGTH_SHORT).show()
                        },
                        onToggleMic = { viewModel.toggleLiveSession() }
                    )
                }

                if (toolTelemetry.isNotEmpty()) {
                    item {
                        TelemetryPillsSection(toolTelemetry = toolTelemetry)
                    }
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = DashCard,
                        border = BorderStroke(1.dp, DashEmerald.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Hub, contentDescription = null, tint = DashEmerald, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "${AiAgentRegistry.ALL_AGENTS.size}টি স্পেশালাইজড এজেন্ট ওয়ার্কফ্লো",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DashText
                                )
                            }
                            Text(
                                text = "যেকোনো এজেন্ট সক্রিয় করুন — সিলেক্টেড এজেন্ট তাৎক্ষণিকভাবে ভয়েস ও অর্কেস্ট্রেশন ব্রেইনে যুক্ত হবে।",
                                fontSize = 11.5.sp,
                                color = DashDim
                            )
                        }
                    }
                }

                items(AiAgentRegistry.ALL_AGENTS, key = { it.id }) { agent ->
                    val isCurrent = agent.id == activeAgent.id
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = DashCard,
                        border = BorderStroke(
                            width = if (isCurrent) 1.5.dp else 1.dp,
                            color = if (isCurrent) DashEmerald else Color(0xFF143C32)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(agent.color.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = agent.icon,
                                            contentDescription = agent.name,
                                            tint = agent.color,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = agent.titleBengali,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DashText
                                        )
                                        Text(
                                            text = agent.name,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = DashDim
                                        )
                                    }
                                }

                                if (isCurrent) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = DashEmerald.copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, DashEmerald)
                                    ) {
                                        Text(
                                            text = "সক্রিয়",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DashEmerald,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.selectAiAgent(agent)
                                            Toast.makeText(context, "${agent.titleBengali} সক্রিয় করা হয়েছে", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, DashEmeraldMid.copy(alpha = 0.7f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(48.dp)
                                    ) {
                                        Text(
                                            text = "সক্রিয় করুন",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = DashEmeraldMid
                                        )
                                    }
                                }
                            }

                            Text(
                                text = agent.description,
                                fontSize = 11.5.sp,
                                color = DashDim,
                                lineHeight = 16.sp
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                agent.samplePrompts.forEach { prompt ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DashSurface,
                                        border = BorderStroke(0.8.dp, Color(0xFF194B3E)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectPrompt(prompt) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = DashEmerald,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = prompt,
                                                fontSize = 11.5.sp,
                                                color = DashText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaAndSatLinkCard(
    liveState: LiveSessionState,
    screenActive: Boolean
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DashCard,
        border = BorderStroke(1.dp, DashEmerald.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MEDIA LINK", color = DashEmerald, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    text = if (liveState == LiveSessionState.DISCONNECTED) "STANDBY" else liveState.name,
                    color = if (liveState == LiveSessionState.LISTENING || liveState == LiveSessionState.CONNECTED) DashEmerald else DashDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DashSurface,
                border = BorderStroke(1.dp, DashCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SAT-LINK VISION", color = DashCyan, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (screenActive) "STREAM ACTIVE" else "READY",
                        color = if (screenActive) DashEmerald else DashDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayHeadlinesDashboardCard(
    news: List<com.example.ai.NewsItem>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DashCard,
        border = BorderStroke(1.dp, DashCyan.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TODAY HEADLINES", color = DashCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "রিফ্রেশ", tint = DashEmerald, modifier = Modifier.size(16.dp))
                }
            }
            when {
                isLoading -> Text("সর্বশেষ সংবাদ সংগ্রহ করা হচ্ছে...", color = DashDim, fontSize = 11.sp)
                news.isEmpty() -> Text(error ?: "ইন্টারনেট না থাকায় সংবাদ পাওয়া যায়নি", color = DashAmber, fontSize = 11.sp)
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(news.take(5)) { item ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DashSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenUrl(item.url) }
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(item.title, color = DashText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(item.source, color = DashEmerald, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterAgentConnectionHeader(
    activeAgent: AiAgent,
    liveState: LiveSessionState,
    onTerminate: () -> Unit,
    onToggleMic: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DashCard,
        border = BorderStroke(1.5.dp, DashEmerald.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(DashEmerald.copy(alpha = 0.12f), Color.Transparent, DashCyan.copy(alpha = 0.08f))
                    )
                )
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(activeAgent.color.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = activeAgent.icon,
                                contentDescription = activeAgent.name,
                                tint = activeAgent.color,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column {
                            Text(activeAgent.titleBengali, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = DashText)
                            Text(activeAgent.roleBengali, fontSize = 11.5.sp, color = DashEmerald, fontWeight = FontWeight.Medium)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onTerminate,
                            colors = ButtonDefaults.buttonColors(containerColor = DashDanger.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, DashDanger),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text("TERMINATE", color = DashDanger, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = onToggleMic,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(DashSurface)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Toggle Mic",
                                tint = if (liveState == LiveSessionState.LISTENING) DashEmerald else DashCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Subtle Circuit Trace Bar
                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    val y = size.height / 2f
                    val path = Path().apply {
                        moveTo(0f, y)
                        lineTo(size.width * 0.35f, y)
                        lineTo(size.width * 0.4f, 2f)
                        lineTo(size.width * 0.6f, 2f)
                        lineTo(size.width * 0.65f, y)
                        lineTo(size.width, y)
                    }
                    drawPath(path = path, color = DashEmerald.copy(alpha = 0.55f), style = Stroke(width = 2f))
                    drawCircle(color = DashCyan, radius = 4f, center = Offset(size.width * 0.5f, 2f))
                }

                Text(
                    text = "SYSTEM ACTIVE • ${activeAgent.description}",
                    fontSize = 11.5.sp,
                    color = DashDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AgentWorkflowMatrixCard(
    allAgents: List<AiAgent>,
    activeAgent: AiAgent,
    activeStepCount: Int,
    onActivateAgent: (AiAgent) -> Unit,
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DashCard,
        border = BorderStroke(1.dp, DashEmerald.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AGENT WORKFLOW MATRIX (${allAgents.size})", color = DashEmerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("PLAN STEPS: $activeStepCount", color = DashCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(allAgents.chunked(2)) { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { agent ->
                            val selected = agent.id == activeAgent.id
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) DashEmerald.copy(alpha = 0.16f) else DashSurface,
                                border = BorderStroke(1.dp, if (selected) DashEmerald else Color(0xFF174438)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onActivateAgent(agent) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(agent.icon, contentDescription = null, tint = agent.color, modifier = Modifier.size(18.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(agent.titleBengali, color = DashText, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (selected) "ACTIVE" else "READY", color = if (selected) DashEmerald else DashDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            activeAgent.samplePrompts.firstOrNull()?.let { sample ->
                OutlinedButton(
                    onClick = { onSelectPrompt(sample) },
                    border = BorderStroke(1.dp, DashCyan.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Text("উদাহরণ কমান্ড চালান: $sample", color = DashCyan, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RightTelemetryAndPromptsPane(
    activeAgent: AiAgent,
    rightPaneMode: String,
    onModeChange: (String) -> Unit,
    toolTelemetry: List<ToolExecutionTelemetry>,
    liveOutput: String,
    quickCommand: String,
    onQuickCommandChange: (String) -> Unit,
    onSendQuickCommand: () -> Unit,
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DashCard,
        border = BorderStroke(1.dp, DashCyan.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("VOICE", "AGENT", "NOTES").forEach { mode ->
                    val sel = rightPaneMode == mode
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (sel) DashEmerald.copy(alpha = 0.2f) else DashSurface,
                        border = BorderStroke(1.dp, if (sel) DashEmerald else Color(0xFF174438)),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onModeChange(mode) }
                    ) {
                        Text(
                            text = mode,
                            color = if (sel) DashEmerald else DashDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 6.dp).wrapContentWidth(Alignment.CenterHorizontally)
                        )
                    }
                }
            }

            Text(
                text = liveOutput.ifBlank { "অ্যাজেন্ট: ${activeAgent.titleBengali} — কোনো লাইভ কমান্ডের জন্য প্রস্তুত।" },
                color = DashText,
                fontSize = 11.5.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            TelemetryPillsSection(toolTelemetry = toolTelemetry, modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = quickCommand,
                    onValueChange = onQuickCommandChange,
                    placeholder = { Text("এজেন্টকে লিখুন...", fontSize = 11.sp, color = DashDim) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendQuickCommand() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DashEmerald,
                        unfocusedBorderColor = Color(0xFF194B3E),
                        focusedTextColor = DashText,
                        unfocusedTextColor = DashText
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onSendQuickCommand,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(DashEmerald)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = DashBg)
                }
            }
        }
    }
}

@Composable
private fun TelemetryPillsSection(
    toolTelemetry: List<ToolExecutionTelemetry>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DashSurface,
        border = BorderStroke(1.dp, DashEmerald.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("MODULE EXECUTION TELEMETRY", color = DashCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            if (toolTelemetry.isEmpty()) {
                Text("এখনো কোনো লাইভ টুল রান হয়নি।", color = DashDim, fontSize = 11.sp)
            } else {
                toolTelemetry.take(5).forEach { entry ->
                    val secs = String.format(java.util.Locale.US, "%.1fs", entry.durationMs / 1000.0)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DashCard,
                        border = BorderStroke(1.dp, if (entry.status == "SUCCEEDED") DashEmerald.copy(alpha = 0.45f) else DashAmber.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Module: ${entry.toolName}",
                                color = DashText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "> $secs (${entry.status})",
                                color = if (entry.status == "SUCCEEDED") DashEmerald else DashAmber,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
