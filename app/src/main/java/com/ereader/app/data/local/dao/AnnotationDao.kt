package com.ereader.app.data.local.dao

import androidx.room.*
import com.ereader.app.data.local.entity.AnnotationEntity
import com.ereader.app.data.local.entity.AnnotationTombstoneEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the [AnnotationEntity] table.
 *
 * Observations (Flow) are used by the reader to keep decorations in sync and
 * by the Notes screen to reflect edits immediately.
 *
 * One-shot suspending queries are used when exporting to PDF.
 */
@Dao
interface AnnotationDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    /** Insert a new annotation or replace it if the same id already exists. */
    @Upsert
    suspend fun upsert(annotation: AnnotationEntity)

    /** Delete a single annotation by its UUID. */
    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Which book an annotation belongs to — read just before deleting it. */
    @Query("SELECT bookId FROM annotations WHERE id = :id")
    suspend fun getBookIdFor(id: String): String?

    // ── Tombstones (deleted notes waiting to be reported to Calibre) ──────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: AnnotationTombstoneEntity)

    @Query("SELECT * FROM annotation_tombstones WHERE bookId = :bookId")
    suspend fun getTombstonesForBook(bookId: String): List<AnnotationTombstoneEntity>

    /** Clears tombstones that were included in a successful sync. */
    @Query("DELETE FROM annotation_tombstones WHERE bookId = :bookId AND deletedAt <= :upTo")
    suspend fun clearTombstones(bookId: String, upTo: Long)

    /** Delete every annotation belonging to [bookId] (e.g. when a book is removed). */
    @Query("DELETE FROM annotations WHERE bookId = :bookId")
    suspend fun deleteAllForBook(bookId: String)

    // ── Live reads ────────────────────────────────────────────────────────────

    /**
     * Live stream of all annotations for a book in reading order.
     * Re-emits whenever an annotation is added, edited, or deleted.
     * Used by the reader to re-draw highlights and by the Notes screen for the list.
     */
    @Query("""
        SELECT * FROM annotations
        WHERE bookId = :bookId
        ORDER BY progressionInBook ASC, createdAt ASC
    """)
    fun observeForBook(bookId: String): Flow<List<AnnotationEntity>>

    // ── One-shot reads ────────────────────────────────────────────────────────

    /**
     * Snapshot of all annotations for a book.
     * Used for PDF/text export where we don't need live updates.
     */
    @Query("""
        SELECT * FROM annotations
        WHERE bookId = :bookId
        ORDER BY progressionInBook ASC, createdAt ASC
    """)
    suspend fun getForBook(bookId: String): List<AnnotationEntity>
}
