package com.ereader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ereader.app.data.local.entity.BookEntity
import com.ereader.app.data.local.entity.ShelfBookEntity
import com.ereader.app.data.local.entity.ShelfEntity
import com.ereader.app.data.local.entity.ShelfWithCount
import kotlinx.coroutines.flow.Flow

/** Custom shelves and which books are on them. */
@Dao
interface ShelfDao {

    @Query("""
        SELECT s.id, s.name, COUNT(sb.bookId) AS bookCount
        FROM shelves s LEFT JOIN shelf_books sb ON sb.shelfId = s.id
        GROUP BY s.id ORDER BY s.name COLLATE NOCASE
    """)
    fun observeShelves(): Flow<List<ShelfWithCount>>

    @Query("""
        SELECT b.* FROM books b JOIN shelf_books sb ON sb.bookId = b.id
        WHERE sb.shelfId = :shelfId ORDER BY b.title COLLATE NOCASE
    """)
    fun observeBooksOnShelf(shelfId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM shelves ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<ShelfEntity>

    @Query("SELECT shelfId FROM shelf_books WHERE bookId = :bookId")
    suspend fun shelfIdsForBook(bookId: String): List<String>

    @Query("""
        SELECT s.name FROM shelves s JOIN shelf_books sb ON sb.shelfId = s.id
        WHERE sb.bookId = :bookId ORDER BY s.name COLLATE NOCASE
    """)
    suspend fun shelfNamesForBook(bookId: String): List<String>

    @Query("SELECT bookId FROM shelf_books WHERE shelfId = :shelfId")
    suspend fun bookIdsOnShelf(shelfId: String): List<String>

    @Query("SELECT * FROM shelves WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ShelfEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShelf(shelf: ShelfEntity)

    @Query("UPDATE shelves SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteShelf(id: String)

    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId")
    suspend fun clearShelf(shelfId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: ShelfBookEntity)

    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId AND bookId = :bookId")
    suspend fun remove(shelfId: String, bookId: String)

    @Query("DELETE FROM shelf_books WHERE bookId = :bookId")
    suspend fun removeBookFromAll(bookId: String)
}
