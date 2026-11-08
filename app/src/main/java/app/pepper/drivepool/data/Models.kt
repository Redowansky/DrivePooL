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

fun formatGb(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 100) "%.0f GB".format(gb) else "%.1f GB".format(gb)
}

val ACCOUNT_COLORS = listOf(0xFF4A90E2, 0xFF4ADE80, 0xFFFACC15, 0xFFA78BFA, 0xFF22D3EE)
