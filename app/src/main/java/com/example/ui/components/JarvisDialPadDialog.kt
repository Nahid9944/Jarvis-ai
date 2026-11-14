package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.permissions.JarvisPermissionManager
import com.example.service.JarvisBackgroundService
import java.net.URLEncoder

private val SpaceDark = Color(0xFF040814)
private val SpaceSurface = Color(0xFF0A1226)
private val CardBg = Color(0xFF0F1A36)
private val CyanNeon = Color(0xFF00F5D4)
private val BlueHolo = Color(0xFF00BBF9)
private val EmeraldNeon = Color(0xFF10B981)
private val AmberGlow = Color(0xFFFEE440)
private val PinkNeon = Color(0xFFF15BB5)
private val TextWhite = Color(0xFFF8FAFC)
private val TextDim = Color(0xFF94A3B8)

@Composable
fun JarvisDialPadDialog(
    initialNumber: String = "",
    frequentContacts: List<com.example.data.local.JarvisFrequentContactEntity> = emptyList(),
    onDismiss: () -> Unit,
    onActivateBackgroundAi: () -> Unit
) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf(initialNumber) }
    var showWhatsAppComposer by remember { mutableStateOf(false) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        JarvisPermissionManager.refreshSnapshotAndDetectRevocations(context)
        if (phoneNumber.isNotBlank()) {
            performSimCall(context, phoneNumber, isGranted)
            onActivateBackgroundAi()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
                .clip(RoundedCornerShape(24.dp)),
            color = SpaceDark,
            border = BorderStroke(1.5.dp, CyanNeon.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Close
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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(CyanNeon.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Dialpad, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(
                                text = "স্মার্ট ডায়াল প্যাড ও সিম কল",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "সরাসরি সিম ফোন কল ও হোয়াটসঅ্যাপ এআই সংযোগ",
                                fontSize = 11.sp,
                                color = TextDim
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextDim)
                    }
                }

                // Phone Number Display Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SpaceSurface,
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (phoneNumber.isEmpty()) "নম্বর লিখুন..." else phoneNumber,
                            fontSize = 22.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (phoneNumber.isEmpty()) TextDim.copy(alpha = 0.6f) else CyanNeon,
                            modifier = Modifier.weight(1f)
                        )
                        if (phoneNumber.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    if (phoneNumber.isNotEmpty()) {
                                        phoneNumber = phoneNumber.dropLast(1)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Backspace, contentDescription = "Backspace", tint = AmberGlow)
                            }
                        }
                    }
                }

                // Real Quick Contacts & Official Emergency Service (Zero Pharmacy Presets)
                val quickNumbers = remember(frequentContacts) {
                    buildList {
                        frequentContacts
                            .filter { it.phoneNumber.isNotBlank() }
                            .take(6)
                            .forEach { contact ->
                                add(contact.name.ifBlank { contact.alias } to contact.phoneNumber)
                            }
                        add("জাতীয় জরুরি সেবা" to "999")
                    }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(quickNumbers) { (label, num) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CardBg,
                            border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable { phoneNumber = num }
                        ) {
                            Text(
                                text = "$label ($num)",
                                fontSize = 11.sp,
                                color = BlueHolo,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // 3x4 Dialpad Keypad Grid
                val keyRows = listOf(
                    listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                    listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                    listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                    listOf("*" to "", "0" to "+", "#" to "")
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (row in keyRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for ((digit, sub) in row) {
                                DialKeyButton(
                                    digit = digit,
                                    subtitle = sub,
                                    onClick = {
                                        phoneNumber += digit
                                    },
                                    onLongClick = {
                                        if (digit == "0") {
                                            phoneNumber += "+"
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Action Call & WhatsApp Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // WhatsApp Button
                    Button(
                        onClick = {
                            if (phoneNumber.isBlank()) {
                                Toast.makeText(context, "অনুগ্রহ করে একটি মোবাইল নম্বর টাইপ করুন", Toast.LENGTH_SHORT).show()
                            } else {
                                showWhatsAppComposer = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldNeon),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = SpaceDark, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("হোয়াটসঅ্যাপ", fontWeight = FontWeight.Bold, color = SpaceDark, fontSize = 13.sp)
                    }

                    // SIM Direct Call Button
                    Button(
                        onClick = {
                            if (phoneNumber.isBlank()) {
                                Toast.makeText(context, "অনুগ্রহ করে একটি নম্বর টাইপ করুন", Toast.LENGTH_SHORT).show()
                            } else {
                                val hasCallPerm = JarvisPermissionManager.hasCallPhonePermission(context)
                                if (hasCallPerm) {
                                    performSimCall(context, phoneNumber, true)
                                    onActivateBackgroundAi()
                                    onDismiss()
                                } else {
                                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = SpaceDark, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("সিম কল করুন", fontWeight = FontWeight.Bold, color = SpaceDark, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showWhatsAppComposer) {
        WhatsAppComposerDialog(
            phoneNumber = phoneNumber,
            onDismiss = { showWhatsAppComposer = false },
            onSend = { msg ->
                showWhatsAppComposer = false
                onActivateBackgroundAi()
                openWhatsAppDirect(context, phoneNumber, msg)
                onDismiss()
            }
        )
    }
}

@Composable
private fun DialKeyButton(
    digit: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = CardBg,
        border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextWhite
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextDim
                )
            }
        }
    }
}

@Composable
fun WhatsAppComposerDialog(
    phoneNumber: String = "",
    onDismiss: () -> Unit,
    onSend: (String) -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val quickTemplates = remember {
        listOf(
            "বলুন স্যার আমি কি লিখব...",
            "শুভকামনা রইল, J.A.R.V.I.S. সিস্টেমের পক্ষ থেকে বার্তা।",
            "জরুরি: আপনার সঙ্গে যোগাযোগের বিশেষ প্রয়োজন।",
            "স্যার, প্রোটোকল অনুযায়ী বার্তা প্রস্তুত রয়েছে।"
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SpaceDark,
            border = BorderStroke(1.5.dp, EmeraldNeon),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        Icon(Icons.Default.Chat, contentDescription = null, tint = EmeraldNeon, modifier = Modifier.size(24.dp))
                        Column {
                            Text(
                                text = "হোয়াটসঅ্যাপ বার্তা প্রেরণ",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                            Text(
                                text = "প্রাপক: $phoneNumber",
                                fontSize = 11.5.sp,
                                color = EmeraldNeon
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = TextDim)
                    }
                }

                // AI Prompt Banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SpaceSurface,
                    border = BorderStroke(1.dp, BlueHolo.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = BlueHolo, modifier = Modifier.size(20.dp))
                        Text(
                            text = "\"বলুন স্যার, আমি এসএমএসে কি লিখব?\" — আপনি যা বলবেন এআই তা লিখে মেসেজ পাঠাবে।",
                            fontSize = 11.sp,
                            color = TextWhite,
                            lineHeight = 16.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text("এসএমএস বার্তা লিখুন বা মুখে বলুন...", fontSize = 12.sp, color = TextDim) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldNeon,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Quick Templates
                Text("কুইক টেমপ্লেট:", fontSize = 11.sp, color = TextDim)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(quickTemplates) { tpl ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CardBg,
                            border = BorderStroke(1.dp, EmeraldNeon.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable {
                                if (tpl.startsWith("বলুন")) {
                                    messageText = "আসসালামু আলাইকুম, আমি মাসুম বিল্লাহ নাহিদ বলছি।"
                                } else {
                                    messageText = tpl
                                }
                            }
                        ) {
                            Text(
                                text = tpl.take(24) + "...",
                                fontSize = 10.5.sp,
                                color = EmeraldNeon,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = { onSend(messageText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldNeon),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = SpaceDark, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("হোয়াটসঅ্যাপে পাঠান", fontWeight = FontWeight.Bold, color = SpaceDark, fontSize = 13.sp)
                }
            }
        }
    }
}

fun performSimCall(context: Context, phoneNumber: String, isPermissionGranted: Boolean) {
    try {
        JarvisBackgroundService.start(context)
        val clean = phoneNumber.filter { it.isDigit() || it == '+' }
        val intent = if (isPermissionGranted) {
            Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$clean")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$clean")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
        context.startActivity(intent)
        Toast.makeText(context, "$clean নম্বরে সিম কল সংযোগ দেওয়া হচ্ছে...", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "কল সংযোগ ব্যর্থ: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun openWhatsAppDirect(context: Context, phoneNumber: String, messageText: String) {
    try {
        JarvisBackgroundService.start(context)
        var cleanPhone = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanPhone.startsWith("+880")) {
            cleanPhone = cleanPhone.removePrefix("+")
        } else if (cleanPhone.startsWith("01")) {
            cleanPhone = "88" + cleanPhone
        }
        val encodedMsg = URLEncoder.encode(messageText, "UTF-8")
        val url = if (cleanPhone.isNotBlank()) {
            "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg"
        } else {
            "https://api.whatsapp.com/send?text=$encodedMsg"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Toast.makeText(context, "হোয়াটসঅ্যাপ চালু হচ্ছে... এআই ব্যাকগ্রাউন্ড মুড সক্রিয় রয়েছে", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "হোয়াটসঅ্যাপ ওপেন করা যায়নি: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
