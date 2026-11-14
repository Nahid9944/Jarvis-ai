package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LockSpaceDark = Color(0xFF040814)
private val LockCardBg = Color(0xFF0F1A36)
private val LockCyan = Color(0xFF00F5D4)
private val LockErrorRed = Color(0xFFEF4444)
private val LockTextWhite = Color(0xFFF8FAFC)
private val LockTextDim = Color(0xFF94A3B8)

@Composable
fun JarvisLockScreenOverlay(
    ownerName: String,
    loginError: String,
    onSubmitPin: (String) -> Unit
) {
    var pinInput by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LockSpaceDark)
            .padding(24.dp)
            .testTag("jarvis_lock_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = LockCardBg,
            border = BorderStroke(1.5.dp, LockCyan.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(LockCyan.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "সিকিউরিটি লক",
                        tint = LockCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "NAHID AI সুরক্ষিত প্রবেশ",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LockTextWhite,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "স্বাগতম, $ownerName। অব্যাহত রাখতে আপনার নির্ধারিত লোকাল পিন দিন।",
                    fontSize = 13.sp,
                    color = LockTextDim,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )

                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("লোকাল পিন") },
                    singleLine = true,
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (pinInput.isNotBlank()) onSubmitPin(pinInput)
                    }),
                    trailingIcon = {
                        IconButton(onClick = { showPin = !showPin }) {
                            Icon(
                                imageVector = if (showPin) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showPin) "পিন লুকান" else "পিন দেখুন",
                                tint = LockTextDim
                            )
                        }
                    },
                    isError = loginError.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lock_screen_pin_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LockCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedLabelColor = LockCyan,
                        unfocusedLabelColor = LockTextDim,
                        focusedTextColor = LockTextWhite,
                        unfocusedTextColor = LockTextWhite
                    )
                )

                if (loginError.isNotBlank()) {
                    Text(
                        text = loginError,
                        color = LockErrorRed,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("lock_screen_error")
                    )
                }

                Button(
                    onClick = { onSubmitPin(pinInput) },
                    enabled = pinInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("lock_screen_unlock_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LockCyan,
                        contentColor = LockSpaceDark
                    )
                ) {
                    Text(
                        text = "আনলক করুন",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
