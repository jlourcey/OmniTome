package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per saved bookmark.
 *
 * [locatorJson]       — Readium Locator serialised to JSON; used to navigate
 *                       back to the exact position when the user taps a bookmark.
 * [chapterTitle]      — Display name of the chapter at bookmark time (may be null
 *                       for books without a table of contents).
 * [chapterHref]       — Relative href of the chapter spine item, e.g. "chapter3.xhtml".
 * [label]             — Optional user-supplied name for the bookmark (e.g. "Where I
 *                       stopped before vacation"). Null means no custom label.
 * [progressionInBook] — 0.0–1.0 total book progression at the bookmarked position;
 *                       stored so the list can be sorted in reading order in SQL.
 */
@Entity(
    tableName = "bookmarks",
    indices   = [Index("bookId")]
)
data class BookmarkEntity(

    @PrimaryKey
    val id: String,

    /** The book this bookmark belongs to — matches [BookEntity.id]. */
    val bookId: String,

    /** Readium Locator serialised to JSON. */
    val locatorJson: String,

    /** Display name of the chapter at the bookmarked position; null if unknown. */
    val chapterTitle: String?,

    /** Relative href of the chapter spine item; null for PDFs. */
    val chapterHref: String?,

    /** Optional user-supplied label; null means no custom label. */
    val label: String?,

    /** 0.0–1.0 total progression — drives reading-order sort in SQL. */
    val progressionInBook: Double,

    val createdAt: Long
)
