package com.ereader.app.ui.notes

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.repository.AnnotationRepository
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Annotation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the standalone Notes screen, launched from the bookshelf
 * via a long-press on a book cover.
 *
 * The book ID is delivered via [SavedStateHandle] from the Navigation component
 * (the nav route is `notes/{bookId}`).
 *
 * Responsibilities:
 *  1. Load the book's title so the TopAppBar and PDF export can show it.
 *  2. Observe all annotations for that book (live Room Flow).
 *  3. Delete individual annotations.
 *  4. Export all annotations to a PDF and emit a share [Intent] for the
 *     Activity / Composable to launch.
 */
@HiltViewModel
class NotesViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val annotationRepository: AnnotationRepository
) : AndroidViewModel(application) {

    /** Supplied by the nav graph as the `bookId` path argument. */
    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    // ── Book title ────────────────────────────────────────────────────────────
    private val _bookTitle = MutableStateFlow("")
    val bookTitle: StateFlow<String> = _bookTitle

    // ── Annotations ───────────────────────────────────────────────────────────
    val annotations: StateFlow<List<Annotation>> =
        annotationRepository.observeForBook(bookId)
            .stateIn(
                scope         = viewModelScope,
                started       = SharingStarted.WhileSubscribed(5_000),
                initialValue  = emptyList()
            )

    // ── Loading state ─────────────────────────────────────────────────────────
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ── Share intent side-effect (one-shot) ───────────────────────────────────
    private val _shareEvent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val shareEvent: SharedFlow<Intent> = _shareEvent

    // ── Error messages ────────────────────────────────────────────────────────
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        loadBookTitle()
    }

    // ── Book title loader ─────────────────────────────────────────────────────

    private fun loadBookTitle() {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching { bookRepository.getBookById(bookId)?.title ?: "" }
                .onSuccess { title -> _bookTitle.value = title }
                .onFailure { e  -> Timber.w(e, "Could not load book title for $bookId") }
            _isLoading.value = false
        }
    }

    // ── Annotation actions ────────────────────────────────────────────────────

    fun deleteAnnotation(id: String) {
        viewModelScope.launch {
            runCatching { annotationRepository.delete(id) }
                .onFailure { e -> Timber.e(e, "Failed to delete annotation $id") }
        }
    }

    /**
     * Render all current annotations as a PDF, then emit a share [Intent] so
     * the Composable can launch the system share sheet.
     *
     * Safe to call multiple times; a guard prevents concurrent exports.
     */
    private var isExporting = false
    fun exportPdf() {
        if (isExporting) return
        val currentAnnotations = annotations.value
        if (currentAnnotations.isEmpty()) return

        isExporting = true
        viewModelScope.launch {
            runCatching {
                AnnotationPdfExporter.export(
                    context     = getApplication(),
                    bookTitle   = _bookTitle.value.ifBlank { "Book" },
                    annotations = currentAnnotations
                )
            }.onSuccess { intent ->
                _shareEvent.emit(intent)
            }.onFailure { e ->
                Timber.e(e, "PDF export failed")
                _error.value = "Could not export notes: ${e.localizedMessage}"
            }
            isExporting = false
        }
    }

    fun clearError() { _error.value = null }
}
