package com.ereader.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ereader.app.data.local.AppDatabase
import com.ereader.app.data.local.dao.AnnotationDao
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.local.dao.BookmarkDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DatabaseModule — tells Hilt how to create and provide the database.
 *
 * Migration history:
 *   MIGRATION_1_2 — adds the `annotations` table introduced in v2.
 *                   All existing `books` rows are preserved.
 *   MIGRATION_2_3 — adds the `tags` nullable TEXT column to the `books` table.
 *   MIGRATION_3_4 — adds the `calibreBookId` nullable INTEGER column so the app can
 *                   push metadata edits back to Calibre via /cdb/set-book-metadata.
 *   MIGRATION_4_5 — adds the `bookmarks` table for the bookmark feature.
 *   MIGRATION_5_6 — adds `books.notesSyncedAt` and the `annotation_tombstones`
 *                   table for syncing notes back to Calibre. Nothing is lost.
 *   MIGRATION_6_7 — adds `books.libraryKey` so books from several Calibre
 *                   libraries can live side by side. Nothing is lost.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * v1 → v2: create the annotations table.
     * Column types mirror [AnnotationEntity] exactly.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS annotations (
                    id                TEXT    NOT NULL PRIMARY KEY,
                    bookId            TEXT    NOT NULL,
                    type              TEXT    NOT NULL,
                    color             TEXT    NOT NULL,
                    selectedText      TEXT,
                    note              TEXT,
                    locatorJson       TEXT    NOT NULL,
                    chapterTitle      TEXT,
                    chapterHref       TEXT,
                    pageNumber        INTEGER,
                    progressionInBook REAL    NOT NULL,
                    createdAt         INTEGER NOT NULL,
                    updatedAt         INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations (bookId)"
            )
        }
    }

    /** v2 → v3: add nullable `tags` column to books (existing rows get NULL). */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT")
        }
    }

    /**
     * v3 → v4: add nullable `calibreBookId` INTEGER column.
     * Existing rows get NULL — the value is populated on the next sync.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN calibreBookId INTEGER")
        }
    }

    /**
     * v4 → v5: create the `bookmarks` table.
     * Column types mirror [BookmarkEntity] exactly.
     */
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id                TEXT    NOT NULL PRIMARY KEY,
                    bookId            TEXT    NOT NULL,
                    locatorJson       TEXT    NOT NULL,
                    chapterTitle      TEXT,
                    chapterHref       TEXT,
                    label             TEXT,
                    progressionInBook REAL    NOT NULL,
                    createdAt         INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks (bookId)"
            )
        }
    }

    /**
     * v5 → v6: Calibre notes sync.
     *  - books.notesSyncedAt: last successful sync time (NULL for every existing
     *    book, so everything you've already read gets sent on the first sync).
     *  - annotation_tombstones: remembers deleted notes so Calibre can be told.
     * Column types mirror [BookEntity] / [AnnotationTombstoneEntity] exactly.
     */
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN notesSyncedAt INTEGER")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS annotation_tombstones (
                    id        TEXT    NOT NULL PRIMARY KEY,
                    bookId    TEXT    NOT NULL,
                    deletedAt INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_annotation_tombstones_bookId ON annotation_tombstones (bookId)"
            )
        }
    }

    /**
     * v6 → v7: which Calibre library each book came from.
     * Existing rows get NULL; the next sync assigns them to the login's
     * default library (where they originally came from).
     */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN libraryKey TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_books_libraryKey ON books (libraryKey)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ereader.db"
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    fun provideBookDao(database: AppDatabase): BookDao = database.bookDao()

    @Provides
    fun provideAnnotationDao(database: AppDatabase): AnnotationDao = database.annotationDao()

    @Provides
    fun provideBookmarkDao(database: AppDatabase): BookmarkDao = database.bookmarkDao()
}
