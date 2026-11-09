// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

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
 * Google sign-in for Drive — same flow as the Library app.
 *
 * v0.5.0 moved from drive.readonly to the full drive scope, because
 * moving files between Drives and uploading need write access (copy
 * out of one account, upload into another, delete the original).
 * Every account linked before v0.5.0 must approve the new scope once:
 * the next refresh/browse marks it "Needs sign-in" and Add Drive
 * re-picks it with the broader consent.
 *
 * The Web client ID is baked into the app (DrivePool Cloud project). The
 * user just taps Add Drive, picks a Google account, and grants Drive
 * access. No pasting, no Console steps for the user.
 * The matching Android OAuth client for package app.pepper.drivepool
 * (signed with the stable release key) exists in the same Cloud project,
 * otherwise sign-in fails with code 10.
 */
object Auth {
    const val RC_SIGN_IN = 9001
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

    /** Web OAuth client ID of the DrivePool Cloud project (public identifier, not a secret). */
    const val WEB_CLIENT_ID = "229634430100-ep0ig8nuc5kp3ciaj3clfs7k6i6hgbat.apps.googleusercontent.com"

    fun client(ctx: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        return GoogleSignIn.getClient(ctx, options)
    }

    fun signInIntent(ctx: Context): Intent = client(ctx).signInIntent

    fun accountFromResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return task.getResult(ApiException::class.java)
    }

    /** Returns an access token, or null. Throws UserRecoverableAuthException when the user must approve again. */
    suspend fun accessToken(ctx: Context, account: GoogleSignInAccount): String? =
        withContext(Dispatchers.IO) {
            try {
                val acct = account.account ?: return@withContext null
                GoogleAuthUtil.getToken(ctx, acct, "oauth2:$DRIVE_SCOPE")
            } catch (_: Exception) { null }
        }

    /**
     * Access token for a stored account by email — no GoogleSignIn state
     * needed. This is what makes Refresh work for EVERY linked account,
     * not just the last one signed in: the OAuth grant lives on the
     * device account, and getToken re-issues from it.
     * Throws UserRecoverableAuthException when that account needs to
     * approve again (caller marks it "Needs sign-in").
     */
    suspend fun accessTokenForEmail(ctx: Context, email: String): String? =
        withContext(Dispatchers.IO) {
            val acct = android.accounts.Account(email, "com.google")
            try {
                GoogleAuthUtil.getToken(ctx, acct, "oauth2:$DRIVE_SCOPE")
            } catch (e: UserRecoverableAuthException) {
                throw e
            } catch (_: Exception) { null }
        }
}
