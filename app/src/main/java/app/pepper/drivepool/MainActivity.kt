// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import java.io.File
import app.pepper.drivepool.data.ACCOUNT_COLORS
import app.pepper.drivepool.data.Auth
import app.pepper.drivepool.data.DriveAccount
import app.pepper.drivepool.data.DriveApi
import app.pepper.drivepool.data.Store
import app.pepper.drivepool.data.Updater
import app.pepper.drivepool.ui.DashboardScreen
import app.pepper.drivepool.ui.FileBrowserScreen
import app.pepper.drivepool.ui.FolderCrumb
import app.pepper.drivepool.ui.SearchHit
import app.pepper.drivepool.ui.SearchScreen
import app.pepper.drivepool.ui.theme.DrivePoolTheme
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var accounts by mutableStateOf<List<DriveAccount>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var hint by mutableStateOf<String?>(null)
    private var refreshing by mutableStateOf(false)
    private var updateInfo by mutableStateOf<Updater.UpdateInfo?>(null)
    private var downloadState by mutableStateOf<Updater.DownloadState>(Updater.DownloadState.Idle)

    private sealed interface Screen {
        data object Dashboard : Screen
        data class Browser(val email: String) : Screen
        data object Search : Screen
        data class PickFolder(val destEmail: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Dashboard)
    private var browserStack by mutableStateOf<List<FolderCrumb>>(listOf(FolderCrumb(null, "My Drive")))
    private var pickStack by mutableStateOf<List<FolderCrumb>>(listOf(FolderCrumb(null, "My Drive")))

    // v0.5.0 move flow: the file being moved, its source account, and the
    // destination account once picked. Null = no move in progress.
    private var moveSourceEmail by mutableStateOf<String?>(null)
    private var moveSourceFile by mutableStateOf<DriveApi.DriveFile?>(null)
    private var moveDestEmail by mutableStateOf<String?>(null)
    // (status text, 0..1) while a move or pooled upload runs; null = idle.
    private var moveProgress by mutableStateOf<Pair<String, Float>?>(null)
    private var uploadProgress by mutableStateOf<Pair<String, Float>?>(null)
    private var moveError by mutableStateOf<String?>(null)
    // Account that must approve the broader Drive scope (v0.5.0).
    private var reauthEmail by mutableStateOf<String?>(null)

    private fun openBrowser(email: String) {
        browserStack = listOf(FolderCrumb(null, "My Drive"))
        screen = Screen.Browser(email)
    }

    private val uploadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { startPooledUpload(it) } }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            error = "Sign-in cancelled."
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                val account = Auth.accountFromResult(result.data)
                if (account == null) { error = "Sign-in returned no account."; return@launch }
                val token = Auth.accessToken(this@MainActivity, account)
                if (token == null) { error = "Could not get a Drive access token. Try again."; return@launch }
                addAccountFromToken(token)
                reauthEmail = null
            } catch (e: ApiException) {
                error = if (e.statusCode == 10) {
                    "Google sign-in error 10: the Android OAuth client for " +
                        "app.pepper.drivepool is not added in your Google Cloud project yet. " +
                        "Add it in Cloud Console > Credentials, then try again."
                } else "Sign-in failed (${e.statusCode}): ${e.message}"
            } catch (e: UserRecoverableAuthException) {
                startActivity(e.intent)
            } catch (e: Exception) {
                error = "Sign-in failed: ${e.message}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accounts = Store.loadAccounts(this)
        checkForUpdate()
        setContent {
            DrivePoolTheme {
                when (val s = screen) {
                    is Screen.Dashboard -> {
                        DashboardScreen(
                            accounts = accounts,
                            error = error,
                            hint = hint,
                            updateInfo = updateInfo,
                            downloadState = downloadState,
                            onUpdate = { startUpdateDownload() },
                            onAddDrive = {
                                // One tap: account picker, then Drive read-only consent.
                                // Sign out first: Google remembers the last account and
                                // would otherwise skip the chooser and re-add the same
                                // account. There is no cap — add as many as you like.
                                error = null
                                hint = null
                                Auth.client(this).signOut().addOnCompleteListener {
                                    signInLauncher.launch(Auth.signInIntent(this))
                                }
                            },
                            onRefresh = { refreshAll() },
                            onRemoveAccount = { email ->
                                Store.removeAccount(this, email)
                                accounts = Store.loadAccounts(this)
                            },
                            onOpenAccount = { openBrowser(it.email) },
                            onSearch = { screen = Screen.Search },
                            onUpload = { uploadPickerLauncher.launch("*/*") }
                        )
                    }
                    is Screen.Browser -> {
                        val account = accounts.firstOrNull { it.email == s.email }
                            ?: return@DrivePoolTheme
                        val index = accounts.indexOf(account)
                        BackHandler {
                            if (browserStack.size > 1) {
                                browserStack = browserStack.dropLast(1)
                            } else {
                                screen = Screen.Dashboard
                            }
                        }
                        FileBrowserScreen(
                            account = account,
                            accountColor = Color(ACCOUNT_COLORS[index % ACCOUNT_COLORS.size]),
                            stack = browserStack,
                            onNavigate = { browserStack = it },
                            onClose = { screen = Screen.Dashboard },
                            loadFiles = { email, folderId, pageToken -> loadFiles(email, folderId, pageToken) },
                            onMoveFile = { file ->
                                moveSourceEmail = s.email
                                moveSourceFile = file
                                moveDestEmail = null
                            },
                            showReauth = reauthEmail == s.email,
                            onReauth = {
                                reauthEmail = null
                                Auth.client(this).signOut().addOnCompleteListener {
                                    signInLauncher.launch(Auth.signInIntent(this))
                                }
                            }
                        )
                    }
                    is Screen.PickFolder -> {
                        val account = accounts.firstOrNull { it.email == s.destEmail }
                            ?: return@DrivePoolTheme
                        val index = accounts.indexOf(account)
                        BackHandler {
                            if (pickStack.size > 1) {
                                pickStack = pickStack.dropLast(1)
                            } else {
                                screen = Screen.Browser(moveSourceEmail ?: return@BackHandler)
                            }
                        }
                        FileBrowserScreen(
                            account = account,
                            accountColor = Color(ACCOUNT_COLORS[index % ACCOUNT_COLORS.size]),
                            stack = pickStack,
                            onNavigate = { pickStack = it },
                            onClose = { screen = Screen.Browser(moveSourceEmail ?: return@FileBrowserScreen) },
                            loadFiles = { email, folderId, pageToken -> loadFiles(email, folderId, pageToken) },
                            pickMode = true,
                            onPickFolder = { folder -> startMove(s.destEmail, folder) },
                            showReauth = reauthEmail == s.destEmail,
                            onReauth = {
                                reauthEmail = null
                                Auth.client(this).signOut().addOnCompleteListener {
                                    signInLauncher.launch(Auth.signInIntent(this))
                                }
                            }
                        )
                    }
                    is Screen.Search -> {
                        BackHandler { screen = Screen.Dashboard }
                        SearchScreen(
                            accounts = accounts,
                            onBack = { screen = Screen.Dashboard },
                            onOpenFolder = { email, folder ->
                                browserStack = listOf(
                                    FolderCrumb(null, "My Drive"), folder
                                )
                                screen = Screen.Browser(email)
                            },
                            searchAll = { query -> searchAll(query) }
                        )
                    }
                }

                // Destination picker for a move: every account except the source.
                val movingFile = moveSourceFile
                if (movingFile != null && moveDestEmail == null) {
                    val srcEmail = moveSourceEmail
                    AlertDialog(
                        onDismissRequest = { moveSourceFile = null; moveSourceEmail = null },
                        containerColor = Color(0xFF1C1C22),
                        title = { Text("Move to which Drive?", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text(
                                    movingFile.name,
                                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                accounts.filter { it.email != srcEmail }.forEach { dest ->
                                    TextButton(
                                        onClick = {
                                            moveDestEmail = dest.email
                                            pickStack = listOf(FolderCrumb(null, "My Drive"))
                                            screen = Screen.PickFolder(dest.email)
                                        }
                                    ) { Text(dest.email, color = Color(0xFF4A90E2)) }
                                }
                                if (accounts.size < 2) {
                                    Text(
                                        "Link a second Drive first — there is nowhere to move it to.",
                                        color = Color(0xFF9A9AA5), fontSize = 14.sp
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { moveSourceFile = null; moveSourceEmail = null }) {
                                Text("Cancel", color = Color(0xFF9A9AA5))
                            }
                        }
                    )
                }

                // Progress while a move or pooled upload runs.
                val progress = moveProgress ?: uploadProgress
                if (progress != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(0xFF1C1C22),
                        title = {
                            Text(
                                if (moveProgress != null) "Moving file" else "Uploading",
                                color = Color.White, fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column {
                                Text(progress.first, color = Color(0xFF9A9AA5), fontSize = 14.sp)
                                Spacer(Modifier.height(12.dp))
                                LinearProgressIndicator(
                                    progress = { progress.second },
                                    color = Color(0xFF4A90E2),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {}
                    )
                }

                moveError?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { moveError = null },
                        containerColor = Color(0xFF1C1C22),
                        title = { Text("Couldn't finish", color = Color.White, fontWeight = FontWeight.Bold) },
                        text = { Text(msg, color = Color(0xFF9A9AA5), fontSize = 14.sp) },
                        confirmButton = {
                            TextButton(onClick = { moveError = null }) {
                                Text("Got it", color = Color(0xFF4A90E2))
                            }
                        }
                    )
                }
            }
        }
    }

    private suspend fun addAccountFromToken(token: String) {
        val quota = DriveApi.fetchQuota(token)
        if (quota == null) { error = "Could not read Drive quota. Check the Drive API is enabled."; return }
        val stats = DriveApi.fetchFileStats(token, todayOnly = false)
        val today = DriveApi.fetchFileStats(token, todayOnly = true)
        val account = DriveAccount(
            email = quota.email,
            usedBytes = quota.used,
            limitBytes = quota.limit,
            status = "Connected",
            fileCount = stats.count,
            todayBytes = today.totalBytes,
            lastSync = System.currentTimeMillis()
        )
        Store.upsertAccount(this, account)
        accounts = Store.loadAccounts(this)
        error = null
        hint = null
    }

    /** Refresh re-reads quota for EVERY linked account, by email —
     *  not just the last one signed in. An account whose grant expired
     *  is marked "Needs sign-in" instead of failing the whole refresh. */
    private fun refreshAll() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            try {
                val stored = Store.loadAccounts(this@MainActivity)
                if (stored.isEmpty()) {
                    hint = "No Google account linked yet — tap Add Drive."
                    return@launch
                }
                var ok = 0
                val needsSignIn = mutableListOf<String>()
                for (acct in stored) {
                    try {
                        val token = Auth.accessTokenForEmail(this@MainActivity, acct.email)
                        if (token == null) { needsSignIn.add(acct.email); continue }
                        addAccountFromToken(token)
                        ok++
                    } catch (e: UserRecoverableAuthException) {
                        needsSignIn.add(acct.email)
                        Store.upsertAccount(
                            this@MainActivity, acct.copy(status = "Needs sign-in")
                        )
                        accounts = Store.loadAccounts(this@MainActivity)
                    } catch (_: Exception) {
                        needsSignIn.add(acct.email)
                    }
                }
                hint = when {
                    needsSignIn.isEmpty() -> "Refreshed $ok account${if (ok == 1) "" else "s"}."
                    ok == 0 -> "Could not refresh — tap Add Drive and pick ${needsSignIn.first()} again."
                    else -> "Refreshed $ok account${if (ok == 1) "" else "s"}. " +
                        "${needsSignIn.joinToString()} needs sign-in again — tap Add Drive and pick it."
                }
            } catch (e: Exception) {
                error = "Refresh failed: ${e.message}"
            } finally {
                refreshing = false
            }
        }
    }

    /** One page of a folder's children for the file browser. Null = the
     *  listing failed (token or network); the screen shows Retry. When the
     *  failure is a missing grant for the broader v0.5.0 scope, the
     *  screen offers "Sign in again" instead. */
    private suspend fun loadFiles(
        email: String,
        folderId: String?,
        pageToken: String?
    ): DriveApi.FilePage? {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (e: UserRecoverableAuthException) {
            reauthEmail = email
            return null
        } catch (_: Exception) { null } ?: return null
        return DriveApi.listFiles(token, folderId, pageToken)
    }

    /** Move = download from the source account, upload into the
     *  destination folder, delete the original. The copy is only
     *  deleted after the upload is confirmed on the destination. */
    private fun startMove(destEmail: String, destFolder: FolderCrumb) {
        val srcEmail = moveSourceEmail ?: return
        val file = moveSourceFile ?: return
        screen = Screen.Browser(srcEmail)
        lifecycleScope.launch {
            moveProgress = "Downloading ${file.name}…" to 0f
            val tmp = File(cacheDir, "move-${System.currentTimeMillis()}")
            try {
                val srcToken = try {
                    Auth.accessTokenForEmail(this@MainActivity, srcEmail)
                } catch (_: Exception) { null }
                val dstToken = try {
                    Auth.accessTokenForEmail(this@MainActivity, destEmail)
                } catch (_: Exception) { null }
                if (srcToken == null || dstToken == null) {
                    moveError = "Could not get access for one of the accounts. " +
                        "Sign in again and retry."
                    return@launch
                }
                val okDown = DriveApi.downloadFile(srcToken, file.id, tmp) { done, total ->
                    if (total > 0) {
                        moveProgress = "Downloading ${file.name}…" to
                            (done.toFloat() / total * 0.5f)
                    }
                }
                if (!okDown) {
                    moveError = "Download failed. Check the connection and retry — " +
                        "the original is untouched."
                    return@launch
                }
                moveProgress = "Uploading to $destEmail…" to 0.5f
                val newId = DriveApi.uploadFile(
                    dstToken, file.name, file.mimeType, destFolder.id, tmp
                ) { done, total ->
                    if (total > 0) {
                        moveProgress = "Uploading to $destEmail…" to
                            (0.5f + done.toFloat() / total * 0.5f)
                    }
                }
                if (newId == null) {
                    moveError = "Upload failed. The original is untouched — " +
                        "retry when the connection is better."
                    return@launch
                }
                moveProgress = "Removing the original…" to 1f
                DriveApi.deleteFile(srcToken, file.id)
                hint = "Moved ${file.name} to $destEmail."
                refreshAll()
            } finally {
                tmp.delete()
                moveProgress = null
                moveSourceFile = null
                moveSourceEmail = null
                moveDestEmail = null
            }
        }
    }

    /** Pooled upload: pick a file on the phone, send it to the linked
     *  account with the most free space. */
    private fun startPooledUpload(uri: Uri) {
        lifecycleScope.launch {
            var name = "upload"
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: name
                }
            }
            val stored = Store.loadAccounts(this@MainActivity)
            if (stored.isEmpty()) {
                error = "No Google account linked yet — tap Add Drive."
                return@launch
            }
            val target = stored.maxByOrNull { it.freeBytes } ?: return@launch
            val tmp = File(cacheDir, "upload-${System.currentTimeMillis()}")
            try {
                val read = contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (read == null) {
                    error = "Could not read that file."
                    return@launch
                }
                uploadProgress = "Uploading $name to ${target.email}…" to 0f
                val token = try {
                    Auth.accessTokenForEmail(this@MainActivity, target.email)
                } catch (_: Exception) { null }
                if (token == null) {
                    error = "Could not get access for ${target.email}. " +
                        "Sign in again and retry."
                    return@launch
                }
                val id = DriveApi.uploadFile(token, name, mime, null, tmp) { done, total ->
                    if (total > 0) {
                        uploadProgress = "Uploading $name to ${target.email}…" to
                            (done.toFloat() / total)
                    }
                }
                if (id == null) {
                    error = "Upload failed. Check the connection and retry."
                    return@launch
                }
                hint = "Uploaded $name to ${target.email} (most free space)."
                refreshAll()
            } finally {
                tmp.delete()
                uploadProgress = null
            }
        }
    }

    /** Name search across EVERY linked account, grouped by account in the UI. */
    private suspend fun searchAll(query: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        for (acct in Store.loadAccounts(this)) {
            val token = try {
                Auth.accessTokenForEmail(this, acct.email)
            } catch (_: Exception) { null } ?: continue
            DriveApi.searchFiles(token, query).forEach { hits.add(SearchHit(acct.email, it)) }
        }
        return hits
    }

    /** Checks R2 version.json once at launch. Fail-open: any problem = no banner. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val remote = Updater.check() ?: return@launch
            if (remote.versionCode > BuildConfig.VERSION_CODE) {
                updateInfo = remote
            }
        }
    }

    private fun startUpdateDownload() {
        val info = updateInfo ?: return
        if (downloadState is Updater.DownloadState.Progress) return
        lifecycleScope.launch {
            Updater.download(this@MainActivity, info.apkUrl) { state ->
                downloadState = state
            }
        }
    }
}
