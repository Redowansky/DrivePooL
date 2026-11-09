// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.pepper.drivepool.data.Auth
import app.pepper.drivepool.data.DriveApi
import app.pepper.drivepool.data.ResumeStore
import app.pepper.drivepool.data.ResumableUpload
import app.pepper.drivepool.data.TransferState
import app.pepper.drivepool.data.UploadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Background uploads and moves (v0.6.0).
 *
 * A foreground service, so a transfer survives the app being swiped
 * away, the screen going off, and Doze — Android keeps a foreground
 * service alive and shows its notification. The notification is
 * persistent while a transfer runs: title, progress bar and percentage,
 * plus a Cancel action.
 *
 * The upload itself is resumable (ResumableUpload): if the service is
 * killed anyway, START_REDELIVER_INTENT brings the same job back and
 * the upload continues from the last confirmed byte instead of
 * restarting from zero.
 *
 * One transfer at a time — a second start while one runs is ignored.
 */
class UploadService : Service() {

    companion object {
        const val ACTION_UPLOAD = "app.pepper.drivepool.UPLOAD"
        const val ACTION_MOVE = "app.pepper.drivepool.MOVE"
        const val ACTION_CANCEL = "app.pepper.drivepool.CANCEL"
        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_ID = 1001

        fun startUpload(ctx: Context, uri: Uri, name: String, mime: String, destEmail: String) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("uri", uri)
                putExtra("name", name)
                putExtra("mime", mime)
                putExtra("destEmail", destEmail)
            }
            ctx.startForegroundService(intent)
        }

        fun startMove(
            ctx: Context,
            srcEmail: String,
            fileId: String,
            fileName: String,
            mime: String,
            fileSize: Long,
            destEmail: String,
            folderId: String?,
            folderName: String
        ) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_MOVE
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("srcEmail", srcEmail)
                putExtra("fileId", fileId)
                putExtra("fileName", fileName)
                putExtra("mime", mime)
                putExtra("fileSize", fileSize)
                putExtra("destEmail", destEmail)
                putExtra("folderId", folderId)
                putExtra("folderName", folderName)
            }
            ctx.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private var lastNotifUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "DrivePool file uploads and moves" }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentJob?.cancel()
            UploadState.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (currentJob?.isActive == true) return START_REDELIVER_INTENT // one transfer at a time
        val jobId = intent?.getStringExtra("jobId") ?: return START_NOT_STICKY
        val action = intent.action ?: return START_NOT_STICKY

        // Foreground immediately — Android kills a service that starts
        // work before posting its notification.
        startForegroundCompat(NOTIF_ID, buildNotification("Preparing…", "Preparing…", 0f, true))
        currentJob = serviceScope.launch {
            try {
                when (action) {
                    ACTION_UPLOAD -> runUpload(intent, jobId)
                    ACTION_MOVE -> runMove(intent, jobId)
                }
            } finally {
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runUpload(intent: Intent, jobId: String) {
        val uri: Uri = intent.getParcelableExtra("uri") ?: return fail("Nothing to upload.")
        val name = intent.getStringExtra("name") ?: "upload"
        val mime = intent.getStringExtra("mime") ?: "application/octet-stream"
        val destEmail = intent.getStringExtra("destEmail") ?: return fail("No destination Drive.")
        val tmp = File(cacheDir, "transfer-$jobId")
        update("Uploading", "Preparing $name…", 0f)
        try {
            if (!tmp.exists()) {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: return fail("Could not read that file.")
            }
            val tokenFor: suspend () -> String? = {
                try { Auth.accessTokenForEmail(this, destEmail) } catch (_: Exception) { null }
            }
            val id = ResumableUpload.upload(
                ctx = this,
                jobId = jobId,
                name = name,
                mimeType = mime,
                parentId = null,
                file = tmp,
                tokenFor = tokenFor,
                onProgress = { done, total ->
                    val frac = if (total > 0) done.toFloat() / total else 0f
                    update(
                        "Uploading",
                        "Uploading $name to $destEmail — ${(frac * 100).toInt()}%",
                        frac
                    )
                }
            )
            if (id == null) return fail("Upload failed. Check the connection and retry.")
            tmp.delete()
            done("Uploading", "Uploaded $name to $destEmail.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                tmp.delete()
                ResumeStore.clear(this, jobId)
                notifyCancelled("Upload cancelled.")
            } else {
                fail("Upload failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private suspend fun runMove(intent: Intent, jobId: String) {
        val srcEmail = intent.getStringExtra("srcEmail") ?: return fail("No source Drive.")
        val fileId = intent.getStringExtra("fileId") ?: return fail("No file to move.")
        val fileName = intent.getStringExtra("fileName") ?: "file"
        val mime = intent.getStringExtra("mime") ?: "application/octet-stream"
        val fileSize = intent.getLongExtra("fileSize", 0L)
        val destEmail = intent.getStringExtra("destEmail") ?: return fail("No destination Drive.")
        val folderId = intent.getStringExtra("folderId")
        val folderName = intent.getStringExtra("folderName") ?: "My Drive"
        val tmp = File(cacheDir, "transfer-$jobId")
        try {
            val srcToken = try {
                Auth.accessTokenForEmail(this, srcEmail)
            } catch (_: Exception) { null } ?: return fail("Could not get access for $srcEmail.")
            // Download (0–50% of the bar). A fully-downloaded temp file
            // from a killed earlier run is reused, not re-downloaded.
            if (tmp.exists() && fileSize > 0 && tmp.length() == fileSize) {
                update("Moving file", "Downloaded — uploading $fileName…", 0.5f)
            } else {
                update("Moving file", "Downloading $fileName — 0%", 0f)
                val ok = DriveApi.downloadFile(srcToken, fileId, tmp) { done, total ->
                    val frac = if (total > 0) (done.toFloat() / total) * 0.5f else 0f
                    update(
                        "Moving file",
                        "Downloading $fileName — ${(frac * 2 * 100).toInt()}%",
                        frac
                    )
                }
                if (!ok) return fail("Download failed. The original is untouched.")
            }
            val dstTokenFor: suspend () -> String? = {
                try { Auth.accessTokenForEmail(this, destEmail) } catch (_: Exception) { null }
            }
            val id = ResumableUpload.upload(
                ctx = this,
                jobId = jobId,
                name = fileName,
                mimeType = mime,
                parentId = folderId,
                file = tmp,
                tokenFor = dstTokenFor,
                onProgress = { done, total ->
                    val frac = if (total > 0) 0.5f + (done.toFloat() / total) * 0.5f else 0.5f
                    update(
                        "Moving file",
                        "Uploading to $destEmail — ${(frac * 100).toInt()}%",
                        frac
                    )
                }
            )
            if (id == null) return fail("Upload failed. The original is untouched.")
            update("Moving file", "Removing the original…", 1f)
            val srcToken2 = try {
                Auth.accessTokenForEmail(this, srcEmail)
            } catch (_: Exception) { null }
            if (srcToken2 != null) DriveApi.deleteFile(srcToken2, fileId)
            tmp.delete()
            done("Moving file", "Moved $fileName to $destEmail ($folderName).")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                tmp.delete()
                ResumeStore.clear(this, jobId)
                notifyCancelled("Move cancelled.")
            } else {
                fail("Move failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Progress update: shared state for the in-app dialog + notification. */
    private fun update(title: String, label: String, progress: Float) {
        UploadState.update(TransferState(title, label, progress.coerceIn(0f, 1f)))
        // Notification updates are cheap but not free — throttle to
        // at most one every 400 ms (a 1% change also forces one).
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < 400 && progress < 1f) return
        lastNotifUpdate = now
        notify(buildNotification(title, label, progress, true))
    }

    private fun done(title: String, message: String) {
        UploadState.update(
            TransferState(title, message, 1f, done = true, doneMessage = message)
        )
        notify(buildNotification(title, message, 1f, false))
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun fail(message: String) {
        UploadState.update(
            TransferState("Transfer", message, 0f, done = true, error = message)
        )
        notify(buildNotification("Transfer failed", message, 0f, false))
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun notifyCancelled(message: String) {
        UploadState.clear()
        notify(buildNotification("Transfer cancelled", message, 0f, false))
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Float,
        ongoing: Boolean
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this, 0,
                Intent(this, UploadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder
                .setProgress(100, (progress * 100).toInt(), progress <= 0f)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        } else {
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                id, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            super.startForeground(id, notification)
        }
    }
}
