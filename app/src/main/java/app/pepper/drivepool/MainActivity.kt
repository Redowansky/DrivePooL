// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
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
    }

    private var screen by mutableStateOf<Screen>(Screen.Dashboard)
    private var browserStack by mutableStateOf<List<FolderCrumb>>(listOf(FolderCrumb(null, "My Drive")))

    private fun openBrowser(email: String) {
        browserStack = listOf(FolderCrumb(null, "My Drive"))
        screen = Screen.Browser(email)
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
                            onSearch = { screen = Screen.Search }
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
                            loadFiles = { email, folderId, pageToken -> loadFiles(email, folderId, pageToken) }
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
     *  listing failed (token or network); the screen shows Retry. */
    private suspend fun loadFiles(
        email: String,
        folderId: String?,
        pageToken: String?
    ): DriveApi.FilePage? {
        val token = try {
            Auth.accessTokenForEmail(this, email)
        } catch (_: Exception) { null } ?: return null
        return DriveApi.listFiles(token, folderId, pageToken)
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
