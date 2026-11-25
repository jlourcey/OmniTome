package com.ereader.app.data.repository

import androidx.room.withTransaction
import com.ereader.app.data.local.AppDatabase
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.local.dao.ShelfDao
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.local.entity.ShelfBookEntity
import com.ereader.app.data.local.entity.ShelfEntity
import com.ereader.app.data.local.entity.ShelfWithCount
import com.ereader.app.data.remote.calibre.CalibreReadColumns
import com.ereader.app.data.remote.calibre.CalibreSyncService
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Custom shelves ("Beach reads", "To read", …). A book can be on any number.
 *
 * Calibre: if a library has a "Shelves" column (Calibre → Preferences → Add your
 * own columns → lookup name `shelves`, type "Comma separated text, like tags"),
 * each Calibre book's shelves are kept in that column — both ways, like the Read
 * column. Shelves you change in OmniTome are sent to Calibre (now, or at the next
 * sync from home); shelves set in Calibre appear in OmniTome after a sync.
 * Without the column, shelves live only in the app. Books from Google Drive or
 * imported from the phone can always be shelved, just not synced.
 */
@Singleton
class ShelfRepository @Inject constructor(
    private val shelfDao: ShelfDao,
    private val bookDao: BookDao,
    private val appDatabase: AppDatabase,
    private val calibreSync: CalibreSyncService
) {
    fun observeShelves(): Flow<List<ShelfWithCount>> = shelfDao.observeShelves()

    fun observeBooksOnShelf(shelfId: String): Flow<List<Book>> =
        shelfDao.observeBooksOnShelf(shelfId).map { list -> list.map { it.toDomain() } }

    suspend fun allShelves(): List<ShelfEntity> = shelfDao.getAll()

    suspend fun shelfIdsForBook(bookId: String): Set<String> = shelfDao.shelfIdsForBook(bookId).toSet()

    /** Creates a shelf (or returns the existing one with that name, any capitalisation). */
    suspend fun createShelf(name: String): ShelfEntity? {
        val clean = name.trim().replace(Regex("\\s+"), " ").replace(",", " ").take(60)
        if (clean.isEmpty()) return null
        shelfDao.findByName(clean)?.let { return it }
        val shelf = ShelfEntity(UUID.randomUUID().toString(), clean)
        shelfDao.insertShelf(shelf)
        return shelfDao.findByName(clean)
    }

    suspend fun renameShelf(shelfId: String, newName: String): Boolean {
        val clean = newName.trim().replace(Regex("\\s+"), " ").replace(",", " ").take(60)
        if (clean.isEmpty()) return false
        shelfDao.findByName(clean)?.let { if (it.id != shelfId) return false }   // name taken
        appDatabase.withTransaction {
            shelfDao.rename(shelfId, clean)
            markMembersDirty(shelfId)
        }
        pushAllPending()
        return true
    }

    suspend fun deleteShelf(shelfId: String) {
        appDatabase.withTransaction {
            markMembersDirty(shelfId)
            shelfDao.clearShelf(shelfId)
            shelfDao.deleteShelf(shelfId)
        }
        pushAllPending()
    }

    private suspend fun markMembersDirty(shelfId: String) {
        shelfDao.bookIdsOnShelf(shelfId).forEach { id ->
            val b = bookDao.getById(id) ?: return@forEach
            if (b.source == BookSource.CALIBRE && b.calibreBookId != null) bookDao.setShelvesDirty(id, true)
        }
    }

    /**
     * Puts [book] on exactly [shelfIds] (adds and removes as needed), then tries
     * to send the change to Calibre.
     */
    suspend fun setBookShelves(book: Book, shelfIds: Set<String>): ReadPushResult {
        val linked = book.source == BookSource.CALIBRE && book.calibreBookId != null
        appDatabase.withTransaction {
            val current = shelfDao.shelfIdsForBook(book.id).toSet()
            (current - shelfIds).forEach { shelfDao.remove(it, book.id) }
            (shelfIds - current).forEach { shelfDao.add(ShelfBookEntity(it, book.id)) }
            if (linked) bookDao.setShelvesDirty(book.id, true)
        }
        return if (linked) pushBook(book.id) else ReadPushResult.NotLinked
    }

    suspend fun removeFromShelf(shelfId: String, book: Book) {
        val ids = shelfIdsForBook(book.id) - shelfId
        setBookShelves(book, ids)
    }

    // ── Calibre ───────────────────────────────────────────────────────────────

    /**
     * Library sync: Calibre's Shelves column → OmniTome, for one book.
     * Skipped while this book's own change is still waiting to be sent.
     * Call inside the sync's transaction.
     */
    suspend fun applyFromCalibre(libraryKey: String, calibreBookId: Int, names: List<String>) {
        val book = bookDao.findByCalibreId(libraryKey, calibreBookId) ?: return
        if (book.shelvesDirty) return
        val wanted = names.mapNotNull { createShelf(it)?.id }.toSet()
        val current = shelfDao.shelfIdsForBook(book.id).toSet()
        if (wanted == current) return
        (current - wanted).forEach { shelfDao.remove(it, book.id) }
        (wanted - current).forEach { shelfDao.add(ShelfBookEntity(it, book.id)) }
    }

    /** Sends one book's shelves to Calibre's Shelves column. */
    suspend fun pushBook(bookId: String, columns: CalibreReadColumns? = null): ReadPushResult {
        val book = bookDao.getById(bookId) ?: return ReadPushResult.NotLinked
        val calibreId = book.calibreBookId ?: return ReadPushResult.NotLinked
        return try {
            val cols = columns ?: calibreSync.fetchReadColumns(calibreId, book.libraryKey)
            val key = cols.shelvesKey ?: return ReadPushResult.NoReadColumn
            calibreSync.pushShelves(calibreId, book.libraryKey, key, shelfDao.shelfNamesForBook(bookId))
            bookDao.setShelvesDirty(bookId, false)
            ReadPushResult.Synced
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Shelves sync failed for '${book.title}'")
            ReadPushResult.Failed(e)
        }
    }

    /** Sends every pending shelf change (after a library sync, or a rename/delete). */
    suspend fun pushAllPending(libraryKey: String? = null, columns: CalibreReadColumns? = null): Int {
        if (columns != null && columns.shelvesKey == null) return 0
        return bookDao.getBooksWithUnsyncedShelves()
            .filter { libraryKey == null || it.libraryKey == null || it.libraryKey == libraryKey }
            .count { pushBook(it.id, columns) == ReadPushResult.Synced }
    }
}
