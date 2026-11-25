package com.ereader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ereader.app.data.local.entity.BookEntity
import com.ereader.app.data.local.entity.BookTextEntity
import com.ereader.app.data.local.entity.SearchIndexedEntity
import com.ereader.app.data.local.entity.TextSearchHit
import kotlinx.coroutines.flow.Flow

/** Full-text search over books saved on the phone. */
@Dao
interface SearchDao {

    /**
     * Chapters matching [match] (FTS syntax, e.g. "dark* stormy*").
     * snippet(): about 14 words around the match, matched words in [ ].
     */
    @Query("""
        SELECT bookId, chapterHref, chapterTitle,
               snippet(book_text, '[', ']', '…', 3, 14) AS snippet
        FROM book_text WHERE book_text MATCH :match LIMIT 300
    """)
    suspend fun search(match: String): List<TextSearchHit>

    @Query("SELECT text FROM book_text WHERE bookId = :bookId AND chapterHref = :href LIMIT 1")
    suspend fun chapterText(bookId: String, href: String): String?

    @Insert
    suspend fun insertAll(rows: List<BookTextEntity>)

    @Query("DELETE FROM book_text WHERE bookId = :bookId")
    suspend fun deleteText(bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markIndexed(entry: SearchIndexedEntity)

    @Query("DELETE FROM search_indexed WHERE bookId = :bookId")
    suspend fun unmark(bookId: String)

    @Query("SELECT COUNT(*) FROM search_indexed")
    fun observeIndexedCount(): Flow<Int>

    /** EPUBs on the phone (saved or imported) that aren't in the index yet. */
    @Query("""
        SELECT * FROM books
        WHERE localFilePath IS NOT NULL AND fileType = 'EPUB'
          AND (isDownloaded = 1 OR source = 'LOCAL')
          AND id NOT IN (SELECT bookId FROM search_indexed)
    """)
    suspend fun booksNeedingIndex(): List<BookEntity>
}
