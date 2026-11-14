package com.example.ui.components

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.JarvisProfileEntity
import com.example.viewmodel.AssistantViewModel

private val ProfileSpaceDark = Color(0xFF040814)
private val ProfileSurface = Color(0xFF0A1226)
private val ProfileCardBg = Color(0xFF0F1A36)
private val ProfileCyan = Color(0xFF00F5D4)
private val ProfileBlue = Color(0xFF00BBF9)
private val ProfileEmerald = Color(0xFF10B981)
private val ProfilePink = Color(0xFFF15BB5)
private val ProfileTextWhite = Color(0xFFF8FAFC)
private val ProfileTextDim = Color(0xFF94A3B8)

/**
 * ProfileManagementComponent — Personal AI Owner Profile & Local Security
 */
@Composable
fun ProfileManagementComponent(
    viewModel: AssistantViewModel,
    onSaveSuccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentProfile by viewModel.profile.collectAsState()
    val migrationReport by viewModel.migrationReport.collectAsState()
    val hasLockPin by viewModel.hasLockPin.collectAsState()
    val savedCreds = remember { viewModel.getSavedCredentials() }

    var systemName by remember(currentProfile) {
        mutableStateOf(
            currentProfile?.systemName
                ?.takeIf { !it.contains("PHARMACY", true) && !it.contains("JANANI", true) }
                ?: "NAHID AI"
        )
    }
    var assistantVoice by remember(currentProfile) {
        mutableStateOf(currentProfile?.assistantVoice?.ifBlank { "Bengali-Natural" } ?: "Bengali-Natural")
    }
    var location by remember(currentProfile) {
        mutableStateOf(currentProfile?.location ?: "")
    }
    var ownerName by remember(currentProfile) {
        mutableStateOf(currentProfile?.ownerName?.ifBlank { "Masum Billah Nahid" } ?: savedCreds.first.ifBlank { "Masum Billah Nahid" })
    }
    var mobileNumber by remember(currentProfile) {
        mutableStateOf(currentProfile?.mobileNumber ?: "")
    }
    var loginUserName by remember(currentProfile) {
        mutableStateOf(currentProfile?.loginUserName?.ifBlank { "Masum Billah Nahid" } ?: savedCreds.first.ifBlank { "Masum Billah Nahid" })
    }
    var optionalLockPin by remember(hasLockPin) { mutableStateOf("") }
    var keepExistingPin by remember(hasLockPin) { mutableStateOf(hasLockPin) }
    var passwordVisible by remember { mutableStateOf(false) }
    var photoUriString by remember(currentProfile) {
        mutableStateOf(currentProfile?.photoUri)
    }
    var showResetConfirm by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            photoUriString = uri.toString()
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ProfileCyan,
        unfocusedBorderColor = Color(0xFF334155),
        focusedLabelColor = ProfileCyan,
        unfocusedLabelColor = ProfileTextDim,
        focusedTextColor = ProfileTextWhite,
        unfocusedTextColor = ProfileTextWhite,
        focusedContainerColor = ProfileSurface,
        unfocusedContainerColor = ProfileSurface
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("profile_management_component"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Owner Avatar + Photo Picker
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .clip(CircleShape)
                    .background(ProfileCardBg)
                    .border(2.5.dp, ProfileCyan, CircleShape)
                    .clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    .testTag("profile_photo_avatar"),
                contentAlignment = Alignment.Center
            ) {
                if (!photoUriString.isNullOrBlank()) {
                    AsyncImage(
                        model = photoUriString,
                        contentDescription = "মালিকের ছবি",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_jarvis_logo),
                        contentDescription = "NAHID AI লোগো",
                        modifier = Modifier.size(72.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ProfileCyan),
                border = BorderStroke(1.dp, ProfileCyan.copy(alpha = 0.6f)),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("upload_photo_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "প্রোফাইল ছবি পরিবর্তন করুন",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 2. Assistant Identity & Voice Mode
        OutlinedTextField(
            value = systemName,
            onValueChange = { systemName = it },
            label = { Text("অ্যাসিস্ট্যান্টের নাম (System Name)") },
            placeholder = { Text("যেমন: NAHID AI") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = ProfileCyan)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_system_name"),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = ProfileCardBg,
            border = BorderStroke(1.dp, ProfileBlue.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = ProfileBlue)
                    Column {
                        Text("প্রধান ভয়েস ও ভাষা", fontSize = 11.5.sp, color = ProfileTextDim)
                        Text(
                            text = "মায়া (Maya) • $assistantVoice (বাংলা / Banglish / EN)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ProfileTextWhite
                        )
                    }
                }
            }
        }

        // 3. Owner Details
        OutlinedTextField(
            value = ownerName,
            onValueChange = { ownerName = it },
            label = { Text("আপনার নাম (Owner Name)") },
            placeholder = { Text("যেমন: Masum Billah Nahid") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Person, contentDescription = null, tint = ProfileCyan)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_owner_name"),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors
        )

        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("শহর / অবস্থান (Location)") },
            placeholder = { Text("যেমন: ঢাকা, বাংলাদেশ") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = ProfileCyan)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_location"),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors
        )

        OutlinedTextField(
            value = mobileNumber,
            onValueChange = { mobileNumber = it },
            label = { Text("মোবাইল নম্বর (Optional)") },
            placeholder = { Text("যেমন: 017xxxxxxxx") },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Phone, contentDescription = null, tint = ProfileCyan)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_mobile_number"),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors
        )

        // 4. Optional Local App Lock Card (Zero hardcoded backdoor PINs)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_credentials_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ProfileCardBg),
            border = BorderStroke(1.dp, ProfileBlue.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = ProfileCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "পার্সোনাল অ্যাসিস্ট্যান্ট লক (ঐচ্ছিক)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ProfileCyan
                    )
                }

                OutlinedTextField(
                    value = loginUserName,
                    onValueChange = { loginUserName = it },
                    label = { Text("ডাকনাম / ইউজারনেম") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null, tint = ProfileBlue)
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_login_username"),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )

                OutlinedTextField(
                    value = optionalLockPin,
                    onValueChange = {
                        optionalLockPin = it
                        if (it.isNotBlank()) keepExistingPin = false
                    },
                    label = {
                        Text(
                            if (keepExistingPin && hasLockPin) {
                                "নতুন পিন লিখুন (আগের পিন এনক্রিপ্টেড ও সক্রিয়)"
                            } else {
                                "কাস্টম লোকাল পিন (খালি রাখলে আনলক থাকবে)"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = ProfileBlue)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "পিন লুকান" else "পিন দেখুন",
                                tint = ProfileTextDim
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_login_password"),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors
                )

                if (hasLockPin) {
                    TextButton(
                        onClick = {
                            optionalLockPin = ""
                            keepExistingPin = false
                            viewModel.clearSavedCredentials()
                            Toast.makeText(context, "লোকাল পিন লক বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("clear_lock_pin_button")
                    ) {
                        Text(
                            text = "বর্তমান লোকাল পিন মুছে ফেলুন",
                            fontSize = 12.sp,
                            color = ProfilePink
                        )
                    }
                }
            }
        }

        // 5. Device & Database Telemetry Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("device_security_telemetry_section"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ProfileCardBg),
            border = BorderStroke(1.2.dp, ProfileEmerald.copy(alpha = 0.55f))
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
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "ডিভাইস ও ডেটাবেস স্ট্যাটাস",
                            tint = ProfileEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "ডিভাইস ও কোর ডেটাবেস স্ট্যাটাস",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ProfileTextWhite
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ProfileEmerald.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, ProfileEmerald.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "LOCAL PRIVATE",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = ProfileEmerald
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ডিভাইস মডেল:", fontSize = 12.sp, color = ProfileTextDim)
                    Text(
                        text = viewModel.currentDeviceModel.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ProfileTextWhite
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("লোকাল ডিভাইস আইডি:", fontSize = 12.sp, color = ProfileTextDim)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ProfileSpaceDark,
                        border = BorderStroke(1.dp, Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = viewModel.persistentDeviceId.ifBlank { viewModel.currentUserId },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ProfileCyan
                        )
                    }
                }

                Text(
                    text = "ডেটাবেস: nahid_personal_ai.db (v19 • অন-ডিভাইস)\nমাইগ্রেশন ও মেমোরি: ${
                        migrationReport?.let {
                            "মেমোরি ${it.memoryCount}, টাস্ক ${it.taskCount}, নোট ${it.noteCount}, চ্যাট ${it.chatHistoryCount}"
                        } ?: "সব পার্সোনাল ডেটা সুরক্ষিতভাবে সংরক্ষিত"
                    }",
                    fontSize = 11.5.sp,
                    color = ProfileTextDim,
                    lineHeight = 17.sp
                )
            }
        }

        // 6. Save & Reset Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val trimmedName = systemName.trim().ifBlank { "NAHID AI" }
                    val trimmedOwner = ownerName.trim().ifBlank { "Masum Billah Nahid" }
                    val trimmedLoginUser = loginUserName.trim().ifBlank { trimmedOwner }
                    val trimmedPin = if (optionalLockPin.isBlank() && keepExistingPin && hasLockPin) {
                        "__LOCKED_IN_VAULT__"
                    } else {
                        optionalLockPin.trim()
                    }

                    val updated = JarvisProfileEntity(
                        id = 1,
                        systemName = trimmedName,
                        assistantVoice = assistantVoice,
                        mobileNumber = mobileNumber.trim(),
                        location = location.trim(),
                        photoUri = photoUriString,
                        ownerName = trimmedOwner,
                        loginUserName = trimmedLoginUser,
                        loginPassword = trimmedPin,
                        securityLevel = currentProfile?.securityLevel ?: "HIGH",
                        autoAutomationAllowed = currentProfile?.autoAutomationAllowed ?: true
                    )
                    viewModel.saveProfile(updated) {
                        Toast.makeText(context, "NAHID AI প্রোফাইল সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                        onSaveSuccess()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("profile_save_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ProfileCyan, contentColor = ProfileSpaceDark)
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "সংরক্ষণ করুন",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = { showResetConfirm = true },
                modifier = Modifier
                    .weight(0.85f)
                    .height(52.dp)
                    .testTag("profile_delete_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ProfilePink),
                border = BorderStroke(1.2.dp, ProfilePink.copy(alpha = 0.7f))
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "রিসেট",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showResetConfirm) {
        DeleteConfirmDialog(
            title = "প্রোফাইল ডিফল্ট রিসেট করবেন?",
            message = "আপনার প্রোফাইল তথ্য মুছে NAHID AI-এর ডিফল্ট প্রোফাইলে ফিরে যেতে চান?",
            onConfirm = {
                viewModel.deleteProfile {
                    systemName = "NAHID AI"
                    assistantVoice = "Bengali-Natural"
                    location = ""
                    ownerName = "Masum Billah Nahid"
                    mobileNumber = ""
                    loginUserName = "Masum Billah Nahid"
                    optionalLockPin = ""
                    photoUriString = null
                    showResetConfirm = false
                    Toast.makeText(context, "প্রোফাইল ডিফল্টে রিসেট করা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showResetConfirm = false }
        )
    }
}
