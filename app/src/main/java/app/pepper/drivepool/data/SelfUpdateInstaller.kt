// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.pepper.drivepool.UpdateInstallReceiver
import java.io.File

/** Silent self-install (v0.11.3) — the F-Droid way, not the
 *  Device Owner way.
 *
 *  Android 12+ lets an app update ITSELF with no confirmation
 *  sheet, no Device Owner, no root, when every one of these
 *  holds at once:
 *   - the app holds REQUEST_INSTALL_PACKAGES (the "install
 *     unknown apps" grant — DrivePool already has it),
 *   - the manifest declares UPDATE_PACKAGES_WITHOUT_USER_ACTION,
 *   - the session sets USER_ACTION_NOT_REQUIRED,
 *   - the target is the installer itself (a self-update),
 *   - the installed app targets a high enough API (29+ on
 *     Android 12, rising to 34+ on Android 16 — DrivePool
 *     targets 34, exactly the Android 16 bar),
 *   - the new APK is signed with the same key and carries a
 *     higher versionCode — the real security boundary.
 *
 *  One honest bootstrap catch, straight from F-Droid's own
 *  experience: the FIRST self-update can still come back as
 *  STATUS_PENDING_USER_ACTION — Android only trusts the app as
 *  its own installer of record from the second update on. So
 *  the very first silent-updater install may still show the
 *  confirmation screen once; every update after that lands
 *  with no tap at all. The receiver handles that case by
 *  showing the system's own confirm screen (or a notification
 *  when the app isn't on screen), never by failing. */
object SelfUpdateInstaller {
    /** Commit a session install of [file]. Returns false only
     *  when the session couldn't even be created — the caller
     *  then falls back to the ACTION_VIEW installer. The real
     *  outcome (installed / needs a tap / failed) arrives
     *  asynchronously in [UpdateInstallReceiver]. */
    fun install(ctx: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    )
                }
            }
            val sessionId = pi.createSession(params)
            val session = pi.openSession(sessionId)
            try {
                session.openWrite("update.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_STATUS)
                val pending = PendingIntent.getBroadcast(
                    ctx, 7, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            } finally {
                session.close()
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
