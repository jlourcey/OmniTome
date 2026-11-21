package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── Enums stored as strings in the database ───────────────────────────────────

/** The file format of the book. */
enum class FileType { EPUB, PDF, UNKNOWN }

/** Where this book originally came from. */
enum class BookSource { CALIBRE, GOOGLE_DRIVE, LOCAL }

/**
 * Room database table — one row per unique book in the merged library.
 *
 * Key design decisions:
 *  - [normalizedTitle] and [normalizedAuthor] are lowercase, punctuation-stripped
 *    versions of the display fields. The composite index on them makes duplicate
 *    detection fast without a full table scan.
 *  - [isbn] is indexed separately because it's the most reliable duplicate signal.
 *  - [series] is indexed so filtering by series doesn't scan the whole table.
 *  - Reading state ([lastLocator], [readingProgress]) lives here so a single
 *    Room update saves the user's position — no separate table needed.
 *
 * @Entity marks this class as a Room table. The [indices] parameter creates
 * database indexes that make queries on those columns much faster.
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["normalizedTitle", "normalizedAuthor"]),
        Index(value = ["isbn"]),
        Index(value = ["series"]),
        Index(value = ["libraryKey"])
    ]
)
data class BookEntity(

    /** Stable UUID assigned once on first insert and never changed. */
    @PrimaryKey
    val id: String,

    // ── Display metadata ─────────────────────────────────────────────────────
    val title: String,
    val author: String,

    /** Lowercase, trimmed, punctuation-stripped title — used by deduplication. */
    val normalizedTitle: String,

    /** Lowercase, trimmed, punctuation-stripped author — used by deduplication. */
    val normalizedAuthor: String,

    val isbn: String?          = null,
    val coverUrl: String?      = null,
    val description: String?   = null,
    val publishedYear: Int?    = null,
    val language: String       = "en",

    // ── Tags / subjects ───────────────────────────────────────────────────────
    /** Genres / subjects from Calibre, comma-separated: "Fantasy,Magic,Adventure" */
    val tags: String?          = null,

    // ── Series ───────────────────────────────────────────────────────────────
    val series: String?        = null,
    val seriesIndex: Float?    = null,  // e.g. 1.0, 2.0, 2.5 for a novella

    // ── File location ─────────────────────────────────────────────────────────
    /** Remote URL (Calibre/Drive) or local file URI once downloaded. */
    val fileUrl: String,
    val fileType: FileType     = FileType.EPUB,
    val source: BookSource     = BookSource.CALIBRE,
    val isDownloaded: Boolean  = false,

    /** Absolute path on-device after the file has been downloaded. */
    val localFilePath: String? = null,

    // ── Reading state ─────────────────────────────────────────────────────────
    /** Readium Locator serialized to JSON — restores exact reading position. */
    val lastLocator: String?   = null,

    /** 0.0 = not started, 1.0 = finished. Drives the progress arc in the UI. */
    val readingProgress: Float = 0f,
    val lastReadAt: Long?      = null,

    // ── User preferences ──────────────────────────────────────────────────────
    val isFavorite: Boolean    = false,

    // ── Calibre link ──────────────────────────────────────────────────────────
    /**
     * Calibre Content Server's internal integer ID for this book (e.g. 521).
     * Populated during sync from /ajax/search.  Used when pushing metadata
     * edits back to Calibre via POST /cdb/set-book-metadata.
     * NULL for Drive books, local imports, or any book not yet synced via JSON API.
     */
    val calibreBookId: Int?    = null,

    /**
     * Which Calibre library this book belongs to (Calibre's library id, e.g.
     * "Calibre_Library"). NULL for Drive / local books, and for Calibre books
     * synced before multi-library support (claimed on the next sync).
     */
    val libraryKey: String?    = null,

    // ── Calibre notes sync ────────────────────────────────────────────────────
    /**
     * When this book's notes, highlights, and reading position were last sent
     * to Calibre successfully (epoch ms). NULL = never synced.
     * Anything changed after this moment is "pending" — see NotesSyncRepository.
     */
    val notesSyncedAt: Long?   = null,

    // ── Housekeeping ──────────────────────────────────────────────────────────
    val addedAt: Long          = System.currentTimeMillis(),
    val updatedAt: Long        = System.currentTimeMillis()
)
