package com.ereader.app.ui.edit

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for [EditBookScreen].
 *
 * Loads the book by ID from [SavedStateHandle], exposes each field as a
 * separate [StateFlow] so the UI can display and edit them independently,
 * and saves the updated metadata back via [BookRepository.updateBookMetadata].
 *
 * Cover images: when the user picks an image from the system photo picker,
 * [onCoverPicked] copies it into the app's private files directory
 * (`filesDir/covers/{bookId}.jpg`) so it persists independently of the
 * source URI (which may become invalid after a reboot on Android 10+).
 */
@HiltViewModel
class EditBookViewModel @Inject constructor(
    private val repository: BookRepository,
    savedStateHandle: SavedStateHandle,
    application: Application
) : AndroidViewModel(application) {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"]) {
        "EditBookViewModel requires a 'bookId' argument in SavedStateHandle"
    }

    // ── Backing data ──────────────────────────────────────────────────────────

    private val _originalBook = MutableStateFlow<Book?>(null)

    // ── Editable field states ──────────────────────────────────────────────────

    private val _title       = MutableStateFlow("")
    private val _author      = MutableStateFlow("")
    private val _series      = MutableStateFlow("")
    private val _seriesIndex = MutableStateFlow("")
    private val _description = MutableStateFlow("")
    private val _tags        = MutableStateFlow("")   // comma-separated display string
    private val _coverUrl    = MutableStateFlow<String?>(null)

    val title:       StateFlow<String>  = _title
    val author:      StateFlow<String>  = _author
    val series:      StateFlow<String>  = _series
    val seriesIndex: StateFlow<String>  = _seriesIndex
    val description: StateFlow<String>  = _description
    val tags:        StateFlow<String>  = _tags
    val coverUrl:    StateFlow<String?> = _coverUrl

    // ── Status ─────────────────────────────────────────────────────────────────

    private val _isLoading  = MutableStateFlow(true)
    private val _isSaving   = MutableStateFlow(false)
    private val _error      = MutableStateFlow<String?>(null)

    val isLoading:  StateFlow<Boolean>  = _isLoading
    val isSaving:   StateFlow<Boolean>  = _isSaving
    val error:      StateFlow<String?>  = _error

    /** Emits Unit once when the save succeeds — the UI pops the back stack. */
    private val _saveComplete = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveComplete: SharedFlow<Unit> = _saveComplete

    init {
        viewModelScope.launch {
            val book = repository.getBookById(bookId)
            if (book == null) {
                _error.value = "Book not found"
                _isLoading.value = false
                return@launch
            }
            _originalBook.value  = book
            _title.value         = book.title
            _author.value        = book.author
            _tags.value          = book.tags.joinToString(", ")
            _series.value        = book.series      ?: ""
            _seriesIndex.value   = book.seriesIndex?.let { idx ->
                // Show "1" not "1.0" for whole numbers
                if (idx == idx.toLong().toFloat()) idx.toLong().toString()
                else idx.toString()
            } ?: ""
            _description.value   = book.description ?: ""
            _coverUrl.value      = book.coverUrl
            _isLoading.value     = false
        }
    }

    // ── Field change handlers ─────────────────────────────────────────────────

    fun onTitleChange(value: String)       { _title.value       = value }
    fun onAuthorChange(value: String)      { _author.value      = value }
    fun onTagsChange(value: String)        { _tags.value        = value }
    fun onSeriesChange(value: String)      { _series.value      = value }
    fun onSeriesIndexChange(value: String) { _seriesIndex.value = value }
    fun onDescriptionChange(value: String) { _description.value = value }

    // ── Cover image ───────────────────────────────────────────────────────────

    /**
     * Called when the user picks an image from the system photo picker.
     *
     * Copies the image into `filesDir/covers/{bookId}.jpg` and updates
     * [coverUrl] to a `file://` URI pointing to the copy. Coil can load
     * `file://` URIs directly without any extra configuration.
     */
    fun onCoverPicked(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val context  = getApplication<Application>()
                val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }
                val destFile  = File(coversDir, "$bookId.jpg")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }

                _coverUrl.value = "file://${destFile.absolutePath}"
                Timber.d("Cover saved to ${destFile.absolutePath}")
            }.onFailure {
                _error.value = "Could not load image: ${it.localizedMessage}"
                Timber.e(it, "Cover copy failed")
            }
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    fun onSave() {
        val original = _originalBook.value ?: return
        if (_title.value.isBlank()) {
            _error.value = "Title cannot be empty"
            return
        }
        if (_author.value.isBlank()) {
            _error.value = "Author cannot be empty"
            return
        }
        viewModelScope.launch {
            _isSaving.value = true
            _error.value    = null
            runCatching {
                // Parse the comma-separated tags string into a clean list
                val parsedTags = _tags.value
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val updated = original.copy(
                    title       = _title.value.trim(),
                    author      = _author.value.trim(),
                    tags        = parsedTags,
                    series      = _series.value.trim().takeIf { it.isNotBlank() },
                    seriesIndex = _seriesIndex.value.trim().toFloatOrNull(),
                    description = _description.value.trim().takeIf { it.isNotBlank() },
                    coverUrl    = _coverUrl.value
                )

                // 1. Always save locally first — this is the source of truth in the app
                repository.updateBookMetadata(updated)

                // 2. If this is a Calibre book with a known ID, push back to the server.
                //    We do this as a best-effort: a network failure shows a warning but
                //    does NOT roll back the local save (the user still has their edits).
                if (updated.source == BookSource.CALIBRE) {
                    // Re-fetch from DB to pick up calibreBookId that may have been
                    // populated by the back-fill AFTER this screen was opened.
                    // (Race condition: sync back-fill runs ~22 s after launch; if the
                    // user opened edit before it finished, _originalBook.calibreBookId
                    // was null even though the DB now has the correct value.)
                    val freshCalibreId = repository.getBookById(bookId)?.calibreBookId
                        ?: updated.calibreBookId
                    if (freshCalibreId != null) {
                        repository.pushMetadataToCalibre(updated.copy(calibreBookId = freshCalibreId))
                            .onFailure { e ->
                                Timber.w(e, "Metadata push to Calibre failed — local save succeeded")
                                // Emit a non-blocking warning; saveComplete still fires below
                                _error.value = "Saved locally. Calibre sync failed: ${e.localizedMessage}"
                            }
                            .onSuccess {
                                Timber.d("Metadata pushed to Calibre for '${updated.title}'")
                            }
                    } else {
                        // calibreBookId is still null — back-fill hasn't run yet for this
                        // book. Sync the library once; on next edit the push will succeed.
                        Timber.w("Calibre push skipped — calibreBookId is null for '${updated.title}'. Sync library to link.")
                        _error.value = "Saved locally. Calibre sync skipped — please sync your library once to link this book, then edits will push automatically."
                    }
                }

                _saveComplete.emit(Unit)
            }.onFailure {
                _error.value = "Save failed: ${it.localizedMessage}"
                Timber.e(it, "updateBookMetadata failed")
            }
            _isSaving.value = false
        }
    }

    fun onErrorDismissed() { _error.value = null }
}
