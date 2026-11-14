package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiAgent
import com.example.ai.AiAgentRegistry
import com.example.viewmodel.AssistantViewModel

private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)

@Composable
fun JarvisAgentsTab(
    viewModel: AssistantViewModel,
    onSelectPrompt: (String) -> Unit
) {
    val context = LocalContext.current
    val activeAgent by viewModel.activeAiAgent.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active Agent Hero Banner
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, activeAgent.color),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    activeAgent.color.copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(activeAgent.color.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = activeAgent.icon,
                                        contentDescription = null,
                                        tint = activeAgent.color,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = activeAgent.titleBengali,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = activeAgent.roleBengali,
                                        fontSize = 11.5.sp,
                                        color = activeAgent.color,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = activeAgent.color.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, activeAgent.color)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = activeAgent.color,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "সক্রিয় এজেন্ট",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = activeAgent.color
                                    )
                                }
                            }
                        }

                        Text(
                            text = activeAgent.description,
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        // Section Title
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(18.dp))
                Text(
                    text = "উপলব্ধ স্পেশালাইজড এআই এজেন্টস (৫টি মোড):",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
            }
        }

        // List of all Agents
        items(AiAgentRegistry.ALL_AGENTS) { agent ->
            val isCurrent = agent.id == activeAgent.id

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBg,
                border = BorderStroke(
                    width = if (isCurrent) 1.5.dp else 1.dp,
                    color = if (isCurrent) agent.color else Color(0xFF1E293B)
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(agent.color.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = agent.icon,
                                    contentDescription = null,
                                    tint = agent.color,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = agent.titleBengali,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Text(
                                    text = agent.name,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextDim
                                )
                            }
                        }

                        if (isCurrent) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = agent.color.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, agent.color)
                            ) {
                                Text(
                                    text = "সক্রিয়",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = agent.color,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    viewModel.selectAiAgent(agent)
                                    Toast.makeText(context, "${agent.titleBengali} সক্রিয় করা হয়েছে", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, agent.color.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = "সক্রিয় করুন",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = agent.color
                                )
                            }
                        }
                    }

                    Text(
                        text = agent.description,
                        fontSize = 11.5.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 16.sp
                    )

                    // Sample Prompts Chips
                    Text(
                        text = "উদাহরণ কমান্ড (ক্লিক করুন):",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextDim
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        agent.samplePrompts.forEach { prompt ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SpaceSurface,
                                border = BorderStroke(0.5.dp, Color(0xFF334155)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectPrompt(prompt) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = agent.color,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = prompt,
                                        fontSize = 11.sp,
                                        color = TextWhite,
                                        maxLines = 1
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
