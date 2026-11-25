package com.ereader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ereader.app.data.local.dao.AnnotationDao
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.local.dao.BookmarkDao
import com.ereader.app.data.local.dao.ReadingDayDao
import com.ereader.app.data.local.entity.AnnotationEntity
import com.ereader.app.data.local.entity.AnnotationTombstoneEntity
import com.ereader.app.data.local.entity.BookEntity
import com.ereader.app.data.local.entity.BookmarkEntity
import com.ereader.app.data.local.entity.ReadingDayEntity

/**
 * The single SQLite database for the entire app.
 *
 * Version history:
 *   v1 → Initial schema (books table only).
 *   v2 → Added annotations table. Migration in DatabaseModule creates the table;
 *         no existing book data is altered.
 *   v3 → Added `tags` column to books table (nullable TEXT, default NULL).
 *   v4 → Added `calibreBookId` column (nullable INTEGER) to link books back to
 *         Calibre's internal ID so metadata edits can be pushed back to the server.
 *   v5 → Added `bookmarks` table for the reader bookmark feature.
 *   v6 → Notes sync to Calibre.   v7 → Multiple Calibre libraries.
 *   v8 → Read status (isRead / readAt / readStatusDirty) and the
 *         `reading_days` table for the reading streak.
 *
 * @TypeConverters wires in the Converters class so Room knows how to handle
 * our custom enum types (FileType, BookSource, AnnotationType, AnnotationColor).
 */
@Database(
    entities     = [
        BookEntity::class,
        AnnotationEntity::class,
        BookmarkEntity::class,
        AnnotationTombstoneEntity::class,
        ReadingDayEntity::class
    ],
    version      = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /** DAO for all book queries. */
    abstract fun bookDao(): BookDao

    /** DAO for highlights, notes, and comments. */
    abstract fun annotationDao(): AnnotationDao

    /** DAO for reader bookmarks. */
    abstract fun bookmarkDao(): BookmarkDao

    /** DAO for the reading-day log (reading streak). */
    abstract fun readingDayDao(): ReadingDayDao
}
