// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pepper.drivepool.BuildConfig
import app.pepper.drivepool.MainActivity
import java.util.concurrent.TimeUnit

/** The silent updater (v0.11.3) — the F-Droid way, not the
 *  Device Owner way.
 *
 *  A background worker checks version.json every 6 hours,
 *  downloads a new APK by itself (SHA-256 verified, same as the
 *  banner download), then installs it with a PackageInstaller
 *  session — no tap at all when Android allows it. The
 *  "Silent Updater" channel carries the exceptions: a "tap to
 *  install" note when Android still wants one confirmation, a
 *  failure note with a retry tap, and "DrivePool updated" once
 *  the new build lands.
 *
 *  Why no tap is possible here at all: Android 12+ lets an app
 *  update ITSELF silently when it holds REQUEST_INSTALL_PACKAGES,
 *  declares UPDATE_PACKAGES_WITHOUT_USER_ACTION, marks the
 *  session USER_ACTION_NOT_REQUIRED, and targets a high enough
 *  API (34+ on Android 16 — DrivePool targets exactly 34).
 *  The same signing key + higher versionCode is the real
 *  security boundary. LockShield's silence comes from Device
 *  Owner instead; DrivePool doesn't need that role for this.
 *
 *  One honest bootstrap catch: the FIRST self-update can still
 *  come back as STATUS_PENDING_USER_ACTION — Android only
 *  trusts the app as its own installer of record from the
 *  second update on. So the very first silent-updater install
 *  may still show the confirmation screen once; every update
 *  after that lands with no tap. The receiver handles that
 *  case instead of failing.
 *
 *  The worker is fail-open and cheap: it checks first and only
 *  downloads when a newer versionCode exists, and it never
 *  re-downloads a version it already staged. */
object SilentUpdater {
    const val CHANNEL_ID = "silent_updater"
    private const val CHANNEL_NAME = "Silent Updater"
    private const val WORK_NAME = "drivepool-silent-updater"
    private const val NOTIF_READY_ID = 2001
    private const val NOTIF_UPDATED_ID = 2002
    const val ACTION_INSTALL_STAGED = "app.pepper.drivepool.INSTALL_STAGED_UPDATE"

    private const val PREFS = "silent_updater"
    private const val KEY_STAGED_CODE = "staged_code"
    private const val KEY_STAGED_NAME = "staged_name"

    fun createChannel(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "DrivePool background updates — new versions download " +
                        "and install themselves; this channel only speaks " +
                        "up when one needs a tap or fails"
            }
        )
    }

    /** Every 6 hours, when there's a connection. KEEP means
     *  calling this on every launch is safe — it never doubles up. */
    fun schedule(ctx: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** The staged version, if a downloaded update is waiting. */
    fun stagedVersion(ctx: Context): Pair<Int, String>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getInt(KEY_STAGED_CODE, 0)
        if (code <= 0) return null
        return code to prefs.getString(KEY_STAGED_NAME, "")!!
    }

    private fun saveStaged(ctx: Context, code: Int, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STAGED_CODE, code)
            .putString(KEY_STAGED_NAME, name)
            .apply()
    }

    fun clearStaged(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_STAGED_CODE).remove(KEY_STAGED_NAME).apply()
        try { Updater.stagedFile(ctx).delete() } catch (_: Exception) {}
    }

    /** "DrivePool 0.11.3 ready — tap to install." */
    fun notifyReady(ctx: Context, versionName: String) {
        createChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_INSTALL_STAGED)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DrivePool $versionName ready")
            .setContentText("Downloaded in the background — tap to install.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_READY_ID, notification)
    }

    /** "DrivePool updated to vX" — posted after the install lands. */
    fun notifyUpdated(ctx: Context) {
        createChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("DrivePool updated")
            .setContentText("Now running v${BuildConfig.VERSION_NAME}.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIF_READY_ID)
        manager.notify(NOTIF_UPDATED_ID, notification)
    }

    /** The background check: version.json first, download only
     *  when a newer build exists, then install it silently —
     *  no tap when Android allows it. When the session can't
     *  even be created, fall back to the "tap to install" note
     *  instead of failing quietly. */
    class UpdateCheckWorker(
        appContext: Context,
        params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val remote = Updater.check() ?: return Result.success()
            if (remote.versionCode <= BuildConfig.VERSION_CODE) return Result.success()
            val staged = stagedVersion(applicationContext)
            if (staged?.first == remote.versionCode &&
                Updater.stagedFile(applicationContext).exists()
            ) {
                // Already downloaded — install it now.
                installOrNotify(remote.versionName)
                return Result.success()
            }
            val ok = Updater.downloadToFile(
                applicationContext, remote.apkUrl, remote.sha256
            )
            if (!ok) return Result.retry()
            saveStaged(applicationContext, remote.versionCode, remote.versionName)
            installOrNotify(remote.versionName)
            return Result.success()
        }

        private fun installOrNotify(versionName: String) {
            val committed = SelfUpdateInstaller.install(
                applicationContext, Updater.stagedFile(applicationContext)
            )
            if (!committed) notifyReady(applicationContext, versionName)
        }
    }
}
