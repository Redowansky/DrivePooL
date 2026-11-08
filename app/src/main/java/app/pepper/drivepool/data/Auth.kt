package app.pepper.drivepool.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google sign-in for Drive, read-only scope.
 *
 * The Web client ID is NOT baked into the app. The user enters their own
 * Web client ID (from their own Google Cloud project, same one they use
 * for Round Sync / rclone) in Settings — it stays on this device only.
 * The matching Android OAuth client for package app.pepper.drivepool must
 * exist in that same Cloud project, otherwise sign-in fails with code 10.
 */
object Auth {
    const val RC_SIGN_IN = 9001
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

    fun client(ctx: Context, webClientId: String): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        return GoogleSignIn.getClient(ctx, options)
    }

    fun signInIntent(ctx: Context, webClientId: String): Intent =
        client(ctx, webClientId).signInIntent

    fun accountFromResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return task.getResult(ApiException::class.java)
    }

    /** Returns an access token, or null. Throws UserRecoverableAuthException when the user must approve again. */
    suspend fun accessToken(ctx: Context, account: GoogleSignInAccount): String? =
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.getToken(ctx, account.account, "oauth2:$DRIVE_SCOPE")
            } catch (_: Exception) { null }
        }
}
