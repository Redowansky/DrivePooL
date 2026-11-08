package app.pepper.drivepool.data

import android.content.Context

/** Local-only storage. Access tokens are never stored here — in memory only. */
object Store {
    private const val PREFS = "drivepool"
    private const val KEY_ACCOUNTS = "accounts"

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
