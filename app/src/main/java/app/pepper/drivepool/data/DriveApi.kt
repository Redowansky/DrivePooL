// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
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

    data class FilePage(val files: List<DriveFile>, val nextPageToken: String?)

    private fun parseFile(f: JSONObject): DriveFile = DriveFile(
        id = f.optString("id"),
        name = f.optString("name"),
        mimeType = f.optString("mimeType"),
        size = f.optString("size", "0").toLongOrNull() ?: 0L,
        modifiedTime = f.optString("modifiedTime")
    )

    /** One page (100) of a folder's children, folders first, then by name. */
    suspend fun listFiles(token: String, folderId: String?, pageToken: String?): FilePage? =
        withContext(Dispatchers.IO) {
            val parent = folderId ?: "root"
            val q = "'$parent' in parents and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?pageSize=100&orderBy=folder,name" +
                "&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime)" +
                "&q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val json = get(token, url) ?: return@withContext null
            val arr = json.optJSONArray("files") ?: return@withContext null
            val files = (0 until arr.length()).map { parseFile(arr.getJSONObject(it)) }
            FilePage(files, json.optString("nextPageToken", "").takeIf { it.isNotEmpty() })
        }

    /** Name search inside one account (first 100 hits, folders + files). */
    suspend fun searchFiles(token: String, query: String): List<DriveFile> =
        withContext(Dispatchers.IO) {
            val safe = query.replace("\\", "\\\\").replace("'", "\\'")
            val q = "name contains '$safe' and trashed = false"
            val url = "https://www.googleapis.com/drive/v3/files" +
                "?pageSize=100&orderBy=folder,name" +
                "&fields=files(id,name,mimeType,size,modifiedTime)" +
                "&q=${java.net.URLEncoder.encode(q, "UTF-8")}"
            val json = get(token, url) ?: return@withContext emptyList()
            val arr = json.optJSONArray("files") ?: return@withContext emptyList()
            (0 until arr.length()).map { parseFile(arr.getJSONObject(it)) }
        }

    private fun get(token: String, url: String): JSONObject? = try {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            JSONObject(body)
        }
    } catch (_: Exception) { null }

    suspend fun fetchQuota(token: String): Quota? = withContext(Dispatchers.IO) {
        val json = get(
            token,
            "https://www.googleapis.com/drive/v3/about" +
                "?fields=user(emailAddress),storageQuota(limit,usage)"
        ) ?: return@withContext null
        val email = json.optJSONObject("user")?.optString("emailAddress") ?: return@withContext null
        val q = json.optJSONObject("storageQuota") ?: return@withContext null
        val limit = q.optString("limit", "0").toLongOrNull() ?: 0L
        val used = q.optString("usage", "0").toLongOrNull() ?: 0L
        Quota(email, used, limit)
    }

    suspend fun fetchFileStats(token: String, todayOnly: Boolean): FileStats =
        withContext(Dispatchers.IO) {
            var count = 0L
            var bytes = 0L
            var pageToken: String? = null
            var pages = 0
            val q = if (todayOnly) {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("Asia/Dhaka")
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Dhaka")
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
