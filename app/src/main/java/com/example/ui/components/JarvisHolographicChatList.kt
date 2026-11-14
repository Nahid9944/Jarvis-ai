package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.JarvisChatHistoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val DeepSpaceDark = Color(0xFF040814)
private val SurfaceCard = Color(0xFF0A1226)
private val SurfaceBubbleJarvis = Color(0xFF0E1A36)
private val SurfaceBubbleUser = Color(0xFF0D2240)
private val AmberWarn = Color(0xFFFFB703)
private val EmeraldSuccess = Color(0xFF10B981)
private val CrimsonAlert = Color(0xFFF72585)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)

/**
 * Scrollable, Holographic-Styled Chat & Voice History List for Jarvis AI OS.
 * Displays user voice/text commands and AI responses persisted in Room Database.
 */
@Composable
fun JarvisHolographicChatList(
    historyList: List<JarvisChatHistoryEntity>,
    onClearAll: () -> Unit,
    onDeleteSingle: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showConfirmClearDialog by remember { mutableStateOf(false) }

    // Auto-scroll to top when a new command arrives
    LaunchedEffect(historyList.size) {
        if (historyList.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepSpaceDark)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // Holographic Console Header
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceCard.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(CyanNeon)
                    )
                    Column {
                        Text(
                            text = "ভয়েস কমান্ড ও এআই হিস্ট্রি",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanNeon
                        )
                        Text(
                            text = "ROOM DATABASE • ${historyList.size} টি রেকর্ড সংরক্ষিত",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextDim,
                            letterSpacing = 1.sp
                        )
                    }
                }

                if (historyList.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showConfirmClearDialog = true },
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("clear_chat_history_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear All",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "সব মুছুন", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (historyList.isEmpty()) {
            // Empty State Hologram Graphic
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(CyanNeon.copy(alpha = 0.2f), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(54.dp),
                            shape = CircleShape,
                            color = SurfaceCard,
                            border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.5f))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = CyanNeon,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "কোনো ভয়েস কমান্ড সংরক্ষিত নেই",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "জারভিসকে বলুন: 'ইউটিউব খোলো', 'আমি কোথায় আছি', 'রহিমকে কল দাও' বা যেকোনো প্রশ্ন করুন। সমস্ত কথপোকথন ও ফলাফল স্বয়ংক্রিয়ভাবে এখানে সংরক্ষিত হবে।",
                        fontSize = 12.sp,
                        color = TextDim,
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp
                    )
                }
            }
        } else {
            // Holographic Scrollable History List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("holographic_chat_history_list"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = historyList,
                    key = { it.id }
                ) { record ->
                    HolographicChatHistoryItem(
                        record = record,
                        onDelete = { onDeleteSingle(record.id) },
                        onCopy = { textToCopy ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis Log", textToCopy))
                            Toast.makeText(context, "টেক্সট কপি করা হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Confirmation Dialog for Clearing History
    if (showConfirmClearDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmClearDialog = false },
            title = {
                Text(
                    text = "সমস্ত হিস্ট্রি মুছে ফেলতে চান?",
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "এটি আপনার লোকাল রুম ডেটাবেস থেকে পূর্ববর্তী সমস্ত ভয়েস কমান্ড ও এআই রেসপন্স সম্পূর্ণ মুছে ফেলবে।",
                    color = TextDim,
                    fontSize = 13.sp
                )
            },
            containerColor = SurfaceCard,
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showConfirmClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert)
                ) {
                    Text("হ্যাঁ, সব মুছুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearDialog = false }) {
                    Text("বাতিল", color = TextDim)
                }
            }
        )
    }
}

@Composable
private fun HolographicChatHistoryItem(
    record: JarvisChatHistoryEntity,
    onDelete: () -> Unit,
    onCopy: (String) -> Unit
) {
    val timeFormatted = remember(record.timestamp) {
        val sdf = SimpleDateFormat("hh:mm a • dd MMM", Locale.getDefault())
        sdf.format(Date(record.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. User Command Bubble (Right Aligned, Blue Neon Hologram)
        if (record.userPrompt.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = SurfaceBubbleUser,
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.5f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier.widthIn(max = 310.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (record.commandType == "VOICE") Icons.Default.Mic else Icons.Default.Keyboard,
                                contentDescription = null,
                                tint = BlueHolo,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "YOU • ${record.commandType}",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = BlueHolo
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = timeFormatted,
                                fontSize = 9.sp,
                                color = TextDim
                            )
                        }

                        Text(
                            text = record.userPrompt,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextWhite,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // 2. Jarvis AI Response Bubble (Left Aligned, Cyan Neon Hologram)
        if (record.aiResponse.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                    color = SurfaceBubbleJarvis,
                    border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.5f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.widthIn(max = 330.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = CyanNeon.copy(alpha = 0.15f),
                                modifier = Modifier.size(18.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AllInclusive,
                                        contentDescription = "Jarvis Core",
                                        tint = CyanNeon,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }

                            Text(
                                text = "JARVIS AI OS",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                color = CyanNeon,
                                letterSpacing = 1.sp
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            // Status Tag
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (record.status) {
                                    "ERROR" -> CrimsonAlert.copy(alpha = 0.18f)
                                    "EXECUTED" -> EmeraldSuccess.copy(alpha = 0.18f)
                                    else -> CyanNeon.copy(alpha = 0.18f)
                                },
                                border = BorderStroke(
                                    0.5.dp,
                                    when (record.status) {
                                        "ERROR" -> CrimsonAlert
                                        "EXECUTED" -> EmeraldSuccess
                                        else -> CyanNeon
                                    }
                                )
                            ) {
                                Text(
                                    text = record.status,
                                    fontSize = 8.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when (record.status) {
                                        "ERROR" -> CrimsonAlert
                                        "EXECUTED" -> EmeraldSuccess
                                        else -> CyanNeon
                                    },
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = record.aiResponse,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 18.sp
                        )

                        // Action Bar on Response (Copy, TTS Speak, Delete)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onCopy(record.aiResponse) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Response",
                                    tint = TextDim,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Record",
                                    tint = CrimsonAlert.copy(alpha = 0.7f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
