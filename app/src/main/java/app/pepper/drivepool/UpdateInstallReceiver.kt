// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import app.pepper.drivepool.data.SilentUpdater

/** The answer to a silent self-install session (v0.11.3).
 *  Three outcomes:
 *   - STATUS_SUCCESS: nothing to do — the installer ends this
 *     process to replace it, and the next launch is the new
 *     build (UpdateInstalledReceiver posts "updated" then).
 *   - STATUS_PENDING_USER_ACTION: Android still wants one tap
 *     — the first self-update, or a ROM that insists. Show the
 *     system's own confirm screen if the app is on screen; if
 *     starting it from here fails (background), post a
 *     notification whose tap re-runs the install from the app.
 *   - Anything else: the install failed — tell the user, with
 *     a tap to retry from the staged file. */
class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STATUS = "app.pepper.drivepool.UPDATE_INSTALL_STATUS"
        private const val NOTIF_INSTALL_ID = 2003
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                // Nothing to do — the process is being replaced.
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirm)
                        return
                    } catch (_: Exception) {
                        // Below: fall through to the notification.
                    }
                }
                notifyTapToInstall(ctx)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                notifyFailed(ctx, message)
            }
        }
    }

    /** The install needs one tap and the app isn't on screen —
     *  a notification whose tap opens the app to install the
     *  staged update. */
    private fun notifyTapToInstall(ctx: Context) {
        SilentUpdater.createChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(SilentUpdater.ACTION_INSTALL_STAGED)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, SilentUpdater.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DrivePool update ready")
            .setContentText("Tap to install — Android asked for one confirmation.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_INSTALL_ID, notification)
    }

    private fun notifyFailed(ctx: Context, detail: String?) {
        SilentUpdater.createChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(SilentUpdater.ACTION_INSTALL_STAGED)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx, 3, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (detail.isNullOrBlank()) {
            "The update couldn't install — tap to try again."
        } else {
            "The update couldn't install ($detail) — tap to try again."
        }
        val notification = NotificationCompat.Builder(ctx, SilentUpdater.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DrivePool update failed")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_INSTALL_ID, notification)
    }
}
