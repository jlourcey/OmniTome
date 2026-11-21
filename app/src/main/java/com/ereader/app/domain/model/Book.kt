package com.ereader.app.domain.model

import com.ereader.app.data.local.entity.BookEntity
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.local.entity.FileType
import java.util.UUID

/**
 * Book — the domain model.
 *
 * This is the object the ViewModel and UI work with. It has NO Room annotations
 * and NO network-specific fields — it's clean business logic data.
 *
 * Why keep this separate from [BookEntity]?
 *   The database schema and the UI's needs often diverge over time. Keeping them
 *   separate means changing the database doesn't break the UI and vice versa.
 *   The mapping functions [toEntity] and [toDomain] handle the translation.
 */
data class Book(
    val id: String               = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val isbn: String?            = null,
    val coverUrl: String?        = null,
    val tags: List<String>       = emptyList(),
    val fileUrl: String,
    val fileType: FileType       = FileType.EPUB,
    val series: String?          = null,
    val seriesIndex: Float?      = null,
    val description: String?     = null,
    val source: BookSource       = BookSource.CALIBRE,
    val readingProgress: Float   = 0f,
    val lastLocator: String?     = null,
    val lastReadAt: Long?        = null,
    val isFavorite: Boolean      = false,
    val isDownloaded: Boolean    = false,
    val localFilePath: String?   = null,
    /** Calibre's internal integer ID — present for CALIBRE-source books after first sync. */
    val calibreBookId: Int?      = null,
    /** Calibre library id this book belongs to (null for Drive / local books). */
    val libraryKey: String?      = null
)

// ── Mapping functions ─────────────────────────────────────────────────────────

/**
 * Convert a domain [Book] → [BookEntity] for storage in Room.
 * The normalize() call produces the lowercase, punctuation-free versions
 * used by the deduplication queries.
 */
fun Book.toEntity(): BookEntity = BookEntity(
    id               = id,
    title            = title,
    normalizedTitle  = title.normalize(),
    author           = author,
    normalizedAuthor = author.normalize(),
    isbn             = isbn,
    coverUrl         = coverUrl,
    tags             = tags.joinToString(",").ifEmpty { null },
    fileUrl          = fileUrl,
    fileType         = fileType,
    series           = series,
    seriesIndex      = seriesIndex,
    description      = description,
    source           = source,
    readingProgress  = readingProgress,
    lastLocator      = lastLocator,
    isFavorite       = isFavorite,
    isDownloaded     = isDownloaded,
    localFilePath    = localFilePath,
    calibreBookId    = calibreBookId,
    libraryKey       = libraryKey
)

/**
 * Convert a [BookEntity] from Room → domain [Book] for the UI.
 */
fun BookEntity.toDomain(): Book = Book(
    id              = id,
    title           = title,
    author          = author,
    isbn            = isbn,
    coverUrl        = coverUrl,
    tags            = tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
    fileUrl         = fileUrl,
    fileType        = fileType,
    series          = series,
    seriesIndex     = seriesIndex,
    description     = description,
    source          = source,
    readingProgress = readingProgress,
    lastLocator     = lastLocator,
    lastReadAt      = lastReadAt,
    isFavorite      = isFavorite,
    isDownloaded    = isDownloaded,
    localFilePath   = localFilePath,
    calibreBookId   = calibreBookId,
    libraryKey      = libraryKey
)

/**
 * Normalizes a string for duplicate detection:
 *  - Converts to lowercase
 *  - Removes everything that isn't a letter, digit, or space
 *  - Collapses multiple spaces into one
 *  - Trims leading/trailing whitespace
 *
 * Examples:
 *   "The Lord of the Rings" → "the lord of the rings"
 *   "Tolkien, J.R.R."       → "tolkien jrr"
 *   "Dune (1965)"           → "dune 1965"
 */
fun String.normalize(): String =
    this.lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
