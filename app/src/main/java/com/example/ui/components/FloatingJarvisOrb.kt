package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class OrbVisualState(
    val titleBengali: String,
    val primaryColor: Color,
    val secondaryColor: Color
) {
    IDLE("স্ট্যান্ডবাই প্রস্তুত", Color(0xFF00F5D4), Color(0xFF00BBF9)),
    LISTENING("শুনছি...", Color(0xFF00F5D4), Color(0xFF38BDF8)),
    THINKING("প্রসেসিং...", Color(0xFFFEE440), Color(0xFFF59E0B)),
    SPEAKING("কথা বলছি...", Color(0xFF10B981), Color(0xFF00F5D4)),
    ERROR("সতর্কতা / ত্রুটি", Color(0xFFEF4444), Color(0xFFF43F5E))
}

@Composable
fun FloatingJarvisOrb(
    modifier: Modifier = Modifier,
    orbState: OrbVisualState = OrbVisualState.IDLE,
    isVoiceSessionActive: Boolean = false,
    audioEnergy: Float = 0f,
    onToggleLiveVoice: () -> Unit = {},
    onTriggerScreenAnalysis: () -> Unit = {},
    onToggleFlashlight: () -> Unit = {},
    onOpenDialer: () -> Unit = {},
    onOpenWhatsApp: () -> Unit = {},
    onOpenFullTerminal: () -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val orbSizePx = with(density) { 62.dp.toPx() }

    // Position state with edge-docking spring animation
    val offsetX = remember { Animatable(screenWidthPx - orbSizePx - 24f) }
    val offsetY = remember { Animatable(screenHeightPx * 0.45f) }

    var isExpanded by remember { mutableStateOf(false) }

    // Visual animation transitions
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulse")
    
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val coreRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (orbState) {
                    OrbVisualState.THINKING -> 1800
                    OrbVisualState.LISTENING -> 3500
                    else -> 6000
                },
                easing = LinearEasing
            )
        ),
        label = "rotation"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        // Draggable Floating Orb
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(62.dp)
                .testTag("floating_jarvis_orb")
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Snap to the closest edge (Left or Right)
                            coroutineScope.launch {
                                val targetX = if (offsetX.value < screenWidthPx / 2) {
                                    24f // Dock Left
                                } else {
                                    screenWidthPx - orbSizePx - 24f // Dock Right
                                }
                                offsetX.animateTo(
                                    targetValue = targetX,
                                    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
                                )
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            val newX = (offsetX.value + dragAmount.x).coerceIn(12f, screenWidthPx - orbSizePx - 12f)
                            val newY = (offsetY.value + dragAmount.y).coerceIn(80f, screenHeightPx - orbSizePx - 100f)
                            offsetX.snapTo(newX)
                            offsetY.snapTo(newY)
                        }
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    isExpanded = !isExpanded
                },
            contentAlignment = Alignment.Center
        ) {
            // Arc Reactor Canvas Animation
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(if (orbState == OrbVisualState.LISTENING) breathingScale + (audioEnergy * 0.005f) else breathingScale)
            ) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2

                // Outer Glow Ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            orbState.primaryColor.copy(alpha = pulseAlpha * 0.6f),
                            orbState.secondaryColor.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * 1.25f
                    ),
                    radius = radius * 1.2f
                )

                // Outer Arc Reactor Ring
                drawCircle(
                    color = orbState.primaryColor,
                    radius = radius * 0.88f,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )

                // Rotating Energy Segments
                val segmentCount = 6
                val angleStep = 360f / segmentCount
                for (i in 0 until segmentCount) {
                    val angleRad = Math.toRadians((i * angleStep + coreRotation).toDouble())
                    val dotRadius = radius * 0.72f
                    val dotX = center.x + (dotRadius * cos(angleRad)).toFloat()
                    val dotY = center.y + (dotRadius * sin(angleRad)).toFloat()

                    drawCircle(
                        color = if (i % 2 == 0) orbState.primaryColor else orbState.secondaryColor,
                        radius = 2.2f,
                        center = Offset(dotX, dotY)
                    )
                }

                // Inner Arc Core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            orbState.primaryColor,
                            orbState.secondaryColor.copy(alpha = 0.8f)
                        ),
                        center = center,
                        radius = radius * 0.5f
                    ),
                    radius = radius * 0.45f
                )
            }
        }

        // Quick-Access Micro HUD Expanded Flyout Menu
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(200), targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF040814).copy(alpha = 0.94f),
                border = BorderStroke(1.5.dp, orbState.primaryColor),
                shadowElevation = 18.dp,
                modifier = Modifier
                    .width(320.dp)
                    .testTag("floating_orb_quick_hud")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                    .background(orbState.primaryColor)
                            )
                            Text(
                                text = "J.A.R.V.I.S. MINI HUD",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC),
                                letterSpacing = 1.sp
                            )
                        }

                        IconButton(
                            onClick = { isExpanded = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                        }
                    }

                    // Live Status Pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F1A36),
                        border = BorderStroke(1.dp, orbState.primaryColor.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "স্ট্যাটাস: ${orbState.titleBengali}",
                                fontSize = 12.sp,
                                color = orbState.primaryColor,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isVoiceSessionActive) "🔴 LIVE" else "READY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isVoiceSessionActive) Color(0xFFEF4444) else Color(0xFF10B981)
                            )
                        }
                    }

                    // Quick-Access Action Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Action 1: Gemini Live Voice Toggle
                        OrbActionItem(
                            icon = if (isVoiceSessionActive) Icons.Default.MicOff else Icons.Default.Mic,
                            label = if (isVoiceSessionActive) "মিউট" else "লাইভ ভয়েস",
                            tint = if (isVoiceSessionActive) Color(0xFFEF4444) else Color(0xFF00F5D4),
                            onClick = {
                                onToggleLiveVoice()
                                isExpanded = false
                            }
                        )

                        // Action 2: Screen Perception / Camera
                        OrbActionItem(
                            icon = Icons.Default.Visibility,
                            label = "স্ক্রিন ভিশন",
                            tint = Color(0xFF00BBF9),
                            onClick = {
                                onTriggerScreenAnalysis()
                                isExpanded = false
                            }
                        )

                        // Action 3: Flashlight Toggle
                        OrbActionItem(
                            icon = Icons.Default.FlashlightOn,
                            label = "ফ্ল্যাশ",
                            tint = Color(0xFFFEE440),
                            onClick = {
                                onToggleFlashlight()
                                isExpanded = false
                            }
                        )

                        // Action 4: Quick WhatsApp
                        OrbActionItem(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            label = "হোয়াটসঅ্যাপ",
                            tint = Color(0xFF10B981),
                            onClick = {
                                onOpenWhatsApp()
                                isExpanded = false
                            }
                        )
                    }

                    HorizontalDivider(color = Color(0xFF1E293B))

                    // Secondary Quick Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onOpenDialer()
                                isExpanded = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                            Spacer(Modifier.width(6.dp))
                            Text("কল / ডায়াল", fontSize = 12.sp, color = Color(0xFFF8FAFC))
                        }

                        Button(
                            onClick = {
                                onOpenFullTerminal()
                                isExpanded = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4))
                        ) {
                            Text("ফুল টার্মিনাল", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF040814))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xFF0F1A36))
                .shadow(4.dp, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = Color(0xFFCBD5E1),
            textAlign = TextAlign.Center
        )
    }
}
