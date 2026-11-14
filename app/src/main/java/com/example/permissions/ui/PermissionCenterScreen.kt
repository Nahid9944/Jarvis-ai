package com.example.permissions.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.permissions.ActionApprovalRegistry
import com.example.permissions.ActionSecurityPolicy
import com.example.permissions.JarvisPermissionManager
import com.example.permissions.PermissionKind
import com.example.permissions.PermissionOrchestrator
import com.example.permissions.PermissionRegistry
import com.example.permissions.PermissionSpec
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val GreenNeon = Color(0xFF10B981)
private val RedAlert = Color(0xFFEF4444)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)
private val BorderBlue = Color(0xFF1E3A8A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionCenterScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var permissionStatuses by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var selectedSection by remember { mutableIntStateOf(0) } // 0: OS Permissions, 1: Audit History, 2: Low-Risk Rules
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, GRANTED, MISSING, SPECIAL
    val currentRequest by PermissionOrchestrator.currentRequest.collectAsState()
    val auditTrail by ActionApprovalRegistry.auditTrail.collectAsState()
    val trustedRules by ActionApprovalRegistry.trustedRules.collectAsState()
    val revokedNotices by JarvisPermissionManager.revokedNotices.collectAsState()

    // Periodically recheck permissions when screen is active
    LaunchedEffect(Unit) {
        while (true) {
            permissionStatuses = PermissionOrchestrator.recheckAll(context)
            delay(1200)
        }
    }

    val allSpecs = remember { PermissionRegistry.getAll() }
    val grantedCount = permissionStatuses.count { it.value }
    val totalCount = allSpecs.size
    val percentage = if (totalCount > 0) (grantedCount * 100 / totalCount) else 0

    // Arc reactor pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "hud_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val filteredSpecs = remember(selectedFilter, permissionStatuses) {
        when (selectedFilter) {
            "GRANTED" -> allSpecs.filter { permissionStatuses[it.id] == true }
            "MISSING" -> allSpecs.filter { permissionStatuses[it.id] != true }
            "SPECIAL" -> allSpecs.filter { it.kind == PermissionKind.SPECIAL_ACCESS }
            else -> allSpecs
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "JARVIS OS — পারমিশন সেন্টার",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyanNeon
                        )
                        Text(
                            text = "ডিভাইস অটোনমি ও ক্যাপাবিলিটি কন্ট্রোল",
                            fontSize = 11.sp,
                            color = TextDim
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = CyanNeon
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            permissionStatuses = PermissionOrchestrator.recheckAll(context)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Status",
                            tint = BlueHolo
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpaceDark)
            )
        },
        containerColor = SpaceDark
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Capability Score HUD Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = CardBg,
                    border = BorderStroke(1.5.dp, Brush.horizontalGradient(listOf(CyanNeon, BlueHolo))),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Circular Gauge
                        Box(
                            modifier = Modifier.size(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 8.dp.toPx()
                                val arcSize = size.width - strokeWidth

                                // Background Arc
                                drawArc(
                                    color = Color(0xFF1E293B),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                                    size = Size(arcSize, arcSize),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )

                                // Progress Arc
                                val sweep = (percentage / 100f) * 360f
                                drawArc(
                                    brush = Brush.sweepGradient(listOf(CyanNeon, BlueHolo, GreenNeon, CyanNeon)),
                                    startAngle = -90f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                                    size = Size(arcSize, arcSize),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$percentage%",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextWhite,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Text(
                                    text = "সক্ষমতা",
                                    fontSize = 10.sp,
                                    color = CyanNeon
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "সিস্টেম সক্ষমতা স্কোর",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "$grantedCount / $totalCount অনুমতি সক্রিয় আছে",
                                fontSize = 13.sp,
                                color = if (percentage >= 80) GreenNeon else AmberGlow,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            val missingSpecs = allSpecs.filter { permissionStatuses[it.id] != true }
                            if (missingSpecs.isNotEmpty()) {
                                Button(
                                    onClick = {
                                        val nextMissing = missingSpecs.firstOrNull()
                                        if (nextMissing != null) {
                                            PermissionOrchestrator.request(nextMissing)
                                        }
                                    },
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon.copy(alpha = 0.2f)),
                                    border = BorderStroke(1.dp, CyanNeon)
                                ) {
                                    Text(
                                        text = "পরবর্তী অনুমতি ঠিক করুন",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanNeon
                                    )
                                }
                            } else {
                                Text(
                                    text = "সব অনুমতি অ্যাক্টিভ! জারভিস ফুল পাওয়ারে প্রস্তুত।",
                                    fontSize = 11.sp,
                                    color = GreenNeon
                                )
                            }
                        }
                    }
                }
            }

            // Primary Section Selector (Permissions / Audit / Trusted Low-Risk Rules)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0 to "পারমিশন ($grantedCount/$totalCount)",
                        1 to "সম্মতির ইতিহাস (${auditTrail.size})",
                        2 to "লো-রিস্ক রুলস"
                    ).forEach { (idx, title) ->
                        val active = selectedSection == idx
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) CyanNeon.copy(alpha = 0.2f) else CardBg,
                            border = BorderStroke(1.dp, if (active) CyanNeon else BorderBlue),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .clickable { selectedSection = idx }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                                Text(
                                    text = title,
                                    color = if (active) CyanNeon else TextDim,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            if (revokedNotices.isNotEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = RedAlert.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, RedAlert.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("অনুমতি প্রত্যাহার সতর্কতা:", color = AmberGlow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            revokedNotices.forEach { note ->
                                Text("• $note", color = TextWhite, fontSize = 11.5.sp)
                            }
                            TextButton(onClick = { JarvisPermissionManager.clearRevokedNotices() }) {
                                Text("বুঝেছি", color = CyanNeon, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }

            when (selectedSection) {
                0 -> {
                    // Filter Tabs
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedFilter == "ALL",
                                    onClick = { selectedFilter = "ALL" },
                                    label = { Text("সকল ($totalCount)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CyanNeon.copy(alpha = 0.2f),
                                        selectedLabelColor = CyanNeon
                                    )
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "MISSING",
                                    onClick = { selectedFilter = "MISSING" },
                                    label = { Text("বাকি (${totalCount - grantedCount})") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = RedAlert.copy(alpha = 0.2f),
                                        selectedLabelColor = AmberGlow
                                    )
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "GRANTED",
                                    onClick = { selectedFilter = "GRANTED" },
                                    label = { Text("অনুমোদিত ($grantedCount)") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GreenNeon.copy(alpha = 0.2f),
                                        selectedLabelColor = GreenNeon
                                    )
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "SPECIAL",
                                    onClick = { selectedFilter = "SPECIAL" },
                                    label = { Text("স্পেশাল সেটিংস") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = BlueHolo.copy(alpha = 0.2f),
                                        selectedLabelColor = BlueHolo
                                    )
                                )
                            }
                        }
                    }

                    // Permission Cards List
                    items(filteredSpecs, key = { it.id }) { spec ->
                        val isGranted = permissionStatuses[spec.id] ?: false
                        PermissionCard(
                            spec = spec,
                            isGranted = isGranted,
                            onActionClick = {
                                PermissionOrchestrator.request(spec)
                            }
                        )
                    }
                }

                1 -> {
                    if (auditTrail.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = CardBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "এখনও কোনো সম্মতি বা অ্যাকশন অনুমোদনের রেকর্ড নেই।",
                                    color = TextDim,
                                    fontSize = 12.5.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(auditTrail) { entry ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = CardBg,
                                border = BorderStroke(1.dp, BorderBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${entry.toolName} → ${entry.targetLabel}",
                                            color = CyanNeon,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp
                                        )
                                        Text(
                                            text = entry.approvalStatus?.name ?: entry.state.name,
                                            color = AmberGlow,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(text = entry.detailsBn, color = TextWhite, fontSize = 11.5.sp)
                                    Text(
                                        text = "উৎস: ${entry.source} • ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(entry.timestamp))}",
                                        color = TextDim,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }

                2 -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AmberGlow.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "নিরাপত্তা নীতি: কল করা, SMS পাঠানো, WhatsApp মেসেজ, ডিলিট বা আর্থিক কাজে কখনোই অটো-অনুমোদন চালু করা যাবে না — প্রতিবার একবার ব্যবহারকারীর সম্মতি লাগবে। নিচে শুধু নিরাপদ পঠনযোগ্য/লো-রিস্ক টুলসের রুল আছে।",
                                color = TextWhite,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    val safeRuleCandidates = listOf(
                        "getBatteryStatus" to "ব্যাটারি ও চার্জ অবস্থা দেখা",
                        "getSystemDiagnostics" to "সিস্টেম ডায়াগনস্টিকস দেখা",
                        "getLiveWeatherForecast" to "আবহাওয়ার পূর্বাভাস দেখা",
                        "searchContacts" to "শুধু কন্টাক্ট নম্বর খোঁজা (কল নয়)"
                    )
                    items(safeRuleCandidates) { (toolId, desc) ->
                        val isEnabled = trustedRules.any { it.toolName == toolId && it.isValid() }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = CardBg,
                            border = BorderStroke(1.dp, BorderBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(desc, color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("টুল: $toolId (২৪ ঘণ্টার স্কোপড রুল)", color = TextDim, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        ActionApprovalRegistry.setTrustedLowRiskRule(toolId, desc, checked)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }

        // Active Rationale Sheet
        currentRequest?.let { event ->
            PermissionRationaleSheet(
                requestEvent = event,
                onDismiss = {
                    PermissionOrchestrator.dismiss()
                    permissionStatuses = PermissionOrchestrator.recheckAll(context)
                }
            )
        }
    }
}
