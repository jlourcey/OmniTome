package com.ereader.app.data.remote.drive

import com.google.api.services.drive.Drive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Google Drive client after the user signs in with OAuth.
 *
 * Why this exists:
 *   A [Drive] instance requires a GoogleAccountCredential, which is only
 *   available after the user completes the Google sign-in flow at runtime.
 *   We can't provide [Drive] directly via Hilt at app startup, so we inject
 *   this holder instead. The [Drive] client is set here once sign-in completes
 *   (currently wired in MainActivity's Google sign-in callback).
 *
 * Usage:
 *   - [DriveService] checks [client] before making any API calls.
 *   - If null, Drive sync is silently skipped until the user signs in.
 *   - Set [client] from MainActivity after a successful GoogleSignIn result.
 */
@Singleton
class DriveClientHolder @Inject constructor() {
    /** Null until the user has completed Google sign-in. */
    var client: Drive? = null
}
