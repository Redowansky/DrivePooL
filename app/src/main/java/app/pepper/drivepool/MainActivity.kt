// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import app.pepper.drivepool.data.ACCOUNT_COLORS
import app.pepper.drivepool.data.Auth
import app.pepper.drivepool.data.DriveAccount
import app.pepper.drivepool.data.DriveApi
import app.pepper.drivepool.data.Store
import app.pepper.drivepool.data.Updater
import app.pepper.drivepool.data.UploadState
import androidx.documentfile.provider.DocumentFile
import app.pepper.drivepool.ui.DashboardScreen
import app.pepper.drivepool.ui.FileBrowserScreen
import app.pepper.drivepool.ui.FolderCrumb
import app.pepper.drivepool.ui.SearchHit
import app.pepper.drivepool.ui.SearchScreen
import app.pepper.drivepool.ui.SettingsScreen
import app.pepper.drivepool.ui.TrashScreen
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
        data object Trash : Screen
        data class PickFolder(val destEmail: String) : Screen
        // v0.11.0: settings (download folder, …).
        data object Settings : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Dashboard)
    private var browserStack by mutableStateOf<List<FolderCrumb>>(listOf(FolderCrumb(null, "My Drive")))
    private var pickStack by mutableStateOf<List<FolderCrumb>>(listOf(FolderCrumb(null, "My Drive")))

    // v0.5.0 move flow: the file being moved, its source account, and the
    // destination account once picked. Null = no move in progress.
    private var moveSourceEmail by mutableStateOf<String?>(null)
    private var moveSourceFile by mutableStateOf<DriveApi.DriveFile?>(null)
    private var moveDestEmail by mutableStateOf<String?>(null)

    /** v0.9.0: multi-select bulk op — the marked files + whether
     *  they are copied (originals stay) or moved (originals go
     *  after a verified copy). Shares the move's destination picker
     *  (account, then folder) and the PickFolder screen. */
    private data class BulkOp(
        val srcEmail: String,
        val files: List<DriveApi.DriveFile>,
        val isCopy: Boolean
    )
    private var bulkOp by mutableStateOf<BulkOp?>(null)
    // (status text, 0..1) while a file-open download runs. Moves and
    // uploads report through UploadState instead (v0.6.0 service).
    private var openProgress by mutableStateOf<Pair<String, Float>?>(null)
    // v0.10.2: progress for same-Drive moves, which run in the
    // activity, not the service — (title, label, fraction).
    // Fraction null = spinning bar (a single file has no count).
    private var localProgress by mutableStateOf<Triple<String, String, Float?>?>(null)
    // Account that must approve the broader Drive scope (v0.5.0).
    private var reauthEmail by mutableStateOf<String?>(null)

    // v0.11.0: the download folder picked in Settings — (tree URI,
    // display name). Null = the phone's Downloads folder.
    private var downloadFolder by mutableStateOf<Pair<String, String>?>(null)
    private val downloadFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Best effort — the service reads the folder right away.
            }
            val name = DocumentFile.fromTreeUri(this, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Folder"
            Store.saveDownloadFolder(this, uri.toString(), name)
            downloadFolder = uri.toString() to name
        }
    }

    private fun openBrowser(email: String) {
        browserStack = listOf(FolderCrumb(null, "My Drive"))
        screen = Screen.Browser(email)
    }

    private val uploadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { startPooledUpload(it) } }

    // v0.8.1: the browser's floating upload button — the target is
    // the account + folder on screen when the picker was opened.
    private var pendingFolderUpload by mutableStateOf<Pair<String, FolderCrumb>?>(null)
    private val folderUploadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingFolderUpload
        pendingFolderUpload = null
        if (uri != null && target != null) {
            startFolderUpload(uri, target.first, target.second)
        }
    }

    // v0.10.0: upload a whole folder — the system folder picker.
    // The persistable permission is taken here so the service can
    // still read the tree after the app goes to the background.
    private var pendingFolderUploadTarget by mutableStateOf<Pair<String, FolderCrumb>?>(null)
    private val folderTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = pendingFolderUploadTarget
        pendingFolderUploadTarget = null
        if (uri != null && target != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Best effort — the service reads the tree right away.
            }
            UploadService.startUploadFolder(
                this, uri, target.first, target.second.id, target.second.name
            )
        }
    }

    // v0.7.1: Android 13+ needs POST_NOTIFICATIONS at runtime, or the
    // upload notification never appears (App Info shows the toggle
    // greyed off). The callback always proceeds to the pending action
    // — a denial never blocks the upload; the in-app progress dialog
    // still shows. Prompted at most once ever (flag in prefs).
    private var pendingAfterNotifPermission: (() -> Unit)? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val run = pendingAfterNotifPermission
        pendingAfterNotifPermission = null
        run?.invoke()
    }

    /** Runs [action] after the notification permission is settled.
     *  No-op on Android < 13 (no runtime permission), or when already
     *  granted, or when already asked once — the prompt shows once. */
    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            action()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        val prefs = getSharedPreferences("drivepool", MODE_PRIVATE)
        if (prefs.getBoolean("notif_perm_asked", false)) {
            action()
            return
        }
        prefs.edit().putBoolean("notif_perm_asked", true).apply()
        pendingAfterNotifPermission = action
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

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
        // v0.11.0: the download folder picked in Settings, if any.
        downloadFolder = Store.loadDownloadFolder(this)
        // v0.7.1: create the "Uploads" channel at app start so the
        // notification toggle exists before any upload runs, and ask
        // once for the runtime notification permission (Android 13+).
        UploadService.createNotificationChannel(this)
        withNotificationPermission {}
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
                            onTrash = { screen = Screen.Trash },
                            onSettings = { screen = Screen.Settings },
                            onUpload = {
                                // v0.7.1: ask for the notification
                                // permission first (once ever); the picker
                                // opens either way — a denial never blocks
                                // the upload.
                                withNotificationPermission {
                                    uploadPickerLauncher.launch("*/*")
                                }
                            }
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
                            loadFiles = { email, folderId, pageToken, sort -> loadFiles(email, folderId, pageToken, sort) },
                            onMoveFile = { file ->
                                moveSourceEmail = s.email
                                moveSourceFile = file
                                moveDestEmail = null
                            },
                            onOpenFile = { file -> openFile(s.email, file) },
                            onRenameFile = { file, newName -> renameDriveFile(s.email, file, newName) },
                            onDeleteFile = { file -> trashDriveFile(s.email, file) },
                            onBulkCopy = { files ->
                                bulkOp = BulkOp(s.email, files, isCopy = true)
                                moveSourceEmail = s.email
                                moveDestEmail = null
                            },
                            onBulkMove = { files ->
                                bulkOp = BulkOp(s.email, files, isCopy = false)
                                moveSourceEmail = s.email
                                moveDestEmail = null
                            },
                            onBulkDownload = { files -> downloadBulk(s.email, files) },
                            onCreateFolder = { name ->
                                createDriveFolder(s.email, browserStack.last().id, name)
                            },
                            onDownloadFile = { file -> downloadToDevice(s.email, file) },
                            onUploadFolder = {
                                pendingFolderUploadTarget = s.email to browserStack.last()
                                withNotificationPermission {
                                    folderTreeLauncher.launch(null)
                                }
                            },
                            // Presence only — opening a folder is
                            // navigation inside the browser screen.
                            onOpenFolder = { },
                            onUploadHere = {
                                pendingFolderUpload = s.email to browserStack.last()
                                withNotificationPermission {
                                    folderUploadPickerLauncher.launch("*/*")
                                }
                            },
                            showReauth = reauthEmail == s.email,
                            onReauth = {
                                reauthEmail = null
                                Auth.client(this).signOut().addOnCompleteListener {
                                    signInLauncher.launch(Auth.signInIntent(this))
                                }
                            },
                            // v0.10.2: the result of the last move —
                            // it used to live only on the dashboard,
                            // so a move that landed you back here
                            // showed nothing at all.
                            notice = error ?: hint,
                            noticeIsError = error != null,
                            onClearNotice = { error = null; hint = null }
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
                            loadFiles = { email, folderId, pageToken, sort -> loadFiles(email, folderId, pageToken, sort) },
                            pickMode = true,
                            onPickFolder = { folder -> startBulkOrMove(s.destEmail, folder) },
                            pickActionLabel = when (bulkOp?.isCopy) {
                                true -> "Copy here"
                                false -> "Move here"
                                null -> "Move here"
                            },
                            showReauth = reauthEmail == s.destEmail,
                            onReauth = {
                                reauthEmail = null
                                Auth.client(this).signOut().addOnCompleteListener {
                                    signInLauncher.launch(Auth.signInIntent(this))
                                }
                            }
                        )
                    }
                    is Screen.Settings -> {
                        BackHandler { screen = Screen.Dashboard }
                        SettingsScreen(
                            downloadFolderName = downloadFolder?.second,
                            onPickDownloadFolder = {
                                downloadFolderPickerLauncher.launch(null)
                            },
                            onResetDownloadFolder = {
                                Store.saveDownloadFolder(this, null, null)
                                downloadFolder = null
                            },
                            versionName = BuildConfig.VERSION_NAME,
                            onBack = { screen = Screen.Dashboard }
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
                            searchAll = { query, type, date -> searchAll(query, type, date) },
                            onOpenFile = { email, file -> openFile(email, file) },
                            onRenameFile = { email, file, newName -> renameDriveFile(email, file, newName) },
                            onDeleteFile = { email, file -> trashDriveFile(email, file) },
                            onDownloadFile = { email, file -> downloadToDevice(email, file) }
                        )
                    }
                    is Screen.Trash -> {
                        BackHandler { screen = Screen.Dashboard }
                        TrashScreen(
                            accounts = accounts,
                            onBack = { screen = Screen.Dashboard },
                            loadTrashed = { email, pageToken -> loadTrashed(email, pageToken) },
                            onRestore = { email, file -> restoreTrashed(email, file) },
                            onDeleteForever = { email, file -> deleteForever(email, file) },
                            onEmptyTrash = { email -> emptyTrashFor(email) }
                        )
                    }
                }

                // Destination picker for a move — and for a bulk
                // copy/move (v0.9.0). v0.10.1: files can also move
                // within their own Drive (root → a folder on the same
                // account), so the source account is listed too.
                val movingFile = moveSourceFile
                val bulk = bulkOp
                if ((movingFile != null || bulk != null) && moveDestEmail == null) {
                    val srcEmail = moveSourceEmail
                    // v0.10.0: a folder can only move within its own
                    // Drive, so the picker offers just that account.
                    val isFolderMove = movingFile?.isFolder == true
                    val title = when {
                        isFolderMove -> "Move folder within this Drive?"
                        bulk != null && bulk.isCopy -> "Copy ${bulk.files.size} files to which Drive?"
                        bulk != null -> "Move ${bulk.files.size} files to which Drive?"
                        else -> "Move to which Drive?"
                    }
                    val summary = when {
                        bulk != null -> bulk.files.take(3).joinToString(", ") { it.name } +
                            if (bulk.files.size > 3) "…" else ""
                        else -> movingFile!!.name
                    }
                    AlertDialog(
                        onDismissRequest = {
                            moveSourceFile = null; moveSourceEmail = null; bulkOp = null
                        },
                        containerColor = Color(0xFF1C1C22),
                        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text(
                                    summary,
                                    color = Color(0xFF9A9AA5), fontSize = 14.sp
                                )
                                Spacer(Modifier.height(8.dp))
                                accounts.filter {
                                    if (isFolderMove) it.email == srcEmail
                                    else true // v0.10.1: same Drive too
                                }.forEach { dest ->
                                    TextButton(
                                        onClick = {
                                            moveDestEmail = dest.email
                                            pickStack = listOf(FolderCrumb(null, "My Drive"))
                                            screen = Screen.PickFolder(dest.email)
                                        }
                                    ) {
                                        Text(
                                            if (dest.email == srcEmail) "$srcEmail (this Drive)"
                                            else dest.email,
                                            color = Color(0xFF4A90E2)
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                moveSourceFile = null; moveSourceEmail = null; bulkOp = null
                            }) {
                                Text("Cancel", color = Color(0xFF9A9AA5))
                            }
                        }
                    )
                }

                // Progress while a move/upload (v0.6.0 service, via
                // UploadState) or a file-open download runs. The service
                // also shows the same progress in its notification, so
                // it keeps going when the app is in the background.
                val transfer by UploadState.state.collectAsState()
                LaunchedEffect(transfer?.done) {
                    val t = transfer ?: return@LaunchedEffect
                    if (!t.done) return@LaunchedEffect
                    if (t.error != null) {
                        error = t.error
                    } else if (t.doneMessage != null) {
                        hint = t.doneMessage
                        refreshAll()
                    }
                    UploadState.clear()
                }
                // v0.10.2: one progress dialog for every kind of
                // transfer — file-open downloads, the service's
                // uploads/moves (UploadState), and same-Drive moves
                // (localProgress). Fraction null = spinning bar.
                val opening = openProgress
                val transferActive = transfer?.takeIf { !it.done }
                val progressInfo: Triple<String, String, Float?>? = when {
                    opening != null -> Triple<String, String, Float?>(
                        "Opening file", opening.first, opening.second
                    )
                    localProgress != null -> localProgress
                    transferActive != null -> Triple<String, String, Float?>(
                        transferActive.title, transferActive.label, transferActive.progress
                    )
                    else -> null
                }
                if (progressInfo != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        containerColor = Color(0xFF1C1C22),
                        title = {
                            Text(
                                progressInfo.first,
                                color = Color.White, fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column {
                                Text(progressInfo.second, color = Color(0xFF9A9AA5), fontSize = 14.sp)
                                Spacer(Modifier.height(12.dp))
                                val fraction = progressInfo.third
                                if (fraction != null) {
                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        color = Color(0xFF4A90E2),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        color = Color(0xFF4A90E2),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        },
                        confirmButton = {}
                    )
                }

            }
        }
    }

    /** Returns true when the quota read succeeded and the account was
     *  stored. On failure returns false; the global "Check the Drive
     *  API is enabled" error is only set when [setErrorOnFailure] is
     *  true — the first-time Add Drive sign-in flow, where a quota
     *  failure really is a setup problem. Refresh passes false: a
     *  one-off failure there keeps the last-known numbers instead of
     *  scaring the user (v0.7.1). */
    private suspend fun addAccountFromToken(
        token: String,
        setErrorOnFailure: Boolean = true
    ): Boolean {
        val quota = DriveApi.fetchQuota(token)
        if (quota == null) {
            if (setErrorOnFailure) {
                error = "Could not read Drive quota. Check the Drive API is enabled."
            }
            return false
        }
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
        return true
    }

    /** Refresh re-reads quota for EVERY linked account, by email —
     *  not just the last one signed in. An account whose grant expired
     *  is marked "Needs sign-in" instead of failing the whole refresh.
     *  v0.7.1: a per-account quota failure no longer sets the global
     *  scary error and no longer fails the refresh — that account
     *  keeps its last-known numbers and is named in the hint. The
     *  global error appears only if EVERY account fails to refresh. */
    private fun refreshAll() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            try {
                error = null
                val stored = Store.loadAccounts(this@MainActivity)
                if (stored.isEmpty()) {
                    hint = "No Google account linked yet — tap Add Drive."
                    return@launch
                }
                var ok = 0
                val needsSignIn = mutableListOf<String>()
                val quotaFailed = mutableListOf<String>()
                for (acct in stored) {
                    try {
                        val token = Auth.accessTokenForEmail(this@MainActivity, acct.email)
                        if (token == null) { needsSignIn.add(acct.email); continue }
                        if (addAccountFromToken(token, setErrorOnFailure = false)) {
                            ok++
                        } else {
                            quotaFailed.add(acct.email)
                        }
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
                if (ok == 0 && needsSignIn.isEmpty() && quotaFailed.isNotEmpty()) {
                    error = "Could not read Drive quotas — check the connection and tap Refresh to retry."
                    return@launch
                }
                val parts = mutableListOf<String>()
                if (ok > 0) parts.add("Refreshed $ok account${if (ok == 1) "" else "s"}.")
                if (quotaFailed.isNotEmpty()) {
                    parts.add(
                        "${quotaFailed.joinToString()} couldn't refresh just now — " +
                            "kept last numbers, tap Refresh to retry."
                    )
                }
                if (needsSignIn.isNotEmpty()) {
                    parts.add(
                        if (ok == 0) "Could not refresh — tap Add Drive and pick ${needsSignIn.first()} again."
                        else "${needsSignIn.joinToString()} needs sign-in again — tap Add Drive and pick it."
                    )
                }
                hint = parts.joinToString(" ")
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
        pageToken: String?,
        sort: DriveApi.FileSort = DriveApi.FileSort.NAME_ASC
    ): DriveApi.FilePage? {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (e: UserRecoverableAuthException) {
            reauthEmail = email
            return null
        } catch (_: Exception) { null } ?: return null
        return DriveApi.listFiles(token, folderId, pageToken, sort)
    }

    /** Renames a file on its Drive. Returns true on success. */
    private suspend fun renameDriveFile(
        email: String,
        file: DriveApi.DriveFile,
        newName: String
    ): Boolean {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return false
        return DriveApi.renameFile(token, file.id, newName)
    }

    /** Deletes a file by moving it to trash (recoverable, v0.8.0) —
     *  not a permanent delete. Returns true on success. */
    private suspend fun trashDriveFile(email: String, file: DriveApi.DriveFile): Boolean {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return false
        return DriveApi.trashFile(token, file.id)
    }

    /** One page of trashed files for the Trash screen. */
    private suspend fun loadTrashed(
        email: String,
        pageToken: String?
    ): DriveApi.FilePage? {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return null
        return DriveApi.listTrashed(token, pageToken)
    }

    private suspend fun restoreTrashed(email: String, file: DriveApi.DriveFile): Boolean {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return false
        return DriveApi.restoreFile(token, file.id)
    }

    /** Permanent delete — only from Trash, after a confirmation. */
    private suspend fun deleteForever(email: String, file: DriveApi.DriveFile): Boolean {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return false
        return DriveApi.deleteFile(token, file.id)
    }

    /** Empties one account's trash, then refreshes quotas (trash
     *  counts toward quota, so emptying frees space). */
    private suspend fun emptyTrashFor(email: String): Boolean {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return false
        val ok = DriveApi.emptyTrash(token)
        if (ok) refreshAll()
        return ok
    }

    /** Move (v0.8.0): the service first tries a server-side copy —
     *  files.copy on the destination account (sharing the file to
     *  it first when the accounts differ), verified by size + md5
     *  before the original is deleted. Only when the server route
     *  is unavailable does it fall back to download + re-upload
     *  through the phone, still verified before deleting.
     *  Runs in UploadService (v0.6.0) so it survives the app being
     *  swiped away — progress comes back through UploadState. */
    private fun startMove(destEmail: String, destFolder: FolderCrumb) {
        val srcEmail = moveSourceEmail ?: return
        val file = moveSourceFile ?: return
        if (destEmail == srcEmail) {
            // v0.10.1: moving within the same Drive — files and
            // folders alike — is one metadata call, no bytes, no
            // service needed. (Folders can only ever move this way.)
            moveItemSameAccount(srcEmail, file, destFolder)
            return
        }
        screen = Screen.Browser(srcEmail)
        UploadService.startMove(
            this, srcEmail, file.id, file.name, file.mimeType, file.size,
            destEmail, destFolder.id, destFolder.name
        )
        moveSourceFile = null
        moveSourceEmail = null
        moveDestEmail = null
    }

    /** v0.10.0: create a folder inside the folder on screen. */
    private suspend fun createDriveFolder(
        email: String,
        parentId: String?,
        name: String
    ): Boolean {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return false
        return DriveApi.createFolder(token, parentId, name) != null
    }

    /** v0.10.0: Save to device — the service downloads the file
     *  into the download folder in the background. v0.11.1: the
     *  notification permission is asked for here too, so the
     *  download progress can actually show in the shade. */
    private fun downloadToDevice(email: String, file: DriveApi.DriveFile) {
        withNotificationPermission {
            UploadService.startDownload(
                this, email, file.id, file.name, file.mimeType, file.size
            )
        }
    }

    /** v0.11.1: save every marked file to the device, one after
     *  another, into the same folder a single download uses. */
    private fun downloadBulk(email: String, files: List<DriveApi.DriveFile>) {
        withNotificationPermission {
            UploadService.startBulkDownload(this, email, files)
        }
    }

    /** v0.10.0: re-parent a folder inside its own Drive. v0.10.1:
     *  the same call moves a file inside its own Drive too. The
     *  list is reloaded from the root afterwards, so the moved item
     *  shows up in its new place. */
    private fun moveItemSameAccount(
        email: String,
        file: DriveApi.DriveFile,
        destFolder: FolderCrumb
    ) {
        lifecycleScope.launch {
            // v0.10.2: show the move happening — the dialog is the
            // same one uploads use, with a spinning bar.
            localProgress = Triple("Moving", "Moving ${file.name}…", null)
            val token = try {
                Auth.accessTokenForEmail(this@MainActivity, email)
            } catch (_: Exception) { null }
            if (token == null) {
                localProgress = null
                error = "Could not get access for $email."
                screen = Screen.Browser(email)
                moveSourceFile = null; moveSourceEmail = null; moveDestEmail = null
                return@launch
            }
            var parents = file.parents
            if (parents.isEmpty()) {
                parents = DriveApi.getFile(token, file.id)?.parents ?: emptyList()
            }
            val ok = DriveApi.moveItem(token, file.id, destFolder.id, parents)
            localProgress = null
            browserStack = listOf(FolderCrumb(null, "My Drive"))
            screen = Screen.Browser(email)
            if (ok) {
                hint = "Moved ${file.name} to ${destFolder.name}."
                refreshAll()
            } else {
                error = "Could not move ${file.name}. Try again."
            }
            moveSourceFile = null; moveSourceEmail = null; moveDestEmail = null
        }
    }

    /** v0.9.0: the PickFolder screen's confirm — a bulk op goes to
     *  the service's bulk job, a single move to the old path.
     *  v0.10.1: a bulk move within the same Drive re-parents each
     *  file with one metadata call apiece, right here. */
    private fun startBulkOrMove(destEmail: String, destFolder: FolderCrumb) {
        val bulk = bulkOp
        if (bulk != null) {
            if (!bulk.isCopy && destEmail == bulk.srcEmail) {
                moveBulkSameAccount(bulk.srcEmail, bulk.files, destFolder)
                return
            }
            screen = Screen.Browser(bulk.srcEmail)
            UploadService.startBulk(
                this, bulk.srcEmail, bulk.files,
                destEmail, destFolder.id, destFolder.name, bulk.isCopy
            )
            bulkOp = null
            moveSourceEmail = null
            moveDestEmail = null
            return
        }
        startMove(destEmail, destFolder)
    }

    /** v0.10.1: move a marked batch inside its own Drive — one
     *  metadata call per file, no bytes, no service. One file that
     *  can't move never blocks the rest; the failures are named. */
    private fun moveBulkSameAccount(
        email: String,
        files: List<DriveApi.DriveFile>,
        destFolder: FolderCrumb
    ) {
        lifecycleScope.launch {
            val token = try {
                Auth.accessTokenForEmail(this@MainActivity, email)
            } catch (_: Exception) { null }
            if (token == null) {
                error = "Could not get access for $email."
                screen = Screen.Browser(email)
                bulkOp = null; moveSourceEmail = null; moveDestEmail = null
                return@launch
            }
            var moved = 0
            val failed = mutableListOf<String>()
            // v0.10.2: live progress — "Moving 23 of 50…" with a
            // bar, like Drive/MEGA, instead of a frozen screen.
            // A file that still can't move after the API retries
            // is named at the end, never silently left behind.
            localProgress = Triple("Moving files", "Moving 0 of ${files.size}…", 0f)
            for (file in files) {
                var parents = file.parents
                if (parents.isEmpty()) {
                    parents = DriveApi.getFile(token, file.id)?.parents ?: emptyList()
                }
                if (DriveApi.moveItem(token, file.id, destFolder.id, parents)) moved++
                else failed.add(file.name)
                val done = moved + failed.size
                localProgress = Triple(
                    "Moving files",
                    "Moving $done of ${files.size}…",
                    done.toFloat() / files.size
                )
            }
            localProgress = null
            browserStack = listOf(FolderCrumb(null, "My Drive"))
            screen = Screen.Browser(email)
            refreshAll()
            if (failed.isEmpty()) {
                hint = "Moved $moved ${if (moved == 1) "file" else "files"} to ${destFolder.name}."
            } else {
                error = "Moved $moved, could not move ${failed.size}: " +
                    failed.take(3).joinToString(", ") +
                    if (failed.size > 3) "…" else ""
            }
            bulkOp = null; moveSourceEmail = null; moveDestEmail = null
        }
    }

    /** Open a Drive file in an external app: download it to the app
     *  cache, then fire an ACTION_VIEW chooser with the file's MIME
     *  type — Android asks which player/app to use. DrivePool never
     *  plays media itself. */
    private fun openFile(email: String, file: DriveApi.DriveFile) {
        lifecycleScope.launch {
            openProgress = "Downloading ${file.name}…" to 0f
            val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tmp = File(File(cacheDir, "open"), "${file.id}_$safeName").apply {
                parentFile?.mkdirs()
            }
            try {
                val token = try {
                    Auth.accessTokenForEmail(this@MainActivity, email)
                } catch (_: Exception) { null }
                if (token == null) {
                    error = "Could not get access for $email. Sign in again and retry."
                    return@launch
                }
                val ok = DriveApi.downloadFile(token, file.id, tmp) { done, total ->
                    if (total > 0) {
                        openProgress = "Downloading ${file.name}…" to
                            (done.toFloat() / total)
                    }
                }
                if (!ok) {
                    error = "Download failed. Check the connection and retry."
                    return@launch
                }
                openProgress = null
                val uri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    tmp
                )
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(Intent.createChooser(view, "Open with"))
                } catch (_: ActivityNotFoundException) {
                    error = "No app on your phone can open this file type."
                }
            } finally {
                openProgress = null
            }
        }
    }

    /** Pooled upload: pick a file on the phone, send it to the linked
     *  account with the most free space. Runs in UploadService (v0.6.0)
     *  so it survives the app being swiped away — the service copies
     *  the file, uploads it resumably, and reports back via UploadState. */
    private fun startPooledUpload(uri: Uri) {
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
        val stored = Store.loadAccounts(this)
        if (stored.isEmpty()) {
            error = "No Google account linked yet — tap Add Drive."
            return
        }
        val target = stored.maxByOrNull { it.freeBytes } ?: return
        UploadService.startUpload(this, uri, name, mime, target.email)
    }

    /** Upload into a specific folder on a specific account (v0.8.1 —
     *  the browser's floating upload button). Unlike the pooled
     *  upload, this does NOT pick the freest Drive — it goes exactly
     *  where the user was looking. */
    private fun startFolderUpload(uri: Uri, email: String, folder: FolderCrumb) {
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
        UploadService.startUpload(this, uri, name, mime, email, folder.id, folder.name)
    }

    /** Name search across EVERY linked account, grouped by account in the UI. */
    private suspend fun searchAll(
        query: String,
        type: DriveApi.SearchType = DriveApi.SearchType.ALL,
        date: DriveApi.SearchDate = DriveApi.SearchDate.ANY
    ): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        for (acct in Store.loadAccounts(this)) {
            val token = try {
                Auth.accessTokenForEmail(this, acct.email)
            } catch (_: Exception) { null } ?: continue
            DriveApi.searchFiles(token, query, type, date).forEach { hits.add(SearchHit(acct.email, it)) }
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
            // v0.8.0: the published SHA-256 is verified before install.
            Updater.download(this@MainActivity, info.apkUrl, info.sha256) { state ->
                downloadState = state
            }
        }
    }
}
