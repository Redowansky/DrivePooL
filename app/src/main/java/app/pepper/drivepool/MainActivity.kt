package app.pepper.drivepool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.pepper.drivepool.data.Auth
import app.pepper.drivepool.data.DriveAccount
import app.pepper.drivepool.data.DriveApi
import app.pepper.drivepool.data.Store
import app.pepper.drivepool.ui.DashboardScreen
import app.pepper.drivepool.ui.theme.DrivePoolTheme
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var accounts by mutableStateOf<List<DriveAccount>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var showSettings by mutableStateOf(false)
    private var webClientId by mutableStateOf("")
    private var refreshing by mutableStateOf(false)

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
        webClientId = Store.webClientId(this)
        setContent {
            DrivePoolTheme {
                DashboardScreen(
                    accounts = accounts,
                    error = error,
                    showSettings = showSettings,
                    webClientId = webClientId,
                    onWebClientIdChange = { webClientId = it },
                    onSaveSettings = {
                        showSettings = !showSettings
                        if (!showSettings) Store.setWebClientId(this, webClientId)
                    },
                    onAddDrive = {
                        if (webClientId.isBlank()) {
                            showSettings = true
                            error = "Enter your Web client ID in Settings first (your own Google Cloud project)."
                        } else {
                            error = null
                            signInLauncher.launch(Auth.signInIntent(this, webClientId))
                        }
                    },
                    onRefresh = { refreshAll() },
                    onRemoveAccount = { email ->
                        Store.removeAccount(this, email)
                        accounts = Store.loadAccounts(this)
                    }
                )
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
    }

    private fun refreshAll() {
        if (refreshing) return
        refreshing = true
        error = "Refreshing needs a fresh sign-in per account in v0.1 — tap Add Drive again to re-sync an account."
        refreshing = false
    }
}
