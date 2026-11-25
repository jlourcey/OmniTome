package com.ereader.app.data.local.dao

import androidx.room.*
import com.ereader.app.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object — every SQL query for the books table lives here.
 *
 * Key patterns:
 *  - Functions that return [Flow] are "live" — Room re-emits automatically
 *    whenever the underlying data changes. Use these for the UI.
 *  - Functions marked [suspend] run on a background thread via coroutines.
 *    Use these for one-shot reads/writes (sync, saving progress, etc.).
 */
@Dao
interface BookDao {

    // ── Live queries (UI observes these) ─────────────────────────────────────

    /** Stream of all books, alphabetical by title. Updates when data changes. */
    //
    // Every list below shows only the books of the library chosen in the
    // bookshelf switch, plus Drive / local books (which belong to no library).
    // :includeUnassigned also shows Calibre books synced before multi-library
    // support, until the next sync assigns them.

    @Query("SELECT * FROM books WHERE (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1)) ORDER BY title ASC")
    fun observeAll(libraryKey: String, includeUnassigned: Boolean): Flow<List<BookEntity>>

    /** Stream of all books sorted by author name, then title within each author. */
    @Query("SELECT * FROM books WHERE (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1)) ORDER BY normalizedAuthor ASC, title ASC")
    fun observeAllByAuthor(libraryKey: String, includeUnassigned: Boolean): Flow<List<BookEntity>>

    /** Stream of all books sorted newest-first (matches Calibre's default "Recently Added" view). */
    @Query("SELECT * FROM books WHERE (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1)) ORDER BY addedAt DESC")
    fun observeAllByDate(libraryKey: String, includeUnassigned: Boolean): Flow<List<BookEntity>>

    /** Stream of books belonging to a single series, in reading order.
     *
     * CASE WHEN puts NULL seriesIndex values last — SQLite's default ASC ordering
     * treats NULL as the smallest value, which would push un-numbered books to the
     * top ahead of Book 1, 2, 3. The CASE expression flips that: 0 = has index
     * (sorts first), 1 = no index (sorts last), then within each group by the
     * actual float value.
     */
    @Query("""
        SELECT * FROM books
        WHERE series = :series AND (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1))
        ORDER BY
            CASE WHEN seriesIndex IS NULL THEN 1 ELSE 0 END,
            seriesIndex ASC,
            title ASC
    """)
    fun observeBySeries(series: String, libraryKey: String, includeUnassigned: Boolean): Flow<List<BookEntity>>

    /** Stream of distinct series names, alphabetical. */
    @Query("SELECT DISTINCT series FROM books WHERE series IS NOT NULL AND (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1)) ORDER BY series ASC")
    fun observeAllSeries(libraryKey: String, includeUnassigned: Boolean): Flow<List<String>>

    /** Stream of all favorited books, alphabetical by title. */
    @Query("SELECT * FROM books WHERE isFavorite = 1 AND (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1)) ORDER BY title ASC")
    fun observeAllFavorites(libraryKey: String, includeUnassigned: Boolean): Flow<List<BookEntity>>

    /** Stream of locally imported books (source = LOCAL), alphabetical by title. */
    @Query("SELECT * FROM books WHERE source = 'LOCAL' ORDER BY addedAt DESC")
    fun observeAllLocalBooks(): Flow<List<BookEntity>>

    /**
     * The single most recently opened in-progress book (0 < progress < 1).
     * Used for the "Continue Reading" banner on the bookshelf.
     * Books that are finished (progress = 1) are excluded.
     */
    @Query("""
        SELECT * FROM books
        WHERE readingProgress > 0.0 AND readingProgress < 1.0 AND lastReadAt IS NOT NULL
          AND (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1))
        ORDER BY lastReadAt DESC
        LIMIT 1
    """)
    fun observeCurrentlyReading(libraryKey: String, includeUnassigned: Boolean): Flow<BookEntity?>

    /** Full-text-style search across title, author, series, and tags fields. */
    @Query("""
        SELECT * FROM books
        WHERE (title  LIKE '%' || :query || '%'
            OR author LIKE '%' || :query || '%'
            OR series LIKE '%' || :query || '%'
            OR tags   LIKE '%' || :query || '%')
          AND (source != 'CALIBRE' OR libraryKey = :libraryKey OR (libraryKey IS NULL AND :includeUnassigned = 1))
        ORDER BY title ASC
    """)
    fun search(query: String, libraryKey: String, includeUnassigned: Boolean): Flow<List<BookEntity>>

    /**
     * Books finished on or after [since] (start of this year), newest first.
     * Feeds the "Finished this year" shelf. Covers every library you've synced,
     * because it's your personal reading record, not one library's.
     */
    @Query("SELECT * FROM books WHERE isRead = 1 AND readAt IS NOT NULL AND readAt >= :since ORDER BY readAt DESC")
    fun observeFinishedSince(since: Long): Flow<List<BookEntity>>

    // ── One-shot reads ────────────────────────────────────────────────────────

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: String): BookEntity?

    /**
     * Deduplication query #1 — find books with the same normalized title + author.
     * The index on (normalizedTitle, normalizedAuthor) makes this fast even
     * with thousands of books.
     */
    @Query("""
        SELECT * FROM books
        WHERE normalizedTitle  = :normalizedTitle
          AND normalizedAuthor = :normalizedAuthor
        LIMIT 5
    """)
    suspend fun findByNormalizedTitleAuthor(
        normalizedTitle: String,
        normalizedAuthor: String
    ): List<BookEntity>

    /**
     * Deduplication query #2 — ISBN is the gold-standard identifier.
     * If two books share an ISBN they are definitely the same book.
     */
    @Query("SELECT * FROM books WHERE isbn = :isbn AND isbn IS NOT NULL LIMIT 5")
    suspend fun findByIsbn(isbn: String): List<BookEntity>

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Insert-or-update a single book.
     * @Upsert replaces the row if the primary key already exists, inserts if not.
     */
    @Upsert
    suspend fun upsert(book: BookEntity)

    /** Batch upsert — used after a full library sync. */
    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    /** Save the user's reading position without touching any other fields. */
    @Query("""
        UPDATE books
        SET lastLocator     = :locator,
            readingProgress = :progress,
            lastReadAt      = :timestamp
        WHERE id = :id
    """)
    suspend fun updateReadingProgress(
        id: String,
        locator: String,
        progress: Float,
        timestamp: Long = System.currentTimeMillis()
    )

    /** Toggle the favorite heart icon on a book. */
    @Query("UPDATE books SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /**
     * Marks a book as saved on the phone for offline reading (or clears it).
     * Only touches the two download columns — reading progress, favorites,
     * and metadata are left alone.
     */
    @Query("UPDATE books SET isDownloaded = :downloaded, localFilePath = :path WHERE id = :id")
    suspend fun setDownloaded(id: String, downloaded: Boolean, path: String?)

    // ── Read status ───────────────────────────────────────────────────────────

    /**
     * Sets the Read status from inside OmniTome ("Mark as Read" / "Mark as Unread",
     * or reaching the last page). [dirty] = still needs sending to Calibre.
     *
     * Also keeps the Times Read count:
     *  - Unread → Read adds one finish waiting to go to Calibre ([BookEntity.pendingReads]).
     *  - Read → Unread before that finish was sent takes it back (a mis-tap).
     */
    @Query("""
        UPDATE books
        SET isRead          = :isRead,
            readAt          = :readAt,
            readStatusDirty = :dirty,
            pendingReads    = CASE
                WHEN :isRead = 1 AND isRead = 0 THEN pendingReads + 1
                WHEN :isRead = 0 AND isRead = 1 AND pendingReads > 0 THEN pendingReads - 1
                ELSE pendingReads
            END
        WHERE id = :id
    """)
    suspend fun setReadStatus(id: String, isRead: Boolean, readAt: Long?, dirty: Boolean)

    /**
     * Called after Calibre accepted a new Times Read value:
     * [newTimesRead] is what Calibre now holds, and [sentPending] of the
     * waiting finishes were included in it.
     */
    @Query("""
        UPDATE books
        SET timesRead    = :newTimesRead,
            pendingReads = MAX(pendingReads - :sentPending, 0)
        WHERE id = :id
    """)
    suspend fun applyTimesReadPushed(id: String, newTimesRead: Int, sentPending: Int)

    /** Copies Calibre's Times Read count during library sync. */
    @Query("""
        UPDATE books
        SET timesRead = :timesRead
        WHERE calibreBookId = :calibreBookId
          AND (libraryKey = :libraryKey OR libraryKey IS NULL)
    """)
    suspend fun applyCalibreTimesRead(libraryKey: String, calibreBookId: Int, timesRead: Int)

    // ── Star rating ───────────────────────────────────────────────────────────

    /** Sets the rating from inside OmniTome. [dirty] = still needs sending to Calibre. */
    @Query("UPDATE books SET rating = :rating, ratingDirty = :dirty WHERE id = :id")
    suspend fun setRating(id: String, rating: Int?, dirty: Boolean)

    /** Called after Calibre accepted the rating — only if it wasn't changed again meanwhile. */
    @Query("UPDATE books SET ratingDirty = 0 WHERE id = :id AND rating IS :sentRating")
    suspend fun clearRatingDirty(id: String, sentRating: Int?)

    /** Calibre-linked books whose rating changed in OmniTome and hasn't reached Calibre. */
    @Query("SELECT * FROM books WHERE ratingDirty = 1 AND calibreBookId IS NOT NULL")
    suspend fun getBooksWithUnsyncedRating(): List<BookEntity>

    /**
     * Copies Calibre's rating during library sync — skipped while our own
     * change is still waiting to be sent.
     */
    @Query("""
        UPDATE books
        SET rating = :rating
        WHERE calibreBookId = :calibreBookId
          AND (libraryKey = :libraryKey OR libraryKey IS NULL)
          AND ratingDirty = 0
    """)
    suspend fun applyCalibreRating(libraryKey: String, calibreBookId: Int, rating: Int?)

    /** Called after Calibre accepted the read status — stops it being sent again. */
    @Query("UPDATE books SET readStatusDirty = 0 WHERE id = :id AND isRead = :sentIsRead")
    suspend fun clearReadStatusDirty(id: String, sentIsRead: Boolean)

    /** Calibre-linked books whose read status changed in OmniTome and hasn't reached Calibre. */
    @Query("SELECT * FROM books WHERE readStatusDirty = 1 AND calibreBookId IS NOT NULL")
    suspend fun getBooksWithUnsyncedReadStatus(): List<BookEntity>

    /**
     * Copies Calibre's Read status onto our copy of the book during library sync.
     * Skipped while our own change is still waiting to be sent ([readStatusDirty]),
     * so marking a book offline is never undone by the next sync.
     *  - Read in Calibre   → Read here; Calibre's "Date Read" wins when it has one,
     *                        otherwise any date we already had is kept.
     *  - Unread in Calibre → Unread here, and the date is cleared.
     */
    @Query("""
        UPDATE books
        SET isRead = :isRead,
            readAt = CASE WHEN :isRead = 1 THEN COALESCE(:calibreReadAt, readAt) ELSE NULL END
        WHERE calibreBookId = :calibreBookId
          AND (libraryKey = :libraryKey OR libraryKey IS NULL)
          AND readStatusDirty = 0
    """)
    suspend fun applyCalibreReadStatus(
        libraryKey: String,
        calibreBookId: Int,
        isRead: Boolean,
        calibreReadAt: Long?
    )

    /** Records a successful notes sync to Calibre. */
    @Query("UPDATE books SET notesSyncedAt = :time WHERE id = :id")
    suspend fun setNotesSyncedAt(id: String, time: Long)

    /**
     * Calibre-linked books with something not yet sent to Calibre:
     * pages read, a new note/highlight, or a deleted one (tombstone).
     */
    @Query("""
        SELECT * FROM books
        WHERE calibreBookId IS NOT NULL AND (
            (lastReadAt IS NOT NULL AND lastReadAt > COALESCE(notesSyncedAt, 0))
            OR EXISTS (SELECT 1 FROM annotations a
                       WHERE a.bookId = books.id AND a.updatedAt > COALESCE(books.notesSyncedAt, 0))
            OR EXISTS (SELECT 1 FROM annotation_tombstones t WHERE t.bookId = books.id)
        )
    """)
    suspend fun getBooksWithUnsyncedNotes(): List<BookEntity>

    /**
     * Calibre books synced before multi-library support have no library.
     * They all came from the login's default library, so assign them to it.
     */
    @Query("UPDATE books SET libraryKey = :libraryKey WHERE source = 'CALIBRE' AND libraryKey IS NULL")
    suspend fun claimUnassignedCalibreBooks(libraryKey: String)

    /** Hard-delete a book by its ID (used after deduplication to remove spare rows). */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Back-fills series metadata for books that were synced before series data
     * was available. Only updates rows where series IS NULL so manually-set
     * series names are never overwritten.
     *
     * Matched by normalizedTitle + normalizedAuthor — the same keys used by the
     * deduplication engine (lowercase, punctuation stripped).
     */
    /**
     * Sets series name and index for a book matched by normalizedTitle +
     * normalizedAuthor. Called during sync after fetching authoritative
     * series data from Calibre's JSON API. Always overwrites both fields
     * because Calibre is the source of truth for series metadata.
     */
    @Query("""
        UPDATE books
        SET series      = :series,
            seriesIndex = :seriesIndex
        WHERE normalizedTitle  = :normalizedTitle
          AND normalizedAuthor = :normalizedAuthor
          AND (libraryKey = :libraryKey OR libraryKey IS NULL)
    """)
    suspend fun backfillSeriesMetadata(
        libraryKey: String,
        normalizedTitle: String,
        normalizedAuthor: String,
        series: String,
        seriesIndex: Float?
    )

    /**
     * Overwrites the tags column for a book matched by normalizedTitle +
     * normalizedAuthor. Called during sync from Calibre's JSON API response.
     * Calibre is the source of truth for tags, so we always overwrite.
     */
    @Query("""
        UPDATE books
        SET tags = :tags
        WHERE normalizedTitle  = :normalizedTitle
          AND normalizedAuthor = :normalizedAuthor
          AND (libraryKey = :libraryKey OR libraryKey IS NULL)
    """)
    suspend fun backfillTags(
        libraryKey: String,
        normalizedTitle: String,
        normalizedAuthor: String,
        tags: String
    )

    /**
     * Stores Calibre's internal integer book ID so metadata edits can be pushed
     * back to the server via POST /cdb/set-book-metadata.
     * Only sets the value when NULL — avoids overwriting a valid ID on re-sync.
     */
    @Query("""
        UPDATE books
        SET calibreBookId = :calibreBookId
        WHERE normalizedTitle  = :normalizedTitle
          AND normalizedAuthor = :normalizedAuthor
          AND calibreBookId IS NULL
          AND (libraryKey = :libraryKey OR libraryKey IS NULL)
    """)
    suspend fun backfillCalibreBookId(
        libraryKey: String,
        normalizedTitle: String,
        normalizedAuthor: String,
        calibreBookId: Int
    )
}
