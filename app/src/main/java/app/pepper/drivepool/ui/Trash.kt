// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pepper.drivepool.data.DriveAccount
import app.pepper.drivepool.data.DriveApi
import kotlinx.coroutines.launch

/** Trash (v0.8.0 — Claude's note: Drive quota counts trash, but the
 *  app never showed it). Trashed files grouped by account, with
 *  Restore per file, Delete forever per file, and Empty trash per
 *  account. Everyday deletes go here instead of deleting forever. */
@Composable
fun TrashScreen(
    accounts: List<DriveAccount>,
    onBack: () -> Unit,
    loadTrashed: suspend (email: String, pageToken: String?) -> DriveApi.FilePage?,
    onRestore: suspend (email: String, file: DriveApi.DriveFile) -> Boolean,
    onDeleteForever: suspend (email: String, file: DriveApi.DriveFile) -> Boolean,
    onEmptyTrash: suspend (email: String) -> Boolean
) {
    val scope = rememberCoroutineScope()
    var trash by remember { mutableStateOf<Map<String, List<DriveApi.DriveFile>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Pair<String, DriveApi.DriveFile>?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, DriveApi.DriveFile>?>(null) }
    var confirmEmpty by remember { mutableStateOf<String?>(null) }

    fun loadAccount(email: String) {
        scope.launch {
            val page = loadTrashed(email, null)
            if (page != null) trash = trash + (email to page.files)
        }
    }

    fun loadAll() {
        scope.launch {
            loading = true
            error = null
            trash = emptyMap()
            for (acct in accounts) {
                val page = loadTrashed(acct.email, null)
                if (page != null) trash = trash + (acct.email to page.files)
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    val total = trash.values.sumOf { it.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Trash", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Trashed files still count toward your Drive quota. " +
                "Restore what you need, or empty the trash to reclaim the space.",
            color = Color(0xFF9A9AA5), fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))

        when {
            loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color(0xFF4A90E2))
                }
            }
            error != null -> {
                Text(error!!, color = Color(0xFFEF5350), fontSize = 14.sp)
            }
            total == 0 -> {
                Text("Trash is empty.", color = Color(0xFF9A9AA5), fontSize = 14.sp)
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    trash.forEach { (email, files) ->
                        if (files.isEmpty()) return@forEach
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    email, color = Color(0xFF4A90E2), fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { confirmEmpty = email }) {
                                    Text("Empty trash", color = Color(0xFFEF5350), fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        items(files, key = { it.id }) { file ->
                            FileRow(file, Color(0xFF4A90E2)) {
                                selected = email to file
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }

    selected?.let { (email, file) ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Color(0xFF1C1C22),
            title = { Text(file.name, color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Trashed on $email. Restore it, or delete it forever.",
                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        selected = null
                        scope.launch {
                            if (onRestore(email, file)) loadAccount(email)
                        }
                    }) { Text("Restore", color = Color(0xFF4A90E2)) }
                    TextButton(onClick = {
                        selected = null
                        confirmDelete = email to file
                    }) { Text("Delete forever", color = Color(0xFFEF5350)) }
                    TextButton(onClick = { selected = null }) {
                        Text("Cancel", color = Color(0xFF9A9AA5))
                    }
                }
            }
        )
    }

    confirmDelete?.let { (email, file) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Color(0xFF1C1C22),
            title = { Text("Delete forever?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "\"${file.name}\" will be permanently deleted. " +
                        "This cannot be undone.",
                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        if (onDeleteForever(email, file)) loadAccount(email)
                    }
                }) { Text("Delete forever", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel", color = Color(0xFF9A9AA5))
                }
            }
        )
    }

    confirmEmpty?.let { email ->
        AlertDialog(
            onDismissRequest = { confirmEmpty = null },
            containerColor = Color(0xFF1C1C22),
            title = { Text("Empty trash?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Everything in $email's trash will be permanently " +
                        "deleted. This cannot be undone.",
                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = null
                    scope.launch {
                        if (onEmptyTrash(email)) loadAccount(email)
                    }
                }) { Text("Empty trash", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = null }) {
                    Text("Cancel", color = Color(0xFF9A9AA5))
                }
            }
        )
    }
}
