package com.ereader.app.ui.downloads

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Book
import com.ereader.app.util.EpubMetadataExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

data class LocalImportUiState(
    val books: List<Book>    = emptyList(),
    val isLoading: Boolean   = true,
    val isImporting: Boolean = false,
    val importError: String? = null
)

@HiltViewModel
class LocalImportViewModel @Inject constructor(
    application: Application,
    private val repository: BookRepository
) : AndroidViewModel(application) {

    private val _isImporting   = MutableStateFlow(false)
    private val _importError   = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LocalImportUiState> = combine(
        repository.observeLocalBooks(),
        _isImporting,
        _importError
    ) { books, importing, error ->
        LocalImportUiState(
            books       = books,
            isLoading   = false,
            isImporting = importing,
            importError = error
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = LocalImportUiState(isLoading = true)
    )

    /**
     * Called after the user picks a file via the system picker.
     *
     * Steps:
     *  1. Resolve the URI to a display name (filename without extension = title)
     *  2. Determine file type (EPUB or PDF) from MIME type or extension
     *  3. Copy the file into app-private storage (filesDir/local_books/)
     *  4. Insert into Room via repository
     */
    fun importFile(uri: Uri, mimeType: String?) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            try {
                val app = getApplication<Application>()
                val ctx = app.applicationContext

                // Resolve display name from content resolver
                val displayName = withContext(Dispatchers.IO) {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
                    } ?: uri.lastPathSegment ?: "Unknown Book"
                }

                // Derive title from filename (strip extension)
                val title = displayName.substringBeforeLast('.').ifBlank { displayName }

                // Determine file type
                val fileType = when {
                    mimeType?.contains("epub") == true       -> FileType.EPUB
                    mimeType?.contains("pdf") == true        -> FileType.PDF
                    displayName.endsWith(".epub", true)      -> FileType.EPUB
                    displayName.endsWith(".pdf", true)       -> FileType.PDF
                    else                                     -> FileType.EPUB
                }

                val ext = if (fileType == FileType.PDF) "pdf" else "epub"

                // Copy to app-private storage
                val destDir  = File(app.filesDir, "local_books").also { it.mkdirs() }
                val destFile = File(destDir, "${title.take(60)}_${System.currentTimeMillis()}.$ext")

                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Could not open selected file")
                }

                // ── Extract EPUB metadata (title, author, cover) ──────────────
                // For EPUBs, read the OPF package document embedded in the ZIP.
                // Falls back to filename-derived title if the EPUB has no metadata.
                var finalTitle   = title
                var finalAuthor: String?       = null
                var coverBytes: ByteArray? = null

                if (fileType == FileType.EPUB) {
                    val meta = withContext(Dispatchers.IO) {
                        EpubMetadataExtractor.extract(destFile)
                    }
                    if (!meta.title.isNullOrBlank())  finalTitle  = meta.title
                    if (!meta.author.isNullOrBlank()) finalAuthor = meta.author
                    coverBytes = meta.coverBytes
                }

                repository.saveLocalBook(finalTitle, destFile, fileType, finalAuthor, coverBytes)
                Timber.d("Imported: '$finalTitle' by '${finalAuthor ?: "Unknown"}' → ${destFile.absolutePath}")

            } catch (e: Exception) {
                Timber.e(e, "Import failed")
                _importError.value = "Import failed: ${e.localizedMessage}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun onFavoriteToggled(book: Book) {
        viewModelScope.launch { repository.toggleFavorite(book.id, !book.isFavorite) }
    }

    fun onErrorDismissed() { _importError.value = null }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * LocalImportScreen — the "Downloads" tab.
 *
 * Shows all locally imported EPUB / PDF files and a FAB that opens the
 * system file picker. Imported files are stored in the app's private
 * filesDir/local_books/ and opened in the same Readium reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalImportScreen(
    windowWidthSizeClass: WindowWidthSizeClass,
    onBookClick: (Book) -> Unit,
    viewModel: LocalImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // System file picker — EPUB and PDF only
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // We don't get the MIME type from the result directly — pass null and
            // rely on the URI's extension inside the ViewModel
            viewModel.importFile(uri, mimeType = null)
        }
    }

    val columns = when (windowWidthSizeClass) {
        WindowWidthSizeClass.Compact -> 3
        WindowWidthSizeClass.Medium  -> 4
        else                         -> 5
    }

    // Error snackbar — stays visible until the user taps Dismiss
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.importError) {
        if (uiState.importError != null) {
            snackbarHostState.showSnackbar(
                message     = uiState.importError!!,
                actionLabel = "Dismiss",
                duration    = SnackbarDuration.Indefinite
            )
            viewModel.onErrorDismissed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = "Downloads",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.books.isNotEmpty()) {
                            Text(
                                text  = "${uiState.books.size} local files",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A1540)
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    filePicker.launch(arrayOf(
                        "application/epub+zip",
                        "application/epub",
                        "application/pdf",
                        "application/octet-stream"  // some EPUBs report this
                    ))
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Import File") },
                containerColor = Color(0xFFDDBB3A),
                contentColor   = Color(0xFF060D30)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF060D30))
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color    = Color(0xFFDDBB3A)
                    )
                }

                uiState.isImporting -> {
                    Column(
                        modifier            = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFFDDBB3A))
                        Text(
                            text  = "Importing…",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                uiState.books.isEmpty() -> {
                    EmptyDownloadsMessage(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyVerticalGrid(
                        columns        = GridCells.Fixed(columns),
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start  = 12.dp,
                            end    = 12.dp,
                            top    = 12.dp,
                            bottom = 96.dp   // leave room above the FAB
                        ),
                        verticalArrangement   = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            com.ereader.app.ui.bookshelf.BookCard(
                                book             = book,
                                onClick          = { onBookClick(book) },
                                onFavoriteToggle = { viewModel.onFavoriteToggled(book) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyDownloadsMessage(modifier: Modifier = Modifier) {
    Column(
        modifier              = modifier.padding(32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector        = Icons.Default.Download,
            contentDescription = null,
            tint               = Color(0xFFDDBB3A).copy(alpha = 0.6f),
            modifier           = Modifier.size(64.dp)
        )
        Text(
            text      = "No local books yet",
            style     = MaterialTheme.typography.titleMedium,
            color     = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text      = "Tap the + button to import an EPUB or PDF from your device.",
            style     = MaterialTheme.typography.bodySmall,
            color     = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center
        )
    }
}
