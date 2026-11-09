// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pepper.drivepool.data.DriveAccount
import app.pepper.drivepool.data.DriveApi
import app.pepper.drivepool.data.formatSize
import app.pepper.drivepool.ui.theme.CardBg
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class FolderCrumb(val id: String?, val name: String)
data class SearchHit(val email: String, val file: DriveApi.DriveFile)

/** "2026-11-08T14:32:00.000Z" -> "8 Nov 2026". Fail-soft: returns the raw string. */
private fun prettyDate(rfc3339: String): String = try {
    val inFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val date = inFmt.parse(rfc3339.substringBefore('.')) ?: return rfc3339
    SimpleDateFormat("d MMM yyyy", Locale.US).format(date)
} catch (_: Exception) { rfc3339 }

@Composable
private fun FileDetailsDialog(
    file: DriveApi.DriveFile,
    onClose: () -> Unit,
    onMove: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(file.name) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        title = { Text(file.name, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!file.isFolder) {
                    Text("Size: ${formatSize(file.size)}", color = Color(0xFF9A9AA5), fontSize = 14.sp)
                }
                Text("Modified: ${prettyDate(file.modifiedTime)}", color = Color(0xFF9A9AA5), fontSize = 14.sp)
                Text("Type: ${file.mimeType}", color = Color(0xFF9A9AA5), fontSize = 14.sp)
                if (!file.isFolder && onMove != null) {
                    Text(
                        "Moving copies the file on Google's servers when " +
                            "possible — no data through your phone. If that " +
                            "isn't possible it downloads and re-uploads instead, " +
                            "and the original is only deleted after the copy " +
                            "is verified.",
                        color = Color(0xFF9A9AA5), fontSize = 13.sp
                    )
                }
                if (!file.isFolder && onOpen != null) {
                    Text(
                        "Open downloads the file to your phone and lets Android " +
                            "ask which app should play it.",
                        color = Color(0xFF9A9AA5), fontSize = 13.sp
                    )
                }
                if (onDelete != null) {
                    Text(
                        "Delete moves it to Trash — you can restore it from " +
                            "Trash later. It is not deleted forever.",
                        color = Color(0xFF9A9AA5), fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (onOpen != null && !file.isFolder) {
                    TextButton(onClick = onOpen) { Text("Open", color = Color(0xFF4A90E2)) }
                }
                if (onMove != null && !file.isFolder) {
                    TextButton(onClick = onMove) { Text("Move", color = Color(0xFF4A90E2)) }
                }
                if (onRename != null) {
                    TextButton(onClick = { showRename = true }) { Text("Rename", color = Color(0xFF4A90E2)) }
                }
                if (onDelete != null) {
                    TextButton(onClick = { showDelete = true }) { Text("Delete", color = Color(0xFFEF5350)) }
                }
                TextButton(onClick = onClose) { Text("Close", color = Color(0xFF4A90E2)) }
            }
        }
    )

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = CardBg,
            title = { Text("Rename", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF4A90E2),
                        unfocusedBorderColor = Color(0xFF2E2E36)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty() && name != file.name) onRename?.invoke(name)
                        showRename = false
                    }
                ) { Text("Save", color = Color(0xFF4A90E2)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel", color = Color(0xFF9A9AA5)) }
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = CardBg,
            title = { Text("Move to trash?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "\"${file.name}\" will go to Trash. You can restore it " +
                        "from Trash — it is not deleted forever.",
                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete?.invoke() }) {
                    Text("Move to trash", color = Color(0xFFEF5350))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel", color = Color(0xFF9A9AA5)) }
            }
        )
    }
}

// Public so the Trash screen can reuse it (v0.8.0).
@Composable
fun FileRow(
    file: DriveApi.DriveFile,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2A2A32)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (file.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isFolder) Color(0xFFFACC15) else tint
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (file.isFolder) "Folder" else "${formatSize(file.size)} • ${prettyDate(file.modifiedTime)}",
                color = Color(0xFF9A9AA5), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FileBrowserScreen(
    account: DriveAccount,
    accountColor: Color,
    stack: List<FolderCrumb>,
    onNavigate: (List<FolderCrumb>) -> Unit,
    onClose: () -> Unit,
    loadFiles: suspend (email: String, folderId: String?, pageToken: String?, sort: DriveApi.FileSort) -> DriveApi.FilePage?,
    // Pick mode (v0.5.0 move flow): the screen becomes a folder chooser —
    // "Move here" picks the current folder, files are not selectable.
    pickMode: Boolean = false,
    onPickFolder: (FolderCrumb) -> Unit = {},
    onMoveFile: ((DriveApi.DriveFile) -> Unit)? = null,
    onOpenFile: ((DriveApi.DriveFile) -> Unit)? = null,
    onRenameFile: (suspend (DriveApi.DriveFile, String) -> Boolean)? = null,
    onDeleteFile: (suspend (DriveApi.DriveFile) -> Boolean)? = null,
    // v0.8.1: floating upload button — uploads into the folder on
    // screen. Null in pick mode (the screen is a folder chooser there).
    onUploadHere: (() -> Unit)? = null,
    // True when the last listing failed because the account must approve
    // the broader Drive permission (v0.5.0 scope change).
    showReauth: Boolean = false,
    onReauth: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val current = stack.last()
    var files by remember(stack) { mutableStateOf<List<DriveApi.DriveFile>>(emptyList()) }
    var nextPage by remember(stack) { mutableStateOf<String?>(null) }
    var loading by remember(stack) { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreFailed by remember { mutableStateOf(false) }
    var error by remember(stack) { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<DriveApi.DriveFile?>(null) }
    // v0.8.0: sort order (Claude's browsing note).
    var sort by remember { mutableStateOf(DriveApi.FileSort.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }

    fun load(pageToken: String?, append: Boolean) {
        scope.launch {
            if (append) loadingMore = true else loading = true
            error = null
            loadMoreFailed = false
            val page = loadFiles(account.email, current.id, pageToken, sort)
            if (page == null) {
                if (append) loadMoreFailed = true
                else error = "Could not list files. Check the connection and try again."
            } else {
                files = if (append) files + page.files else page.files
                nextPage = page.nextPageToken
            }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(stack, sort) { load(null, append = false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (stack.size > 1) onNavigate(stack.dropLast(1)) else onClose()
            }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    current.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    account.email, color = Color(0xFF9A9AA5), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            // v0.8.0: sort menu (Claude's browsing note).
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    DriveApi.FileSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (option == sort) "✓ " else "") + option.label,
                                    color = Color.White
                                )
                            },
                            onClick = {
                                sort = option
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        // Breadcrumb: root › folder › subfolder.
        Text(
            stack.joinToString("  ›  ") { it.name },
            color = Color(0xFF9A9AA5), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))

        when {
            loading -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF4A90E2))
                }
            }
            error != null -> {
                Text(
                    if (showReauth) {
                        "This account needs to approve the new Drive permission " +
                            "(v0.5.0 moved from read-only to full Drive access, " +
                            "so files can be moved and uploaded)."
                    } else error!!,
                    color = Color(0xFFEF5350), fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                if (showReauth && onReauth != null) {
                    Button(
                        onClick = onReauth,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2))
                    ) { Text("Sign in again", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = { load(null, append = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A32))
                ) { Text("Retry", color = Color.White) }
            }
            files.isEmpty() -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty.", color = Color(0xFF9A9AA5), fontSize = 14.sp)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    // Room for the floating upload button (v0.8.1).
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileRow(file, accountColor) {
                            if (file.isFolder) {
                                onNavigate(stack + FolderCrumb(file.id, file.name))
                            } else if (!pickMode) {
                                selected = file
                            }
                        }
                    }
                    if (nextPage != null) {
                        item {
                            // v0.8.0: infinite scroll (Claude's browsing
                            // note) — the next page loads itself when
                            // you reach the bottom. If it fails, a
                            // manual retry button appears instead.
                            Spacer(Modifier.height(12.dp))
                            if (loadMoreFailed) {
                                Button(
                                    onClick = { load(nextPage, append = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A32)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Couldn't load more — tap to retry", color = Color.White)
                                }
                            } else {
                                if (!loadingMore) {
                                    LaunchedEffect(files.size) {
                                        load(nextPage, append = true)
                                    }
                                }
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF4A90E2),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
        if (pickMode) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onPickFolder(current) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Move here — ${current.name}", fontWeight = FontWeight.Bold)
            }
        }
    }

    // v0.8.1: floating upload button — it follows you into every
    // folder you open; the picked file lands in the folder on screen,
    // on the account you are browsing. Hidden in pick mode.
    if (!pickMode && onUploadHere != null) {
        FloatingActionButton(
            onClick = onUploadHere,
            containerColor = Color(0xFF4A90E2),
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Filled.Upload, contentDescription = "Upload to this folder")
        }
    }
    }

    selected?.let {
        FileDetailsDialog(
            file = it,
            onClose = { selected = null },
            onMove = onMoveFile?.let { move -> { move(it); selected = null } },
            onOpen = onOpenFile?.let { open -> { open(it); selected = null } },
            onRename = onRenameFile?.let { rename ->
                { newName ->
                    scope.launch {
                        if (rename(it, newName)) load(null, append = false)
                        selected = null
                    }
                }
            },
            onDelete = onDeleteFile?.let { delete ->
                {
                    scope.launch {
                        if (delete(it)) load(null, append = false)
                        selected = null
                    }
                }
            }
        )
    }
}

@Composable
fun SearchScreen(
    accounts: List<DriveAccount>,
    onBack: () -> Unit,
    onOpenFolder: (email: String, folder: FolderCrumb) -> Unit,
    searchAll: suspend (String, DriveApi.SearchType, DriveApi.SearchDate) -> List<SearchHit>,
    onOpenFile: ((email: String, file: DriveApi.DriveFile) -> Unit)? = null,
    onRenameFile: (suspend (email: String, file: DriveApi.DriveFile, newName: String) -> Boolean)? = null,
    onDeleteFile: (suspend (email: String, file: DriveApi.DriveFile) -> Boolean)? = null
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    // (account email, file) — the email is needed to download the file.
    var selected by remember { mutableStateOf<Pair<String, DriveApi.DriveFile>?>(null) }
    // v0.8.0: type + date filters (Claude's browsing note).
    var type by remember { mutableStateOf(DriveApi.SearchType.ALL) }
    var date by remember { mutableStateOf(DriveApi.SearchDate.ANY) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            searching = true
            searched = true
            hits = searchAll(q, type, date)
            searching = false
        }
    }

    // Changing a filter re-runs the current search.
    LaunchedEffect(type, date) {
        if (searched && query.trim().isNotEmpty()) runSearch()
    }

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
                "Search all Drives", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("File or folder name…", color = Color(0xFF9A9AA5)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF9A9AA5)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF4A90E2),
                unfocusedBorderColor = Color(0xFF2E2E36)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { runSearch() },
            enabled = query.trim().isNotEmpty() && !searching,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (searching) "Searching…" else "Search", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        // v0.8.0: type + date filter chips.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DriveApi.SearchType.entries.forEach { option ->
                FilterPill(
                    label = option.label,
                    selected = option == type,
                    onClick = { type = option }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DriveApi.SearchDate.entries.forEach { option ->
                FilterPill(
                    label = option.label,
                    selected = option == date,
                    onClick = { date = option }
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            searching -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF4A90E2))
                }
            }
            !searched -> {
                Text(
                    "Searches every linked Drive by name — files and folders.",
                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                )
            }
            hits.isEmpty() -> {
                Text("Nothing found for \"$query\".", color = Color(0xFF9A9AA5), fontSize = 14.sp)
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val grouped = hits.groupBy { it.email }
                    grouped.forEach { (email, group) ->
                        item {
                            Text(
                                email, color = Color(0xFF4A90E2), fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        items(group, key = { it.email + it.file.id }) { hit ->
                            FileRow(hit.file, Color(0xFF4A90E2)) {
                                if (hit.file.isFolder) {
                                    onOpenFolder(hit.email, FolderCrumb(hit.file.id, hit.file.name))
                                } else {
                                    selected = hit.email to hit.file
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }

    selected?.let { (email, file) ->
        FileDetailsDialog(
            file = file,
            onClose = { selected = null },
            onOpen = onOpenFile?.let { open -> { open(email, file); selected = null } },
            onRename = onRenameFile?.let { rename ->
                { newName ->
                    scope.launch {
                        if (rename(email, file, newName)) runSearch()
                        selected = null
                    }
                }
            },
            onDelete = onDeleteFile?.let { delete ->
                {
                    scope.launch {
                        if (delete(email, file)) runSearch()
                        selected = null
                    }
                }
            }
        )
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF4A90E2) else Color(0xFF2A2A32))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
