package com.example.ui.components

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.JarvisFloatingOverlayService
import com.example.service.JarvisWakeWordService
import com.example.service.WakeWordSensitivity
import com.example.util.JarvisDeviceAutomationHelper
import com.example.util.JarvisLiveLocationTracker
import com.example.util.LiveGpsLocation
import kotlinx.coroutines.launch

private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val EmeraldNeon = Color(0xFF10B981)
private val CrimsonNeon = Color(0xFFEF4444)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)

@Composable
fun JarvisOsSettingsDialog(
    onRequestScreenCapture: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isWakeWordActive by JarvisWakeWordService.isRunning.collectAsState()
    val wakeWordSensitivity by JarvisWakeWordService.sensitivityState.collectAsState()
    val hasScreenCaptureConsent by com.example.util.JarvisScreenCaptureManager.hasMediaProjectionConsent.collectAsState()
    val liveGpsLocation by JarvisLiveLocationTracker.currentLocation.collectAsState()
    val isLocationTrackingActive by JarvisLiveLocationTracker.isTrackingActive.collectAsState()

    var isLocationFetching by remember { mutableStateOf(false) }
    var locationStatusMsg by remember { mutableStateOf<String?>(null) }

    // Re-check permissions dynamically
    var isAccessibilityActive: Boolean by remember {
        mutableStateOf(JarvisDeviceAutomationHelper.isAccessibilityGranted(context))
    }
    var isOverlayActive: Boolean by remember {
        mutableStateOf(JarvisDeviceAutomationHelper.isOverlayGranted(context))
    }
    var isBatteryIgnored: Boolean by remember {
        mutableStateOf(JarvisDeviceAutomationHelper.isBatteryOptimizationIgnored(context))
    }
    var isFloatingBubbleRunning: Boolean by remember {
        mutableStateOf(JarvisFloatingOverlayService.isRunning)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = CardBg,
            border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
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
                                .background(CyanNeon.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsSuggest,
                                contentDescription = null,
                                tint = CyanNeon,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "J.A.R.V.I.S. OS Control Matrix",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = TextWhite
                            )
                            Text(
                                text = "অ্যান্ড্রয়েড সিস্টেম অটোমেশন ও পারমিশন কন্ট্রোল",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(SpaceSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextDim,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = Color(0xFF1E3A8A))
                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable permissions and quick automation controls
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // PERMISSION 1: ACCESSIBILITY SERVICE (CRITICAL)
                    PermissionStatusCard(
                        title = "১. অ্যাক্সেসিবিলিটি সার্ভিস (Accessibility)",
                        description = "ইউটিউব, হোয়াটসঅ্যাপ, ফেসবুক ওপেন/ক্লোজ করা, ভিডিও প্লে, স্ক্রিনের টেক্সট পড়া এবং স্বয়ংক্রিয় ক্লিকের জন্য বাধ্যতামূলক।",
                        isGranted = isAccessibilityActive,
                        grantedText = "অনুমোদিত ও সক্রিয় (Active)",
                        notGrantedText = "অনুমতি বন্ধ আছে (Disabled)",
                        actionButtonText = "সেটিংস থেকে চালু করুন",
                        onAction = {
                            JarvisDeviceAutomationHelper.openAccessibilitySettings(context)
                        }
                    )

                    // PERMISSION 2: FLOATING OVERLAY (SYSTEM ALERT WINDOW)
                    PermissionStatusCard(
                        title = "২. ভাসমান আর্ক রিঅ্যাক্টর HUD (Display Over Apps)",
                        description = "ইউটিউব, ফেসবুক বা গেম খেলার সময় স্ক্রিনে সার্বক্ষণিক জারভিস বাবল প্রদর্শনের অনুমতি।",
                        isGranted = isOverlayActive,
                        grantedText = "ভাসমান অনুমতি সক্রিয়",
                        notGrantedText = "ভাসমান অনুমতি প্রয়োজন",
                        actionButtonText = "সেটিংস থেকে পারমিশন দিন",
                        onAction = {
                            JarvisDeviceAutomationHelper.openOverlaySettings(context)
                        }
                    )

                    // Quick Toggle for Floating HUD Bubble
                    if (isOverlayActive) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = SpaceSurface,
                            border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "আর্ক রিঅ্যাক্টর ভাসমান বাবল",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                    Text(
                                        text = if (isFloatingBubbleRunning) "স্ক্রিনে ভাসমান বাবল সক্রিয় রয়েছে" else "অন্যান্য অ্যাপের উপর বাবল দেখাতে অন করুন",
                                        fontSize = 11.sp,
                                        color = TextDim
                                    )
                                }
                                Switch(
                                    checked = isFloatingBubbleRunning,
                                    onCheckedChange = { start ->
                                        if (start) {
                                            JarvisFloatingOverlayService.startOverlay(context)
                                            isFloatingBubbleRunning = true
                                        } else {
                                            JarvisFloatingOverlayService.stopOverlay(context)
                                            isFloatingBubbleRunning = false
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = CyanNeon,
                                        checkedTrackColor = CyanNeon.copy(alpha = 0.3f),
                                        uncheckedThumbColor = TextDim,
                                        uncheckedTrackColor = SpaceDark
                                    )
                                )
                            }
                        }
                    }

                    // PERMISSION 3: BATTERY OPTIMIZATION
                    PermissionStatusCard(
                        title = "৩. ব্যাটারি অপ্টিমাইজেশন (Background Continuity)",
                        description = "ফোন লক থাকলে বা স্ক্রিন অফ থাকলে জারভিস ব্যাকগ্রাউন্ডে সক্রিয় রাখার জন্য ব্যাটারি রেস্ট্রিকশন বন্ধ রাখুন।",
                        isGranted = isBatteryIgnored,
                        grantedText = "অনিয়ন্ত্রিত / সক্রিয় (Unrestricted)",
                        notGrantedText = "ব্যাটারি অপ্টিমাইজড (Optimized)",
                        actionButtonText = "ব্যাটারি অপ্টিমাইজেশন ছাড় দিন",
                        onAction = {
                            JarvisDeviceAutomationHelper.openBatteryOptimizationSettings(context)
                        }
                    )

                    // FEATURE 4: CONTINUOUS WAKE-WORD LISTENING SERVICE WITH SENSITIVITY CONFIG
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, if (isWakeWordActive) CyanNeon.copy(alpha = 0.6f) else Color(0xFF334155)),
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
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = null,
                                        tint = if (isWakeWordActive) CyanNeon else TextDim,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "৪. হ্যান্ডস-ফ্রি ওয়েক-ওয়ার্ড লিসেনিং",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = "উচ্চারণ করুন: 'Jarvis', 'Hey Jarvis', 'জারভিস'",
                                            fontSize = 10.5.sp,
                                            color = CyanNeon
                                        )
                                    }
                                }

                                Switch(
                                    checked = isWakeWordActive,
                                    onCheckedChange = { enable ->
                                        if (enable) {
                                            JarvisWakeWordService.start(context)
                                        } else {
                                            JarvisWakeWordService.stop(context)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = CyanNeon,
                                        checkedTrackColor = CyanNeon.copy(alpha = 0.3f),
                                        uncheckedThumbColor = TextDim,
                                        uncheckedTrackColor = SpaceDark
                                    )
                                )
                            }

                            Text(
                                text = "স্ক্রিন অফ বা অন্য অ্যাপ চললেও ব্যাকগ্রাউন্ডে স্পিচ রেকগনাইজার চালু থাকবে এবং 'Jarvis' বললেই হ্যাপটিক ভাইব্রেশন দিয়ে স্বয়ংক্রিয়ভাবে সক্রিয় হবে।",
                                fontSize = 11.sp,
                                color = TextDim,
                                lineHeight = 16.sp
                            )

                            // Wake-word Sensitivity Threshold Configuration
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SpaceDark.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = CyanNeon,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "ওয়েক-ওয়ার্ড ট্রিগার সংবেদনশীলতা (Sensitivity):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextWhite
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val levels = listOf(
                                        Triple(WakeWordSensitivity.LOW, "কম (Low)", "শান্ত পরিবেশ"),
                                        Triple(WakeWordSensitivity.MEDIUM, "মিডিয়াম", "স্ট্যান্ডার্ড"),
                                        Triple(WakeWordSensitivity.HIGH, "উচ্চ (High)", "দূর/শোরগোল")
                                    )

                                    levels.forEach { (level, title, subtitle) ->
                                        val isCurrent = wakeWordSensitivity == level
                                        Surface(
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isCurrent) CyanNeon.copy(alpha = 0.2f) else SpaceSurface,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isCurrent) CyanNeon else Color(0xFF334155)
                                            ),
                                            onClick = {
                                                JarvisWakeWordService.setSensitivity(context, level)
                                            }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = title,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
                                                    color = if (isCurrent) CyanNeon else TextWhite
                                                )
                                                Text(
                                                    text = subtitle,
                                                    fontSize = 8.5.sp,
                                                    color = if (isCurrent) BlueHolo else TextDim
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // FEATURE 5: MEDIAPROJECTION SCREEN CAPTURE & VISION
                    PermissionStatusCard(
                        title = "৫. স্ক্রিন পারসেপশন ও ক্যাপচার (MediaProjection API)",
                        description = "রিয়েল-টাইম স্ক্রিনশট ও ডিসপ্লে ফ্রেম ধারণ করে জেমিনি ভিশন মডেলকে পাঠানোর অনুমতি। এর মাধ্যমে এআই মোবাইল স্ক্রিনের যেকোনো তথ্য সরাসরি দেখে বুঝতে ও পরিচালনা করতে পারে।",
                        isGranted = hasScreenCaptureConsent,
                        grantedText = "স্ক্রিন ক্যাপচার সক্রিয় (Granted)",
                        notGrantedText = "স্ক্রিন অনুমতি প্রয়োজন (Consent Required)",
                        actionButtonText = "স্ক্রিন ক্যাপচার অনুমতি দিন",
                        onAction = {
                            onRequestScreenCapture()
                        }
                    )

                    // FEATURE 6: REAL-TIME GPS & LIVE LOCATION TRACKING
                    val hasLocationPermission = JarvisLiveLocationTracker.isLocationPermissionGranted(context)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SpaceSurface,
                        border = BorderStroke(
                            1.dp,
                            if (hasLocationPermission) CyanNeon.copy(alpha = 0.5f) else AmberGlow.copy(alpha = 0.5f)
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
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = null,
                                        tint = if (hasLocationPermission) CyanNeon else AmberGlow,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "৬. রিয়েল-টাইম জিপিএস লোকেশন ট্র্যাকিং",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = if (hasLocationPermission) "জিপিএস পারমিশন সক্রিয় (GPS Ready)" else "লোকেশন অনুমতি প্রয়োজন",
                                            fontSize = 10.5.sp,
                                            color = if (hasLocationPermission) EmeraldNeon else AmberGlow
                                        )
                                    }
                                }

                                Switch(
                                    checked = isLocationTrackingActive,
                                    onCheckedChange = { active ->
                                        if (active) {
                                            JarvisLiveLocationTracker.startContinuousTracking(context)
                                        } else {
                                            JarvisLiveLocationTracker.stopContinuousTracking(context)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = CyanNeon,
                                        checkedTrackColor = CyanNeon.copy(alpha = 0.3f),
                                        uncheckedThumbColor = TextDim,
                                        uncheckedTrackColor = SpaceDark
                                    )
                                )
                            }

                            Text(
                                text = "জারভিস হাই-একুরেসি জিপিএস হার্ডওয়্যার ও রিভার্স জিওকোডিং ব্যবহার করে আপনি বর্তমানে কোন এলাকা, থানা, জেলা বা বিভাগে আছেন তা বাংলায় সরাসরি বলবে।",
                                fontSize = 11.sp,
                                color = TextDim,
                                lineHeight = 16.sp
                            )

                            // Live Location display card if available
                            if (liveGpsLocation != null) {
                                val loc = liveGpsLocation!!
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = SpaceDark.copy(alpha = 0.8f),
                                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Place,
                                                contentDescription = null,
                                                tint = CyanNeon,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "বর্তমান সনাক্তকৃত অবস্থান:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = CyanNeon
                                            )
                                        }
                                        Text(
                                            text = loc.fullAddress,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextWhite
                                        )
                                        Text(
                                            text = "স্থানাঙ্ক: ${String.format(java.util.Locale.US, "%.5f", loc.latitude)}, ${String.format(java.util.Locale.US, "%.5f", loc.longitude)} (একুরেসি: ±${loc.accuracy.toInt()}m)",
                                            fontSize = 10.sp,
                                            color = TextDim
                                        )
                                    }
                                }
                            }

                            // Fetch GPS Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (!hasLocationPermission) {
                                            JarvisDeviceAutomationHelper.openApplicationDetailsSettings(context)
                                        } else {
                                            isLocationFetching = true
                                            coroutineScope.launch {
                                                val (ok, loc) = JarvisLiveLocationTracker.fetchCurrentLocation(context)
                                                isLocationFetching = false
                                                if (ok && loc != null) {
                                                    locationStatusMsg = "সফল: ${loc.fullAddress}"
                                                } else {
                                                    locationStatusMsg = "লোকেশন পেতে ব্যর্থ বা ডিভাইস জিপিএস বন্ধ"
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (hasLocationPermission) CyanNeon.copy(alpha = 0.15f) else AmberGlow.copy(alpha = 0.2f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (hasLocationPermission) CyanNeon else AmberGlow
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    if (isLocationFetching) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = CyanNeon,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("জিপিএস অনুসন্ধান চলছে...", color = CyanNeon, fontSize = 12.sp)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.GpsFixed,
                                            contentDescription = null,
                                            tint = if (hasLocationPermission) CyanNeon else AmberGlow,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (hasLocationPermission) "📍 বর্তমান অবস্থান টেস্ট করুন" else "লোকেশন অনুমতি প্রদান করুন",
                                            color = if (hasLocationPermission) CyanNeon else AmberGlow,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (locationStatusMsg != null) {
                                Text(
                                    text = locationStatusMsg!!,
                                    fontSize = 11.sp,
                                    color = EmeraldNeon,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // PERMISSION 7: ALL APP PERMISSIONS
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = CyanNeon,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "অ্যাপের সমস্ত সিস্টেম অনুমতি",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                            }
                            Text(
                                text = "মাইক্রোফোন, ফোন কল, কন্টাক্টস ও অন্যান্য সমস্ত পারমিশন সরাসরি অ্যান্ড্রয়েড অ্যাপ ইনফো থেকে নিয়ন্ত্রণ করুন।",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                            Button(
                                onClick = {
                                    JarvisDeviceAutomationHelper.openApplicationDetailsSettings(context)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = SpaceDark),
                                border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    tint = CyanNeon,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("অ্যাপ ইনফো সেটিংসে যান", color = CyanNeon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // QUICK SYSTEM TEST BUTTONS
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SpaceSurface,
                        border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "কুইক অটোমেশন টেস্ট (Quick Action Test)",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmberGlow
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { JarvisDeviceAutomationHelper.openApp(context, "youtube") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.5f))
                                ) {
                                    Text("ইউটিউব", fontSize = 11.sp, color = CyanNeon)
                                }
                                OutlinedButton(
                                    onClick = { JarvisDeviceAutomationHelper.openApp(context, "whatsapp") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, EmeraldNeon.copy(alpha = 0.5f))
                                ) {
                                    Text("হোয়াটসঅ্যাপ", fontSize = 11.sp, color = EmeraldNeon)
                                }
                                OutlinedButton(
                                    onClick = { JarvisDeviceAutomationHelper.performSystemAction("home") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.5f))
                                ) {
                                    Text("হোম", fontSize = 11.sp, color = BlueHolo)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "ঠিক আছে, সম্পন্ন করুন",
                        fontWeight = FontWeight.Bold,
                        color = SpaceDark,
                        fontSize = 13.5.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    description: String,
    isGranted: Boolean,
    grantedText: String,
    notGrantedText: String,
    actionButtonText: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SpaceSurface,
        border = BorderStroke(
            1.dp,
            if (isGranted) EmeraldNeon.copy(alpha = 0.5f) else AmberGlow.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isGranted) EmeraldNeon.copy(alpha = 0.15f) else CrimsonNeon.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (isGranted) EmeraldNeon else CrimsonNeon)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isGranted) EmeraldNeon else CrimsonNeon,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (isGranted) "অনুমোদিত" else "প্রয়োজন",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isGranted) EmeraldNeon else CrimsonNeon
                        )
                    }
                }
            }

            Text(
                text = description,
                fontSize = 11.sp,
                color = TextDim,
                lineHeight = 16.sp
            )

            if (!isGranted) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AmberGlow),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = null,
                        tint = SpaceDark,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = actionButtonText,
                        color = SpaceDark,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
