// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pepper.drivepool.data.SilentUpdater

/** Fires after DrivePool replaces itself with a newer build
 *  (v0.11.3): the staged file is stale now, so clear it, and post
 *  the "updated" proof on the Silent Updater channel — the same
 *  visible confirmation LockShield's receiver posts. */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        SilentUpdater.clearStaged(ctx)
        SilentUpdater.notifyUpdated(ctx)
    }
}
