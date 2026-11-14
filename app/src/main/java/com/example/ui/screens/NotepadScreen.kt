package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.NoteEntity
import com.example.ui.components.AppHeaderBar
import com.example.ui.components.DeleteConfirmDialog
import com.example.viewmodel.AssistantViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NotepadScreen(
    viewModel: AssistantViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allNotes by viewModel.allNotes.collectAsState()

    var showAddNoteDialog by remember { mutableStateOf(false) }
    var noteToEdit by remember { mutableStateOf<NoteEntity?>(null) }
    var noteToDelete by remember { mutableStateOf<NoteEntity?>(null) }

    Scaffold(
        topBar = {
            AppHeaderBar(
                title = "NAHID AI • Neural Notes",
                onBack = onBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddNoteDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier.testTag("add_note_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "নতুন নোট")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(14.dp)
        ) {
            if (allNotes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "কোনো নোট লেখা নেই।\nনিচের (+) বাটনে চাপ দিয়ে নতুন নোট তৈরি করুন।",
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allNotes) { note ->
                        val dateFormatted = remember(note.timestamp) {
                            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(note.timestamp))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = note.title.ifBlank { "নোট" },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Row {
                                        IconButton(onClick = { noteToEdit = note }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Edit, contentDescription = "সম্পাদনা", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = { noteToDelete = note }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Delete, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                Text(
                                    text = note.content,
                                    fontSize = 13.5.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = dateFormatted,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Note Dialog
    if (showAddNoteDialog) {
        var nTitle by remember { mutableStateOf("") }
        var nContent by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("নতুন নোট লিখুন") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nTitle,
                        onValueChange = { nTitle = it },
                        label = { Text("শিরোনাম (Title)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = nContent,
                        onValueChange = { nContent = it },
                        label = { Text("নোট বিবরণ (Content) *") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nContent.isBlank()) {
                            Toast.makeText(context, "নোটের বিবরণ লিখুন!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val entity = NoteEntity(title = nTitle.trim(), content = nContent.trim())
                        viewModel.saveNote(entity) {
                            Toast.makeText(context, "নোট সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                            showAddNoteDialog = false
                        }
                    }
                ) { Text("সংরক্ষণ করুন") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddNoteDialog = false }) { Text("বাতিল") }
            }
        )
    }

    // Edit Note Dialog
    if (noteToEdit != null) {
        val note = noteToEdit!!
        var eTitle by remember { mutableStateOf(note.title) }
        var eContent by remember { mutableStateOf(note.content) }

        AlertDialog(
            onDismissRequest = { noteToEdit = null },
            title = { Text("নোট সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = eTitle, onValueChange = { eTitle = it }, label = { Text("শিরোনাম") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eContent, onValueChange = { eContent = it }, label = { Text("বিবরণ") }, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = note.copy(title = eTitle.trim(), content = eContent.trim(), timestamp = System.currentTimeMillis())
                        viewModel.saveNote(updated) {
                            Toast.makeText(context, "আপডেট হয়েছে!", Toast.LENGTH_SHORT).show()
                            noteToEdit = null
                        }
                    }
                ) { Text("আপডেট") }
            },
            dismissButton = {
                OutlinedButton(onClick = { noteToEdit = null }) { Text("বাতিল") }
            }
        )
    }

    // Delete Note Confirmation
    if (noteToDelete != null) {
        val note = noteToDelete!!
        DeleteConfirmDialog(
            title = "নোট মুছবেন?",
            message = "আপনি কি নিশ্চিত যে এই নোটটি মুছে ফেলতে চান?",
            onConfirm = {
                viewModel.deleteNote(note.id) {
                    Toast.makeText(context, "নোট মুছে ফেলা হয়েছে", Toast.LENGTH_SHORT).show()
                    noteToDelete = null
                }
            },
            onDismiss = { noteToDelete = null }
        )
    }
}
