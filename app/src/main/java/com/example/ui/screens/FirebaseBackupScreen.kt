package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.AssistantViewModel

private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val AmberGlow = Color(0xFFFFB703)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirebaseBackupScreen(
    viewModel: AssistantViewModel,
    onBack: () -> Unit,
    onLock: (() -> Unit)? = null
) {
    val backupStatus by viewModel.backupStatus.collectAsState()
    val isLoading by viewModel.isBackupLoading.collectAsState()
    val cloudInfo by viewModel.cloudBackupInfo.collectAsState()
    val isCheckingCloud by viewModel.isCheckingCloud.collectAsState()

    val allMemories by viewModel.allMemories.collectAsState()
    val allTasks by viewModel.allAiTasks.collectAsState()
    val allNotes by viewModel.allNotes.collectAsState()
    val allChats by viewModel.allJarvisChatHistory.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkCloudBackupStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "NAHID AI লোকাল ব্যাকআপ ভল্ট",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanNeon
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "ব্যাক",
                            tint = CyanNeon
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.checkCloudBackupStatus() }) {
                        if (isCheckingCloud) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = CyanNeon,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "রিফ্রেশ",
                                tint = CyanNeon
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpaceDark)
            )
        },
        containerColor = SpaceDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SpaceSurface),
                border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(CyanNeon.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = CyanNeon,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "NAHID AI PERSONAL VAULT",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "আপনার ব্যক্তিগত AI মেমোরি, টাস্ক, নোটস ও চ্যাট হিস্ট্রি ডিভাইসের নিরাপদ ভল্টে ব্যাকআপ ও রিস্টোর করুন।",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Explicit Firebase Disconnected Notice
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = AmberGlow.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = AmberGlow, modifier = Modifier.size(20.dp))
                    Text(
                        text = "পুরোনো ফার্মেসি Firebase RTDB সংযোগ স্থায়ীভাবে বিচ্ছিন্ন করা হয়েছে। আপনার সব ব্যাকআপ এখন সম্পূর্ণ অফলাইনে ও ব্যক্তিগতভাবে আপনার ডিভাইসে সংরক্ষিত থাকে।",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            AnimatedVisibility(
                visible = backupStatus != null || isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SpaceSurface),
                    border = BorderStroke(1.dp, BlueHolo)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = CyanNeon,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = backupStatus ?: "কাজ সম্পন্ন হচ্ছে...",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }

            Text(
                text = "📊 মেমোরি ও ডাটা সংরক্ষণ স্থিতি",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = CyanNeon,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SpaceSurface),
                    border = BorderStroke(1.dp, CyanNeon.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("সক্রিয় ডেটাবেস", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
                        }
                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                        Text("• AI মেমোরি: ${allMemories.size}টি", fontSize = 12.sp, color = Color.White)
                        Text("• শিডিউলড টাস্ক: ${allTasks.size}টি", fontSize = 12.sp, color = Color.White)
                        Text("• সংরক্ষিত নোট: ${allNotes.size}টি", fontSize = 12.sp, color = Color.White)
                        Text("• চ্যাট হিস্ট্রি: ${allChats.size}টি", fontSize = 12.sp, color = Color.White)
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SpaceSurface),
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderZip, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ভল্ট ব্যাকআপ", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = BlueHolo)
                        }
                        HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                        if (cloudInfo != null && cloudInfo!!.hasBackup) {
                            Text("• AI মেমোরি: ${cloudInfo!!.memoryCount}টি", fontSize = 12.sp, color = Color.White)
                            Text("• শিডিউলড টাস্ক: ${cloudInfo!!.taskCount}টি", fontSize = 12.sp, color = Color.White)
                            Text("• সংরক্ষিত নোট: ${cloudInfo!!.noteCount}টি", fontSize = 12.sp, color = Color.White)
                            Text("• চ্যাট হিস্ট্রি: ${cloudInfo!!.chatCount}টি", fontSize = 12.sp, color = Color.White)
                        } else {
                            Text(
                                text = "ভল্টে কোনো ব্যাকআপ নেই",
                                fontSize = 11.5.sp,
                                color = Color(0xFFFEE440)
                            )
                        }
                    }
                }
            }

            if (cloudInfo != null && cloudInfo!!.hasBackup) {
                Surface(
                    color = SpaceSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "সর্বশেষ ভল্ট ব্যাকআপ: ${cloudInfo!!.lastUpdatedFormatted}",
                            fontSize = 11.5.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.uploadToFirebase() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("firebase_upload_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "লোকাল ভল্টে নিরাপদ ব্যাকআপ তৈরি করুন",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.Black
                )
            }

            Button(
                onClick = { viewModel.downloadFromFirebase() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("firebase_download_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlueHolo)
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "ভল্ট থেকে মেমোরি ও ডাটা রিস্টোর করুন",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.Black
                )
            }
        }
    }
}
