package com.ereader.app.ui.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.data.repository.CalibreLibraryChoices
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.ReadingStreak
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── Sort order options ────────────────────────────────────────────────────────

enum class SortOrder {
    DATE_ADDED,   // newest-first — matches Calibre's default view
    BY_TITLE,     // A → Z
    BY_AUTHOR,    // A → Z by author last name
    BY_SERIES     // group books into series bundles; singles listed after
}

// ── Shelf item — either a single book or a collapsed series bundle ────────────

/**
 * What the grid actually renders.
 *
 * [SingleBook]   — a book that has no series, or whose series only has one book.
 * [SeriesBundle] — 2+ books sharing the same series name, collapsed into one card.
 *                  Tapping it opens [SeriesDetailScreen].
 */
sealed class ShelfItem {
    data class SingleBook(val book: Book) : ShelfItem()
    data class SeriesBundle(
        val series: String,
        val books: List<Book>,    // already sorted by seriesIndex ASC
        val coverBook: Book       // first book — whose cover represents the bundle
    ) : ShelfItem()
}

// ── UI state ──────────────────────────────────────────────────────────────────

data class BookshelfUiState(
    val items: List<ShelfItem>  = emptyList(),
    val allSeries: List<String> = emptyList(),
    val sortOrder: SortOrder    = SortOrder.DATE_ADDED,
    val sortAscending: Boolean  = true,
    val searchQuery: String     = "",
    val isLoading: Boolean      = true,
    val isSyncing: Boolean      = false,
    val error: String?          = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val repository: BookRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    /** Immediately-updated query — used by the text field so typing is responsive.
     *  The debounced version inside [uiState] drives the actual DB search. */
    val rawSearchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder    = MutableStateFlow(SortOrder.DATE_ADDED)
    private val _sortAscending = MutableStateFlow(true)
    private val _isSyncing   = MutableStateFlow(false)
    private val _error       = MutableStateFlow<String?>(null)

    /** The most recently opened, in-progress book — shown in the "Continue Reading" banner. */
    val currentlyReading: StateFlow<Book?> = repository
        .observeCurrentlyReading()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Books finished since 1 January — the "Finished this year" shelf. */
    val finishedThisYear: StateFlow<List<Book>> = repository
        .observeFinishedThisYear()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Days-in-a-row reading streak. */
    val readingStreak: StateFlow<ReadingStreak> = repository
        .observeReadingStreak()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingStreak())

    val uiState: StateFlow<BookshelfUiState> = combine(
        _searchQuery.debounce(300L),
        _sortOrder,
        _sortAscending,
        _isSyncing,
        _error
    ) { query, sort, ascending, syncing, error ->
        Quintuple(query, sort, ascending, syncing, error)
    }.flatMapLatest { (query, sort, ascending, syncing, error) ->

        // If user is searching, always show flat sorted-by-title results
        val booksFlow: Flow<List<Book>> = when {
            query.isNotBlank()          -> repository.search(query)
            sort == SortOrder.BY_AUTHOR -> repository.observeAllBooksByAuthor()
            sort == SortOrder.BY_TITLE  -> repository.observeAllBooks()
            // BY_SERIES uses title order — grouping into bundles happens in buildSeriesItems()
            sort == SortOrder.BY_SERIES -> repository.observeAllBooks()
            else                        -> repository.observeAllBooksByDate()  // DATE_ADDED
        }

        combine(booksFlow, repository.observeAllSeries()) { books, allSeries ->
            // Reverse the list when the user wants descending order.
            // Each DB query already returns its natural "ascending" order
            // (DATE_ADDED = newest-first, TITLE/AUTHOR = A→Z).
            val orderedBooks = if (!ascending && query.isBlank() && sort != SortOrder.BY_SERIES) {
                books.reversed()
            } else {
                books
            }
            val items = if (query.isNotBlank() || sort != SortOrder.BY_SERIES) {
                // Flat list — wrap every book as a SingleBook
                orderedBooks.map { ShelfItem.SingleBook(it) }
            } else {
                // Series mode — group books with series into bundles
                buildSeriesItems(orderedBooks)
            }
            BookshelfUiState(
                items         = items,
                allSeries     = allSeries,
                sortOrder     = sort,
                sortAscending = ascending,
                searchQuery   = query,
                isLoading     = false,
                isSyncing     = syncing,
                error         = error
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookshelfUiState(isLoading = true)
    )

    init {
        onSyncRequested()
        // Picking a different library in the switch → load its books right away.
        viewModelScope.launch {
            repository.activeLibraryChanges.drop(1).collect { onSyncRequested() }
        }
    }

    // ── Library switch ────────────────────────────────────────────────────────

    /** Libraries this Calibre login can open, and which one is showing. */
    val libraryChoices: StateFlow<CalibreLibraryChoices> = repository.libraryChoices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalibreLibraryChoices(emptyList(), null))

    fun onLibrarySelected(libraryId: String) {
        viewModelScope.launch { repository.switchLibrary(libraryId) }
    }

    // ── Series grouping logic ─────────────────────────────────────────────────

    /**
     * Builds the series-view shelf:
     *  - Standalone books (series == null) are excluded entirely — they only
     *    appear in Date/Title/Author views.
     *  - Series are sorted alphabetically (case-insensitive).
     *  - Books within each series are sorted by [Book.seriesIndex] ascending
     *    (supports fractional indices like 0.5, 4.1); books with no index
     *    float to the end of their series.
     *
     * Every series — even one with a single book — renders as a [SeriesBundle]
     * so the stacked-cover card is always shown in this view.
     */
    private fun buildSeriesItems(books: List<Book>): List<ShelfItem> {
        return books
            .filter { it.series != null }           // drop standalone books
            .groupBy { it.series!! }                // group by series name
            .entries
            .sortedBy { (name, _) -> name.lowercase() }  // A → Z by series name
            .map { (seriesName, seriesBooks) ->
                val sorted = seriesBooks.sortedWith(
                    // Primary: series index (nulls last); secondary: title
                    compareBy({ it.seriesIndex ?: Float.MAX_VALUE }, { it.title })
                )
                ShelfItem.SeriesBundle(
                    series    = seriesName,
                    books     = sorted,
                    coverBook = sorted.first()   // book 1 of the series is the cover
                )
            }
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun onSearchQueryChange(query: String) { _searchQuery.value = query }

    /**
     * Handles tapping a sort chip.
     * - First tap: switch to that sort (ascending / natural DB order).
     * - Second tap on the same chip: reverse the direction (toggle ↑/↓).
     */
    fun onSortOrderSelected(order: SortOrder) {
        if (_sortOrder.value == order) {
            _sortAscending.value = !_sortAscending.value
        } else {
            _sortOrder.value    = order
            _sortAscending.value = true
        }
    }

    /** Set when a sync is requested while one is already running (e.g. a library switch). */
    private var resyncPending = false

    fun onSyncRequested() {
        if (_isSyncing.value) { resyncPending = true; return }
        _isSyncing.value = true
        viewModelScope.launch {
            do {
                resyncPending = false
                _isSyncing.value = true
                _error.value     = null
                runCatching { repository.syncLibrary() }
                    .onFailure { _error.value = "Sync failed: ${it.localizedMessage}" }
            } while (resyncPending)
            _isSyncing.value = false
        }
    }

    fun onFavoriteToggled(book: Book) {
        viewModelScope.launch { repository.toggleFavorite(book.id, !book.isFavorite) }
    }

    fun onDeleteBook(book: Book) {
        viewModelScope.launch {
            runCatching { repository.deleteBook(book) }
                .onFailure { _error.value = "Could not remove book: ${it.localizedMessage}" }
        }
    }

    // Tracks a Calibre upload in progress to prevent duplicate taps
    private var _isUploading = false

    fun onUploadToCalibre(book: Book) {
        if (_isUploading) return
        _isUploading = true
        viewModelScope.launch {
            _error.value = null
            repository.uploadToCalibre(book)
                .onSuccess {
                    // Remove the local copy so we don't end up with two entries.
                    // deleteBook() cleans up the DB row, the local file on disk,
                    // and any custom cover — safe to call even if the file is missing.
                    runCatching { repository.deleteBook(book) }
                        .onFailure { Timber.w(it, "Could not delete local copy after Calibre upload") }
                    // Pull the Calibre-managed version back so the user sees it
                    // immediately under the correct Calibre title and metadata.
                    _error.value = "✓ \"${book.title}\" added to Calibre — syncing…"
                    onSyncRequested()
                }
                .onFailure {
                    _error.value = it.localizedMessage ?: "Upload failed"
                }
            _isUploading = false
        }
    }

    // ── Read Offline ──────────────────────────────────────────────────────────

    /**
     * IDs of books that are busy — saving for offline reading OR syncing notes
     * to Calibre. Drives the spinner on the cover.
     */
    val downloadingIds: StateFlow<Set<String>> =
        combine(repository.downloadingIds, repository.syncingIds) { a, b -> a + b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** "Remove Download" popup → Sync & Remove (syncFirst = true) or Remove Without Syncing. */
    fun onRemoveDownload(book: Book, syncFirst: Boolean) {
        viewModelScope.launch {
            _error.value = removeDownloadWithMessage(repository, book, syncFirst)
        }
    }

    /** Long-press → "Sync Notes to Calibre". */
    fun onSyncNotes(book: Book) {
        viewModelScope.launch { _error.value = syncNotesWithMessage(repository, book) }
    }

    /** Long-press → "Read Offline": saves the book onto the phone. */
    fun onReadOffline(book: Book) {
        viewModelScope.launch {
            repository.downloadForOffline(book)
                .onSuccess {
                    _error.value = "\u2713 \"${book.title}\" is saved on your phone for offline reading"
                }
                .onFailure { _error.value = offlineDownloadErrorMessage(book.title, it) }
        }
    }

    /** Long-press → "Mark as Read" (read = true) or "Mark as Unread" (read = false). */
    fun onMarkRead(book: Book, read: Boolean, finishedToday: Boolean) {
        viewModelScope.launch { _error.value = setReadWithMessage(repository, book, read, finishedToday) }
    }

    fun onErrorDismissed() { _error.value = null }
}

/**
 * Turns a download failure into a plain-English message.
 * Shared by the bookshelf and the series screen (same package).
 */
internal fun offlineDownloadErrorMessage(title: String, error: Throwable): String {
    val reason = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException ->
            "Can't reach your Calibre server. Make sure you're on your home network."
        else -> error.localizedMessage ?: "Unknown error"
    }
    return "Couldn't save \"$title\" for offline reading. $reason"
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component1() = a
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component2() = b
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component3() = c
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component4() = d
private operator fun <A, B, C, D, E> Quintuple<A, B, C, D, E>.component5() = e
