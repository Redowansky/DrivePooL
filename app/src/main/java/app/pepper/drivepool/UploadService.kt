// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import app.pepper.drivepool.data.Auth
import app.pepper.drivepool.data.DriveApi
import app.pepper.drivepool.data.Store
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
        const val ACTION_BULK = "app.pepper.drivepool.BULK"
        const val ACTION_BULK_DOWNLOAD = "app.pepper.drivepool.BULK_DOWNLOAD"
        const val ACTION_DOWNLOAD = "app.pepper.drivepool.DOWNLOAD"
        const val ACTION_UPLOAD_FOLDER = "app.pepper.drivepool.UPLOAD_FOLDER"
        const val ACTION_CANCEL = "app.pepper.drivepool.CANCEL"
        private const val CHANNEL_ID = "transfers"
        private const val NOTIF_ID = 1001

        /** [folderId] null = the Drive's root. v0.8.1: the browser's
         *  floating upload button passes the folder you are looking
         *  at, so the file lands there instead of the root. */
        fun startUpload(
            ctx: Context,
            uri: Uri,
            name: String,
            mime: String,
            destEmail: String,
            folderId: String? = null,
            folderName: String = "My Drive"
        ) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("uri", uri)
                putExtra("name", name)
                putExtra("mime", mime)
                putExtra("destEmail", destEmail)
                putExtra("folderId", folderId)
                putExtra("folderName", folderName)
            }
            ctx.startForegroundService(intent)
        }

        /** v0.7.1: create the channel at app start too, so it
         *  exists (and the notification toggle is on) before any
         *  transfer ever runs — not only when the service first
         *  starts. Idempotent; safe to call twice. v0.11.1: the
         *  channel was "Uploads" — downloads show the same
         *  notification now, so it is "Transfers", and the old
         *  channel is removed so it doesn't linger in Settings. */
        fun createNotificationChannel(ctx: Context) {
            val manager = ctx.getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel("uploads")
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Transfers",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "DrivePool file uploads, downloads and moves" }
            )
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

        /** v0.9.0: bulk copy/move — the browser's multi-select.
         *  [files] are the marked files (id, name, mime, size).
         *  [isCopy] false = move (original deleted after a verified
         *  copy, same rule as a single move). */
        fun startBulk(
            ctx: Context,
            srcEmail: String,
            files: List<DriveApi.DriveFile>,
            destEmail: String,
            folderId: String?,
            folderName: String,
            isCopy: Boolean
        ) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_BULK
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("srcEmail", srcEmail)
                putStringArrayListExtra("fileIds", ArrayList(files.map { it.id }))
                putStringArrayListExtra("fileNames", ArrayList(files.map { it.name }))
                putStringArrayListExtra("fileMimes", ArrayList(files.map { it.mimeType }))
                putExtra("fileSizes", files.map { it.size }.toLongArray())
                putExtra("destEmail", destEmail)
                putExtra("folderId", folderId)
                putExtra("folderName", folderName)
                putExtra("isCopy", isCopy)
            }
            ctx.startForegroundService(intent)
        }

        /** v0.10.0: Save to device — downloads a file straight into
         *  the phone's Downloads folder, in this service so it
         *  survives the screen going off like uploads do. */
        fun startDownload(
            ctx: Context,
            email: String,
            fileId: String,
            fileName: String,
            mime: String,
            fileSize: Long = 0
        ) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("srcEmail", email)
                putExtra("fileId", fileId)
                putExtra("fileName", fileName)
                putExtra("mime", mime)
                putExtra("fileSize", fileSize)
            }
            ctx.startForegroundService(intent)
        }

        /** v0.11.1: save every marked file to the device. */
        fun startBulkDownload(
            ctx: Context,
            email: String,
            files: List<DriveApi.DriveFile>
        ) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_BULK_DOWNLOAD
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("srcEmail", email)
                putStringArrayListExtra("fileIds", ArrayList(files.map { it.id }))
                putStringArrayListExtra("fileNames", ArrayList(files.map { it.name }))
                putStringArrayListExtra("fileMimes", ArrayList(files.map { it.mimeType }))
                putExtra("fileSizes", files.map { it.size }.toLongArray())
            }
            ctx.startForegroundService(intent)
        }

        /** v0.10.0: upload a whole local folder — [treeUri] is the
         *  folder the user picked with the system folder picker.
         *  The caller must have taken a persistable read permission
         *  on it first, or the service cannot read it in the
         *  background. */
        fun startUploadFolder(
            ctx: Context,
            treeUri: Uri,
            destEmail: String,
            folderId: String?,
            folderName: String
        ) {
            val intent = Intent(ctx, UploadService::class.java).apply {
                action = ACTION_UPLOAD_FOLDER
                putExtra("jobId", UUID.randomUUID().toString())
                putExtra("treeUri", treeUri)
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
    /** v0.11.1: the notification icon — a download arrow while a
     *  download job runs, the upload arrow for everything else. */
    private var currentJobIsDownload = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
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
        currentJobIsDownload = action == ACTION_DOWNLOAD || action == ACTION_BULK_DOWNLOAD
        startForegroundCompat(NOTIF_ID, buildNotification("Preparing…", "Preparing…", 0f, true))
        currentJob = serviceScope.launch {
            try {
                when (action) {
                    ACTION_UPLOAD -> runUpload(intent, jobId)
                    ACTION_MOVE -> runMove(intent, jobId)
                    ACTION_BULK -> runBulk(intent, jobId)
                    ACTION_DOWNLOAD -> runDownload(intent, jobId)
                    ACTION_BULK_DOWNLOAD -> runBulkDownload(intent, jobId)
                    ACTION_UPLOAD_FOLDER -> runUploadFolder(intent, jobId)
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
        val folderId = intent.getStringExtra("folderId")
        val folderName = intent.getStringExtra("folderName") ?: "My Drive"
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
                parentId = folderId,
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
            val where = if (folderId != null) " to $destEmail ($folderName)" else " to $destEmail"
            done("Uploading", "Uploaded $name$where.")
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
            val dstToken = try {
                Auth.accessTokenForEmail(this, destEmail)
            } catch (_: Exception) { null } ?: return fail("Could not get access for $destEmail.")
            // Fingerprint first — the original is only ever deleted
            // after the copy is verified against it (v0.8.0).
            update("Moving file", "Checking $fileName…", 0.05f)
            val srcMeta = DriveApi.getFile(srcToken, fileId)
                ?: return fail("Could not read $fileName on $srcEmail.")

            // 1. Server-side copy first: the bytes never pass through
            // the phone, so a move costs no data at all (v0.8.0 —
            // Claude's "moves cost data twice" note).
            update("Moving file", "Copying on Google's servers…", 0.3f)
            val copied = tryServerCopy(srcToken, dstToken, srcEmail, destEmail, fileId, folderId)
            if (copied != null) {
                val dstMeta = DriveApi.getFile(dstToken, copied.id)
                if (dstMeta != null && verified(srcMeta, dstMeta)) {
                    DriveApi.deleteFile(srcToken, fileId)
                    done(
                        "Moving file",
                        "Moved $fileName to $destEmail ($folderName) — " +
                            "copied on Google's servers, no data through your phone."
                    )
                    return
                }
                // A copy landed but could not be verified — remove the
                // unverified copy and fall through to the phone path
                // instead of ever deleting the original.
                DriveApi.deleteFile(dstToken, copied.id)
            }

            // 2. Fallback: download + resumable upload, then verify
            // before the original is touched.
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
            update("Moving file", "Verifying the copy…", 0.95f)
            val upMeta = DriveApi.getFile(dstToken, id)
            if (upMeta == null || !verified(srcMeta, upMeta)) {
                return fail(
                    "Upload finished but the copy could not be verified — " +
                        "the original is untouched. Check $destEmail before retrying."
                )
            }
            DriveApi.deleteFile(srcToken, fileId)
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

    /** Bulk copy/move (v0.9.0): the browser's multi-select, one
     *  server-side copy per file, each verified by size + md5 before
     *  the original is deleted (move only). Deliberately NO phone
     *  fallback here — a bulk job of multi-GB files must never
     *  silently download + re-upload through the phone; a file the
     *  server route can't copy is counted as failed and the rest
     *  keep going, so one bad file never blocks the batch. */
    private suspend fun runBulk(intent: Intent, jobId: String) {
        val verb = if (intent.getBooleanExtra("isCopy", true)) "Copy" else "Move"
        val past = if (intent.getBooleanExtra("isCopy", true)) "Copied" else "Moved"
        val srcEmail = intent.getStringExtra("srcEmail") ?: return fail("No source Drive.")
        val destEmail = intent.getStringExtra("destEmail") ?: return fail("No destination Drive.")
        val folderId = intent.getStringExtra("folderId")
        val folderName = intent.getStringExtra("folderName") ?: "My Drive"
        val isCopy = intent.getBooleanExtra("isCopy", true)
        val ids = intent.getStringArrayListExtra("fileIds") ?: return fail("Nothing to $verb.")
        val names = intent.getStringArrayListExtra("fileNames") ?: List(ids.size) { "file" }
        val total = ids.size
        try {
            val srcToken = try {
                Auth.accessTokenForEmail(this, srcEmail)
            } catch (_: Exception) { null } ?: return fail("Could not get access for $srcEmail.")
            val dstToken = try {
                Auth.accessTokenForEmail(this, destEmail)
            } catch (_: Exception) { null } ?: return fail("Could not get access for $destEmail.")
            var ok = 0
            val failed = mutableListOf<String>()
            for (i in ids.indices) {
                val name = names.getOrNull(i) ?: "file"
                update(
                    "${verb}ing files",
                    "$verb ${i + 1} of $total — $name",
                    i.toFloat() / total
                )
                val srcMeta = DriveApi.getFile(srcToken, ids[i])
                val copied = if (srcMeta != null) {
                    tryServerCopy(srcToken, dstToken, srcEmail, destEmail, ids[i], folderId)
                } else null
                val dstMeta = copied?.let { DriveApi.getFile(dstToken, it.id) }
                if (copied != null && dstMeta != null && srcMeta != null && verified(srcMeta, dstMeta)) {
                    if (!isCopy) DriveApi.deleteFile(srcToken, ids[i])
                    ok++
                } else {
                    if (copied != null) DriveApi.deleteFile(dstToken, copied.id)
                    failed.add(name)
                }
            }
            val where = " to $destEmail ($folderName)"
            if (ok == 0) {
                return fail(
                    "$verb failed — none of the $total files could be copied " +
                        "on Google's servers. The originals are untouched."
                )
            }
            val tail = if (failed.isEmpty()) {
                ""
            } else {
                " ${failed.size} failed: ${failed.take(3).joinToString(", ")}" +
                    if (failed.size > 3) "…" else ""
            }
            done(
                "${verb}ing files",
                "$past $ok of $total$where — copied on Google's servers, " +
                    "no data through your phone.$tail"
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled("$verb cancelled.")
            } else {
                fail("$verb failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** A native Google file (Docs/Sheets/Slides…) — it can't be
     *  downloaded with alt=media, so it fails fast with a plain
     *  explanation instead of a cryptic API error. Folders aren't
     *  files, so they don't count. */
    private fun isGoogleDocsFile(mime: String): Boolean =
        mime.startsWith("application/vnd.google-apps.") &&
            mime != "application/vnd.google-apps.folder"

    /** One file saved to the device. [location] is where it went
     *  ("Downloads" or the Settings folder's name); [error] is the
     *  plain reason it failed. Shared by the single download and
     *  the v0.11.1 bulk download, so both land in the same place. */
    private data class SaveOutcome(
        val ok: Boolean,
        val location: String? = null,
        val error: String? = null
    )

    /** "368 MB of 2.99 GB" — the download labels show how much
     *  has actually arrived, not just a percentage. */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(value, units[unit])
    }

    /** Save to device (v0.10.0): streams a file straight into the
     *  phone's download folder — no temp copy on a tight phone.
     *  The folder is the one picked in Settings, or Downloads.
     *  v0.11.2: [knownSize] is the file's size from the Drive
     *  listing. Drive sends no Content-Length for big downloads,
     *  so without it the progress callback reports total = -1 and
     *  the bar sits at 0% forever while bytes are actually flowing
     *  — the listing size is the total instead. */
    private suspend fun saveOneFile(
        token: String,
        fileId: String,
        name: String,
        mime: String,
        knownSize: Long = 0,
        onProgress: (Long, Long) -> Unit
    ): SaveOutcome {
        // v0.11.0: a download folder picked in Settings — write
        // straight into it via SAF instead of MediaStore. The
        // permission was persisted when the folder was picked.
        val customFolder = Store.loadDownloadFolder(this)
        if (customFolder != null) {
            val dir = try {
                DocumentFile.fromTreeUri(this, Uri.parse(customFolder.first))
            } catch (_: Exception) { null }
            if (dir == null || !dir.exists() || !dir.canWrite()) {
                return SaveOutcome(
                    ok = false,
                    error = "The download folder “${customFolder.second}” can't be " +
                        "opened anymore — pick it again in Settings, or " +
                        "switch back to Downloads."
                )
            }
            // A file with the same name is replaced, not duplicated.
            dir.findFile(name)?.delete()
            val file = dir.createFile(mime, name)
                ?: return SaveOutcome(ok = false, error = "Could not create the download.")
            val ok = contentResolver.openOutputStream(file.uri)?.use { out ->
                DriveApi.downloadToStream(token, fileId, out) { doneBytes, total ->
                    onProgress(doneBytes, if (total > 0) total else knownSize)
                }
            } ?: false
            if (!ok) {
                file.delete()
                return SaveOutcome(ok = false, error = "Download failed. Nothing was saved.")
            }
            return SaveOutcome(ok = true, location = customFolder.second)
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val row = contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return SaveOutcome(ok = false, error = "Could not create the download.")
        val ok = contentResolver.openOutputStream(row)?.use { out ->
            DriveApi.downloadToStream(token, fileId, out) { doneBytes, total ->
                onProgress(doneBytes, if (total > 0) total else knownSize)
            }
        } ?: false
        if (!ok) {
            contentResolver.delete(row, null, null)
            return SaveOutcome(ok = false, error = "Download failed. Nothing was saved.")
        }
        return SaveOutcome(ok = true, location = "Downloads")
    }

    private suspend fun runDownload(intent: Intent, jobId: String) {
        val email = intent.getStringExtra("srcEmail") ?: return fail("No source Drive.")
        val fileId = intent.getStringExtra("fileId") ?: return fail("No file to save.")
        val name = intent.getStringExtra("fileName") ?: "file"
        val mime = intent.getStringExtra("mime") ?: "application/octet-stream"
        val size = intent.getLongExtra("fileSize", 0)
        if (isGoogleDocsFile(mime)) {
            return fail(
                "“$name” is a Google Docs file — it can't be downloaded " +
                    "as a file. Open it in the Drive app instead."
            )
        }
        try {
            val token = try {
                Auth.accessTokenForEmail(this, email)
            } catch (_: Exception) { null } ?: return fail("Could not get access for $email.")
            val result = saveOneFile(token, fileId, name, mime, size) { doneBytes, total ->
                val frac = if (total > 0) doneBytes.toFloat() / total else 0f
                val label = if (total > 0) {
                    "Saving $name — ${(frac * 100).toInt()}% · " +
                        "${formatBytes(doneBytes)} of ${formatBytes(total)}"
                } else {
                    "Saving $name — ${formatBytes(doneBytes)}"
                }
                update("Downloading", label, frac)
            }
            if (!result.ok) return fail(result.error ?: "Download failed.")
            done("Downloading", "Saved $name to ${result.location}.")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled("Download cancelled.")
            } else {
                fail("Download failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Bulk download (v0.11.1): every marked file saved to the
     *  device, one after another, into the same folder a single
     *  download uses. The notification shows the overall count
     *  ("Saving 3 of 25…") on one progress bar, and the result
     *  names the files that couldn't be saved. Google Docs files
     *  are skipped up front — they can't be downloaded as files. */
    private suspend fun runBulkDownload(intent: Intent, jobId: String) {
        val email = intent.getStringExtra("srcEmail") ?: return fail("No source Drive.")
        val ids = intent.getStringArrayListExtra("fileIds") ?: return fail("No files to save.")
        val names = intent.getStringArrayListExtra("fileNames")
            ?: List(ids.size) { "file" }
        val mimes = intent.getStringArrayListExtra("fileMimes")
            ?: List(ids.size) { "application/octet-stream" }
        val sizes = intent.getLongArrayExtra("fileSizes")
            ?: LongArray(ids.size) { 0L }
        val total = ids.size
        var saved = 0
        val failed = mutableListOf<String>()
        var location: String? = null
        try {
            val token = try {
                Auth.accessTokenForEmail(this, email)
            } catch (_: Exception) { null } ?: return fail("Could not get access for $email.")
            for (i in ids.indices) {
                val name = names.getOrNull(i) ?: "file"
                val mime = mimes.getOrNull(i) ?: "application/octet-stream"
                if (isGoogleDocsFile(mime)) {
                    failed += "$name (Google Docs file)"
                    continue
                }
                val result = saveOneFile(
                    token, ids[i], name, mime, sizes.getOrNull(i) ?: 0L
                ) { doneBytes, fileTotal ->
                    val fileFrac = if (fileTotal > 0) doneBytes.toFloat() / fileTotal else 0f
                    val overall = (saved + fileFrac) / total
                    val label = if (fileTotal > 0) {
                        "Saving ${saved + 1} of $total — $name — " +
                            "${(fileFrac * 100).toInt()}% · " +
                            "${formatBytes(doneBytes)} of ${formatBytes(fileTotal)}"
                    } else {
                        "Saving ${saved + 1} of $total — $name — " +
                            formatBytes(doneBytes)
                    }
                    update("Downloading", label, overall)
                }
                if (result.ok) {
                    saved++
                    location = result.location
                } else {
                    failed += name
                }
            }
            val tail = if (failed.isEmpty()) {
                ""
            } else {
                " ${failed.size} failed: ${failed.take(3).joinToString(", ")}" +
                    if (failed.size > 3) "…" else ""
            }
            if (saved == 0) return fail("Nothing was saved.$tail")
            done("Downloading", "Saved $saved of $total to ${location ?: "Downloads"}.$tail")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled("Download cancelled.")
            } else {
                fail("Download failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Upload a whole local folder (v0.10.0): walks the picked
     *  folder tree, mirrors subfolders on Drive, and uploads every
     *  file with the resumable uploader. A file that is already in
     *  the destination folder with the same name and size is
     *  skipped, so if the service is killed and Android restarts
     *  the job, it picks up where it left off instead of uploading
     *  duplicates. */
    private suspend fun runUploadFolder(intent: Intent, jobId: String) {
        val treeUri: Uri = intent.getParcelableExtra("treeUri")
            ?: return fail("No folder picked.")
        val destEmail = intent.getStringExtra("destEmail")
            ?: return fail("No destination Drive.")
        val folderId = intent.getStringExtra("folderId")
        val folderName = intent.getStringExtra("folderName") ?: "My Drive"
        val root = DocumentFile.fromTreeUri(this, treeUri)
            ?: return fail("Could not open the picked folder.")
        try {
            val tokenFor: suspend () -> String? = {
                try { Auth.accessTokenForEmail(this, destEmail) } catch (_: Exception) { null }
            }
            tokenFor() ?: return fail("Could not get access for $destEmail.")
            // The skip check compares parents, so the root needs its
            // real id (root-level files don't carry the "root" alias).
            val effectiveParent = folderId ?: tokenFor()?.let { DriveApi.rootId(it) }
            var total = 0
            fun count(dir: DocumentFile) {
                dir.listFiles().forEach { child ->
                    if (child.isDirectory) count(child) else if (child.isFile) total++
                }
            }
            count(root)
            if (total == 0) return fail("The folder is empty — nothing to upload.")
            var doneCount = 0
            var failed = 0
            val tmp = File(cacheDir, "folder-$jobId")
            suspend fun uploadDir(dir: DocumentFile, parentId: String?) {
                dir.listFiles().forEach { child ->
                    if (child.isDirectory) {
                        val tok = tokenFor() ?: run { failed++; return@forEach }
                        val created = DriveApi.createFolder(
                            tok, parentId, child.name ?: "folder"
                        )
                        if (created != null) uploadDir(child, created.id) else failed++
                    } else if (child.isFile) {
                        val name = child.name ?: "file"
                        val mime = child.type ?: "application/octet-stream"
                        val size = child.length()
                        update(
                            "Uploading folder",
                            "Uploading ${doneCount + 1} of $total — $name",
                            doneCount.toFloat() / total
                        )
                        // Already there with the same name + size?
                        // Skip it — this is what makes a restarted
                        // job resume instead of duplicating.
                        val tok = tokenFor()
                        // Inside a subfolder, parentId is already
                        // the created folder's real id; at the top
                        // level it is the resolved root id.
                        val checkParent = parentId ?: effectiveParent
                        val alreadyThere = tok != null && DriveApi.searchFiles(tok, name)
                            .any {
                                it.name == name && it.size == size &&
                                    checkParent in it.parents
                            }
                        if (alreadyThere) {
                            doneCount++
                            return@forEach
                        }
                        var ok = false
                        try {
                            contentResolver.openInputStream(child.uri)?.use { input ->
                                tmp.outputStream().use { out -> input.copyTo(out) }
                            }
                            val id = ResumableUpload.upload(
                                ctx = this,
                                jobId = "$jobId-$doneCount",
                                name = name,
                                mimeType = mime,
                                parentId = parentId,
                                file = tmp,
                                tokenFor = tokenFor,
                                onProgress = { up, tot ->
                                    val frac = if (tot > 0) {
                                        (doneCount + up.toFloat() / tot) / total
                                    } else doneCount.toFloat() / total
                                    update(
                                        "Uploading folder",
                                        "Uploading ${doneCount + 1} of $total — $name",
                                        frac
                                    )
                                }
                            )
                            ok = id != null
                        } catch (_: Exception) {
                            ok = false
                        }
                        tmp.delete()
                        if (ok) doneCount++ else failed++
                    }
                }
            }
            uploadDir(root, folderId)
            val where = " to $destEmail ($folderName)"
            if (doneCount == 0) {
                return fail(
                    "Folder upload failed — none of the $total files " +
                        "could be uploaded."
                )
            }
            done(
                "Uploading folder",
                "Uploaded $doneCount of $total files$where." +
                    if (failed > 0) " $failed failed." else ""
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                notifyCancelled("Folder upload cancelled.")
            } else {
                fail("Folder upload failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Server-side copy (v0.8.0): same account → files.copy directly;
     *  cross-account → share the file with the destination account,
     *  then files.copy with its token. Returns the new file, or null
     *  when the server route is not available (caller falls back to
     *  download + upload through the phone). */
    private suspend fun tryServerCopy(
        srcToken: String,
        dstToken: String,
        srcEmail: String,
        destEmail: String,
        fileId: String,
        folderId: String?
    ): DriveApi.DriveFile? {
        if (srcEmail == destEmail) {
            return DriveApi.copyFile(dstToken, fileId, folderId)
        }
        if (!DriveApi.shareFile(srcToken, fileId, destEmail)) return null
        return DriveApi.copyFile(dstToken, fileId, folderId)
    }

    /** True when [dst] is the same content as [src]: same size, and
     *  the same md5 checksum when both sides have one. Native Google
     *  files have no checksum — size match is the whole check. */
    private fun verified(src: DriveApi.FileMeta, dst: DriveApi.FileMeta): Boolean {
        if (src.size != dst.size) return false
        val s = src.md5Checksum
        val d = dst.md5Checksum
        if (s.isNullOrEmpty() || d.isNullOrEmpty()) return true
        return s == d
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
            .setSmallIcon(
                if (currentJobIsDownload) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_upload
            )
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
