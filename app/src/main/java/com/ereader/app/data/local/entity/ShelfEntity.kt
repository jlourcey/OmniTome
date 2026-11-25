package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A shelf you created ("Beach reads", "To read"). Favorites is separate (books.isFavorite). */
@Entity(tableName = "shelves", indices = [Index(value = ["name"], unique = true)])
data class ShelfEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** A book on a shelf. A book can be on any number of shelves. */
@Entity(
    tableName   = "shelf_books",
    primaryKeys = ["shelfId", "bookId"],
    indices     = [Index(value = ["bookId"])]
)
data class ShelfBookEntity(
    val shelfId: String,
    val bookId: String,
    val addedAt: Long = System.currentTimeMillis()
)

/** A shelf plus how many books are on it (for the shelf chips). */
data class ShelfWithCount(
    val id: String,
    val name: String,
    val bookCount: Int
)
