// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** What the transfer UI shows — the in-app progress dialog AND the
 *  foreground service's notification read the same state. Lives in a
 *  process-wide object because the service and MainActivity share it;
 *  no binder, no broadcasts. */
data class TransferState(
    val title: String,      // "Uploading" / "Moving file"
    val label: String,      // "Uploading foo.mp3 to x@gmail.com…"
    val progress: Float,    // 0..1
    val done: Boolean = false,
    val error: String? = null,
    val doneMessage: String? = null
)

object UploadState {
    private val _state = MutableStateFlow<TransferState?>(null)
    val state: StateFlow<TransferState?> = _state.asStateFlow()

    fun update(state: TransferState) { _state.value = state }
    fun clear() { _state.value = null }
}

/** Persisted resumable-upload session, so a service killed mid-upload
 *  can pick up where it stopped instead of restarting from byte zero. */
object ResumeStore {
    private const val PREFS = "upload_resume"

    data class Session(val sessionUri: String, val uploadedBytes: Long, val totalBytes: Long)

    fun save(ctx: Context, jobId: String, session: Session) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                jobId,
                JSONObject().apply {
                    put("sessionUri", session.sessionUri)
                    put("uploadedBytes", session.uploadedBytes)
                    put("totalBytes", session.totalBytes)
                }.toString()
            )
            .apply()
    }

    fun load(ctx: Context, jobId: String): Session? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(jobId, null) ?: return null
        return try {
            val j = JSONObject(raw)
            Session(j.getString("sessionUri"), j.getLong("uploadedBytes"), j.getLong("totalBytes"))
        } catch (_: Exception) { null }
    }

    fun clear(ctx: Context, jobId: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(jobId).apply()
    }
}
