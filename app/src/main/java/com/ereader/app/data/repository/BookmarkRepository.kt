package com.ereader.app.data.repository

import com.ereader.app.data.local.dao.BookmarkDao
import com.ereader.app.domain.model.Bookmark
import com.ereader.app.domain.model.toDomain
import com.ereader.app.domain.model.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point of access for all bookmark data.
 *
 * The reader uses [observeForBook] to keep the bookmark list in sync in real
 * time. [getForBook] is used for one-shot checks (e.g. "is this page already
 * bookmarked?").
 */
@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao
) {

    /** Live stream of all bookmarks for [bookId], in reading order. */
    fun observeForBook(bookId: String): Flow<List<Bookmark>> =
        dao.observeForBook(bookId).map { list -> list.map { it.toDomain() } }

    /** One-shot snapshot of all bookmarks for [bookId]. */
    suspend fun getForBook(bookId: String): List<Bookmark> =
        dao.getForBook(bookId).map { it.toDomain() }

    /** Save (insert or update) a bookmark. */
    suspend fun save(bookmark: Bookmark) = dao.upsert(bookmark.toEntity())

    /** Delete a single bookmark by id. */
    suspend fun delete(id: String) = dao.deleteById(id)

    /** Delete all bookmarks for a book (called when the book is removed). */
    suspend fun deleteAll(bookId: String) = dao.deleteAllForBook(bookId)
}
