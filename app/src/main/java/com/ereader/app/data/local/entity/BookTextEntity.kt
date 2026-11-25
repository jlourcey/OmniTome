package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-text search index: the text of one chapter of a book saved on the phone.
 * SQLite FTS4 with the unicode61 tokenizer (ignores case and accents).
 * [bookId] and [chapterHref] are stored but not searched.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["bookId", "chapterHref"])
@Entity(tableName = "book_text")
data class BookTextEntity(
    val bookId: String,
    val chapterHref: String,
    val chapterTitle: String?,
    val text: String
)

/** Which books are in the search index (and when they were added). */
@Entity(tableName = "search_indexed")
data class SearchIndexedEntity(
    @PrimaryKey val bookId: String,
    val indexedAt: Long = System.currentTimeMillis()
)

/** One search hit: a chapter of a book, with a short excerpt around the words. */
data class TextSearchHit(
    val bookId: String,
    val chapterHref: String,
    val chapterTitle: String?,
    val snippet: String
)
