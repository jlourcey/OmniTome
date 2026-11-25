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
import org.readium.r2.shared.publication.services.positions
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

/** Read-aloud sleep timer. */
sealed class SleepTimer {
    data object Off : SleepTimer()
    /** Stops at [endsAt] (epoch ms). */
    data class At(val endsAt: Long, val minutes: Int) : SleepTimer()
    /** Stops when the chapter ends. */
    data object EndOfChapter : SleepTimer()
}

sealed class ReaderState {
    data object Loading : ReaderState()
    data class Ready(
        val publication: Publication,
        val bookId: String,
        val lastLocator: String?
    ) : ReaderState()
    /** A PDF — shown by OmniTome's own viewer (PdfReader.kt), not Readium. */
    data class PdfReady(
        val file: File,
        val bookId: String,
        val title: String,
        /** Zero-based page to open at (from the saved position). */
        val initialPage: Int
    ) : ReaderState()
    /** Calibre is converting a MOBI/AZW3/comic to EPUB (0.0–1.0). */
    data class Converting(val title: String, val progress: Float) : ReaderState()
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
    private val okHttpClient: OkHttpClient,
    private val userPreferences: com.ereader.app.data.preferences.UserPreferences,
    private val dictionary: com.ereader.app.data.dictionary.DictionaryRepository
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

    // ── Areas of the screen covered by reader controls ────────────────────────
    // The Activity treats a short tap anywhere as "show/hide the controls". Taps
    // that land on the controls themselves (top bar, bottom bar, read-aloud
    // player) must NOT do that, or e.g. the ⋮ menu closes the moment it opens.
    private val controlAreas = java.util.concurrent.ConcurrentHashMap<String, android.graphics.RectF>()

    /** Called by the controls when they're laid out (bounds in window pixels). */
    fun setControlArea(key: String, left: Float, top: Float, right: Float, bottom: Float) {
        controlAreas[key] = android.graphics.RectF(left, top, right, bottom)
    }

    /** Called when a control bar leaves the screen. */
    fun clearControlArea(key: String) { controlAreas.remove(key) }

    /** True if (x, y) — window pixels — is on a reader control. */
    fun isOnControl(x: Float, y: Float): Boolean = controlAreas.values.any { it.contains(x, y) }

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

    /**
     * Opens a book. [gotoHref]/[gotoProgression] open it at a particular spot
     * instead of where you left off (used by "Search inside books").
     */
    fun loadBook(bookId: String, gotoHref: String? = null, gotoProgression: Double? = null) {
        viewModelScope.launch {
            _readerState.value = ReaderState.Loading
            try {
                Timber.d("ReaderViewModel: loading book $bookId")
                var book = repository.getBookById(bookId)
                    ?: throw Exception("Book not found in library (id=$bookId)")
                _bookTitle.value = book.title
                Timber.i(
                    "OmniTomePos open id=$bookId title='${book.title}' " +
                    "savedProgress=${"%.3f".format(book.readingProgress)} hasSavedPage=${book.lastLocator != null}"
                )

                // MOBI / AZW3 / comic: Calibre makes an EPUB first (only the first time).
                if (book.fileType == FileType.CONVERT) {
                    _readerState.value = ReaderState.Converting(book.title, 0f)
                    book = try {
                        repository.convertIfNeeded(book) { p ->
                            _readerState.value = ReaderState.Converting(book.title, p)
                        }
                    } catch (e: java.io.IOException) {
                        throw Exception(
                            if (e is java.net.UnknownHostException || e is java.net.ConnectException ||
                                e is java.net.SocketTimeoutException)
                                "This book needs converting to EPUB by your Calibre server first, and the " +
                                "server can't be reached. Try again on your home network."
                            else e.localizedMessage ?: "Calibre couldn't convert this book"
                        )
                    }
                }

                val localFile = ensureLocalFile(book)
                // Keep the book you're reading on the phone, in case you leave home.
                viewModelScope.launch { runCatching { repository.autoSaveForOffline(bookId, localFile) } }

                // PDFs open in OmniTome's own viewer (Android's PdfRenderer).
                if (book.fileType == FileType.PDF || isPdfFile(localFile)) {
                    val page = book.lastLocator?.let { json ->
                        runCatching { Locator.fromJSON(org.json.JSONObject(json))?.locations?.position }.getOrNull()
                    }?.minus(1) ?: 0
                    _readerState.value = ReaderState.PdfReady(localFile, bookId, book.title, page.coerceAtLeast(0))
                    return@launch
                }

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

                prepareReadingStats(bookId, publication)
                _readerState.value = ReaderState.Ready(
                    publication = publication,
                    bookId      = bookId,
                    lastLocator = gotoHref?.let { href ->
                        org.json.JSONObject()
                            .put("href", href)
                            .put("type", "application/xhtml+xml")
                            .put("locations", org.json.JSONObject().put("progression", (gotoProgression ?: 0.0).coerceIn(0.0, 1.0)))
                            .toString()
                    } ?: book.lastLocator
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to open book $bookId")
                _readerState.value = ReaderState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    /** True if the file starts with "%PDF" (catches PDFs imported with an unusual name). */
    private suspend fun isPdfFile(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            file.inputStream().use { s -> ByteArray(4).also { s.read(it) }.decodeToString() == "%PDF" }
        }.getOrDefault(false)
    }

    /**
     * PDF viewer → a new page is showing. Saved exactly like an EPUB position, so
     * Continue Reading, the progress arc, Calibre sync (as "how far through"),
     * the reading streak and "Read" at the last page all work for PDFs too.
     */
    fun onPdfPageChanged(page: Int, pageCount: Int) {
        val href = org.readium.r2.shared.util.Url("document.pdf") ?: return
        val total = if (pageCount <= 1) 1.0 else page.toDouble() / (pageCount - 1)
        updateCurrentLocator(
            Locator(
                href      = href,
                mediaType = org.readium.r2.shared.util.mediatype.MediaType.PDF,
                locations = Locator.Locations(
                    fragments        = listOf("page=${page + 1}"),
                    progression      = total,
                    position         = page + 1,
                    totalProgression = total
                )
            )
        )
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
        val bookId = when (val s = _readerState.value) {
            is ReaderState.Ready    -> s.bookId
            is ReaderState.PdfReady -> s.bookId
            else                    -> return
        }
        viewModelScope.launch {
            val locatorJson = locator.toJSON().toString()
            val progress    = locator.locations.totalProgression?.toFloat() ?: return@launch
            Timber.i("OmniTomePos save id=$bookId progress=${"%.3f".format(progress)} pageTurn=$pageTurn")
            val justFinished = repository.saveReadingProgress(bookId, locatorJson, progress, pageTurn)
            // Reached the last pages → ask for a rating. Asked even if the book already
            // has one: that may have come with Calibre's metadata, not from the reader.
            // The popup starts at the current rating, so keeping it is one tap.
            if (justFinished) {
                repository.getBookById(bookId)?.let { _finishedBook.value = it }
            }
        }
    }

    // ── Finished the book → "How would you rate it?" ──────────────────────────

    private val _finishedBook = MutableStateFlow<Book?>(null)
    /** Set when the reader just reached the end of an unrated book; the reader shows the rating popup. */
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

    // ── Page numbers + time left ──────────────────────────────────────────────

    private var statsTracker: ReadingStatsTracker? = null
    private var statsBookId: String? = null

    private val _readingStats = MutableStateFlow<ReadingStats?>(null)
    /** "Page X of Y" and minutes left — null until the first page is measured. */
    val readingStats: StateFlow<ReadingStats?> = _readingStats

    /** Reads Readium's position map and this book's learned reading speed. */
    private suspend fun prepareReadingStats(bookId: String, publication: Publication) {
        runCatching {
            val positions = publication.positions()
            if (positions.isEmpty()) return
            val chapters = linkedMapOf<String, Pair<Int, Int>>()
            positions.forEachIndexed { i, loc ->
                val href = loc.href.toString().substringBefore('#')
                val cur = chapters[href]
                chapters[href] = if (cur == null) i to 1 else cur.first to cur.second + 1
            }
            val (cpm, samples) = userPreferences.readingSpeed(bookId)
            statsTracker = ReadingStatsTracker(chapters, positions.size, cpm, samples)
            statsBookId  = bookId
        }.onFailure { Timber.w(it, "Couldn't prepare page numbers / time left") }
    }

    /**
     * The reader measured the page on screen: page [pageInChapter] of
     * [pagesInChapter] in chapter [href], at screen width [screenWidthPx].
     */
    fun onLayoutMeasured(href: String, pageInChapter: Int, pagesInChapter: Int, screenWidthPx: Int) {
        val tracker = statsTracker ?: return
        val layout = "${_fontSizeSp.value}|${_fontFamilyPref.value}|$screenWidthPx"
        val speedChanged = tracker.onLayoutMeasured(href, pageInChapter, pagesInChapter, layout, System.currentTimeMillis())
        tracker.stats(href, pageInChapter)?.let { _readingStats.value = it }
        if (speedChanged) {
            val id = statsBookId ?: return
            viewModelScope.launch { userPreferences.saveReadingSpeed(id, tracker.speedCpm, tracker.samples) }
        }
    }

    // ── Read aloud: speed + sleep timer ───────────────────────────────────────

    private val _ttsRate = MutableStateFlow(1.0f)
    /** Read-aloud speed, remembered between books. */
    val ttsRate: StateFlow<Float> = _ttsRate

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer

    fun setTtsRate(rate: Float) {
        _ttsRate.value = rate
        viewModelScope.launch { userPreferences.saveTtsRate(rate) }
    }

    fun setSleepTimer(timer: SleepTimer) { _sleepTimer.value = timer }

    // ── Volume-key page turns ─────────────────────────────────────────────────

    /** Settings → "Turn pages with volume keys". */
    val volumeKeyPageTurns: StateFlow<Boolean> = userPreferences.volumeKeyPageTurns
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _pdfPageTurns = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** +1 / -1 page requests for the PDF viewer (from the volume keys). */
    val pdfPageTurns: SharedFlow<Int> = _pdfPageTurns
    fun turnPdfPage(delta: Int) { _pdfPageTurns.tryEmit(delta) }

    // ── Dictionary ("Define") ─────────────────────────────────────────────────

    private val _dictionaryCard = MutableStateFlow<DictionaryCardState?>(null)
    /** Non-null while the definition card is open. */
    val dictionaryCard: StateFlow<DictionaryCardState?> = _dictionaryCard

    /** Selection menu → "Define". Looks the word up in the offline dictionary. */
    fun lookUpWord(selection: String) {
        val text = selection.trim()
        if (text.isEmpty()) return
        _dictionaryCard.value = DictionaryCardState(selection = text)
        viewModelScope.launch {
            val result = runCatching { dictionary.lookup(text) }
                .onFailure { Timber.e(it, "Dictionary lookup failed") }
                .getOrElse { com.ereader.app.data.dictionary.DictionaryResult(text, null, emptyList()) }
            _dictionaryCard.update { it?.copy(result = result) }
        }
    }

    /** Card → "Look up on Wikipedia" (needs internet). */
    fun lookUpOnWikipedia() {
        val card = _dictionaryCard.value ?: return
        val term = card.result?.headword ?: card.selection
        _dictionaryCard.update { it?.copy(wiki = WikiState.Loading) }
        viewModelScope.launch {
            val state = try {
                dictionary.wikipedia(term)?.let { WikiState.Loaded(it) } ?: WikiState.NotFound
            } catch (e: java.io.IOException) {
                WikiState.Offline
            } catch (e: Exception) {
                Timber.w(e, "Wikipedia lookup failed"); WikiState.Offline
            }
            _dictionaryCard.update { it?.copy(wiki = state) }
        }
    }

    fun dismissDictionary() { _dictionaryCard.value = null }

    // Reading settings are remembered: the reader opens with what you last chose
    // (in the reader controls or in Settings → Reader), and changes are saved.
    init {
        viewModelScope.launch {
            _fontSizeSp.value = userPreferences.readerFontSize.first().coerceIn(12, 28)
            runCatching { ReaderTheme.valueOf(userPreferences.readerTheme.first()) }
                .onSuccess { _readerThemePref.value = it }
            runCatching { ReaderFontFamily.valueOf(userPreferences.readerFontFamily.first()) }
                .onSuccess { _fontFamilyPref.value = it }
            _ttsRate.value = userPreferences.ttsRate.first()
        }
    }

    fun setFontSize(sp: Int) {
        _fontSizeSp.value = sp.coerceIn(12, 28)
        viewModelScope.launch { userPreferences.saveReaderFontSize(_fontSizeSp.value) }
    }
    fun setTheme(theme: ReaderTheme) {
        _readerThemePref.value = theme
        viewModelScope.launch { userPreferences.saveReaderTheme(theme.name) }
    }
    fun setFontFamily(f: ReaderFontFamily) {
        _fontFamilyPref.value = f
        viewModelScope.launch { userPreferences.saveReaderFontFamily(f.name) }
    }

    override fun onCleared() {
        super.onCleared()
        // Send the page you stopped on (and any new notes) to Calibre — now if
        // it can be reached, otherwise automatically once you're back online.
        repository.schedulePendingSync()
        // Home-screen widget: show the new progress / streak.
        com.ereader.app.widget.ContinueReadingWidget.updateAll(getApplication())
        (_readerState.value as? ReaderState.Ready)?.publication?.close()
    }
}
