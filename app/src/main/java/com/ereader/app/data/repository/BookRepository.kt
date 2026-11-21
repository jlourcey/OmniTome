package com.ereader.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.ereader.app.data.local.AppDatabase
import com.ereader.app.data.local.dao.BookDao
import com.ereader.app.data.local.entity.BookEntity
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.data.remote.calibre.CalibreSyncService
import com.ereader.app.data.remote.calibre.CalibreUploadService
import com.ereader.app.data.remote.calibre.OPDSService
import com.ereader.app.data.remote.drive.DriveService
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.normalize
import com.ereader.app.domain.model.toDomain
import com.ereader.app.domain.model.toEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BookRepository — the single entry point for all book data in the app.
 *
 * The UI never touches the database (Room) or the network (OPDSService,
 * DriveService) directly. It always goes through this repository.
 *
 * Responsibilities:
 *  1. Expose live [Flow] streams to the UI via the ViewModel.
 *  2. Run the library sync: fetch from Calibre + Drive, then deduplicate.
 *  3. Save reading progress back to Room.
 */
/**
 * What the bookshelf's library switch shows: every library this Calibre login
 * can open (id, display name) and which one is currently selected.
 */
data class CalibreLibraryChoices(
    val libraries: List<Pair<String, String>>,
    val activeId: String?
)

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val appDatabase: AppDatabase,
    private val opdsService: OPDSService,
    private val driveService: DriveService,
    private val calibreUploadService: CalibreUploadService,
    private val okHttpClient: OkHttpClient,
    private val notesSync: NotesSyncRepository,
    private val userPreferences: UserPreferences,
    private val calibreSyncService: CalibreSyncService,
    @ApplicationContext private val appContext: Context
) {

    // ── Live data for the UI ──────────────────────────────────────────────────

    // Every list follows the library chosen in the bookshelf switch.
    // When the switch changes, flatMapLatest swaps to the new library's query.

    private data class LibraryView(val key: String, val includeUnassigned: Boolean)

    private val libraryView: Flow<LibraryView> = combine(
        userPreferences.activeCalibreLibraryId,
        userPreferences.defaultCalibreLibraryId
    ) { active, default ->
        LibraryView(
            key               = active ?: default ?: "",
            // Books synced before multi-library support belong to the default library
            includeUnassigned = active == null || active == default
        )
    }.distinctUntilChanged()

    fun observeAllBooks(): Flow<List<Book>> =
        libraryView.flatMapLatest { v -> bookDao.observeAll(v.key, v.includeUnassigned) }
            .map { entities -> entities.map { it.toDomain() } }

    fun observeAllBooksByAuthor(): Flow<List<Book>> =
        libraryView.flatMapLatest { v -> bookDao.observeAllByAuthor(v.key, v.includeUnassigned) }
            .map { it.map { e -> e.toDomain() } }

    fun observeAllBooksByDate(): Flow<List<Book>> =
        libraryView.flatMapLatest { v -> bookDao.observeAllByDate(v.key, v.includeUnassigned) }
            .map { it.map { e -> e.toDomain() } }

    fun observeAllFavorites(): Flow<List<Book>> =
        libraryView.flatMapLatest { v -> bookDao.observeAllFavorites(v.key, v.includeUnassigned) }
            .map { it.map { e -> e.toDomain() } }

    fun observeAllSeries(): Flow<List<String>> =
        libraryView.flatMapLatest { v -> bookDao.observeAllSeries(v.key, v.includeUnassigned) }

    fun observeBooksBySeries(series: String): Flow<List<Book>> =
        libraryView.flatMapLatest { v -> bookDao.observeBySeries(series, v.key, v.includeUnassigned) }
            .map { it.map { e -> e.toDomain() } }

    /** The most recently opened in-progress book — drives the "Continue Reading" banner. */
    fun observeCurrentlyReading(): Flow<Book?> =
        libraryView.flatMapLatest { v -> bookDao.observeCurrentlyReading(v.key, v.includeUnassigned) }
            .map { it?.toDomain() }

    fun search(query: String): Flow<List<Book>> =
        libraryView.flatMapLatest { v -> bookDao.search(query, v.key, v.includeUnassigned) }
            .map { it.map { e -> e.toDomain() } }

    // ── Library switch ────────────────────────────────────────────────────────

    /** Libraries this login can open + the selected one (for the bookshelf switch). */
    val libraryChoices: Flow<CalibreLibraryChoices> = combine(
        userPreferences.knownCalibreLibraries,
        userPreferences.effectiveCalibreLibraryId
    ) { libs, active -> CalibreLibraryChoices(libs, active) }

    /** Emits whenever the user picks a different library. */
    val activeLibraryChanges: Flow<String?> =
        userPreferences.activeCalibreLibraryId.distinctUntilChanged()

    suspend fun switchLibrary(libraryId: String) =
        userPreferences.setActiveCalibreLibrary(libraryId)

    /**
     * Asks Calibre which libraries this login can open and decides which one to
     * sync. Returns null only if Calibre can't be reached and nothing is cached.
     */
    private suspend fun resolveCalibreLibrary(): String? {
        val info = runCatching { calibreSyncService.fetchLibraryInfo() }
            .onFailure { Timber.w(it, "Could not read Calibre's library list") }
            .getOrNull()
            ?: return userPreferences.effectiveCalibreLibraryId.first()

        userPreferences.saveCalibreLibraryInfo(info.libraries, info.defaultId)
        // Books synced before this update all came from the default library.
        bookDao.claimUnassignedCalibreBooks(info.defaultId)

        val active = userPreferences.activeCalibreLibraryId.first()
        if (active != null && info.libraries.none { it.first == active }) {
            // The picked library was removed or this login lost access to it.
            Timber.w("Library '$active' is no longer available — using the default")
            userPreferences.setActiveCalibreLibrary(null)
            return info.defaultId
        }
        return active ?: info.defaultId
    }

    // ── Library sync ──────────────────────────────────────────────────────────

    /**
     * Full library sync — fetches from all sources and deduplicates.
     *
     * Step 1: Fetch from Calibre OPDS (failures are caught and logged,
     *         so a dead Calibre server doesn't block Drive books).
     * Step 2: Fetch from Google Drive.
     * Step 3: For each book, run [mergeAndUpsert] which checks for duplicates
     *         before writing to the database.
     */
    suspend fun syncLibrary() {
        Timber.d("Library sync started")

        // ── Phase 0: which Calibre library? ───────────────────────────────────
        val libraryId = resolveCalibreLibrary()
        Timber.d("Syncing Calibre library: ${libraryId ?: "(server default)"}")

        // ── Phase 1: fetch all books ──────────────────────────────────────────
        val calibreBooks = runCatching { opdsService.fetchAllBooks(libraryId) }
            .onFailure { Timber.e(it, "Calibre OPDS fetch failed") }
            .getOrDefault(emptyList())
            .map { it.copy(libraryKey = libraryId) }   // tag each book with its library

        val driveBooks = runCatching { driveService.fetchAllEpubsAndPdfs() }
            .onFailure { Timber.e(it, "Google Drive fetch failed") }
            .getOrDefault(emptyList())

        val allFetched = calibreBooks + driveBooks
        Timber.d("Fetched ${allFetched.size} total books (Calibre: ${calibreBooks.size}, Drive: ${driveBooks.size})")

        allFetched.forEach { book ->
            mergeAndUpsert(book.toEntity(), libraryId)
        }

        // ── Phase 2: back-fill series metadata via Calibre JSON API ─────────
        // The OPDS "newest" feed omits <calibre:series_index> entirely.
        // Calibre's /ajax/search + /ajax/books JSON endpoints return exact
        // series names and float indices directly — no XML namespace issues.
        val seriesInfoList = runCatching { opdsService.fetchSeriesMetadataViaJson(libraryId) }
            .onFailure { Timber.e(it, "Series metadata (JSON API) fetch failed — series view may be empty") }
            .getOrDefault(emptyList())

        if (seriesInfoList.isNotEmpty()) {
            Timber.d("Back-filling series, tags, and calibreBookId for ${seriesInfoList.size} books")
            // All back-fill queries run inside a single SQLite transaction.
            // Without this, each of the ~3 queries per book opens and commits
            // its own transaction — ~786 round-trips for 262 books takes ~22 s.
            // One transaction commits them all at once, dropping that to ~1 s.
            appDatabase.withTransaction {
                seriesInfoList.forEach { info ->
                    // Always back-fill the Calibre integer ID (only writes when NULL)
                    if (info.calibreBookId > 0) {
                        bookDao.backfillCalibreBookId(
                            libraryKey       = libraryId.orEmpty(),
                            normalizedTitle  = info.normalizedTitle,
                            normalizedAuthor = info.normalizedAuthor,
                            calibreBookId    = info.calibreBookId
                        )
                    }
                    // Always back-fill series (Calibre is the source of truth)
                    if (info.seriesName.isNotBlank()) {
                        bookDao.backfillSeriesMetadata(
                            libraryKey       = libraryId.orEmpty(),
                            normalizedTitle  = info.normalizedTitle,
                            normalizedAuthor = info.normalizedAuthor,
                            series           = info.seriesName,
                            seriesIndex      = info.seriesIndex
                        )
                    }
                    // Always back-fill tags — overwrites whatever was there before so
                    // edits made in Calibre desktop are reflected after the next sync.
                    if (info.tags.isNotEmpty()) {
                        bookDao.backfillTags(
                            libraryKey       = libraryId.orEmpty(),
                            normalizedTitle  = info.normalizedTitle,
                            normalizedAuthor = info.normalizedAuthor,
                            tags             = info.tags.joinToString(",")
                        )
                    }
                }
            }
            Timber.d("Series, tags, and calibreBookId back-fill complete")
        } else {
            Timber.w("No metadata returned from JSON API — series and tags may be missing")
        }

        // ── Phase 3: send pending notes + reading positions back to Calibre ──
        // Only when Calibre answered this time — i.e. we're back on the home
        // network. Each book that fails stays pending and is retried next sync.
        if (calibreBooks.isNotEmpty()) {
            runCatching { notesSync.syncAllPending() }
                .onSuccess { if (it > 0) Timber.d("Synced notes for $it book(s) to Calibre") }
                .onFailure { Timber.e(it, "Automatic notes sync failed") }
        }

        Timber.d("Library sync complete")
    }

    // ── Deduplication engine ──────────────────────────────────────────────────

    /**
     * Decides whether [incoming] is a new book or a duplicate, then writes
     * the correct result to the database.
     *
     * Detection priority:
     *  1. ISBN match     → definitive duplicate (ISBNs are globally unique)
     *  2. Title + Author → likely duplicate (handles books without ISBNs)
     *  3. No match       → new book, assign a fresh UUID and insert
     *
     * When a duplicate is found, we "merge" the two records:
     *  - The existing row's ID is preserved (so reading progress is not lost)
     *  - Any field that is null in the existing row is filled from the incoming one
     *  - The richer description (longer text) wins
     *  - If there are multiple duplicate rows (e.g. both Calibre and Drive had it),
     *    the extras are deleted so only one row remains
     */
    private suspend fun mergeAndUpsert(incoming: BookEntity, activeLibrary: String?) {
        // Look up potential duplicates
        val byIsbn = incoming.isbn?.let { bookDao.findByIsbn(it) }.orEmpty()
        val byTitleAuthor = bookDao.findByNormalizedTitleAuthor(
            incoming.normalizedTitle,
            incoming.normalizedAuthor
        )

        // Combine both result lists, remove any row that appears in both.
        // Only rows from the SAME library (or with no library, i.e. Drive/local)
        // count as duplicates — the same book in two Calibre libraries stays
        // as two separate books, each with its own progress and notes.
        val targetLibrary = incoming.libraryKey ?: activeLibrary
        val candidates = (byIsbn + byTitleAuthor).distinctBy { it.id }
            .filter { it.libraryKey == null || it.libraryKey == targetLibrary }

        if (candidates.isEmpty()) {
            // This is a brand-new book — give it a stable UUID and save it
            Timber.d("New book: '${incoming.title}' by ${incoming.author}")
            bookDao.upsert(incoming.copy(id = UUID.randomUUID().toString()))
            return
        }

        // Pick the existing row with the most metadata filled in as our "primary"
        val primary = candidates.maxBy { it.filledFieldCount() }

        // Merge: take the best of both records
        val merged = primary.mergeWith(incoming)
        bookDao.upsert(merged)
        Timber.d("Merged duplicate: '${incoming.title}' into existing id=${primary.id}")

        // Delete any leftover duplicate rows (keep only the primary)
        candidates
            .filter { it.id != primary.id }
            .forEach { bookDao.deleteById(it.id) }
    }

    /**
     * Creates a merged entity that combines the best metadata from [this]
     * (the pre-existing database record) and [other] (the incoming fetched book).
     *
     * Rules:
     *  - The existing [id] is always kept (preserves reading progress)
     *  - Null fields in [this] are filled from [other]
     *  - The longer description wins
     *  - File URL: prefer local downloaded copy if we have one; otherwise keep current
     *  - Reading state, favorites, and timestamps stay from [this]
     */
    private fun BookEntity.mergeWith(other: BookEntity): BookEntity = copy(
        isbn          = isbn          ?: other.isbn,
        coverUrl      = coverUrl      ?: other.coverUrl,
        description   = bestDescription(description, other.description),
        // Tags: always take the incoming value when it's non-empty — Calibre is
        // the source of truth and the user may have added/changed tags there.
        // Only keep the existing value when the incoming book has no tags at all.
        tags          = other.tags?.takeIf { it.isNotBlank() } ?: tags,
        series        = series        ?: other.series,
        seriesIndex   = seriesIndex   ?: other.seriesIndex,
        publishedYear = publishedYear ?: other.publishedYear,
        language      = if (language != "en") language else other.language,
        // If already downloaded, keep our local path; otherwise take the remote URL
        fileUrl       = if (isDownloaded && localFilePath != null) fileUrl else other.fileUrl,
        // Preserve Calibre's integer ID from whichever record has it
        calibreBookId = calibreBookId ?: other.calibreBookId,
        libraryKey    = libraryKey    ?: other.libraryKey,
        updatedAt     = System.currentTimeMillis()
        // id, lastLocator, readingProgress, isFavorite, addedAt stay from [this]
    )

    /**
     * Returns the better of the two descriptions.
     *
     * Special case: if the stored description [a] is a legacy Calibre OPDS
     * summary that still starts with "TAGS:" or "SERIES:" metadata lines
     * (from before we cleaned the parser), swap in the incoming [b] instead
     * so the column gets cleaned up on the next sync without needing a migration.
     */
    private fun bestDescription(a: String?, b: String?): String? {
        if (a == null) return b
        if (b == null) return a
        // If the stored description is polluted with Calibre's prefix, replace it.
        val aPolluted = a.trimStart().let {
            it.startsWith("TAGS:", ignoreCase = true) ||
            it.startsWith("SERIES:", ignoreCase = true)
        }
        if (aPolluted) return b.ifBlank { a }
        return if (a.length >= b.length) a else b
    }

    /** Count how many optional metadata fields have actual values. */
    private fun BookEntity.filledFieldCount(): Int =
        listOf(isbn, coverUrl, description, series, seriesIndex, publishedYear)
            .count { it != null }

    // ── One-shot lookups ──────────────────────────────────────────────────────

    /** Returns a single book by its Room ID, or null if not found. */
    suspend fun getBookById(id: String): Book? = bookDao.getById(id)?.toDomain()

    // ── Reading state ─────────────────────────────────────────────────────────

    suspend fun saveReadingProgress(bookId: String, locatorJson: String, progress: Float) {
        bookDao.updateReadingProgress(bookId, locatorJson, progress)
    }

    suspend fun toggleFavorite(bookId: String, isFavorite: Boolean) {
        bookDao.setFavorite(bookId, isFavorite)
    }

    /**
     * Upserts a locally imported EPUB or PDF file into the library.
     *
     * Called after the user picks a file via the system file picker. The file
     * has already been copied to permanent storage before this is invoked.
     *
     * @param title         Display title — use the EPUB's embedded dc:title if
     *                      available; otherwise derive from the filename.
     * @param localFile     The copied file in app's private storage
     * @param fileType      EPUB or PDF
     * @param author        Author extracted from EPUB metadata, or null if unknown.
     * @param coverBytes    Raw cover image bytes extracted from the EPUB, or null.
     *                      Saved to [filesDir/local_books/covers/{id}.jpg] and
     *                      stored as a file:// URL in [BookEntity.coverUrl].
     */
    suspend fun saveLocalBook(
        title: String,
        localFile: File,
        fileType: FileType,
        author: String?       = null,
        coverBytes: ByteArray? = null
    ): Book {
        val id = UUID.randomUUID().toString()

        // Persist the extracted cover to private storage so Coil can load it.
        // Named {id}.jpg so deleteBook() can clean it up by ID without extra state.
        var coverUrl: String? = null
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            runCatching {
                val coversDir = File(appContext.filesDir, "local_books/covers").also { it.mkdirs() }
                val coverFile = File(coversDir, "$id.jpg")
                coverFile.outputStream().use { out -> out.write(coverBytes) }
                coverUrl = "file://${coverFile.absolutePath}"
            }.onFailure { Timber.w(it, "saveLocalBook: could not save cover for '$title'") }
        }

        val resolvedAuthor = author?.takeIf { it.isNotBlank() } ?: "Unknown Author"
        val entity = BookEntity(
            id               = id,
            title            = title,
            normalizedTitle  = title.normalize(),
            author           = resolvedAuthor,
            normalizedAuthor = resolvedAuthor.normalize(),
            fileUrl          = localFile.absolutePath,
            fileType         = fileType,
            source           = BookSource.LOCAL,
            isDownloaded     = true,
            localFilePath    = localFile.absolutePath,
            coverUrl         = coverUrl,
            addedAt          = System.currentTimeMillis(),
            updatedAt        = System.currentTimeMillis()
        )
        bookDao.upsert(entity)
        Timber.d("Saved local book: '$title' by '$resolvedAuthor' (id=$id, cover=${coverUrl != null})")
        return entity.toDomain()
    }

    /** Stream of all locally imported books (source = LOCAL), newest first. */
    fun observeLocalBooks(): Flow<List<Book>> =
        bookDao.observeAllLocalBooks().map { it.map { e -> e.toDomain() } }

    // ── Read Offline ──────────────────────────────────────────────────────────

    /**
     * IDs of books currently being saved for offline reading.
     * The bookshelf watches this to show a spinner on the cover while the
     * download runs. It lives here (the repository is a singleton) so every
     * screen sees the same downloads, even after navigating away and back.
     */
    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    /** Folder in the app's private, permanent storage that holds offline books. */
    private val offlineDir: File
        get() = File(appContext.filesDir, "offline_books").also { it.mkdirs() }

    /**
     * Saves a Calibre (or Google Drive) book onto the phone so it can be read
     * with no internet connection.
     *
     * Why filesDir and not cacheDir?
     *   The reader already keeps a temporary copy in cacheDir, but Android is
     *   allowed to wipe the cache whenever storage runs low. filesDir is only
     *   cleared if the app is uninstalled or the user taps "Clear storage".
     *
     * The file is first written as "{id}.epub.part" and only renamed to
     * "{id}.epub" once every byte has arrived, so a dropped Wi-Fi connection
     * halfway through can never leave a broken book behind.
     *
     * Once saved, [BookEntity.isDownloaded] = true and [BookEntity.localFilePath]
     * points at the file. ReaderViewModel.ensureLocalFile() already checks those
     * two fields first, so the reader opens the saved copy with no network.
     */
    suspend fun downloadForOffline(book: Book): Result<Unit> = withContext(Dispatchers.IO) {
        // Imported books already live on the phone — nothing to do.
        if (book.source == BookSource.LOCAL) return@withContext Result.success(Unit)
        // Already saved and the file is still there — nothing to do.
        if (book.isDownloaded && book.localFilePath != null && File(book.localFilePath).exists()) {
            return@withContext Result.success(Unit)
        }
        // A download for this book is already running — don't start a second one.
        if (book.id in _downloadingIds.value) return@withContext Result.success(Unit)

        _downloadingIds.update { it + book.id }
        val ext     = if (book.fileType == FileType.PDF) "pdf" else "epub"
        val dest    = File(offlineDir, "${book.id}.$ext")
        val partial = File(offlineDir, "${book.id}.$ext.part")
        try {
            val request = Request.Builder().url(book.fileUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Calibre returned an error (HTTP ${response.code})")
                }
                val body = response.body ?: throw IOException("The server sent an empty file")
                body.byteStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (partial.length() == 0L) throw IOException("The downloaded file was empty")
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) throw IOException("Could not save the file on the phone")

            bookDao.setDownloaded(book.id, true, dest.absolutePath)
            Timber.d("Saved '${book.title}' for offline reading -> ${dest.absolutePath}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            Timber.e(e, "Offline download failed for '${book.title}'")
            Result.failure(e)
        } finally {
            _downloadingIds.update { it - book.id }
        }
    }

    // ── Remove Download ───────────────────────────────────────────────────────

    /**
     * Deletes the phone's copy of a Calibre/Drive book but KEEPS the book in the
     * library, along with its notes, bookmarks, and reading position.
     * Also clears the reader's temporary cached copy so the file is truly gone.
     */
    suspend fun removeOfflineCopy(book: Book) = withContext(Dispatchers.IO) {
        if (book.source == BookSource.LOCAL) return@withContext   // imported books ARE the phone copy
        book.localFilePath?.let { path ->
            runCatching { File(path).delete() }
                .onFailure { Timber.w(it, "Could not delete offline file: $path") }
        }
        val ext = if (book.fileType == FileType.PDF) "pdf" else "epub"
        File(appContext.cacheDir, "books/${book.id}.$ext").let { if (it.exists()) it.delete() }
        bookDao.setDownloaded(book.id, false, null)
        Timber.d("Removed offline copy of '${book.title}'")
    }

    // ── Calibre notes sync ────────────────────────────────────────────────────

    /** IDs of books whose notes are being sent to Calibre right now. */
    val syncingIds: StateFlow<Set<String>> = notesSync.syncingIds

    /** Sends this book's notes, highlights, and reading position to Calibre. */
    suspend fun syncNotesToCalibre(book: Book): Result<Unit> = notesSync.syncBook(book.id)

    // ── Book management ───────────────────────────────────────────────────────

    /**
     * Permanently removes a book from the library.
     *
     * For LOCAL books, also deletes the on-device EPUB/PDF file.
     * For all books, cleans up any custom cover saved in app storage.
     */
    suspend fun deleteBook(book: Book) {
        bookDao.deleteById(book.id)

        // Delete the book file for locally imported books
        book.localFilePath?.let { path ->
            runCatching { File(path).delete() }
                .onFailure { Timber.w(it, "Could not delete book file: $path") }
        }

        // Delete a custom cover saved by EditBookScreen (filesDir/covers/{id}.jpg)
        File(appContext.filesDir, "covers/${book.id}.jpg").let { f ->
            if (f.exists()) runCatching { f.delete() }
                .onFailure { Timber.w(it, "Could not delete cover: ${f.path}") }
        }
        // Delete a cover extracted from a locally imported EPUB
        // (filesDir/local_books/covers/{id}.jpg)
        File(appContext.filesDir, "local_books/covers/${book.id}.jpg").let { f ->
            if (f.exists()) runCatching { f.delete() }
                .onFailure { Timber.w(it, "Could not delete local book cover: ${f.path}") }
        }
    }

    /**
     * Updates editable metadata for a book (title, author, series, description,
     * cover). Reading state, source, and file path are unchanged.
     */
    suspend fun updateBookMetadata(book: Book) {
        val existing = bookDao.getById(book.id) ?: return
        bookDao.upsert(
            existing.copy(
                title            = book.title,
                normalizedTitle  = book.title.normalize(),
                author           = book.author,
                normalizedAuthor = book.author.normalize(),
                series           = book.series,
                seriesIndex      = book.seriesIndex,
                description      = book.description,
                coverUrl         = book.coverUrl,
                tags             = book.tags.joinToString(",").ifEmpty { null },
                updatedAt        = System.currentTimeMillis()
            )
        )
        Timber.d("Updated metadata for book id=${book.id} title='${book.title}'")
    }

    // ── Calibre upload ────────────────────────────────────────────────────────

    /**
     * Uploads a locally imported book to the Calibre Content Server.
     *
     * Returns [Result.success] on HTTP 2xx, or [Result.failure] with a
     * descriptive message the UI can show to the user.
     */
    suspend fun uploadToCalibre(book: Book): Result<Unit> =
        calibreUploadService.upload(book)

    // ── Calibre metadata push ─────────────────────────────────────────────────

    /**
     * Pushes the book's current metadata back to the Calibre Content Server.
     *
     * Requires [book.calibreBookId] to be set (populated after the first sync).
     * Uses Calibre's JSON API: POST /cdb/set-book-metadata.
     *
     * Returns [Result.success] on HTTP 2xx, or [Result.failure] if the book
     * has no Calibre ID or the server returns an error.
     */
    suspend fun pushMetadataToCalibre(book: Book): Result<Unit> {
        val calibreId = book.calibreBookId
            ?: return Result.failure(IllegalStateException(
                "Cannot push to Calibre — this book has no Calibre ID. " +
                "Sync the library once to link it."
            ))
        return calibreUploadService.pushMetadata(calibreId, book)
    }
}
