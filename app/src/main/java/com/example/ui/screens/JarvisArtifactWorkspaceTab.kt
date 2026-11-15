package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ArtifactEntity
import com.example.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.*

private val ArtSpaceBg = Color(0xFF020806)
private val ArtCardBg = Color(0xFF061B16)
private val ArtSurfaceBg = Color(0xFF0A2420)
private val ArtEmerald = Color(0xFF00FF9D)
private val ArtCyan = Color(0xFF00E5FF)
private val ArtAmber = Color(0xFFFEE440)
private val ArtDanger = Color(0xFFFF476F)
private val ArtText = Color(0xFFF5FFF9)
private val ArtDim = Color(0xFF93B9AA)

@Composable
fun JarvisArtifactWorkspaceTab(
    viewModel: AssistantViewModel
) {
    val context = LocalContext.current
    val artifacts by viewModel.allArtifacts.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }
    var previewArtifact by remember { mutableStateOf<ArtifactEntity?>(null) }

    val dateFormatter = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ArtCardBg,
            border = BorderStroke(1.dp, ArtEmerald.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Description, contentDescription = "Artifacts", tint = ArtEmerald, modifier = Modifier.size(26.dp))
                    Column {
                        Text(
                            text = "আর্টিফ্যাক্ট ও ডকুমেন্ট ওয়ার্কস্পেস",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = ArtText
                        )
                        Text(
                            text = "স্থায়ী সংরক্ষিত ডকুমেন্ট, কোড ও রিসার্চ রিপোর্ট (${artifacts.size}টি)",
                            fontSize = 11.5.sp,
                            color = ArtDim
                        )
                    }
                }
                Button(
                    onClick = { showCreateDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ArtEmerald),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("artifact_create_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "নতুন আর্টিফ্যাক্ট", tint = ArtSpaceBg)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("নতুন", color = ArtSpaceBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        if (artifacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Article, contentDescription = null, tint = ArtDim.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                    Text(
                        text = "এখনো কোনো আর্টিফ্যাক্ট তৈরি করা হয়নি",
                        color = ArtText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "‘নতুন’ বাটনে চাপ দিয়ে Markdown, Text, Code বা JSON ফাইল সংরক্ষণ ও শেয়ার করুন।",
                        color = ArtDim,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(artifacts, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ArtCardBg,
                        border = BorderStroke(1.dp, ArtCyan.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { previewArtifact = item }
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = ArtText,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${item.format} • ${item.category} • ${dateFormatter.format(Date(item.updatedAt))}",
                                        color = ArtEmerald,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ArtSurfaceBg,
                                    border = BorderStroke(1.dp, ArtCyan.copy(alpha = 0.45f))
                                ) {
                                    Text(
                                        text = item.format,
                                        color = ArtCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = item.content.take(180),
                                color = ArtDim,
                                fontSize = 12.sp,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { previewArtifact = item },
                                    border = BorderStroke(1.dp, ArtEmerald.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("দেখুন", color = ArtEmerald, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { editingArtifact = item },
                                    border = BorderStroke(1.dp, ArtCyan.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("সম্পাদনা", color = ArtCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.exportArtifactToFile(context, item).onSuccess { uri ->
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, item.title)
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "আর্টিফ্যাক্ট এক্সপোর্ট করুন"))
                                        }.onFailure { err ->
                                            Toast.makeText(context, err.localizedMessage ?: "এক্সপোর্ট ব্যর্থ", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    border = BorderStroke(1.dp, ArtAmber.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("এক্সপোর্ট", color = ArtAmber, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.deleteArtifact(item.id)
                                        Toast.makeText(context, "আর্টিফ্যাক্ট মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "মুছুন", tint = ArtDanger)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog || editingArtifact != null) {
        val target = editingArtifact
        var title by remember(target) { mutableStateOf(target?.title ?: "") }
        var content by remember(target) { mutableStateOf(target?.content ?: "") }
        var format by remember(target) { mutableStateOf(target?.format ?: "MARKDOWN") }
        var category by remember(target) { mutableStateOf(target?.category ?: "DOCUMENT") }

        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                editingArtifact = null
            },
            containerColor = ArtCardBg,
            title = {
                Text(
                    text = if (target == null) "নতুন আর্টিফ্যাক্ট তৈরি" else "আর্টিফ্যাক্ট সম্পাদনা",
                    color = ArtText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("শিরোনাম") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ArtText, unfocusedTextColor = ArtText)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("MARKDOWN", "TEXT", "CODE", "JSON").forEach { fmt ->
                            FilterChip(
                                selected = format == fmt,
                                onClick = { format = fmt },
                                label = { Text(fmt, fontSize = 10.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("বিষয়বস্তু") },
                        minLines = 5,
                        maxLines = 10,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ArtText, unfocusedTextColor = ArtText)
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = title.isNotBlank() && content.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ArtEmerald),
                    onClick = {
                        viewModel.saveArtifact(
                            id = target?.id ?: 0L,
                            title = title,
                            content = content,
                            format = format,
                            category = category
                        ) {
                            showCreateDialog = false
                            editingArtifact = null
                            Toast.makeText(context, "আর্টিফ্যাক্ট সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("সংরক্ষণ", color = ArtSpaceBg, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateDialog = false
                    editingArtifact = null
                }) {
                    Text("বাতিল", color = ArtDim)
                }
            }
        )
    }

    previewArtifact?.let { item ->
        AlertDialog(
            onDismissRequest = { previewArtifact = null },
            containerColor = ArtCardBg,
            title = {
                Column {
                    Text(item.title, color = ArtEmerald, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${item.format} • ${item.category}", color = ArtDim, fontSize = 11.sp)
                }
            },
            text = {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ArtSurfaceBg,
                    border = BorderStroke(1.dp, ArtCyan.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                ) {
                    LazyColumn(modifier = Modifier.padding(12.dp)) {
                        item {
                            Text(
                                text = item.content,
                                color = ArtText,
                                fontSize = 12.5.sp,
                                fontFamily = if (item.format in listOf("CODE", "JSON")) FontFamily.Monospace else FontFamily.SansSerif,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewArtifact = null }) {
                    Text("বন্ধ", color = ArtEmerald, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
