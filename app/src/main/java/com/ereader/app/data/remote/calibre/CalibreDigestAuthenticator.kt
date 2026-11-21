package com.ereader.app.data.remote.calibre

import com.ereader.app.data.preferences.UserPreferences
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp [Authenticator] that handles Calibre Content Server's Digest-MD5
 * authentication challenge (RFC 2617).
 *
 * Calibre 9.9.0 uses HTTP Digest Auth when "Require username and password"
 * is enabled in Preferences → Sharing over the net.  It sends:
 *
 *   WWW-Authenticate: Digest realm="calibre", nonce="…", algorithm="MD5", qop="auth"
 *
 * OkHttp calls [authenticate] whenever it receives a 401.  We:
 *  1. Parse the challenge from the WWW-Authenticate header.
 *  2. Read credentials from [UserPreferences] (via runBlocking — this runs
 *     on OkHttp's IO thread, so blocking is acceptable).
 *  3. Compute HA1 = MD5("user:realm:pass"), HA2 = MD5("method:uri"),
 *     response = MD5("HA1:nonce:nc:cnonce:qop:HA2") and build the
 *     Authorization header.
 *  4. Return the retried request with the Authorization header set.
 *
 * Returning null stops retrying (prevents infinite loops when credentials are wrong).
 */
@Singleton
class CalibreDigestAuthenticator @Inject constructor(
    private val userPreferences: UserPreferences
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // If we already sent an Authorization header and still got 401, stop retrying.
        if (response.request.header("Authorization") != null) {
            Timber.w("DigestAuth: credentials rejected (wrong username or password?)")
            return null
        }

        val wwwAuth = response.header("WWW-Authenticate") ?: return null

        // Read saved credentials. runBlocking is fine on OkHttp's IO thread.
        val username = runBlocking { userPreferences.calibreUsername.firstOrNull() }.orEmpty()
        val password = runBlocking { userPreferences.calibrePassword.firstOrNull() }.orEmpty()
        if (username.isBlank()) {
            Timber.w("DigestAuth: no username configured — cannot authenticate")
            return null
        }

        return when {
            wwwAuth.startsWith("Digest", ignoreCase = true) ->
                buildDigestRequest(response, wwwAuth, username, password)
            wwwAuth.startsWith("Basic", ignoreCase = true) ->
                response.request.newBuilder()
                    .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                    .build()
            else -> {
                Timber.w("DigestAuth: unsupported auth scheme in '$wwwAuth'")
                null
            }
        }
    }

    // ── Digest-MD5 computation ────────────────────────────────────────────────

    private fun buildDigestRequest(
        response: Response,
        wwwAuth: String,
        username: String,
        password: String
    ): Request? {
        val realm  = extractParam(wwwAuth, "realm")  ?: return null
        val nonce  = extractParam(wwwAuth, "nonce")  ?: return null
        val qop    = extractParam(wwwAuth, "qop")     // "auth" or null
        val opaque = extractParam(wwwAuth, "opaque")  // optional field

        // URI must include query string if present
        val uri = response.request.url.let { url ->
            val q = url.encodedQuery
            if (q != null) "${url.encodedPath}?$q" else url.encodedPath
        }
        val method = response.request.method
        val cnonce = generateCnonce()
        val nc     = "00000001"

        // RFC 2617 §3.2.2
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val digestResponse = if (qop != null) {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val authValue = buildString {
            append("""Digest username="$username", """)
            append("""realm="$realm", """)
            append("""nonce="$nonce", """)
            append("""uri="$uri", """)
            append("algorithm=MD5")
            if (qop != null) append(""", qop=$qop, nc=$nc, cnonce="$cnonce"""")
            if (opaque != null) append(""", opaque="$opaque"""")
            append(""", response="$digestResponse"""")
        }

        Timber.d("DigestAuth: responding to challenge — user='$username', uri=$uri")
        return response.request.newBuilder()
            .header("Authorization", authValue)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun generateCnonce(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts a named parameter from a WWW-Authenticate header.
     * Handles both quoted  (name="value")  and unquoted  (name=value)  forms.
     */
    private fun extractParam(header: String, name: String): String? {
        // Quoted form: name="value"
        Regex("""$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.let { return it }
        // Unquoted form: name=value
        return Regex("""$name\s*=\s*([^,\s"]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
    }
}
