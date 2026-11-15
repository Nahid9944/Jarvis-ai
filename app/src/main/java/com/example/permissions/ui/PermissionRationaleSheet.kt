package com.example.permissions.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.permissions.PermissionKind
import com.example.permissions.PermissionOrchestrator
import com.example.permissions.PermissionRequestEvent

private val SheetBg = Color(0xFF070D1E)
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
fun PermissionRationaleSheet(
    requestEvent: PermissionRequestEvent,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val spec = requestEvent.spec

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.isNotEmpty() && results.values.all { it }
        if (allGranted) {
            PermissionOrchestrator.notifyResolved(true)
            com.example.permissions.JarvisPermissionManager.onResumeRecheckAndExecute(context)
        } else {
            PermissionOrchestrator.notifyResolved(false)
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        scrimColor = Color.Black.copy(alpha = 0.75f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(48.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(CyanNeon.copy(alpha = 0.6f))
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .testTag("permission_rationale_sheet")
        ) {
            // Header with Arc Reactor / Shield Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(CyanNeon.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )
                        .border(1.5.dp, CyanNeon, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Security Shield",
                        tint = CyanNeon,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "অনুমতি প্রয়োজন — JARVIS OS",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyanNeon,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "স্মার্ট অ্যাসিস্ট্যান্ট ক্ষমতা আনলক করুন",
                        fontSize = 12.sp,
                        color = TextDim
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Main Info Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBg,
                border = BorderStroke(1.dp, BorderBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = spec.titleBn,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AmberGlow.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = when (spec.kind) {
                                    PermissionKind.RUNTIME -> "সিস্টেম পারমিশন"
                                    PermissionKind.SPECIAL_ACCESS -> "সেটিংস এক্সেস"
                                    PermissionKind.ROLE -> "ডিফল্ট রোল"
                                    PermissionKind.PER_SESSION -> "সেশন টোকেন"
                                    PermissionKind.MANIFEST_ONLY -> "প্যাকেজ অনুমতি"
                                },
                                fontSize = 11.sp,
                                color = AmberGlow,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = spec.whyBn,
                        fontSize = 13.sp,
                        color = TextWhite.copy(alpha = 0.9f),
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // What it enables
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = GreenNeon,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "যা করতে পারবে: ${spec.whatItEnablesBn}",
                            fontSize = 12.sp,
                            color = BlueHolo,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 17.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Privacy Assurance
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = CyanNeon,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "গোপনীয়তা রক্ষা: ${spec.privacyNoteBn}",
                            fontSize = 12.sp,
                            color = TextDim,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Button(
                onClick = {
                    when (spec.kind) {
                        PermissionKind.RUNTIME -> {
                            if (spec.androidPermissions.isNotEmpty()) {
                                runtimeLauncher.launch(spec.androidPermissions.toTypedArray())
                            } else {
                                onDismiss()
                            }
                        }
                        PermissionKind.SPECIAL_ACCESS,
                        PermissionKind.ROLE,
                        PermissionKind.MANIFEST_ONLY -> {
                            PermissionOrchestrator.openPermissionSettings(context, spec)
                            onDismiss()
                        }
                        PermissionKind.PER_SESSION -> {
                            val handler = PermissionOrchestrator.sessionPermissionHandler
                            if (handler != null) {
                                handler.invoke(context, spec)
                            } else {
                                PermissionOrchestrator.openPermissionSettings(context, spec)
                            }
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("permission_rationale_grant_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
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
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (spec.kind == PermissionKind.SPECIAL_ACCESS) "সেটিংসে গিয়ে সক্রিয় করুন" else "অনুমতি প্রদান করুন",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            if (spec.kind == PermissionKind.RUNTIME) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        PermissionOrchestrator.openPermissionSettings(context, spec)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .testTag("permission_rationale_open_settings_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderBlue)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "অ্যাপ সেটিংস খুলুন (ফিরে এলে কাজটি স্বয়ংক্রিয়ভাবে চালু হবে)",
                        fontSize = 12.sp,
                        color = CyanNeon
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = {
                        PermissionOrchestrator.markPostponed(spec.id)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("permission_rationale_postpone_button")
                ) {
                    Text(
                        text = "পরে মনে করাবেন",
                        fontSize = 13.sp,
                        color = TextDim
                    )
                }

                TextButton(
                    onClick = {
                        PermissionOrchestrator.markPermanentlyDenied(spec.id)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("permission_rationale_deny_button")
                ) {
                    Text(
                        text = "আর দেখাবেন না",
                        fontSize = 13.sp,
                        color = RedAlert.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
