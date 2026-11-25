package com.ereader.app.data.remote.calibre

import com.ereader.app.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The libraries one Calibre login can open: (id, display name) + the login's default. */
data class CalibreLibraryInfo(
    val libraries: List<Pair<String, String>>,
    val defaultId: String
)

/**
 * Talks to the Calibre Content Server's library-list and reading-sync endpoints —
 * the same ones Calibre's own browser viewer uses (src/calibre/srv/ajax.py, books.py):
 *
 *   GET  /ajax/library-info
 *        → {"library_map": {id: name, ...}, "default_library": id}
 *        Only lists the libraries the logged-in user is allowed to open.
 *
 *   POST /book-update-annotations/{library_id}/{book_id}/{FORMAT}
 *        body: {"highlight": [ ...highlight objects... ]}
 *        Calibre MERGES these with what it already has, matching on "uuid"
 *        and keeping whichever copy has the newer "timestamp". Sending the
 *        same note twice is therefore harmless.
 *
 *   POST /book-set-last-read-position/{library_id}/{book_id}/{FORMAT}
 *        body: {"device": "...", "cfi": "...", "pos_frac": 0.0-1.0}
 *        Only stored for logged-in users — Calibre answers 403 otherwise.
 *
 *   GET  /ajax/book/{book_id}/{library_id}
 *        → one book's metadata, including its custom columns ("user_metadata").
 *        Used to find the library's "Read" column (see CalibreReadColumns).
 *
 *   POST /cdb/set-fields/{book_id}/{library_id}
 *        body: {"changes": {"#read": true, "#date_read": "...", "#times_read": 2}, "loaded_book_ids": [id]}
 *        Sets the Read status, Times Read and the star rating ("rating": 0–10).
 *        Needs a user allowed to make changes.
 *
 *   GET  /ajax/search/{library_id}?query=...&num=...
 *        → {"book_ids": [...]}  Runs a Calibre search — used by Smart Shelves.
 *
 * Authentication uses the shared OkHttpClient, which already answers
 * Calibre's Digest password challenge (see NetworkModule).
 */
@Singleton
class CalibreSyncService @Inject constructor(
    private val userPreferences: UserPreferences,
    private val okHttpClient: OkHttpClient
) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun baseUrl(): HttpUrl {
        val raw = userPreferences.calibreBaseUrl.firstOrNull().orEmpty().trimEnd('/')
        return raw.toHttpUrlOrNull()
            ?: throw IllegalStateException("Calibre server address is not set up. Go to Settings to add it.")
    }

    /** Asks Calibre which libraries this login can open. */
    suspend fun fetchLibraryInfo(): CalibreLibraryInfo {
        val base = baseUrl()
        return withContext(Dispatchers.IO) {
            val url = base.newBuilder().addPathSegments("ajax/library-info").build()
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Calibre returned HTTP ${resp.code} for the library list")
                val json = JSONObject(resp.body?.string().orEmpty())
                val map  = json.optJSONObject("library_map") ?: JSONObject()
                val libraries = map.keys().asSequence().map { id -> id to map.optString(id, id) }.toList()
                val defaultId = json.optString("default_library").takeIf { it.isNotBlank() }
                    ?: libraries.firstOrNull()?.first
                    ?: throw IOException("Calibre did not report any libraries for this login")
                CalibreLibraryInfo(libraries, defaultId)
            }
        }
    }

    /**
     * Builds  {base}/{endpoint}/{library_id}/{book_id}/{FORMAT}  with safe URL encoding.
     * [libraryId] is the book's own library; if unknown, the selected/default one is used.
     */
    private suspend fun bookUrl(endpoint: String, calibreBookId: Int, libraryId: String?, format: String): HttpUrl {
        val base = baseUrl()
        val lib  = libraryId
            ?: userPreferences.effectiveCalibreLibraryId.firstOrNull()
            ?: fetchLibraryInfo().defaultId
        return base.newBuilder()
            .addPathSegment(endpoint)
            .addPathSegment(lib)
            .addPathSegment(calibreBookId.toString())
            .addPathSegment(format)
            .build()
    }

    /**
     * Sends highlights / notes (and deletion markers) for one book.
     * Throws on any failure so the caller can keep the changes for next time.
     */
    suspend fun pushAnnotations(calibreBookId: Int, libraryId: String?, format: String, highlights: JSONArray) {
        val url  = bookUrl("book-update-annotations", calibreBookId, libraryId, format)
        val body = JSONObject().put("highlight", highlights).toString().toRequestBody(jsonType)
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Calibre explains refusals in plain text, e.g.
                    // "The user james does not have permission to make changes".
                    val reason = resp.body?.string().orEmpty().trim().take(200)
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> "Calibre refused the notes (HTTP ${resp.code})" +
                                        (if (reason.isNotBlank()) ": $reason." else ".") +
                                        " In Calibre, give this user permission to make changes."
                            else     -> "Calibre returned HTTP ${resp.code} when saving notes" +
                                        (if (reason.isNotBlank()) ": $reason" else "")
                        }
                    )
                }
            }
        }
        Timber.d("Pushed ${highlights.length()} annotation(s) for Calibre book $calibreBookId")
    }

    /**
     * Sends the current reading position for one book.
     *
     * @return true if Calibre stored it; false if Calibre only accepts reading
     *         positions from logged-in users and this connection isn't logged in
     *         (that case is not treated as an error).
     */
    suspend fun pushLastReadPosition(
        calibreBookId: Int,
        libraryId: String?,
        format: String,
        cfi: String?,
        posFrac: Double
    ): Boolean {
        val url  = bookUrl("book-set-last-read-position", calibreBookId, libraryId, format)
        val body = JSONObject()
            .put("device", "OmniTome")
            .put("cfi", cfi ?: JSONObject.NULL)
            .put("pos_frac", posFrac.coerceIn(0.0, 1.0))
            .toString().toRequestBody(jsonType)
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> true
                    resp.code == 403  -> {
                        Timber.w("Calibre skipped the reading position (not logged in)")
                        false
                    }
                    else -> throw IOException("Calibre returned HTTP ${resp.code} when saving the page you're on")
                }
            }
        }
    }

    // ── Read status ───────────────────────────────────────────────────────────

    /** The library to use: the book's own, else the selected/default one. */
    private suspend fun libFor(libraryId: String?): String =
        libraryId
            ?: userPreferences.effectiveCalibreLibraryId.firstOrNull()
            ?: fetchLibraryInfo().defaultId

    /**
     * One book's full metadata from Calibre (GET /ajax/book/{id}/{library}),
     * including "user_metadata" (custom columns) and "rating".
     * Throws if Calibre can't be reached.
     */
    suspend fun fetchBookJson(calibreBookId: Int, libraryId: String?): JSONObject {
        val base = baseUrl()
        val lib  = libFor(libraryId)
        val url = base.newBuilder()
            .addPathSegments("ajax/book")
            .addPathSegment(calibreBookId.toString())
            .addPathSegment(lib)
            .addQueryParameter("category_urls", "false")
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Calibre returned HTTP ${resp.code} when looking up the book")
                JSONObject(resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * Finds this library's "Read", "Date Read" and "Times Read" columns by
     * looking at one of its books, using the picks from Settings → Read Tracking.
     * Returns [CalibreReadColumns.NONE] if the library has none of them.
     * Throws if Calibre can't be reached.
     */
    suspend fun fetchReadColumns(calibreBookId: Int, libraryId: String?): CalibreReadColumns {
        val lib    = libFor(libraryId)
        val json   = fetchBookJson(calibreBookId, lib)
        val choice = userPreferences.getReadColumnChoice(lib)
        return CalibreReadColumns.detect(json.optJSONObject("user_metadata"), choice)
    }

    /**
     * Sets one book's Read status in Calibre.
     * Marking read also fills in the Date Read column (if the library has one)
     * when [readAt] is known; marking unread clears it.
     * [timesRead] = the new Times Read count to store (null = leave it alone).
     * Throws on any failure so the caller can keep the change for next time.
     */
    suspend fun pushReadStatus(
        calibreBookId: Int,
        libraryId: String?,
        columns: CalibreReadColumns,
        isRead: Boolean,
        readAt: Long?,
        timesRead: Int? = null
    ) {
        val readKey = columns.readKey ?: throw IllegalStateException("No Read column in Calibre")
        val changes = JSONObject().put(readKey, isRead)
        columns.dateKey?.let { dateKey ->
            when {
                !isRead        -> changes.put(dateKey, JSONObject.NULL)
                readAt != null -> changes.put(dateKey, java.time.Instant.ofEpochMilli(readAt).toString())
                // Read, but date unknown ("A while ago") → leave Calibre's date alone.
            }
        }
        if (columns.timesKey != null && timesRead != null) changes.put(columns.timesKey, timesRead)
        setFields(calibreBookId, libraryId, changes, "the Read status")
        Timber.d("Set $readKey=$isRead (times read: $timesRead) for Calibre book $calibreBookId")
    }

    /**
     * Sets one book's star rating in Calibre's built-in Rating field.
     * [stars] 1–5, or null to clear it. Calibre stores two points per star.
     */
    suspend fun pushRating(calibreBookId: Int, libraryId: String?, stars: Int?) {
        val value = (stars ?: 0).coerceIn(0, 5) * 2
        setFields(calibreBookId, libraryId, JSONObject().put("rating", value), "the rating")
        Timber.d("Set rating=$stars★ for Calibre book $calibreBookId")
    }

    /**
     * POST /cdb/set-fields/{book_id}/{library_id} with {"changes": {...}}.
     * [what] is used in the error message, e.g. "the rating".
     */
    private suspend fun setFields(calibreBookId: Int, libraryId: String?, changes: JSONObject, what: String) {
        val base = baseUrl()
        val lib  = libFor(libraryId)
        val body = JSONObject()
            .put("changes", changes)
            .put("loaded_book_ids", JSONArray().put(calibreBookId))
            .toString().toRequestBody(jsonType)
        val url = base.newBuilder()
            .addPathSegments("cdb/set-fields")
            .addPathSegment(calibreBookId.toString())
            .addPathSegment(lib)
            .build()
        withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val reason = resp.body?.string().orEmpty().trim().take(200)
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> "Calibre refused $what (HTTP ${resp.code})" +
                                        (if (reason.isNotBlank()) ": $reason." else ".") +
                                        " In Calibre, give this user permission to make changes."
                            else     -> "Calibre returned HTTP ${resp.code} when saving $what" +
                                        (if (reason.isNotBlank()) ": $reason" else "")
                        }
                    )
                }
            }
        }
    }

    // ── Settings → Read Tracking ──────────────────────────────────────────────

    /** What the Read Tracking pickers need: the library's columns + what Automatic finds. */
    data class LibraryColumns(
        val columns: List<CalibreColumnInfo>,
        val automatic: CalibreReadColumns
    )

    /**
     * Lists the custom columns of a library, by looking at its first book
     * (every book carries every custom column). Throws if Calibre can't be
     * reached or the library has no books yet.
     */
    suspend fun fetchLibraryColumns(libraryId: String?): LibraryColumns {
        val lib = libFor(libraryId)
        val firstId = searchBookIds("", lib, limit = 1).firstOrNull()
            ?: throw IOException("This Calibre library has no books yet, so its columns can't be read.")
        val meta = fetchBookJson(firstId, lib).optJSONObject("user_metadata")
        return LibraryColumns(
            columns   = CalibreReadColumns.listColumns(meta),
            automatic = CalibreReadColumns.detect(meta)
        )
    }

    // ── Smart Shelves ─────────────────────────────────────────────────────────

    /**
     * Runs a Calibre search (GET /ajax/search/{library}?query=…) and returns the
     * matching Calibre book ids, in Calibre's order. An empty query = every book.
     * Throws if Calibre can't be reached or doesn't understand the search.
     */
    suspend fun searchBookIds(query: String, libraryId: String?, limit: Int = 100_000): List<Int> {
        val base = baseUrl()
        val lib  = libFor(libraryId)
        val url = base.newBuilder()
            .addPathSegments("ajax/search")
            .addPathSegment(lib)
            .addQueryParameter("query", query)
            .addQueryParameter("num", limit.toString())
            .build()
        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val reason = resp.body?.string().orEmpty().trim().take(200)
                    throw IOException(
                        "Calibre couldn't run that search (HTTP ${resp.code})" +
                        (if (reason.isNotBlank()) ": $reason" else ".")
                    )
                }
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("book_ids") ?: JSONArray()
                (0 until arr.length()).map { arr.getInt(it) }
            }
        }
    }

    // ── Reachability ──────────────────────────────────────────────────────────

    /**
     * Quick yes/no: does the Calibre server answer right now?
     * Uses short timeouts so a phone on mobile data (which can't reach a home
     * server) finds out in seconds instead of waiting 30+ seconds per request.
     */
    suspend fun isReachable(): Boolean {
        val base = runCatching { baseUrl() }.getOrNull() ?: return false
        val quick = okHttpClient.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = base.newBuilder().addPathSegments("ajax/library-info").build()
                quick.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }
}
