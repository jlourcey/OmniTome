package com.ereader.app.ui.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationType
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.data.repository.AnnotationRepository
import com.ereader.app.data.repository.BookmarkRepository
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Annotation
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.Bookmark
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

// ── Text-to-speech playback state ────────────────────────────────────────────

/**
 * Tracks the current state of the built-in TTS engine.
 *
 * IDLE    — not reading (default).
 * PLAYING — actively speaking.
 * PAUSED  — reading was paused; [ttsChunkIndex] in the Activity holds the
 *            position so reading can resume from the same chunk.
 */
enum class TtsState { IDLE, PLAYING, PAUSED }

// ── Reader UI states ──────────────────────────────────────────────────────────

sealed class ReaderState {
    data object Loading : ReaderState()
    data class Ready(
        val publication: Publication,
        val bookId: String,
        val lastLocator: String?
    ) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

// ── Pending annotation — held in memory while the dialog is open ──────────────

/**
 * Captures the context when a user selects text (EPUB) or taps "Add Note" (PDF).
 * Passed to the annotation dialog composable so it can display chapter/page info
 * while the user picks a colour and writes their note.
 *
 * NOT persisted — if the process dies while the dialog is open, the selection
 * is lost. That is acceptable for this ephemeral UI state.
 *
 * [selectedText] is blank for page-level COMMENT annotations.
 * [locator]      is the Readium Locator at the selection point.
 */
data class PendingAnnotation(
    val selectedText: String,
    val locator: Locator
) {
    val pageNumber: Int?       get() = locator.locations.position
    val chapterTitle: String?  get() = locator.title
    val isPageNote: Boolean    get() = selectedText.isBlank()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    application: Application,
    private val repository: BookRepository,
    private val annotationRepository: AnnotationRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val okHttpClient: OkHttpClient
) : AndroidViewModel(application) {

    // ── Reader state ──────────────────────────────────────────────────────────
    private val _readerState = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val readerState: StateFlow<ReaderState> = _readerState

    // ── Book title (populated once the book is loaded) ────────────────────────
    private val _bookTitle = MutableStateFlow("")
    val bookTitle: StateFlow<String> = _bookTitle

    // ── Current locator (updated on every page turn) ──────────────────────────
    private val _currentLocator = MutableStateFlow<Locator?>(null)

    /**
     * Reading progress as a 0..1 float — derived from the current locator's
     * totalProgression. 0f until the user opens a book and turns at least one page.
     * Exposed so the HUD can show a progress bar without extra plumbing.
     */
    val readingProgress: StateFlow<Float> = _currentLocator
        .map { it?.locations?.totalProgression?.toFloat() ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // ── Annotations for the open book (live, re-rendered as decorations) ──────
    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations

    // ── Pending annotation — drives the colour-picker dialog in the HUD ───────
    private val _pendingAnnotation = MutableStateFlow<PendingAnnotation?>(null)
    val pendingAnnotation: StateFlow<PendingAnnotation?> = _pendingAnnotation

    // ── Text-to-speech state ──────────────────────────────────────────────────
    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState

    fun onTtsStarted()  { _ttsState.value = TtsState.PLAYING }
    fun onTtsPaused()   { _ttsState.value = TtsState.PAUSED  }
    fun onTtsResumed()  { _ttsState.value = TtsState.PLAYING }
    fun onTtsStopped()  { _ttsState.value = TtsState.IDLE    }

    // ── HUD visibility ────────────────────────────────────────────────────────
    private val _hudVisible = MutableStateFlow(false)
    val hudVisible: StateFlow<Boolean> = _hudVisible

    /**
     * Toggles the reader controls HUD on a single tap.
     * Ignored when any overlay (annotation dialog, notes, bookmarks) is open —
     * those overlays should not be dismissed by an accidental tap on the reader.
     */
    fun toggleHud() {
        if (_pendingAnnotation.value != null) return
        if (_showNotes.value) return
        if (_showBookmarks.value) return
        _hudVisible.value = !_hudVisible.value
    }

    /** Hides the HUD — called when an overlay opens or the user starts reading aloud. */
    fun hideHud() { _hudVisible.value = false }

    // ── Notes overlay visibility ──────────────────────────────────────────────
    private val _showNotes = MutableStateFlow(false)
    val showNotes: StateFlow<Boolean> = _showNotes

    // ── Bookmarks for the open book (live) ────────────────────────────────────
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    /**
     * True when the current reading position already has a bookmark within
     * 1% of totalProgression — prevents the icon from flickering on minor
     * scroll adjustments.
     *
     * Initialized as a single persistent StateFlow (not recomputed on every
     * property access) so it shares a single coroutine subscription.
     */
    val isCurrentPageBookmarked: StateFlow<Boolean> by lazy {
        combine(_currentLocator, _bookmarks) { locator, bms ->
            val prog = locator?.locations?.totalProgression ?: return@combine false
            bms.any { kotlin.math.abs(it.progressionInBook - prog) < 0.01 }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

    /** Whether the bookmarks sheet is open. */
    private val _showBookmarks = MutableStateFlow(false)
    val showBookmarks: StateFlow<Boolean> = _showBookmarks

    init {
        // Whenever the reader becomes Ready, subscribe to that book's annotations
        // and bookmarks.
        viewModelScope.launch {
            _readerState
                .filterIsInstance<ReaderState.Ready>()
                .flatMapLatest { state ->
                    annotationRepository.observeForBook(state.bookId)
                }
                .collect { list -> _annotations.value = list }
        }
        viewModelScope.launch {
            _readerState
                .filterIsInstance<ReaderState.Ready>()
                .flatMapLatest { state ->
                    bookmarkRepository.observeForBook(state.bookId)
                }
                .collect { list -> _bookmarks.value = list }
        }
    }

    // ── Load book ─────────────────────────────────────────────────────────────

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            _readerState.value = ReaderState.Loading
            try {
                Timber.d("ReaderViewModel: loading book $bookId")
                val book = repository.getBookById(bookId)
                    ?: throw Exception("Book not found in library (id=$bookId)")
                _bookTitle.value = book.title

                val localFile = ensureLocalFile(book)

                val fileUri = Uri.fromFile(localFile).toString()
                val absUrl  = AbsoluteUrl(fileUri)
                    ?: throw Exception("Could not build file URL: $fileUri")

                val asset = when (val r = assetRetriever.retrieve(absUrl)) {
                    is Try.Success -> r.value
                    is Try.Failure -> throw Exception("Cannot open file: ${r.value.message}")
                }
                val publication = when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
                    is Try.Success -> r.value
                    is Try.Failure -> throw Exception("Cannot parse book: ${r.value.message}")
                }

                _readerState.value = ReaderState.Ready(
                    publication = publication,
                    bookId      = bookId,
                    lastLocator = book.lastLocator
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to open book $bookId")
                _readerState.value = ReaderState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    private suspend fun ensureLocalFile(book: Book): File = withContext(Dispatchers.IO) {
        if (book.isDownloaded && book.localFilePath != null) {
            val f = File(book.localFilePath)
            if (f.exists()) return@withContext f
        }
        val ext      = if (book.fileType == FileType.PDF) "pdf" else "epub"
        val cacheDir = File(getApplication<Application>().cacheDir, "books").also { it.mkdirs() }
        val dest     = File(cacheDir, "${book.id}.$ext")
        if (dest.exists() && dest.length() > 0) return@withContext dest

        Timber.d("ReaderViewModel: downloading ${book.title} from ${book.fileUrl}")
        // Write to a ".part" file first and rename at the end, so an interrupted
        // download can never be mistaken for a finished book on the next open.
        val partial = File(cacheDir, "${book.id}.$ext.part")
        try {
            val request = Request.Builder().url(book.fileUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty response body")
                body.byteStream().use { i -> partial.outputStream().use { o -> i.copyTo(o) } }
            }
        } catch (e: java.io.IOException) {
            // IOException = no network / server unreachable. Explain what to do.
            partial.delete()
            throw Exception(
                "This book isn't saved on your phone, and your Calibre server can't be reached. " +
                "Connect to your home network, or long-press the book and choose " +
                "\"Read Offline\" before you leave home next time."
            )
        }
        if (dest.exists()) dest.delete()
        partial.renameTo(dest)
        Timber.d("ReaderViewModel: saved to ${dest.absolutePath}")
        dest
    }

    // ── Locator / progress ────────────────────────────────────────────────────

    /** Called by the Activity each time Readium reports the current page position. */
    fun updateCurrentLocator(locator: Locator) {
        // The first position Readium reports is just the book reopening at its saved
        // page. Only later, different positions are real page turns — those count
        // toward the reading streak and can finish the book.
        val previous = _currentLocator.value
        val pageTurn = previous != null &&
            (previous.href != locator.href ||
             previous.locations.totalProgression != locator.locations.totalProgression)
        _currentLocator.value = locator
        val state = _readerState.value as? ReaderState.Ready ?: return
        viewModelScope.launch {
            val locatorJson = locator.toJSON().toString()
            val progress    = locator.locations.totalProgression?.toFloat() ?: return@launch
            val justFinished = repository.saveReadingProgress(state.bookId, locatorJson, progress, pageTurn)
            // Reached the last pages → offer to rate it (not if it already has a rating).
            if (justFinished) {
                val book = repository.getBookById(state.bookId)
                if (book != null && book.rating == null) _finishedBook.value = book
            }
        }
    }

    // ── Finished the book → "How would you rate it?" ──────────────────────────

    private val _finishedBook = MutableStateFlow<Book?>(null)
    /** Set when the reader just reached the end of an unrated book; the HUD shows the rating popup. */
    val finishedBook: StateFlow<Book?> = _finishedBook.asStateFlow()

    fun dismissFinishedRating() { _finishedBook.value = null }

    /** "Save" in the finished-book rating popup. */
    fun rateFinishedBook(book: Book, stars: Int?) {
        _finishedBook.value = null
        if (stars == null) return
        viewModelScope.launch {
            runCatching { repository.setRating(book, stars) }
                .onFailure { Timber.w(it, "Could not save rating for '${book.title}'") }
        }
    }

    // ── Annotation: text selection (EPUB) ─────────────────────────────────────

    /**
     * Called from [ReaderActivity]'s [SelectionActionModeCallback] when the user
     * chooses "Highlight" or "Add Note" from the selection action menu.
     * Stores the selection so the HUD can render the annotation dialog.
     */
    fun onTextSelected(selectedText: String, locator: Locator) {
        _pendingAnnotation.value = PendingAnnotation(
            selectedText = selectedText,
            locator      = locator
        )
    }

    // ── Annotation: page-level note (EPUB current position / PDF) ────────────

    /**
     * Called when the user taps "Add Note" in the HUD top bar (no text selection).
     * Uses the most recently reported locator to anchor the note to the current page.
     */
    fun onAddPageNote() {
        val locator = _currentLocator.value ?: return
        _pendingAnnotation.value = PendingAnnotation(
            selectedText = "",   // empty = COMMENT type
            locator      = locator
        )
    }

    // ── Annotation: dialog actions ────────────────────────────────────────────

    /** Called when the user closes the annotation dialog without saving. */
    fun dismissAnnotationDialog() { _pendingAnnotation.value = null }

    /**
     * Save an annotation from the dialog.
     *
     * [color] — the highlight colour chosen by the user.
     * [note]  — optional written note; null or blank → not saved.
     *
     * Type is derived automatically:
     *  - No selected text → [AnnotationType.COMMENT]
     *  - Selected text, no note → [AnnotationType.HIGHLIGHT]
     *  - Selected text + note → [AnnotationType.NOTE]
     */
    fun saveAnnotation(color: AnnotationColor, note: String?) {
        val pending = _pendingAnnotation.value ?: return
        val state   = _readerState.value as? ReaderState.Ready ?: return

        val type = when {
            pending.isPageNote          -> AnnotationType.COMMENT
            note.isNullOrBlank()        -> AnnotationType.HIGHLIGHT
            else                        -> AnnotationType.NOTE
        }

        viewModelScope.launch {
            val annotation = Annotation(
                id                = UUID.randomUUID().toString(),
                bookId            = state.bookId,
                type              = type,
                color             = color,
                selectedText      = pending.selectedText.ifBlank { null },
                note              = note?.ifBlank { null },
                locatorJson       = pending.locator.toJSON().toString(),
                chapterTitle      = pending.chapterTitle,
                chapterHref       = pending.locator.href.toString(),
                pageNumber        = pending.pageNumber,
                progressionInBook = pending.locator.locations.totalProgression ?: 0.0,
                createdAt         = System.currentTimeMillis(),
                updatedAt         = System.currentTimeMillis()
            )
            annotationRepository.save(annotation)
            Timber.d("Saved annotation ${annotation.id} (${annotation.type})")
            _pendingAnnotation.value = null
        }
    }

    /** Delete an annotation by id (e.g. swiping to dismiss in the Notes overlay). */
    fun deleteAnnotation(id: String) {
        viewModelScope.launch { annotationRepository.delete(id) }
    }

    // ── Notes overlay ─────────────────────────────────────────────────────────

    fun openNotes()   { _showNotes.value = true  }
    fun closeNotes()  { _showNotes.value = false }

    // ── Bookmarks ─────────────────────────────────────────────────────────────

    fun openBookmarks()  { _showBookmarks.value = true  }
    fun closeBookmarks() { _showBookmarks.value = false }

    /**
     * Adds a bookmark at the current reading position, or removes it if one
     * already exists within 1% of the current progression.
     *
     * This gives the top-bar bookmark button a simple toggle behaviour:
     * tap once to add, tap again to remove.
     */
    fun toggleBookmark() {
        val locator = _currentLocator.value ?: return
        val state   = _readerState.value as? ReaderState.Ready ?: return
        val prog    = locator.locations.totalProgression ?: 0.0

        viewModelScope.launch {
            val existing = _bookmarks.value.firstOrNull { kotlin.math.abs(it.progressionInBook - prog) < 0.01 }
            if (existing != null) {
                bookmarkRepository.delete(existing.id)
                Timber.d("Removed bookmark ${existing.id}")
            } else {
                val bookmark = Bookmark(
                    id                = UUID.randomUUID().toString(),
                    bookId            = state.bookId,
                    locatorJson       = locator.toJSON().toString(),
                    chapterTitle      = locator.title,
                    chapterHref       = locator.href.toString(),
                    label             = null,
                    progressionInBook = prog,
                    createdAt         = System.currentTimeMillis()
                )
                bookmarkRepository.save(bookmark)
                Timber.d("Saved bookmark ${bookmark.id} at $prog")
            }
        }
    }

    /** Delete a specific bookmark by id (called from the bookmark list sheet). */
    fun deleteBookmark(id: String) {
        viewModelScope.launch { bookmarkRepository.delete(id) }
    }

    // ── Page turn events — drive PageCurlOverlay in the HUD ──────────────────
    //
    // SharedFlow (not StateFlow) so every emission is delivered even if the
    // previous animation hasn't finished. extraBufferCapacity = 2 prevents
    // tryEmit from dropping rapid back-to-back page turns.
    private val _pageTurnChannel = MutableSharedFlow<PageTurnDirection>(extraBufferCapacity = 2)
    val pageTurnChannel: SharedFlow<PageTurnDirection> = _pageTurnChannel

    /** Called from ReaderActivity whenever a page-turn fling or locator change is detected. */
    fun dispatchPageTurn(dir: PageTurnDirection) { _pageTurnChannel.tryEmit(dir) }

    // ── Reader display preferences ────────────────────────────────────────────
    //
    // These are kept in StateFlow so the Activity can collect them and push
    // the values into the WebView via JavaScript, and so the HUD stays in sync
    // without maintaining its own duplicate local state.

    private val _fontSizeSp      = MutableStateFlow(16)
    private val _readerThemePref = MutableStateFlow(ReaderTheme.DAY)
    private val _fontFamilyPref  = MutableStateFlow(ReaderFontFamily.SERIF)

    val fontSizeSp:      StateFlow<Int>               = _fontSizeSp
    val readerThemePref: StateFlow<ReaderTheme>       = _readerThemePref
    val fontFamilyPref:  StateFlow<ReaderFontFamily>  = _fontFamilyPref

    fun setFontSize(sp: Int)               { _fontSizeSp.value      = sp.coerceIn(12, 28) }
    fun setTheme(theme: ReaderTheme)       { _readerThemePref.value = theme }
    fun setFontFamily(f: ReaderFontFamily) { _fontFamilyPref.value  = f }

    override fun onCleared() {
        super.onCleared()
        // Send the page you stopped on (and any new notes) to Calibre — now if
        // it can be reached, otherwise automatically once you're back online.
        repository.schedulePendingSync()
        (_readerState.value as? ReaderState.Ready)?.publication?.close()
    }
}
