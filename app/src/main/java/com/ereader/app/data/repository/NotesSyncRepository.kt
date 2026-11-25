package com.ereader.app.data.repository

import android.content.Context
import com.ereader.app.data.local.dao.AnnotationDao
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationEntity
import com.ereader.app.data.local.entity.BookEntity
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.data.remote.calibre.CalibreReadColumns
import com.ereader.app.data.remote.calibre.CalibreSyncService
import com.ereader.app.sync.SyncScheduler
import com.ereader.app.util.EpubSpineReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends a book's notes, highlights, and reading position back to Calibre.
 *
 * How "what needs syncing" is tracked:
 *   Each book row has [BookEntity.notesSyncedAt] — the moment of its last
 *   successful sync. A book needs syncing when, since that moment:
 *     - a note/highlight was created (annotation.updatedAt is newer), or
 *     - a note/highlight was deleted (a tombstone exists), or
 *     - the user turned pages (book.lastReadAt is newer).
 *
 * When it runs:
 *   1. "Sync & Remove" in the Remove Download popup.
 *   2. "Sync Notes to Calibre" in a book's long-press menu.
 *   3. Automatically after every successful library sync — i.e. as soon as
 *      the app reaches Calibre again after you've been away from home.
 *
 * About positions: OmniTome's reading engine (Readium) records positions
 * differently from Calibre. Calibre therefore gets the exact text, note,
 * colour, chapter, and how far through the book you are — but its own viewer
 * may not be able to paint the highlight over the exact words.
 */
@Singleton
class NotesSyncRepository @Inject constructor(
    private val bookDao: BookDao,
    private val annotationDao: AnnotationDao,
    private val calibreSync: CalibreSyncService,
    private val syncScheduler: SyncScheduler,
    @ApplicationContext private val appContext: Context
) {

    /** IDs of books whose notes are being sent right now (drives the cover spinner). */
    private val _syncingIds = MutableStateFlow<Set<String>>(emptySet())
    val syncingIds: StateFlow<Set<String>> = _syncingIds.asStateFlow()

    /** Syncs one book. Returns a failure (and changes nothing locally) if Calibre can't be reached. */
    suspend fun syncBook(bookId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId)
            ?: return@withContext Result.failure(IllegalStateException("Book not found"))
        val calibreId = book.calibreBookId
            ?: return@withContext Result.failure(IllegalStateException(
                "This book isn't linked to Calibre yet. Refresh the library once while you're " +
                "on your home network, then try again."
            ))
        if (bookId in _syncingIds.value) return@withContext Result.success(Unit)

        _syncingIds.update { it + bookId }
        try {
            // Anything changed after this moment stays "pending" for the next sync.
            val startedAt = System.currentTimeMillis()
            val format    = if (book.fileType == FileType.PDF) "PDF" else "EPUB"
            val spine     = spineFor(book)

            // ── Notes and highlights (plus deletions) ─────────────────────────
            val highlights = JSONArray()
            annotationDao.getForBook(bookId).forEach { highlights.put(toCalibreHighlight(it, spine)) }
            annotationDao.getTombstonesForBook(bookId).forEach { t ->
                highlights.put(
                    JSONObject()
                        .put("type", "highlight")
                        .put("uuid", t.id)
                        .put("removed", true)
                        .put("timestamp", iso(t.deletedAt))
                )
            }
            if (highlights.length() > 0) {
                calibreSync.pushAnnotations(calibreId, book.libraryKey, format, highlights)
            }

            // ── Reading position ("page read") ────────────────────────────────
            if (book.lastLocator != null && book.readingProgress > 0f) {
                val href = runCatching { JSONObject(book.lastLocator).optString("href") }.getOrNull()
                val si   = EpubSpineReader.indexOf(spine, href)
                // Calibre's position format: epubcfi(/N) where N = (spine index + 1) * 2.
                // This points at the start of the chapter you're in; pos_frac carries the
                // exact percentage through the book.
                val cfi  = si?.let { "epubcfi(/${(it + 1) * 2})" }
                calibreSync.pushLastReadPosition(calibreId, book.libraryKey, format, cfi, book.readingProgress.toDouble())
            }

            // ── Success: mark everything up to startedAt as synced ────────────
            annotationDao.clearTombstones(bookId, startedAt)
            bookDao.setNotesSyncedAt(bookId, startedAt)
            Timber.d("Notes sync complete for '${book.title}' (${highlights.length()} item(s))")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Notes sync failed for '${book.title}'")
            // Nothing is lost — try again automatically once Calibre can be reached.
            syncScheduler.schedule()
            Result.failure(e)
        } finally {
            _syncingIds.update { it - bookId }
        }
    }

    /**
     * Syncs every Calibre book with pending changes. Called after a successful
     * library sync. Failures are logged and simply retried next time.
     * @return how many books were synced successfully.
     */
    suspend fun syncAllPending(): Int {
        val pending = bookDao.getBooksWithUnsyncedNotes()
        if (pending.isEmpty()) return 0
        Timber.d("Auto-syncing notes for ${pending.size} book(s)")
        return pending.count { syncBook(it.id).isSuccess }
    }

    // ── Read status ───────────────────────────────────────────────────────────

    /**
     * Sends one book's Read status to Calibre's "Read" column.
     * [columns] can be passed in when already known (library sync); otherwise
     * they're looked up from the book itself.
     * On success the book is no longer "pending". On any failure it stays pending
     * and is retried on the next library sync.
     */
    suspend fun pushReadStatus(bookId: String, columns: CalibreReadColumns? = null): ReadPushResult =
        withContext(Dispatchers.IO) {
            val book = bookDao.getById(bookId) ?: return@withContext ReadPushResult.NotLinked
            val calibreId = book.calibreBookId ?: return@withContext ReadPushResult.NotLinked
            try {
                val cols = columns ?: calibreSync.fetchReadColumns(calibreId, book.libraryKey)
                if (cols.readKey == null) return@withContext ReadPushResult.NoReadColumn

                // Times Read: add the finishes recorded here to Calibre's CURRENT count
                // (read fresh, so a number changed on the PC since the last sync isn't lost).
                val sentPending = book.pendingReads
                val newTimesRead = if (cols.timesKey != null && sentPending > 0) {
                    val meta = calibreSync.fetchBookJson(calibreId, book.libraryKey).optJSONObject("user_metadata")
                    CalibreReadColumns.timesValue(meta, cols.timesKey) + sentPending
                } else null

                calibreSync.pushReadStatus(calibreId, book.libraryKey, cols, book.isRead, book.readAt, newTimesRead)
                // Only clear if nothing changed while we were sending.
                bookDao.clearReadStatusDirty(bookId, book.isRead)
                if (newTimesRead != null) bookDao.applyTimesReadPushed(bookId, newTimesRead, sentPending)
                ReadPushResult.Synced
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Read status sync failed for '${book.title}'")
                ReadPushResult.Failed(e)
            }
        }

    /**
     * Sends every pending Read status. Called after a successful library sync,
     * with the columns that sync found (one lookup for the whole library).
     * @return how many books were synced.
     */
    suspend fun syncAllPendingReadStatus(libraryKey: String?, columns: CalibreReadColumns): Int {
        if (columns.readKey == null) return 0
        val pending = bookDao.getBooksWithUnsyncedReadStatus()
            .filter { libraryKey == null || it.libraryKey == null || it.libraryKey == libraryKey }
        if (pending.isEmpty()) return 0
        Timber.d("Syncing Read status for ${pending.size} book(s)")
        return pending.count { pushReadStatus(it.id, columns) == ReadPushResult.Synced }
    }

    // ── Star rating ───────────────────────────────────────────────────────────

    /**
     * Sends one book's star rating to Calibre's Rating field.
     * On any failure it stays pending and is retried on the next sync.
     */
    suspend fun pushRating(bookId: String): ReadPushResult = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId) ?: return@withContext ReadPushResult.NotLinked
        val calibreId = book.calibreBookId ?: return@withContext ReadPushResult.NotLinked
        try {
            calibreSync.pushRating(calibreId, book.libraryKey, book.rating)
            bookDao.clearRatingDirty(bookId, book.rating)
            ReadPushResult.Synced
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Rating sync failed for '${book.title}'")
            ReadPushResult.Failed(e)
        }
    }

    /**
     * Sends every pending rating (for [libraryKey], or all libraries when null).
     * @return how many books were synced.
     */
    suspend fun syncAllPendingRatings(libraryKey: String?): Int {
        val pending = bookDao.getBooksWithUnsyncedRating()
            .filter { libraryKey == null || it.libraryKey == null || it.libraryKey == libraryKey }
        if (pending.isEmpty()) return 0
        Timber.d("Syncing ratings for ${pending.size} book(s)")
        return pending.count { pushRating(it.id) == ReadPushResult.Synced }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Spine of the book's EPUB, from the saved offline copy or the reader's cache. */
    private fun spineFor(book: BookEntity): List<String> {
        if (book.fileType != FileType.EPUB) return emptyList()
        val candidates = listOfNotNull(
            book.localFilePath?.let { File(it) },
            File(appContext.cacheDir, "books/${book.id}.epub")
        )
        val file = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return emptyList()
        return EpubSpineReader.spinePaths(file)
    }

    /** Converts one OmniTome annotation into Calibre's highlight format. */
    private fun toCalibreHighlight(a: AnnotationEntity, spine: List<String>): JSONObject {
        val si = EpubSpineReader.indexOf(spine, a.chapterHref) ?: 0
        // Readium sometimes records a CFI fragment for the selection; use it when present.
        // Otherwise anchor at the start of the chapter ("/2").
        val partialCfi = runCatching {
            JSONObject(a.locatorJson).optJSONObject("locations")?.optString("partialCfi")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val anchor = partialCfi ?: "/2"

        return JSONObject().apply {
            put("type", "highlight")
            put("uuid", a.id)
            put("timestamp", iso(a.updatedAt))
            put("highlighted_text", a.selectedText?.takeIf { it.isNotBlank() } ?: "[Page note]")
            a.note?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
            put("style", JSONObject()
                .put("type", "builtin")
                .put("kind", "color")
                .put("which", calibreColor(a.color)))
            put("spine_index", si)
            spine.getOrNull(si)?.let { put("spine_name", it) }
            put("start_cfi", anchor)
            put("end_cfi", anchor)
            a.chapterTitle?.takeIf { it.isNotBlank() }?.let {
                put("toc_family_titles", JSONArray().put(it))
            }
        }
    }

    /** Calibre's built-in highlight colours are yellow, green, blue, red (shown as pink) and purple. */
    private fun calibreColor(c: AnnotationColor): String = when (c) {
        AnnotationColor.YELLOW -> "yellow"
        AnnotationColor.GREEN  -> "green"
        AnnotationColor.BLUE   -> "blue"
        AnnotationColor.PINK   -> "red"
        AnnotationColor.PURPLE -> "purple"
    }

    /** ISO-8601 UTC timestamp, e.g. 2026-11-20T19:45:12.345Z — the format Calibre expects. */
    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()
}

/** What happened when OmniTome tried to send a Read status to Calibre. */
sealed class ReadPushResult {
    /** Calibre has it now. */
    data object Synced : ReadPushResult()
    /** Not a Calibre book (or not linked yet) — nothing to send. */
    data object NotLinked : ReadPushResult()
    /** This Calibre library has no "Read" column — kept in OmniTome only, sent once one exists. */
    data object NoReadColumn : ReadPushResult()
    /** Couldn't reach Calibre or it refused — kept and retried at the next sync. */
    data class Failed(val error: Throwable) : ReadPushResult()
}
