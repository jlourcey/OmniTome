package com.ereader.app.data.remote.calibre

import android.content.Context
import android.net.Uri
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.domain.model.Book
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads a locally imported book file to the user's Calibre Content Server.
 *
 * ## How Calibre receives the upload
 *
 * Calibre's Content Server (v3.47+) exposes a multipart upload endpoint:
 *
 *   POST {calibreUrl}/cdb/add-books/
 *   Content-Type: multipart/form-data
 *
 * The book file is sent as the `book_file` field.  Calibre parses the
 * filename to determine the format (EPUB / PDF) and extracts embedded
 * metadata automatically.
 *
 * ## Authentication
 *
 * If your Calibre server requires a username / password, set them in
 * Calibre → Preferences → Sharing → Sharing over the net → Require
 * username and password, then add the credentials to the app's Settings
 * screen.  This service reads them from [UserPreferences] and adds a
 * Basic-Auth header when they are present.
 *
 * ## Error handling
 *
 * A [Result.failure] is returned for network errors, missing file, or a
 * non-2xx HTTP response.  The caller should surface the error message to
 * the user.
 *
 * ## Compatibility note
 *
 * The `/cdb/add-books/` endpoint was introduced in Calibre 3.47.  If your
 * Calibre is older the server returns 404.  In that case, update Calibre
 * or consider adding the book manually via the Calibre desktop app.
 */
@Singleton
class CalibreUploadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences,
    private val digestAuthenticator: CalibreDigestAuthenticator
) {

    /**
     * OkHttp client with Digest-Auth support.
     *
     * Calibre 9.9.0 uses HTTP Digest-MD5 when "Require username and password"
     * is enabled.  When we send a request without credentials, Calibre responds
     * with 401 + WWW-Authenticate: Digest …  OkHttp then calls
     * [CalibreDigestAuthenticator.authenticate], which computes the proper
     * MD5 Digest response and retries the request automatically.
     *
     * Note: the request body (file bytes) is backed by a ByteArray, so OkHttp
     * CAN replay it on the retry without re-reading from disk.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .authenticator(digestAuthenticator)
        .build()

    /**
     * Uploads [book]'s local file to the Calibre Content Server.
     *
     * Returns [Result.success(Unit)] on a 2xx response, or
     * [Result.failure] with a descriptive [Exception] on error.
     */
    suspend fun upload(book: Book): Result<Unit> = runCatching {

        // ── Resolve the file to upload ────────────────────────────────────────
        val filePath = book.localFilePath
            ?: return Result.failure(
                IllegalStateException("Book has no local file — only locally imported books can be uploaded to Calibre")
            )

        val bookFile = File(filePath)
        if (!bookFile.exists()) {
            return Result.failure(
                IllegalStateException("Local file not found: $filePath")
            )
        }

        // ── Determine the Calibre server URL ──────────────────────────────────
        val baseUrl = userPreferences.calibreBaseUrl.firstOrNull().orEmpty()
        if (baseUrl.isBlank()) {
            return Result.failure(
                IllegalStateException("Calibre server address is not configured. Go to Settings to set it up.")
            )
        }

        // ── Build the Calibre add-book URL ────────────────────────────────────
        //
        // Calibre Content Server endpoint (src/calibre/srv/cdb.py):
        //   POST /cdb/add-book/{job_id}/{add_duplicates}/{filename}
        //
        // • job_id         — any unique string; Calibre echoes it back in the response.
        // • add_duplicates — "y" = add even if a book with this title already exists.
        // • filename       — the actual filename; Calibre uses the extension to detect
        //                    format (EPUB, PDF, …). Must be URL-encoded.
        //
        // The request body MUST be raw file bytes — NOT multipart/form-data.
        // The old "/cdb/add-books/" path does not exist in Calibre's source.
        val jobId           = System.currentTimeMillis().toString()
        val encodedFilename = java.net.URLEncoder.encode(bookFile.name, "UTF-8")
        // Add to the library currently selected in the bookshelf switch
        val libPath         = userPreferences.effectiveCalibreLibraryId.firstOrNull()
            ?.let { "/${Uri.encode(it)}" } ?: ""
        val uploadUrl       = "$baseUrl/cdb/add-book/$jobId/y/$encodedFilename$libPath"
        Timber.d("Uploading '${book.title}' to $uploadUrl")

        // ── Raw-bytes body ────────────────────────────────────────────────────
        val requestBody = bookFile.readBytes()
            .toRequestBody("application/octet-stream".toMediaTypeOrNull())

        // ── Build request ─────────────────────────────────────────────────────
        // Auth is handled automatically via CalibreDigestAuthenticator: OkHttp
        // sends the request unauthenticated, receives a 401 + Digest challenge,
        // then retries with the computed MD5 Digest header — no manual header needed.
        val requestBuilder = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)

        // ── Execute ───────────────────────────────────────────────────────────
        val response = withContext(Dispatchers.IO) { client.newCall(requestBuilder.build()).execute() }
        val responseBody = response.use {
            if (!it.isSuccessful) {
                val body = it.body?.string() ?: "(no body)"
                Timber.w("Upload failed — HTTP ${it.code}: $body")
                // Strip HTML tags so we can show the raw error text in the Snackbar.
                // Calibre's 403 body is usually a short string or simple JSON.
                val bodySummary = body
                    .replace(Regex("<[^>]*>"), " ")   // remove HTML tags
                    .replace(Regex("\\s+"), " ")       // collapse whitespace
                    .trim()
                    .take(100)
                // Detect the specific "anonymous" rejection — this means Calibre received
                // the credentials but found no matching user in its User Accounts list,
                // so it fell back to treating the request as anonymous and then blocked
                // the anonymous write attempt.
                val isAnonymousRejection = bodySummary.contains("anonymous", ignoreCase = true)
                val message = when (it.code) {
                    403 -> when {
                        isAnonymousRejection ->
                            // Credentials sent but no matching Calibre user exists.
                            // "Allow un-authenticated local connections" only covers
                            // 127.0.0.1, not a phone on the LAN.
                            "Calibre has no user matching your credentials (treated as anonymous). " +
                            "Fix in Calibre: Preferences → Sharing over the net → " +
                            "User accounts tab → Add user with same username/password → " +
                            "tick 'Allow to make changes'. Also enable 'Require username " +
                            "and password' on the Main tab."
                        else ->
                            "Upload blocked (403). Calibre says: \"$bodySummary\". " +
                            "Verify 'Allow to make changes' is ticked for your user in " +
                            "Calibre → Preferences → Sharing over the net → User accounts."
                    }
                    401 -> "Calibre login failed (401). Check username and password in Settings → Calibre Server."
                    404 -> "Calibre endpoint not found (404). Make sure Calibre Content Server is version 3.47 or later."
                    else -> "Calibre upload failed (${it.code}). Make sure the Content Server is running."
                }
                throw Exception(message)
            }
            // Read body before closing the response
            it.body?.string()
        }

        Timber.d("Upload successful: '${book.title}'")

        // ── Post-upload metadata push ─────────────────────────────────────────
        // Calibre derives the book title from the EPUB's OPF <dc:title> element.
        // If the EPUB has no embedded title (common for locally imported files),
        // Calibre may store "null" or the raw filename stem.  We fix this by
        // immediately pushing the correct title/author from our own database.
        //
        // Calibre's response format: {"book_id": {"$jobId": [calibreId]}}
        val newCalibreId = parseAddBookResponse(responseBody, jobId)
        if (newCalibreId != null) {
            Timber.d("New Calibre book ID: $newCalibreId — pushing correct title/author metadata")
            runCatching { pushMetadata(newCalibreId, book) }
                .onFailure { Timber.w(it, "Post-upload metadata push failed (non-fatal) for book '${book.title}'") }
        } else {
            Timber.w("Could not parse new Calibre book ID from upload response — title may need manual correction in Calibre")
        }
    }

    /**
     * Pushes updated metadata for [book] back to Calibre using the internal
     * Calibre book ID ([calibreId]).
     *
     * ## Endpoint
     *   POST {calibreUrl}/cdb/set-fields/{book_id}
     *   Content-Type: application/json
     *   Authorization: Basic base64(username:password)   ← required when Calibre
     *                                                      has a password set
     *
     * The book_id is a URL path segment; the library_id is optional (defaults
     * to Calibre's active library). Discovered from Calibre 9.9 source at
     * src/calibre/srv/cdb.py — the endpoint `/cdb/set-book-metadata` does NOT
     * exist in this or any recent version of Calibre.
     *
     * ## Auth
     * When Calibre's Content Server is password-protected, write endpoints return
     * HTTP 404 for unauthenticated requests (not 401) to avoid leaking that they
     * exist. The app reads credentials from [UserPreferences] and sends a Basic
     * Auth header automatically when a username is configured.
     *
     * ## Body
     * ```json
     * {
     *   "changes": {
     *     "title":        "Book Title",
     *     "authors":      ["Author Name"],
     *     "tags":         ["Fantasy", "Magic"],
     *     "series":       "Series Name",
     *     "series_index": 1.0,
     *     "comments":     "Description text"
     *   },
     *   "loaded_book_ids": [521]
     * }
     * ```
     *
     * ## Why "comments" for description?
     * Calibre's internal field for the book synopsis is named "comments" in
     * its database schema. The JSON API uses that same name.
     *
     * Returns [Result.success(Unit)] on HTTP 2xx, or [Result.failure] with
     * a descriptive message if the server is unreachable or rejects the update.
     */
    suspend fun pushMetadata(calibreId: Int, book: Book): Result<Unit> = runCatching {

        val baseUrl = userPreferences.calibreBaseUrl.firstOrNull().orEmpty()
        if (baseUrl.isBlank()) {
            return Result.failure(
                IllegalStateException("Calibre server address is not configured. Go to Settings to set it up.")
            )
        }

        // Build the "changes" object with only the fields we allow the user to edit
        val changes = JSONObject().apply {
            put("title",   book.title)
            put("authors", JSONArray().apply { put(book.author) })
            put("tags",    JSONArray().apply { book.tags.forEach { put(it) } })
            // Empty string clears the series in Calibre; null would be ignored
            put("series",  book.series ?: "")
            put("series_index", book.seriesIndex?.toDouble() ?: 1.0)
            // Calibre calls the description field "comments"
            put("comments", book.description ?: "")
        }

        // book_id goes in the URL path; the body carries only field changes.
        // loaded_book_ids tells Calibre which books to return in the response
        // (we always include the book we just edited so we get back its fresh state).
        val body = JSONObject().apply {
            put("changes", changes)
            put("loaded_book_ids", JSONArray().apply { put(calibreId) })
        }

        // Correct Calibre Content Server endpoint (src/calibre/srv/cdb.py).
        // The book ID is a URL path segment; library_id defaults to the active
        // library when omitted.  /cdb/set-book-metadata does NOT exist in Calibre.
        // /cdb/set-fields/{book_id}/{library_id} — the book's own library
        val libraryId = book.libraryKey ?: userPreferences.effectiveCalibreLibraryId.firstOrNull()
        val url = "$baseUrl/cdb/set-fields/$calibreId" + (libraryId?.let { "/${Uri.encode(it)}" } ?: "")
        Timber.d("Pushing metadata for '${book.title}' (Calibre ID=$calibreId) to $url")

        val requestBody = body.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        // Auth is handled automatically via CalibreDigestAuthenticator (challenge-response).
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        // Must run on Dispatchers.IO — OkHttp .execute() blocks the calling
        // thread, and this function may be called from Dispatchers.Main.
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        response.use {
            if (!it.isSuccessful) {
                val errorBody = it.body?.string() ?: "(no body)"
                Timber.w("Calibre push failed — HTTP ${it.code}, body='$errorBody'")
                throw Exception("Calibre returned HTTP ${it.code} when saving metadata: $errorBody")
            }
        }

        Timber.d("Metadata push successful for '${book.title}'")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses the Calibre book ID from the JSON body returned by /cdb/add-book.
     *
     * Calibre response format (src/calibre/srv/cdb.py):
     *   {"book_id": {"$jobId": [calibreId1, calibreId2, ...]}}
     *
     * Returns null if the response is missing, malformed, or the ID cannot
     * be extracted — callers should treat this as non-fatal.
     */
    private fun parseAddBookResponse(json: String?, jobId: String): Int? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj       = org.json.JSONObject(json)
            val bookIdMap = obj.getJSONObject("book_id")
            // The map key is the job ID we supplied
            bookIdMap.getJSONArray(jobId).getInt(0).takeIf { it > 0 }
        } catch (e: Exception) {
            Timber.w("parseAddBookResponse: could not parse — body=${json.take(200)}: ${e.message}")
            null
        }
    }
}
