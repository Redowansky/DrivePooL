// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import android.content.Context
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Drive resumable uploads — the reason a background upload can survive
 * being killed and pick up where it stopped instead of restarting.
 *
 * Protocol: create a session (POST uploadType=resumable) -> the server
 * returns a session URI -> PUT the file in 1 MB chunks with
 * Content-Range. A 308 means "got it so far, here's how much" — the
 * Range header says exactly where to continue. On a network failure
 * the session is queried first, then uploading continues from the
 * confirmed byte offset. Session + offset are persisted in
 * ResumeStore after every chunk, so even a killed service resumes.
 *
 * The old single-shot multipart upload (DriveApi.uploadFile) is gone:
 * it could not resume and could not report progress reliably.
 */
object ResumableUpload {
    private const val CHUNK = 1024 * 1024L // 1 MB, a multiple of 256 KB as Drive requires
    private const val MAX_RETRIES = 5

    // No redirect following: Drive's 308 "Resume Incomplete" is a
    // status to act on, not a redirect — following it would break the flow.
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    private class SliceBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val mime: String,
        private val onWrote: (Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = mime.toMediaTypeOrNull()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(64 * 1024)
                var remaining = length
                var wrote = 0L
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    remaining -= n
                    wrote += n
                    onWrote(wrote)
                }
            }
        }
    }

    /** Uploads [file] into a Drive folder (null parent = My Drive root).
     *  Returns the new file id, or null on failure. [jobId] keys the
     *  persisted session so a killed upload resumes instead of restarting.
     *  [tokenFor] re-fetches the access token when the server says 401. */
    suspend fun upload(
        ctx: Context,
        jobId: String,
        name: String,
        mimeType: String,
        parentId: String?,
        file: File,
        tokenFor: suspend () -> String?,
        onProgress: (uploaded: Long, total: Long) -> Unit
    ): String? {
        val total = file.length()
        var token = tokenFor() ?: return null

        // Resume an earlier session if one was persisted for this job.
        var sessionUri = ResumeStore.load(ctx, jobId)?.sessionUri
        var uploaded = ResumeStore.load(ctx, jobId)?.uploadedBytes ?: 0L
        if (sessionUri != null) {
            val confirmed = queryStatus(sessionUri, total, token)
            when (confirmed) {
                null -> { // session gone (expired) — start over
                    sessionUri = null
                    uploaded = 0L
                    ResumeStore.clear(ctx, jobId)
                }
                -1L -> return null // already complete, but we never got the id — re-upload
                else -> uploaded = confirmed
            }
        }

        if (sessionUri == null) {
            sessionUri = createSession(name, mimeType, parentId, total, token) ?: return null
            ResumeStore.save(ctx, jobId, ResumeStore.Session(sessionUri, 0L, total))
        }

        var retries = 0
        while (uploaded < total) {
            val end = minOf(uploaded + CHUNK, total) - 1
            val chunkLen = end - uploaded + 1
            var chunkWrote = 0L
            val body = SliceBody(file, uploaded, chunkLen, mimeType) { w ->
                chunkWrote = w
                onProgress(uploaded + w, total)
            }
            val req = Request.Builder()
                .url(sessionUri)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Range", "bytes $uploaded-$end/$total")
                .put(body).build()
            try {
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 201 -> {
                            val id = resp.body?.string()?.let {
                                JSONObject(it).optString("id")
                            }.takeIf { !it.isNullOrEmpty() }
                            ResumeStore.clear(ctx, jobId)
                            onProgress(total, total)
                            return id
                        }
                        308 -> {
                            val range = resp.header("Range") // "bytes=0-1048575"
                            val got = range
                                ?.substringAfter("=", "")
                                ?.substringAfter("-", "")
                                ?.toLongOrNull()
                                ?.plus(1) ?: (uploaded + chunkWrote)
                            uploaded = got
                            ResumeStore.save(
                                ctx, jobId,
                                ResumeStore.Session(sessionUri, uploaded, total)
                            )
                            onProgress(uploaded, total)
                            retries = 0
                        }
                        401 -> {
                            token = tokenFor() ?: return null
                            retries++
                            if (retries > MAX_RETRIES) return null
                        }
                        // v0.8.0: rate limits and server errors get a
                        // backoff retry (Retry-After when sent) instead
                        // of failing the whole upload outright.
                        429, in 500..599 -> {
                            retries++
                            if (retries > MAX_RETRIES) return null
                            delay(backoffDelay(retries, resp.header("Retry-After")))
                        }
                        else -> return null
                    }
                }
            } catch (_: IOException) {
                // Network dropped mid-chunk: ask the server what landed.
                delay(2000)
                val confirmed = queryStatus(sessionUri, total, token)
                if (confirmed == null) {
                    retries++
                    if (retries > MAX_RETRIES) return null
                } else if (confirmed == -1L) {
                    return null
                } else {
                    uploaded = confirmed
                    ResumeStore.save(
                        ctx, jobId,
                        ResumeStore.Session(sessionUri, uploaded, total)
                    )
                    onProgress(uploaded, total)
                    retries = 0
                }
            }
        }
        return null
    }

    private fun createSession(
        name: String,
        mimeType: String,
        parentId: String?,
        total: Long,
        token: String
    ): String? {
        val meta = JSONObject().apply {
            put("name", name)
            put("mimeType", mimeType)
            if (parentId != null) put("parents", org.json.JSONArray().put(parentId))
        }
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .addHeader("X-Upload-Content-Type", mimeType)
            .addHeader("X-Upload-Content-Length", total.toString())
            .post(RequestBody.create("application/json; charset=UTF-8".toMediaTypeOrNull(), meta.toString()))
            .build()
        // v0.8.0: creating the session is retried too — a 429/5xx or
        // a network blip here used to fail the upload before it began.
        var attempt = 0
        while (true) {
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> return resp.header("Location")
                        resp.code == 429 || resp.code in 500..599 -> {
                            if (attempt >= 2) return null
                        }
                        else -> return null
                    }
                    attempt++
                    Thread.sleep(backoffDelay(attempt, resp.header("Retry-After")))
                }
            } catch (_: IOException) {
                if (attempt >= 2) return null
                attempt++
                Thread.sleep(backoffDelay(attempt, null))
            }
        }
    }

    /** Backoff for one retry: the server's Retry-After (seconds,
     *  capped at 60) when it sends one, else 2s, 4s, 8s, 16s… */
    private fun backoffDelay(retries: Int, retryAfter: String?): Long {
        val after = retryAfter?.toLongOrNull()?.coerceIn(1, 60)
        return if (after != null) after * 1000L else (1000L shl minOf(retries, 5))
    }

    /** Asks the server how many bytes of this session landed.
     *  Returns the confirmed count, -1 if the upload already finished,
     *  or null if the session is unknown/dead. */
    private fun queryStatus(sessionUri: String, total: Long, token: String): Long? {
        val req = Request.Builder()
            .url(sessionUri)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Range", "bytes */$total")
            .put(RequestBody.create(null, ByteArray(0)))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200, 201 -> -1L
                    308 -> resp.header("Range")
                        ?.substringAfter("=", "")
                        ?.substringAfter("-", "")
                        ?.toLongOrNull()
                        ?.plus(1) ?: 0L
                    else -> null
                }
            }
        } catch (_: IOException) { null }
    }
}
