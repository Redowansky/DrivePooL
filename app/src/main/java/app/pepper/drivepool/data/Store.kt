// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import android.content.Context

/** Local-only storage. Access tokens are never stored here — in memory only. */
object Store {
    private const val PREFS = "drivepool"
    private const val KEY_ACCOUNTS = "accounts"
    // v0.11.0: where "Save to device" puts files — a SAF tree URI
    // the user picked in Settings, plus its display name. Null =
    // the phone's Downloads folder (the old behaviour).
    private const val KEY_DOWNLOAD_FOLDER_URI = "download_folder_uri"
    private const val KEY_DOWNLOAD_FOLDER_NAME = "download_folder_name"

    fun loadDownloadFolder(ctx: Context): Pair<String, String>? {
        val uri = prefs(ctx).getString(KEY_DOWNLOAD_FOLDER_URI, null) ?: return null
        val name = prefs(ctx).getString(KEY_DOWNLOAD_FOLDER_NAME, null) ?: return null
        return uri to name
    }

    fun saveDownloadFolder(ctx: Context, uri: String?, name: String?) {
        prefs(ctx).edit()
            .putString(KEY_DOWNLOAD_FOLDER_URI, uri)
            .putString(KEY_DOWNLOAD_FOLDER_NAME, name)
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAccounts(ctx: Context): List<DriveAccount> =
        DriveAccount.listFromJson(prefs(ctx).getString(KEY_ACCOUNTS, "") ?: "")

    fun saveAccounts(ctx: Context, list: List<DriveAccount>) {
        prefs(ctx).edit().putString(KEY_ACCOUNTS, DriveAccount.listToJson(list)).apply()
    }

    fun upsertAccount(ctx: Context, account: DriveAccount) {
        val list = loadAccounts(ctx).toMutableList()
        val idx = list.indexOfFirst { it.email.equals(account.email, ignoreCase = true) }
        if (idx >= 0) list[idx] = account else list.add(account)
        saveAccounts(ctx, list)
    }

    fun removeAccount(ctx: Context, email: String) {
        saveAccounts(ctx, loadAccounts(ctx).filterNot { it.email.equals(email, ignoreCase = true) })
    }
}
