package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.util.JarvisDeviceAutomationHelper

private val CyanGlow = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val DarkSpaceBg = Color(0xFF070D1E)
private val CardSurface = Color(0xFF0F1A34)
private val BorderCyan = Color(0xFF00F5D4).copy(alpha = 0.45f)
private val AmberWarn = Color(0xFFFFB703)

/**
 * Immersive Iron Man / Jarvis HUD dialog that prompts the user
 * to enable the Android AccessibilityService for system-level automation.
 */
@Composable
fun JarvisAccessibilityPromptDialog(
    onDismiss: () -> Unit,
    onPermissionGranted: () -> Unit = {}
) {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(JarvisDeviceAutomationHelper.isAccessibilityGranted(context)) }

    // Pulse animation for holographic effect
    val infiniteTransition = rememberInfiniteTransition(label = "hud_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Re-check periodically when user returns from settings
    LaunchedEffect(Unit) {
        while (true) {
            val current = JarvisDeviceAutomationHelper.isAccessibilityGranted(context)
            if (current != isGranted) {
                isGranted = current
                if (current) {
                    onPermissionGranted()
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = DarkSpaceBg,
            border = BorderStroke(1.5.dp, if (isGranted) CyanGlow else AmberWarn.copy(alpha = 0.8f)),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                (if (isGranted) CyanGlow else AmberWarn).copy(alpha = 0.12f),
                                DarkSpaceBg
                            )
                        )
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Holographic Arc Icon with Glow
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    (if (isGranted) CyanGlow else AmberWarn).copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        color = CardSurface,
                        border = BorderStroke(2.dp, if (isGranted) CyanGlow else AmberWarn)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.AccessibilityNew,
                                contentDescription = "Accessibility Icon",
                                tint = if (isGranted) CyanGlow else AmberWarn,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = if (isGranted) "অ্যাক্সেসিবিলিটি সক্রিয় [ONLINE]" else "অ্যাক্সেসিবিলিটি অনুমতি প্রয়োজন",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isGranted) CyanGlow else AmberWarn,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "JARVIS OS AUTOMATION PROTOCOL",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                // Explanation card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardSurface,
                    border = BorderStroke(1.dp, BorderCyan.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeatureRow(
                            icon = "👆",
                            title = "স্বয়ংক্রিয় স্ক্রিন ক্লিক ও টাইপ",
                            desc = "যেকোনো বাটনে ক্লিক করা এবং ইনপুট ফিল্ডে লেখা।"
                        )
                        FeatureRow(
                            icon = "📱",
                            title = "অ্যাপ ক্লোজ ও হোমস্ক্রিন",
                            desc = "যেকোনো চলমান অ্যাপ বন্ধ করে স্বয়ংক্রিয় হোমে ফেরা।"
                        )
                        FeatureRow(
                            icon = "📜",
                            title = "স্মার্ট সোয়াইপ ও স্ক্রোল",
                            desc = "স্ক্রিন উপরে-নিচে নামানো ও ডানে-বামে সোয়াইপ।"
                        )
                        FeatureRow(
                            icon = "👁️",
                            title = "লাইভ স্ক্রিন বিশ্লেষণ",
                            desc = "স্ক্রিনে কী লেখা আছে তা পড়ে আপনাকে জানানো।"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                if (!isGranted) {
                    // Instruction Banner
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = AmberWarn,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "সেটিংস পেজে গিয়ে 'Downloaded apps' বা 'Installed services' থেকে 'Jarvis AI' অন করুন।",
                                fontSize = 11.5.sp,
                                color = Color(0xFFCBD5E1),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                }

                // Action Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isGranted) {
                        Button(
                            onClick = {
                                JarvisDeviceAutomationHelper.openAccessibilitySettings(context)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("grant_accessibility_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanGlow,
                                contentColor = DarkSpaceBg
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "সেটিংস থেকে চালু করুন",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                onPermissionGranted()
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("continue_accessibility_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanGlow,
                                contentColor = DarkSpaceBg
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "অ্যাক্সেসিবিলিটি চালু আছে • জারভিস প্রস্তুত",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("dismiss_accessibility_button"),
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = if (isGranted) "বন্ধ করুন" else "পরে করব",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = icon, fontSize = 14.sp)
        Column {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF1F5F9)
            )
            Text(
                text = desc,
                fontSize = 10.5.sp,
                color = Color(0xFF94A3B8),
                lineHeight = 14.sp
            )
        }
    }
}
