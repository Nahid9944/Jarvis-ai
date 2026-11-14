package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ProfileManagementComponent
import com.example.viewmodel.AssistantViewModel

private val ProfileScreenSpaceDark = Color(0xFF040814)
private val ProfileScreenCyan = Color(0xFF00F5D4)
private val ProfileScreenTextDim = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: AssistantViewModel,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = ProfileScreenSpaceDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NAHID AI • ওনার প্রোফাইল",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = ProfileScreenCyan
                        )
                        Text(
                            text = "ব্যক্তিগত পরিচয়, ভয়েস ও ডিভাইস সেটিংস",
                            fontSize = 11.sp,
                            color = ProfileScreenTextDim
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "ফিরে যান",
                            tint = ProfileScreenCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ProfileScreenSpaceDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ProfileScreenSpaceDark)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            ProfileManagementComponent(
                viewModel = viewModel,
                onSaveSuccess = onBack
            )
        }
    }
}
