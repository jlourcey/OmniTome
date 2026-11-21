package com.ereader.app.data.local.dao

import androidx.room.*
import com.ereader.app.data.local.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the [BookmarkEntity] table.
 *
 * [observeForBook] drives the live bookmark list shown in the reader.
 * [getForBook] is a one-shot read used to check if the current page is bookmarked.
 */
@Dao
interface BookmarkDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    /** Insert a new bookmark or replace it if the same id already exists. */
    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    /** Delete a single bookmark by its UUID. */
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Delete every bookmark belonging to [bookId] (e.g. when a book is removed). */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    // ── Live reads ────────────────────────────────────────────────────────────

    /**
     * Live stream of all bookmarks for a book in reading order.
     * Re-emits whenever a bookmark is added or deleted.
     */
    @Query("""
        SELECT * FROM bookmarks
        WHERE bookId = :bookId
        ORDER BY progressionInBook ASC, createdAt ASC
    """)
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    // ── One-shot reads ────────────────────────────────────────────────────────

    /**
     * Snapshot of all bookmarks for a book.
     * Used when checking if the current position is already bookmarked.
     */
    @Query("""
        SELECT * FROM bookmarks
        WHERE bookId = :bookId
        ORDER BY progressionInBook ASC, createdAt ASC
    """)
    suspend fun getForBook(bookId: String): List<BookmarkEntity>
}
