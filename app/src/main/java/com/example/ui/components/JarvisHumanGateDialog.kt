package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.automation.GateRequest

private val CardBg = Color(0xFF0F1A36)
private val SpaceDark = Color(0xFF040814)
private val CyanNeon = Color(0xFF00F5D4)
private val AmberGlow = Color(0xFFFEE440)
private val RedAlert = Color(0xFFEF4444)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)
private val BorderBlue = Color(0xFF1E3A8A)

@Composable
fun JarvisHumanGateDialog(
    request: GateRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (request.isDoubleConfirm) RedAlert.copy(alpha = 0.2f)
                            else AmberGlow.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (request.isDoubleConfirm) RedAlert else AmberGlow,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "নিশ্চিতকরণ প্রয়োজন — JARVIS",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (request.isDoubleConfirm) RedAlert else CyanNeon,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = if (request.isDoubleConfirm) "অপরিবর্তনযোগ্য সিস্টেম পদক্ষেপ" else "স্মার্ট অটোমেশন সুরক্ষা গেট",
                        fontSize = 11.sp,
                        color = TextDim
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SpaceDark,
                    border = BorderStroke(
                        1.dp,
                        if (request.isDoubleConfirm) RedAlert.copy(alpha = 0.5f) else AmberGlow.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = request.promptBn,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextWhite,
                            lineHeight = 20.sp
                        )

                        if (request.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "\"${request.content}\"",
                                fontSize = 12.sp,
                                color = CyanNeon,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (request.isDoubleConfirm) {
                    Text(
                        text = "সতর্কতা: এই প্রক্রিয়াটি সম্পূর্ণ হলে আর পূর্বাবস্থায় ফিরিয়ে আনা যাবে না।",
                        fontSize = 11.sp,
                        color = RedAlert,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (request.isDoubleConfirm) RedAlert else CyanNeon
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SpaceDark,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (request.isDoubleConfirm) "হ্যাঁ, চূড়ান্ত নিশ্চিত" else "হ্যাঁ, সম্মতি দিচ্ছি",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SpaceDark
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, TextDim.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = TextDim,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "না, বাতিল",
                    fontSize = 12.sp,
                    color = TextDim
                )
            }
        }
    )
}
