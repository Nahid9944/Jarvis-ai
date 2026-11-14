package com.example.ui.screens

import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.LiveWeatherData
import com.example.ai.NewsAndRegulatoryService
import com.example.ai.NewsItem
import com.example.ai.WikipediaResult
import com.example.automation.PlaybookStore
import com.example.service.JarvisFloatingOverlayService
import com.example.ui.components.JarvisDialPadDialog
import com.example.ui.components.JarvisOsSettingsDialog
import com.example.ui.components.WhatsAppComposerDialog
import com.example.util.JarvisDeviceAutomationHelper
import com.example.viewmodel.AssistantViewModel
import kotlinx.coroutines.launch

private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val AmberGlow = Color(0xFFFEE440)
private val EmeraldNeon = Color(0xFF10B981)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)

@Composable
fun JarvisToolsTab(
    viewModel: AssistantViewModel,
    onAskJarvis: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val aiMemories by viewModel.aiMemories.collectAsState()
    val aiTasks by viewModel.aiTasks.collectAsState()
    val allNotes by viewModel.allNotes.collectAsState()
    val learnedSkills by viewModel.allLearnedSkills.collectAsState()
    val pendingAnnouncements by viewModel.pendingAnnouncements.collectAsState()
    val latestSmsStatus by viewModel.latestSmsStatus.collectAsState()

    // Teach Mode / GitHub / YouTube Learning Dialogs
    var showTeachSkillDialog by remember { mutableStateOf(false) }
    var showGitHubImportDialog by remember { mutableStateOf(false) }
    var showYouTubeImportDialog by remember { mutableStateOf(false) }
    var learningStatusMessage by remember { mutableStateOf<String?>(null) }

    // Weather Tool State
    var weatherLocation by remember { mutableStateOf("ঢাকা") }
    var weatherResult by remember { mutableStateOf<LiveWeatherData?>(null) }
    var isFetchingWeather by remember { mutableStateOf(false) }

    // Location State
    var gpsAddress by remember { mutableStateOf<String?>(null) }

    // Dial Pad & WhatsApp States
    var showDialPadModal by remember { mutableStateOf(false) }
    var showWhatsAppModal by remember { mutableStateOf(false) }

    // Wikipedia Search State
    var wikiQuery by remember { mutableStateOf("") }
    var wikiResult by remember { mutableStateOf<WikipediaResult?>(null) }
    var isSearchingWiki by remember { mutableStateOf(false) }

    // Live News State
    var liveNewsList by remember { mutableStateOf<List<NewsItem>?>(null) }
    var isFetchingNews by remember { mutableStateOf(false) }

    // OS Control State
    var showOsSettingsModal by remember { mutableStateOf(false) }
    var screenSummaryResult by remember { mutableStateOf<String?>(null) }
    var quickCommandInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBg,
                border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.BuildCircle, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(28.dp))
                    Column {
                        Text(
                            text = "J.A.R.V.I.S. Multi-System Tools Matrix",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = "ডিভাইস অটোমেশন, সিস্টেম ডায়াগনস্টিকস, লাইভ ওয়েদার, জিপিএস ও নলেজ কোর।",
                            fontSize = 11.5.sp,
                            color = TextDim
                        )
                    }
                }
            }
        }

        // TOOL 0: JARVIS ANDROID OS AUTOMATION & PERMISSIONS
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CyanNeon.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.SmartToy, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = "অ্যান্ড্রয়েড সিস্টেম অটোমেশন ও কন্ট্রোল",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "স্ক্রিন দেখা, বাটন ক্লিক, অ্যাপ ওপেন ও নোটিফিকেশন নিয়ন্ত্রণ",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val summary = JarvisDeviceAutomationHelper.inspectCurrentScreen()
                                screenSummaryResult = summary
                                Toast.makeText(context, "স্ক্রিন পরিদর্শন সম্পন্ন", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, CyanNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("স্ক্রিন দেখুন", color = CyanNeon, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                JarvisFloatingOverlayService.start(context)
                                Toast.makeText(context, "ভাসমান ওভারলে চালু হয়েছে", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlueHolo.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, BlueHolo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ভাসমান বাবল", color = BlueHolo, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (screenSummaryResult != null) {
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = screenSummaryResult ?: "",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // TOOL 1: ARC REACTOR SYSTEM DIAGNOSTICS
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, AmberGlow.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = AmberGlow, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "সিস্টেম ডায়াগনস্টিকস ও আর্করিয়েক্টর টেলিমেট্রি",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "কোর স্ট্যাটাস, হার্ডওয়্যার স্ট্যাট ও অ্যাক্টিভ ডাটা রেকর্ডস",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
                    val deviceModel = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("আর্ক কোর", fontSize = 10.sp, color = TextDim)
                                Text("NOMINAL", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
                            }
                        }
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("ব্যাটারি লেভেল", fontSize = 10.sp, color = TextDim)
                                Text("$batteryLevel%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AmberGlow)
                            }
                        }
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("AI মেমোরি", fontSize = 10.sp, color = TextDim)
                                Text("${aiMemories.size} রেকর্ড", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EmeraldNeon)
                            }
                        }
                    }

                    Text(
                        text = "ডিভাইস: $deviceModel | টাস্ক: ${aiTasks.size} | নোটস: ${allNotes.size}",
                        fontSize = 11.sp,
                        color = TextDim,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // TOOL 2: LIVE WEATHER & FORECAST
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, BlueHolo.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "লাইভ আবহাওয়া ও পূর্বাভাস",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "তাপমাত্রা, আর্দ্রতা, বাতাসের গতি ও বৃষ্টির পূর্বাভাস",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = weatherLocation,
                            onValueChange = { weatherLocation = it },
                            placeholder = { Text("লোকেশন (যেমন: ঢাকা, মোড়লগঞ্জ)", color = TextDim, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedBorderColor = BlueHolo,
                                unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                        Button(
                            onClick = {
                                if (weatherLocation.isNotBlank()) {
                                    isFetchingWeather = true
                                    coroutineScope.launch {
                                        weatherResult = viewModel.quickFetchWeather(weatherLocation)
                                        isFetchingWeather = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BlueHolo),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("রিপোর্ট", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (weatherResult != null) {
                        val w = weatherResult!!
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${w.locationName}: ${w.temperatureC}°C (${w.conditionBengali})", color = BlueHolo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("আর্দ্রতা: ${w.humidityPercent}% | বাতাস: ${w.windSpeedKmh} কিমি/ঘণ্টা | বৃষ্টি: ${w.precipitationMm} মিমি", color = TextWhite, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(w.rainSummaryBengali, color = CyanNeon, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
        }

        // TOOL 3: LIVE GPS LOCATION
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, EmeraldNeon.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "রিয়েল-টাইম জিপিএস অবস্থান",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "ডিভাইসের নির্ভুল অবস্থান, ঠিকানা ও ভৌগোলিক স্থানাঙ্ক",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val (ok, loc) = com.example.util.JarvisLiveLocationTracker.fetchCurrentLocation(context)
                                if (ok && loc != null) {
                                    gpsAddress = "${loc.fullAddress} (অক্ষাংশ: ${String.format(java.util.Locale.US, "%.5f", loc.latitude)}, দ্রাঘিমাংশ: ${String.format(java.util.Locale.US, "%.5f", loc.longitude)})"
                                } else {
                                    gpsAddress = "লোকেশন সংযোগ সক্রিয় করা সম্ভব হয়নি।"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldNeon),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("বর্তমান অবস্থান সনাক্ত করুন", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    if (gpsAddress != null) {
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = gpsAddress ?: "",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // TOOL 4: SMART SIM CALL & WHATSAPP
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "টেলিফোনি ও হোয়াটসঅ্যাপ হ্যান্ডস-ফ্রি",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "সরাসরি সিম কল ডায়াল ও হোয়াটসঅ্যাপ বার্তা প্রেরণ",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showDialPadModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Dialpad, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("সিম ডায়াল প্যাড", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showWhatsAppModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("হোয়াটসঅ্যাপ", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // TOOL 5: AUTOMATION PLAYBOOKS
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, BlueHolo.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "অটোমেশন প্লেবুক হাব",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "যাচাইকৃত স্ক্রিপ্ট রান করুন (WhatsApp, YouTube, Facebook)",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    val playbooks = remember { PlaybookStore.getAll() }
                    Text(
                        text = "মোট নিবন্ধিত প্লেবুক: ${playbooks.size}টি",
                        fontSize = 12.sp,
                        color = CyanNeon,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onAskJarvis("ইউটিউব ওপেন করে বাংলা গান প্লে করো") },
                            colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface),
                            border = BorderStroke(1.dp, BlueHolo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("YouTube প্লে", color = BlueHolo, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { onAskJarvis("হোয়াটসঅ্যাপে সালাম মেসেজ পাঠাও") },
                            colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface),
                            border = BorderStroke(1.dp, EmeraldNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("WhatsApp বার্তা", color = EmeraldNeon, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { onAskJarvis("ফেসবুক ওপেন করো") },
                            colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface),
                            border = BorderStroke(1.dp, AmberGlow),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Facebook পোস্ট", color = AmberGlow, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // TOOL 6: WIKIPEDIA KNOWLEDGE ENCYCLOPEDIA
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, AmberGlow.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, tint = AmberGlow, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "উইকিপিডিয়া নলেজ এনসাইক্লোপিডিয়া",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "বিজ্ঞান, প্রযুক্তি, ইতিহাস ও সাধারণ জ্ঞান অনুসন্ধান",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = wikiQuery,
                            onValueChange = { wikiQuery = it },
                            placeholder = { Text("অনুসন্ধান বিষয় (যেমন: কোয়ান্টাম কম্পিউটার)", color = TextDim, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedBorderColor = AmberGlow,
                                unfocusedBorderColor = Color(0xFF334155)
                            )
                        )
                        Button(
                            onClick = {
                                if (wikiQuery.isNotBlank()) {
                                    isSearchingWiki = true
                                    coroutineScope.launch {
                                        wikiResult = NewsAndRegulatoryService.searchWikipedia(wikiQuery)
                                        isSearchingWiki = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberGlow),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("খুঁজুন", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (wikiResult != null) {
                        val w = wikiResult!!
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(w.title, color = AmberGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(w.extract, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // TOOL 7: SELF-LEARNING & REUSABLE SKILLS MANAGER
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.School, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "স্বয়ংক্রিয় স্কিল ও টিচ মোড (Skills Engine)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "মোট ${learnedSkills.size}টি শেখা স্কিল • GitHub, YouTube ও সহজ বাংলা ধাপে শেখান",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { showTeachSkillDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("tools_teach_skill_button")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("টিচ মোড", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { showGitHubImportDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface),
                            border = BorderStroke(1.dp, BlueHolo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("tools_github_import_button")
                        ) {
                            Text("GitHub", color = BlueHolo, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        Button(
                            onClick = { showYouTubeImportDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SpaceSurface),
                            border = BorderStroke(1.dp, AmberGlow),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("tools_youtube_import_button")
                        ) {
                            Text("YouTube", color = AmberGlow, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    if (!learningStatusMessage.isNullOrBlank()) {
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = learningStatusMessage ?: "",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (learnedSkills.isEmpty()) {
                        Text(
                            text = "এখনো কোনো কাস্টম স্কিল সেভ করা হয়নি। উপরে 'টিচ মোড', 'GitHub' অথবা 'YouTube' ব্যবহার করে শেখান।",
                            color = TextDim,
                            fontSize = 11.5.sp
                        )
                    } else {
                        learnedSkills.take(8).forEach { skill ->
                            val isEnabled = skill.status.equals("ACTIVE", ignoreCase = true)
                            val primaryTrigger = remember(skill.triggerPhrasesJson) {
                                try {
                                    org.json.JSONArray(skill.triggerPhrasesJson).optString(0).ifBlank { skill.name }
                                } catch (_: Exception) {
                                    skill.name
                                }
                            }
                            val hasPreviousVersion = skill.previousStepsJson.isNotBlank() &&
                                skill.previousStepsJson != "[]" &&
                                skill.previousVersion > 0

                            Surface(
                                color = SpaceSurface,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isEnabled) CyanNeon.copy(alpha = 0.35f) else Color(0xFF334155)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${skill.nameBengali} (v${skill.version})",
                                                color = TextWhite,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "ট্রিগার: $primaryTrigger • ${skill.sourceType}",
                                                color = CyanNeon,
                                                fontSize = 10.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isEnabled,
                                            onCheckedChange = { viewModel.toggleSkillEnabled(skill.skillId, it) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = SpaceDark,
                                                checkedTrackColor = CyanNeon
                                            )
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { onAskJarvis(primaryTrigger) },
                                            border = BorderStroke(1.dp, EmeraldNeon),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("চালান", color = EmeraldNeon, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        if (hasPreviousVersion) {
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.rollbackSkill(skill.skillId) { _, msg ->
                                                        learningStatusMessage = msg
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                border = BorderStroke(1.dp, AmberGlow),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("রোলব্যাক v${skill.previousVersion}", color = AmberGlow, fontSize = 10.5.sp)
                                            }
                                        }

                                        IconButton(
                                            onClick = { viewModel.deleteSkill(skill) },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Skill", tint = Color(0xFFF15BB5))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // TOOL 8: NOTIFICATION INTELLIGENCE QUEUE & SMS DELIVERY VERIFICATION
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg,
                border = BorderStroke(1.5.dp, EmeraldNeon.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(22.dp))
                            Column {
                                Text(
                                    text = "লাইভ ঘোষণা কিউ ও SMS ডেলিভারি স্ট্যাটাস",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                                Text(
                                    text = "অপেক্ষমাণ ঘোষণা: ${pendingAnnouncements.size}টি (OTP স্বয়ংক্রিয়ভাবে গোপন)",
                                    fontSize = 11.sp,
                                    color = TextDim
                                )
                            }
                        }
                        if (pendingAnnouncements.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearAnnouncementQueue() }) {
                                Text("পরিষ্কার", color = AmberGlow, fontSize = 11.5.sp)
                            }
                        }
                    }

                    latestSmsStatus?.let { sms ->
                        Surface(
                            color = SpaceSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "সর্বশেষ SMS অবস্থা: ${sms.stage.name} (${sms.recipient}) — ${sms.summaryBengali}",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (pendingAnnouncements.isNotEmpty()) {
                        pendingAnnouncements.take(3).forEach { ann ->
                            Surface(
                                color = SpaceSurface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "• [${ann.category}] ${ann.text}",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialPadModal) {
        JarvisDialPadDialog(
            onDismiss = { showDialPadModal = false },
            onActivateBackgroundAi = {
                showDialPadModal = false
                viewModel.toggleBackgroundMode(true)
            }
        )
    }

    if (showWhatsAppModal) {
        WhatsAppComposerDialog(
            phoneNumber = "",
            onDismiss = { showWhatsAppModal = false },
            onSend = { text ->
                showWhatsAppModal = false
                com.example.ui.components.openWhatsAppDirect(context, "", text)
            }
        )
    }

    if (showTeachSkillDialog) {
        var name by remember { mutableStateOf("") }
        var trigger by remember { mutableStateOf("") }
        var stepsText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTeachSkillDialog = false },
            containerColor = CardBg,
            title = { Text("নতুন স্কিল শেখান (Teach Mode)", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("স্কিলের নাম (যেমন: মায়ের হোয়াটসঅ্যাপ রিপোর্ট)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                    OutlinedTextField(
                        value = trigger,
                        onValueChange = { trigger = it },
                        label = { Text("ভয়েস কমান্ড (যেমন: আম্মুকে {message} পাঠাও)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { stepsText = it },
                        label = { Text("ধাপসমূহ ('->' বা নতুন লাইন দিয়ে লিখুন)") },
                        placeholder = { Text("WhatsApp খোলো -> {contact} সার্চ করো -> {message} টাইপ করো -> Send ক্লিক করো", fontSize = 11.sp) },
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && stepsText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    onClick = {
                        viewModel.teachNewSkill(
                            skillName = name,
                            triggerPhrase = trigger.ifBlank { name },
                            naturalLanguageSteps = stepsText
                        ) { ok, msg ->
                            learningStatusMessage = msg
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            if (ok) showTeachSkillDialog = false
                        }
                    }
                ) { Text("সংরক্ষণ", color = SpaceDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTeachSkillDialog = false }) {
                    Text("বাতিল", color = TextDim)
                }
            }
        )
    }

    if (showGitHubImportDialog) {
        var repoUrl by remember { mutableStateOf("") }
        var isImporting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showGitHubImportDialog = false },
            containerColor = CardBg,
            title = { Text("GitHub রেপো থেকে স্কিল শিখুন", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = repoUrl,
                    onValueChange = { repoUrl = it },
                    label = { Text("GitHub Repo URL (https://github.com/owner/repo)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                )
            },
            confirmButton = {
                Button(
                    enabled = repoUrl.isNotBlank() && !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueHolo),
                    onClick = {
                        isImporting = true
                        viewModel.importSkillFromGitHub(repoUrl.trim()) { res ->
                            isImporting = false
                            val status = res.optString("status", "UNKNOWN")
                            val summary = res.optString("summaryBengali", "গিটহাব রেপো যাচাই সম্পন্ন")
                            learningStatusMessage = "[GitHub: $status] $summary"
                            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                            showGitHubImportDialog = false
                        }
                    }
                ) { Text(if (isImporting) "যাচাই হচ্ছে..." else "ইমপোর্ট", color = SpaceDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showGitHubImportDialog = false }) { Text("বাতিল", color = TextDim) }
            }
        )
    }

    if (showYouTubeImportDialog) {
        var ytUrl by remember { mutableStateOf("") }
        var ytTranscript by remember { mutableStateOf("") }
        var isImporting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showYouTubeImportDialog = false },
            containerColor = CardBg,
            title = { Text("YouTube টিউটোরিয়াল থেকে শিখুন", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ytUrl,
                        onValueChange = { ytUrl = it },
                        label = { Text("YouTube Video URL") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                    OutlinedTextField(
                        value = ytTranscript,
                        onValueChange = { ytTranscript = it },
                        label = { Text("ঐচ্ছিক: ক্যাপশন না থাকলে ধাপ/ট্রান্সক্রিপ্ট পেস্ট করুন") },
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextWhite, unfocusedTextColor = TextWhite)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = ytUrl.isNotBlank() && !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = AmberGlow),
                    onClick = {
                        isImporting = true
                        viewModel.importSkillFromYouTube(ytUrl.trim(), ytTranscript.trim()) { res ->
                            isImporting = false
                            val status = res.optString("status", "UNKNOWN")
                            val summary = res.optString("summaryBengali", "ইউটিউব টিউটোরিয়াল বিশ্লেষণ সম্পন্ন")
                            learningStatusMessage = "[YouTube: $status] $summary"
                            Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                            showYouTubeImportDialog = false
                        }
                    }
                ) { Text(if (isImporting) "বিশ্লেষণ..." else "শিখুন", color = SpaceDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showYouTubeImportDialog = false }) { Text("বাতিল", color = TextDim) }
            }
        )
    }
}
