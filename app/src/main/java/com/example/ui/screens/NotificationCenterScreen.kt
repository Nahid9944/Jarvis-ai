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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppNotificationEntity
import com.example.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.*

private val NotifSpaceDark = Color(0xFF040814)
private val NotifSurface = Color(0xFF0A1226)
private val NotifCardBg = Color(0xFF0F1A36)
private val NotifCyan = Color(0xFF00F5D4)
private val NotifBlue = Color(0xFF00BBF9)
private val NotifAmber = Color(0xFFFEE440)
private val NotifPink = Color(0xFFF15BB5)
private val NotifEmerald = Color(0xFF10B981)
private val NotifViolet = Color(0xFFA78BFA)
private val NotifTextWhite = Color(0xFFF8FAFC)
private val NotifTextDim = Color(0xFF94A3B8)

private val ALERT_NOTIFICATION_TYPES = setOf(
    "REMINDER", "TASK_UPCOMING", "SECURITY_ALERT", "SECURITY_OTP",
    "PERMISSION_REQUEST", "DEVICE_ALERT", "ALARM"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    viewModel: AssistantViewModel,
    onNavigate: (route: String, targetQuery: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val notifications by viewModel.allNotifications.collectAsState()
    val unreadCount by viewModel.unreadNotificationCount.collectAsState()

    var selectedFilterIndex by remember { mutableIntStateOf(0) } // 0: All, 1: Unread, 2: Alerts/Reminders, 3: AI Activity
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val filteredList = remember(notifications, selectedFilterIndex) {
        when (selectedFilterIndex) {
            1 -> notifications.filter { !it.isRead }
            2 -> notifications.filter { it.type in ALERT_NOTIFICATION_TYPES }
            3 -> notifications.filter { it.type !in ALERT_NOTIFICATION_TYPES }
            else -> notifications
        }
    }

    Scaffold(
        containerColor = NotifSpaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "নোটিফিকেশন ইন্টেলিজেন্স",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = NotifCyan
                        )
                        if (unreadCount > 0) {
                            Text(
                                text = "$unreadCount টি অপঠিত বিজ্ঞপ্তি",
                                fontSize = 11.5.sp,
                                color = NotifAmber
                            )
                        } else {
                            Text(
                                text = "সর্বমোট ${notifications.size}টি বার্তা",
                                fontSize = 11.5.sp,
                                color = NotifTextDim
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("notif_center_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "ব্যাক",
                            tint = NotifCyan
                        )
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        IconButton(
                            onClick = {
                                viewModel.markAllNotificationsAsRead()
                                Toast.makeText(context, "সকল নোটিফিকেশন পড়া হিসেবে চিহ্নিত হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "সব পড়ুন",
                                tint = NotifCyan
                            )
                        }
                    }

                    if (notifications.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearConfirmDialog = true },
                            modifier = Modifier.testTag("clear_all_notifications_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "সব মুছুন",
                                tint = NotifPink
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NotifSpaceDark,
                    titleContentColor = NotifTextWhite
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NotifSpaceDark)
                .navigationBarsPadding()
        ) {
            // Filter Chips
            if (notifications.isNotEmpty()) {
                val alertCount = notifications.count { it.type in ALERT_NOTIFICATION_TYPES }
                val activityCount = notifications.size - alertCount
                val filters = listOf(
                    "সকল (${notifications.size})",
                    "অপঠিত ($unreadCount)",
                    "রিমাইন্ডার ও অ্যালার্ট ($alertCount)",
                    "কার্যক্রম ও মেসেজ ($activityCount)"
                )

                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters.size) { idx ->
                        val selected = selectedFilterIndex == idx
                        FilterChip(
                            selected = selected,
                            onClick = { selectedFilterIndex = idx },
                            label = {
                                Text(
                                    text = filters[idx],
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = NotifSurface,
                                labelColor = NotifTextDim,
                                selectedContainerColor = NotifCyan.copy(alpha = 0.2f),
                                selectedLabelColor = NotifCyan
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = Color(0xFF334155),
                                selectedBorderColor = NotifCyan
                            )
                        )
                    }
                }
            }

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(NotifCardBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsNone,
                                contentDescription = null,
                                tint = NotifCyan,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Text(
                            text = "কোনো নতুন নোটিফিকেশন নেই",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = NotifTextWhite
                        )
                        Text(
                            text = "কোনো ব্যক্তিগত রিমাইন্ডার, অটোমেশন বা সিস্টেম নোটিফিকেশন এলে এখানে দেখাবে।",
                            fontSize = 13.sp,
                            color = NotifTextDim,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredList, key = { it.id }) { notif ->
                        NotificationItemCard(
                            notification = notif,
                            onClick = {
                                viewModel.markNotificationAsRead(notif.id)
                                onNavigate(notif.targetRoute, notif.targetName)
                            },
                            onDelete = {
                                viewModel.deleteNotification(notif.id)
                                Toast.makeText(context, "নোটিফিকেশন মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            containerColor = NotifCardBg,
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = NotifPink,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "সব নোটিফিকেশন মুছবেন?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = NotifTextWhite
                )
            },
            text = {
                Text(
                    text = "নোটিফিকেশন সেন্টারের সব বিজ্ঞপ্তি মুছে ফেলতে চান? আপনার সংরক্ষিত মেমোরি, নোট বা টাস্ক অক্ষুণ্ণ থাকবে।",
                    fontSize = 13.sp,
                    color = NotifTextDim
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllNotifications()
                        showClearConfirmDialog = false
                        Toast.makeText(context, "সকল নোটিফিকেশন মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NotifPink)
                ) {
                    Text("সব মুছুন", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("বাতিল", color = NotifTextDim)
                }
            }
        )
    }
}

@Composable
fun NotificationItemCard(
    notification: AppNotificationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val meta = getNotificationVisualMeta(notification.type)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("notification_card_${notification.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!notification.isRead) NotifCardBg else NotifSurface
        ),
        border = BorderStroke(
            1.2.dp,
            if (!notification.isRead) meta.iconColor.copy(alpha = 0.6f) else Color(0xFF1E293B)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(meta.bgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = meta.icon,
                    contentDescription = null,
                    tint = meta.iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = meta.bgColor,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = meta.tagText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = meta.iconColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatNotificationTime(notification.timestamp),
                            fontSize = 11.sp,
                            color = NotifTextDim
                        )
                        if (!notification.isRead) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(NotifCyan)
                            )
                        }
                    }
                }

                Text(
                    text = notification.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = NotifTextWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = notification.message,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = NotifTextDim
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = meta.actionCue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = meta.iconColor
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = meta.iconColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("delete_notification_btn_${notification.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "নোটিফিকেশন মুছুন",
                    tint = NotifTextDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

data class NotificationVisualMeta(
    val tagText: String,
    val icon: ImageVector,
    val iconColor: Color,
    val bgColor: Color,
    val actionCue: String
)

fun getNotificationVisualMeta(type: String): NotificationVisualMeta {
    return when (type) {
        "REMINDER", "ALARM", "TASK_UPCOMING" -> NotificationVisualMeta(
            tagText = "⏱️ রিমাইন্ডার",
            icon = Icons.Default.Alarm,
            iconColor = NotifAmber,
            bgColor = NotifAmber.copy(alpha = 0.16f),
            actionCue = "টাস্ক দেখুন"
        )
        "TASK_COMPLETED" -> NotificationVisualMeta(
            tagText = "✅ কাজ সম্পন্ন",
            icon = Icons.Default.CheckCircle,
            iconColor = NotifEmerald,
            bgColor = NotifEmerald.copy(alpha = 0.16f),
            actionCue = "বিস্তারিত দেখুন"
        )
        "SECURITY_OTP", "SECURITY_ALERT" -> NotificationVisualMeta(
            tagText = "🔐 সিকিউরিটি / OTP",
            icon = Icons.Default.Security,
            iconColor = NotifPink,
            bgColor = NotifPink.copy(alpha = 0.16f),
            actionCue = "নিরাপত্তা লগ দেখুন"
        )
        "PERMISSION_REQUEST" -> NotificationVisualMeta(
            tagText = "🛡️ পারমিশন",
            icon = Icons.Default.Shield,
            iconColor = NotifBlue,
            bgColor = NotifBlue.copy(alpha = 0.16f),
            actionCue = "পারমিশন সেন্টার"
        )
        "AUTOMATION", "WORKFLOW" -> NotificationVisualMeta(
            tagText = "⚡ অটোমেশন",
            icon = Icons.Default.Bolt,
            iconColor = NotifViolet,
            bgColor = NotifViolet.copy(alpha = 0.16f),
            actionCue = "অটোমেশন দেখুন"
        )
        "INCOMING_NOTIFICATION" -> NotificationVisualMeta(
            tagText = "💬 ইনকামিং মেসেজ",
            icon = Icons.Default.MarkChatUnread,
            iconColor = NotifCyan,
            bgColor = NotifCyan.copy(alpha = 0.16f),
            actionCue = "রিপ্লাই / বিস্তারিত"
        )
        "DEVICE_ALERT" -> NotificationVisualMeta(
            tagText = "📱 ডিভাইস অ্যালার্ট",
            icon = Icons.Default.Devices,
            iconColor = NotifAmber,
            bgColor = NotifAmber.copy(alpha = 0.16f),
            actionCue = "ডিভাইস স্ট্যাটাস"
        )
        else -> NotificationVisualMeta(
            tagText = "✨ NAHID AI",
            icon = Icons.Default.Notifications,
            iconColor = NotifCyan,
            bgColor = NotifCyan.copy(alpha = 0.14f),
            actionCue = "বিস্তারিত দেখুন"
        )
    }
}

fun formatNotificationTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val notifTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(timestamp))

    return if (now.get(Calendar.YEAR) == notifTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == notifTime.get(Calendar.DAY_OF_YEAR)
    ) {
        "আজ, $formattedTime"
    } else if (now.get(Calendar.YEAR) == notifTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - notifTime.get(Calendar.DAY_OF_YEAR) == 1
    ) {
        "গতকাল, $formattedTime"
    } else {
        val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        dateFormat.format(Date(timestamp))
    }
}
