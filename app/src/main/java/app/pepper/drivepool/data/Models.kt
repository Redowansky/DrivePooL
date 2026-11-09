// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import org.json.JSONArray
import org.json.JSONObject

data class DriveAccount(
    val email: String,
    val usedBytes: Long,
    val limitBytes: Long,
    val status: String = "Connected",
    val fileCount: Long = 0,
    val todayBytes: Long = 0,
    val lastSync: Long = 0L
) {
    val freeBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (limitBytes > 0) usedBytes.toFloat() / limitBytes else 0f
    fun toJson(): JSONObject = JSONObject().apply {
        put("email", email)
        put("usedBytes", usedBytes)
        put("limitBytes", limitBytes)
        put("status", status)
        put("fileCount", fileCount)
        put("todayBytes", todayBytes)
        put("lastSync", lastSync)
    }

    companion object {
        fun fromJson(o: JSONObject) = DriveAccount(
            email = o.optString("email"),
            usedBytes = o.optLong("usedBytes"),
            limitBytes = o.optLong("limitBytes"),
            status = o.optString("status", "Connected"),
            fileCount = o.optLong("fileCount"),
            todayBytes = o.optLong("todayBytes"),
            lastSync = o.optLong("lastSync")
        )

        fun listFromJson(s: String): List<DriveAccount> {
            if (s.isBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
        }

        fun listToJson(list: List<DriveAccount>): String {
            val a = JSONArray()
            list.forEach { a.put(it.toJson()) }
            return a.toString()
        }
    }
}

/**
 * Human storage size, decimal units like Google reports them.
 * 5 TB of Google One shows as "5.00 TB", not "5000 GB".
 * Small values stay in GB so the 15 GB free tier still reads "15.0 GB".
 */
/** Percent with enough decimals to mean something at TB scale:
 *  0.13% (not "0%") when the used share is tiny, 42.5% in the middle,
 *  87% when it is large. */
fun formatPercent(percent: Float): String = when {
    percent < 1f -> "%.2f%%".format(percent)
    percent < 10f -> "%.1f%%".format(percent)
    else -> "%.0f%%".format(percent)
}

fun formatGb(bytes: Long): String {
    val b = bytes.toDouble()
    return when {
        b >= 1_000_000_000_000_000.0 -> "%.2f PB".format(b / 1_000_000_000_000.0)
        b >= 1_000_000_000_000.0 -> "%.2f TB".format(b / 1_000_000_000_000.0)
        else -> {
            val gb = b / 1_000_000_000.0
            if (gb >= 100) "%.0f GB".format(gb) else "%.1f GB".format(gb)
        }
    }
}

/** Exact file size in the right unit — a 240 KB PDF shows "240 KB",
 *  not "0.0 GB" (formatGb always divides by a billion). Same decimal
 *  units as the dashboard totals, so the numbers agree. */
fun formatSize(bytes: Long): String {
    val b = bytes.toDouble()
    return when {
        b < 1_000.0 -> "$bytes B"
        b < 1_000_000.0 -> "%.0f KB".format(b / 1_000.0)
        b < 1_000_000_000.0 -> "%.1f MB".format(b / 1_000_000.0)
        b < 1_000_000_000_000.0 -> "%.2f GB".format(b / 1_000_000_000.0)
        b < 1_000_000_000_000_000.0 -> "%.2f TB".format(b / 1_000_000_000_000.0)
        else -> "%.2f PB".format(b / 1_000_000_000_000_000.0)
    }
}

val ACCOUNT_COLORS = listOf(0xFF4A90E2, 0xFF4ADE80, 0xFFFACC15, 0xFFA78BFA, 0xFF22D3EE)
