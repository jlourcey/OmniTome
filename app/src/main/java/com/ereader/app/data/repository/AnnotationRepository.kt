package com.ereader.app.data.repository

import com.ereader.app.data.local.dao.AnnotationDao
import com.ereader.app.data.local.entity.AnnotationTombstoneEntity
import com.ereader.app.domain.model.Annotation
import com.ereader.app.domain.model.toDomain
import com.ereader.app.domain.model.toEntity
import com.ereader.app.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point of access for all annotation data.
 *
 * The reader uses [observeForBook] to keep the decoration layer in sync with
 * the database in real time. The Notes screen uses the same flow for its list.
 * Export functions call [getForBook] for a one-shot snapshot.
 */
@Singleton
class AnnotationRepository @Inject constructor(
    private val dao: AnnotationDao,
    private val syncScheduler: SyncScheduler
) {

    /** Live stream of all annotations for [bookId], in reading order. */
    fun observeForBook(bookId: String): Flow<List<Annotation>> =
        dao.observeForBook(bookId).map { list -> list.map { it.toDomain() } }

    /** One-shot snapshot — used when exporting to PDF. */
    suspend fun getForBook(bookId: String): List<Annotation> =
        dao.getForBook(bookId).map { it.toDomain() }

    /** Save (insert or update) an annotation. */
    /** Save (insert or update) an annotation, and queue it for Calibre. */
    suspend fun save(annotation: Annotation) {
        dao.upsert(annotation.toEntity())
        syncScheduler.schedule()
    }

    /**
     * Delete a single annotation by id.
     * Leaves a tombstone so the next Calibre sync can remove it there too.
     */
    suspend fun delete(id: String) {
        val bookId = dao.getBookIdFor(id)
        dao.deleteById(id)
        if (bookId != null) {
            dao.insertTombstone(AnnotationTombstoneEntity(id, bookId, System.currentTimeMillis()))
            syncScheduler.schedule()
        }
    }

    /** Delete all annotations for a book (e.g. when the book is removed). */
    suspend fun deleteAll(bookId: String) = dao.deleteAllForBook(bookId)
}
