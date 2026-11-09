// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DriveApi {
    private val client = OkHttpClient()

    data class Quota(val email: String, val used: Long, val limit: Long)
    data class FileStats(val count: Long, val totalBytes: Long)

    data class DriveFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val modifiedTime: String
    ) {
        val isFolder: Boolean
            get() = mimeType == "application/vnd.google-apps.folder"
    }

    /** Full fingerprint of one file — size + md5 checksum, for
     *  verifying a move before the original is deleted (v0.8.0).
     *  Native Google files (Docs/Sheets/Slides) have no md5Checksum
     *  and size 0 — for those, size match is the whole check. */
    data class FileMeta(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val md5Checksum: String?,
        val trashed: Boolean
    )

    data class FilePage(val files: List<DriveFile>, val nextPageToken: String?)

    /** Browser sort — orderBy values are Drive API values. */
    enum class FileSort(val label: String, val orderBy: String) {
        NAME_ASC("Name A–Z", "folder,name"),
        NAME_DESC("Name Z–A", "folder,name desc"),
        NEWEST("Newest first", "modifiedTime desc"),
        OLDEST("Oldest first", "modifiedTime"),
        LARGEST("Largest first", "quotaBytesUsed desc"),
        SMALLEST("Smallest first", "quotaBytesUsed")
    }

    /** Search type filter — the queryPart goes straight into the
     *  Drive search query. Drive has no size filter, so there is
     *  none here either. */
    enum class SearchType(val label: String, val queryPart: String?) {
        ALL("All", null),
        FILES("Files", "mimeType != 'application/vnd.google-apps.folder'"),
        FOLDERS("Folders", "mimeType = 'application/vnd.google-apps.folder'"),
        IMAGES("Images", "mimeType contains 'image/'"),
        VIDEOS("Videos", "mimeType contains 'video/'"),
        AUDIO("Audio", "mimeType contains 'audio/'"),
        DOCS(
            "Docs",
            "(mimeType contains 'application/vnd.google-apps.' or " +
                "mimeType = 'application/pdf' or " +
                "mimeType = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' or " +
                "mimeType = 'application/msword')"
        )
    }

    enum class SearchDate(val label: String) {
        ANY("Any time"),
        TODAY("Today"),
        WEEK("This week"),
        MONTH("This month")
    }

    private fun parseFile(f: JSONObject): DriveFile = DriveFile(
        id = f.optString("id"),
        name = f.optString("name"),
        mimeType = f.optString("mimeType"),
        size = f.optString("size", "0").toLongOrNull() ?: 0L,
        modifiedTime = f.optString("modifiedTime")
    )

    private fun parseMeta(f: JSONObject): FileMeta = FileMeta(
        id = f.optString("id"),
        name = f.optString("name"),
        mimeType = f.optString("mimeType"),
        size = f.optString("size", "0").toLongOrNull() ?: 0L,
        md5Checksum = f.optString("md5Checksum", "").takeIf { it.isNotEmpty() },
        trashed = f.optBoolean("trashed", false)
    )

    /** One API call with retries on 429 / 5xx (v0.8.0 — Claude's
     *  reliability note). Backoff is 1s, 2s, 4s, or the server's
     *  Retry-After header when it sends one. Returns the parsed
     *  body, or null when the call never succeeded. Auth failures
     *  (401/403) and bad requests return null immediately —
     *  retrying those never helps. */
    private suspend fun callApi(
        token: String,
        method: String,
        url: String,
        body: String? = null
    ): JSONObject? {
        var attempt = 0
        while (true) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                when (method) {
                    "POST" -> builder.post(
                        (body ?: "{}").toRequestBody(
                            "application/json; charset=UTF-8".toMediaTypeOrNull()
                        )
                    )
                    "PATCH" -> builder.patch(
                        (body ?: "{}").toRequestBody(
                            "application/json; charset=UTF-8".toMediaTypeOrNull()
                        )
                    )
                    "DELETE" -> builder.delete()
                    else -> builder.get()
                }
                client.newCall(builder.build()).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            val text = resp.body?.string().orEmpty()
                            return if (text.isBlank()) JSONObject() else JSONObject(text)
                        }
                        resp.code == 429 || resp.code in 500..599 -> {
                            if (attempt >= 2) return null
                            delay(retryDelay(resp, attempt))
                            attempt++
                        }
                        else -> return null
                    }
                }
            } catch (_: IOException) {
                if (attempt >= 2) return null
                delay(1000L shl attempt)
                attempt++
            }
        }
    }

    private fun retryDelay(resp: okhttp3.Response, attempt: Int): Long {
        val after = resp.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 30)
        return if (after != null) after * 1000L else (1000L shl attempt)
    }

    private suspend fun get(token: String, url: String): JSONObject? =
        callApi(token, "GET", url)

    /** One page (100) of a folder's children, in the chosen sort order. */
    suspend fun listFiles(
        token: String,
        folderId: String?,
        pageToken: String?,
        sort: FileSort = FileSort.NAME_ASC
    ): FilePage? =
        withContext(Dispatchers.IO) {
            val parent = folderId ?: "root"
            val q = "'$parent' in parents and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?pageSize=100&orderBy=${java.net.URLEncoder.encode(sort.orderBy, "UTF-8")}" +
                "&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime)" +
                "&q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val json = get(token, url) ?: return@withContext null
            val arr = json.optJSONArray("files") ?: return@withContext null
            val files = (0 until arr.length()).map { parseFile(arr.getJSONObject(it)) }
            FilePage(files, json.optString("nextPageToken", "").takeIf { it.isNotEmpty() })
        }

    /** Streams a file's bytes into [dest] (used for the move fallback
     *  and for opening files). Returns false on failure. */
    suspend fun downloadFile(
        token: String,
        fileId: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $token").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                val total = body.contentLength()
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    /** Permanently deletes a file. Used only after a move has been
     *  verified (v0.8.0) or when the user confirms "Delete forever"
     *  in Trash — everyday deletes go to trash instead (trashFile). */
    suspend fun deleteFile(token: String, fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            callApi(token, "DELETE", "https://www.googleapis.com/drive/v3/files/$fileId") != null
        }

    /** Full fingerprint of one file (size + md5Checksum). */
    suspend fun getFile(token: String, fileId: String): FileMeta? =
        withContext(Dispatchers.IO) {
            val json = get(
                token,
                "https://www.googleapis.com/drive/v3/files/$fileId" +
                    "?fields=id,name,mimeType,size,md5Checksum,trashed"
            ) ?: return@withContext null
            parseMeta(json)
        }

    /** Shares a file with another Google account (writer, no email
     *  notification) so that account can copy it — the first step of
     *  a server-side cross-account move (v0.8.0). */
    suspend fun shareFile(token: String, fileId: String, targetEmail: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("type", "user")
                put("role", "writer")
                put("emailAddress", targetEmail)
            }.toString()
            callApi(
                token, "POST",
                "https://www.googleapis.com/drive/v3/files/$fileId/permissions" +
                    "?sendNotificationEmail=false",
                body
            ) != null
        }

    /** Server-side copy of a file into [parentId] (null = My Drive
     *  root) on the account behind [token]. The copy is owned by
     *  that account and its bytes never pass through the phone.
     *  Returns the new file, or null on failure. */
    suspend fun copyFile(token: String, fileId: String, parentId: String?): DriveFile? =
        withContext(Dispatchers.IO) {
            val body = if (parentId != null) {
                JSONObject().apply {
                    put("parents", org.json.JSONArray().put(parentId))
                }.toString()
            } else "{}"
            val json = callApi(
                token, "POST",
                "https://www.googleapis.com/drive/v3/files/$fileId/copy" +
                    "?fields=id,name,mimeType,size,modifiedTime",
                body
            ) ?: return@withContext null
            parseFile(json)
        }

    /** Renames a file. */
    suspend fun renameFile(token: String, fileId: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("name", newName) }.toString()
            callApi(
                token, "PATCH",
                "https://www.googleapis.com/drive/v3/files/$fileId",
                body
            ) != null
        }

    /** Moves a file to trash (recoverable — restore from Trash).
     *  This is what everyday deletes do now (v0.8.0). */
    suspend fun trashFile(token: String, fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("trashed", true) }.toString()
            callApi(
                token, "PATCH",
                "https://www.googleapis.com/drive/v3/files/$fileId",
                body
            ) != null
        }

    /** Restores a trashed file. */
    suspend fun restoreFile(token: String, fileId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("trashed", false) }.toString()
            callApi(
                token, "PATCH",
                "https://www.googleapis.com/drive/v3/files/$fileId",
                body
            ) != null
        }

    /** One page (100) of trashed files, newest first. */
    suspend fun listTrashed(token: String, pageToken: String?): FilePage? =
        withContext(Dispatchers.IO) {
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?pageSize=100&orderBy=modifiedTime%20desc" +
                "&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime)" +
                "&q=${java.net.URLEncoder.encode("trashed = true", "UTF-8")}" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val json = get(token, url) ?: return@withContext null
            val arr = json.optJSONArray("files") ?: return@withContext null
            val files = (0 until arr.length()).map { parseFile(arr.getJSONObject(it)) }
            FilePage(files, json.optString("nextPageToken", "").takeIf { it.isNotEmpty() })
        }

    /** Empties the whole trash for one account. */
    suspend fun emptyTrash(token: String): Boolean =
        withContext(Dispatchers.IO) {
            callApi(token, "DELETE", "https://www.googleapis.com/drive/v3/files/trash") != null
        }

    /** Name search inside one account (first 100 hits, folders + files),
     *  with type and date filters (v0.8.0). */
    suspend fun searchFiles(
        token: String,
        query: String,
        type: SearchType = SearchType.ALL,
        date: SearchDate = SearchDate.ANY
    ): List<DriveFile> =
        withContext(Dispatchers.IO) {
            val safe = query.replace("\\", "\\\\").replace("'", "\\'")
            val parts = mutableListOf("name contains '$safe'")
            type.queryPart?.let { parts.add(it) }
            dateCutoff(date)?.let { parts.add("modifiedTime >= '$it'") }
            parts.add("trashed = false")
            val q = parts.joinToString(" and ")
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?pageSize=100&orderBy=folder,name" +
                "&fields=files(id,name,mimeType,size,modifiedTime)" +
                "&q=${java.net.URLEncoder.encode(q, "UTF-8")}"
            val json = get(token, url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("files") ?: return@withContext emptyList()
            (0 until arr.length()).map { parseFile(arr.getJSONObject(it)) }
        }

    /** Start of the date filter window, in the device timezone
     *  (v0.8.0 — was hardcoded to Asia/Dhaka). */
    private fun dateCutoff(date: SearchDate): String? {
        val cal = Calendar.getInstance()
        when (date) {
            SearchDate.ANY -> return null
            SearchDate.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            }
            SearchDate.WEEK -> cal.add(Calendar.DAY_OF_YEAR, -7)
            SearchDate.MONTH -> cal.add(Calendar.DAY_OF_YEAR, -30)
        }
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(cal.time)
    }

    /** v0.7.1: retry once after a short delay before giving up.
     *  A single failed call (network hiccup, Google 429/500, token
     *  blip) used to surface as the scary "Check the Drive API is
     *  enabled" error; most of those succeed on the second try.
     *  v0.8.0: the retry now lives inside callApi (backoff +
     *  Retry-After), so this is a single call. */
    suspend fun fetchQuota(token: String): Quota? = withContext(Dispatchers.IO) {
        val json = get(
            token,
            "https://www.googleapis.com/drive/v3/about" +
                "?fields=user(emailAddress),storageQuota(limit,usage)"
        ) ?: return@withContext null
        parseQuota(json)
    }

    private fun parseQuota(json: JSONObject): Quota? {
        val email = json.optJSONObject("user")?.optString("emailAddress") ?: return null
        val q = json.optJSONObject("storageQuota") ?: return null
        val limit = q.optString("limit", "0").toLongOrNull() ?: 0L
        val used = q.optString("usage", "0").toLongOrNull() ?: 0L
        return Quota(email, used, limit)
    }

    suspend fun fetchFileStats(token: String, todayOnly: Boolean): FileStats =
        withContext(Dispatchers.IO) {
            var count = 0L
            var bytes = 0L
            var pageToken: String? = null
            var pages = 0
            val q = if (todayOnly) {
                // "Today" in the device timezone (v0.8.0 — was
                // hardcoded to Asia/Dhaka).
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }.format(Date())
                "modifiedTime >= '${today}T00:00:00' and trashed = false"
            } else "trashed = false"
            val encodedQ = java.net.URLEncoder.encode(q, "UTF-8")
            while (pages < 10) {
                val url = "https://www.googleapis.com/drive/v3/files" +
                    "?pageSize=1000&fields=nextPageToken,files(id,size,modifiedTime)" +
                    "&q=$encodedQ" +
                    (pageToken?.let { "&pageToken=$it" } ?: "")
                val json = get(token, url) ?: break
                val files = json.optJSONArray("files") ?: break
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    count++
                    bytes += f.optString("size", "0").toLongOrNull() ?: 0L
                }
                pageToken = json.optString("nextPageToken", "")
                pages++
                if (pageToken.isNullOrEmpty()) break
            }
            FileStats(count, bytes)
        }
}
