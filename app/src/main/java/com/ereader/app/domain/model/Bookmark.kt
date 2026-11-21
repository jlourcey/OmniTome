package com.ereader.app.domain.model

import com.ereader.app.data.local.entity.BookmarkEntity

/**
 * The bookmark domain model — what the UI and ViewModel work with.
 *
 * Mirrors [BookmarkEntity] field for field; kept separate so database schema
 * changes don't bleed into UI code.
 */
data class Bookmark(
    val id: String,
    val bookId: String,
    val locatorJson: String,
    val chapterTitle: String?,
    val chapterHref: String?,
    val label: String?,
    val progressionInBook: Double,
    val createdAt: Long
) {
    /**
     * Human-readable display name for this bookmark.
     * Uses the user's custom label if set; otherwise the chapter title;
     * otherwise a generic "Bookmark" string.
     */
    val displayName: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: chapterTitle?.takeIf { it.isNotBlank() }
            ?: "Bookmark"

    /** Progress formatted as a percentage (e.g. "42%"). */
    val progressPercent: String
        get() = "${(progressionInBook * 100).toInt()}%"
}

// ── Mappers ───────────────────────────────────────────────────────────────────

fun BookmarkEntity.toDomain() = Bookmark(
    id                = id,
    bookId            = bookId,
    locatorJson       = locatorJson,
    chapterTitle      = chapterTitle,
    chapterHref       = chapterHref,
    label             = label,
    progressionInBook = progressionInBook,
    createdAt         = createdAt
)

fun Bookmark.toEntity() = BookmarkEntity(
    id                = id,
    bookId            = bookId,
    locatorJson       = locatorJson,
    chapterTitle      = chapterTitle,
    chapterHref       = chapterHref,
    label             = label,
    progressionInBook = progressionInBook,
    createdAt         = createdAt
)
