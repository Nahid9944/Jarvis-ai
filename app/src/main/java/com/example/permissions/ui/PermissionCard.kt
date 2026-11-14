package com.example.permissions.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.permissions.PermissionKind
import com.example.permissions.PermissionSpec

private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val GreenNeon = Color(0xFF10B981)
private val RedAlert = Color(0xFFEF4444)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)
private val BorderDark = Color(0xFF1E3A8A)

@Composable
fun PermissionCard(
    spec: PermissionSpec,
    isGranted: Boolean,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isGranted) GreenNeon.copy(alpha = 0.5f) else AmberGlow.copy(alpha = 0.4f),
        label = "border_color"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon Box
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGranted) GreenNeon.copy(alpha = 0.15f)
                            else AmberGlow.copy(alpha = 0.15f)
                        )
                        .border(
                            1.dp,
                            if (isGranted) GreenNeon.copy(alpha = 0.5f)
                            else AmberGlow.copy(alpha = 0.5f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getIconForSpec(spec.iconKey),
                        contentDescription = spec.titleBn,
                        tint = if (isGranted) GreenNeon else AmberGlow,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = spec.titleBn,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite,
                            fontFamily = FontFamily.SansSerif
                        )

                        // Status Badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isGranted) GreenNeon.copy(alpha = 0.18f) else RedAlert.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, if (isGranted) GreenNeon else RedAlert.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = if (isGranted) "অনুমোদিত" else "প্রয়োজন",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isGranted) GreenNeon else RedAlert,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = when (spec.kind) {
                            PermissionKind.RUNTIME -> "রানটাইম অনুমতি"
                            PermissionKind.SPECIAL_ACCESS -> "সিস্টেম স্পেশাল এক্সেস"
                            PermissionKind.ROLE -> "ডিফল্ট অ্যাপ রোল"
                            PermissionKind.PER_SESSION -> "লাইভ সেশন টোকেন"
                            PermissionKind.MANIFEST_ONLY -> "সিস্টেম প্যাকেজ"
                        },
                        fontSize = 11.sp,
                        color = CyanNeon.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = spec.whyBn,
                fontSize = 12.sp,
                color = TextDim,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = CyanNeon,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "যা সক্ষম করে: ${spec.whatItEnablesBn}",
                    fontSize = 11.sp,
                    color = BlueHolo,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onActionClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00B4D8), Color(0xFF0077B6))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (spec.kind == PermissionKind.SPECIAL_ACCESS) Icons.Default.Settings else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = TextWhite,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (spec.kind == PermissionKind.SPECIAL_ACCESS) "সেটিংসে গিয়ে চালু করুন" else "অনুমতি দিন",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getIconForSpec(key: String): ImageVector {
    return when (key) {
        "camera" -> Icons.Default.CameraAlt
        "mic" -> Icons.Default.Mic
        "notifications" -> Icons.Default.Notifications
        "notifications_active" -> Icons.Default.NotificationsActive
        "contacts" -> Icons.Default.Contacts
        "person_add" -> Icons.Default.PersonAdd
        "phone_android" -> Icons.Default.PhoneAndroid
        "call" -> Icons.Default.Call
        "call_received" -> Icons.Default.CallReceived
        "sms" -> Icons.Default.Sms
        "mark_chat_unread" -> Icons.Default.MarkChatUnread
        "send" -> Icons.Default.Send
        "my_location" -> Icons.Default.MyLocation
        "location_searching" -> Icons.Default.LocationSearching
        "calendar_month" -> Icons.Default.CalendarMonth
        "event" -> Icons.Default.Event
        "photo_library" -> Icons.Default.PhotoLibrary
        "audiotrack" -> Icons.Default.Audiotrack
        "accessibility_new" -> Icons.Default.AccessibilityNew
        "layers" -> Icons.Default.Layers
        "battery_charging_full" -> Icons.Default.BatteryChargingFull
        "alarm" -> Icons.Default.Alarm
        "query_stats" -> Icons.Default.QueryStats
        "screenshot" -> Icons.Default.Screenshot
        "tune" -> Icons.Default.Tune
        "apps" -> Icons.Default.Apps
        else -> Icons.Default.Security
    }
}
