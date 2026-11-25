package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.SubcomposeAsyncImage
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Private colors (same palette as BookshelfScreen) ─────────────────────────
private val ShelfNavy   = Color(0xFF060D30)
private val ShelfRodTop = Color(0xFF4270D8)
private val ShelfRodMid = Color(0xFF1B3C96)
private val ShelfRodBot = Color(0xFF0B1E56)
private val GoldLight   = Color(0xFFDDBB3A)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val repository: BookRepository
) : ViewModel() {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    private var loaded = false

    /** IDs of books that are busy — saving for offline OR syncing notes. */
    val downloadingIds: StateFlow<Set<String>> =
        combine(repository.downloadingIds, repository.syncingIds) { a, b -> a + b }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun onRemoveDownload(book: Book, syncFirst: Boolean) {
        viewModelScope.launch { _message.value = removeDownloadWithMessage(repository, book, syncFirst) }
    }

    fun onSyncNotes(book: Book) {
        viewModelScope.launch { _message.value = syncNotesWithMessage(repository, book) }
    }

    /** Short message shown at the bottom of the screen (success or error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun onReadOffline(book: Book) {
        viewModelScope.launch {
            repository.downloadForOffline(book)
                .onSuccess {
                    _message.value = "\u2713 \"${book.title}\" is saved on your phone for offline reading"
                }
                .onFailure { _message.value = offlineDownloadErrorMessage(book.title, it) }
        }
    }

    /** Long-press → "Mark as Read" / "Mark as Unread". */
    fun onMarkRead(book: Book, read: Boolean, finishedToday: Boolean) {
        viewModelScope.launch { _message.value = setReadWithMessage(repository, book, read, finishedToday) }
    }

    fun onMessageDismissed() { _message.value = null }

    /**
     * Called from the composable via [LaunchedEffect]. Starts collecting the
     * series' book list from Room. Safe to call multiple times — only loads once.
     */
    fun loadSeries(series: String) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            repository.observeBooksBySeries(series)
                .collect { list -> _books.value = list }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * SeriesDetailScreen — a full bookshelf showing only the books in [seriesName],
 * ordered by their reading sequence (seriesIndex ASC).
 *
 * Each row is a horizontal entry: cover thumbnail on the left, metadata on the right.
 * This "reading list" layout makes the sequence order obvious at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    seriesName: String,
    onBookClick: (Book) -> Unit,
    onNotesClick: (Book) -> Unit = {},
    onEditClick: (Book) -> Unit  = {},
    onBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    // Kick off the series load once
    LaunchedEffect(seriesName) { viewModel.loadSeries(seriesName) }

    val books          by viewModel.books.collectAsStateWithLifecycle()
    val downloadingIds by viewModel.downloadingIds.collectAsStateWithLifecycle()
    val message        by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = seriesName,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        if (books.isNotEmpty()) {
                            Text(
                                text  = "${books.size} books",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A1540)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ShelfNavy)
        ) {
            LazyColumn(
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Top shelf bar
                item { SeriesShelfBar() }

                itemsIndexed(books, key = { _, b -> b.id }) { index, book ->
                    SeriesBookRow(
                        book         = book,
                        readingOrder = index + 1,
                        onClick      = { onBookClick(book) },
                        onNotesClick = { onNotesClick(book) },
                        onEditClick  = { onEditClick(book) },
                        isDownloading = book.id in downloadingIds,
                        onReadOffline = { viewModel.onReadOffline(book) },
                        onRemoveDownload = { syncFirst -> viewModel.onRemoveDownload(book, syncFirst) },
                        onSyncNotes   = { viewModel.onSyncNotes(book) },
                        onMarkRead    = { read, today -> viewModel.onMarkRead(book, read, today) }
                    )
                    SeriesShelfBar()
                }
            }

            // Success / error message for "Read Offline"
            message?.let { text ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::onMessageDismissed) { Text("Dismiss") }
                    }
                ) { Text(text) }
            }
        }
    }
}

// ── Row composable ────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesBookRow(
    book: Book,
    readingOrder: Int,
    onClick: () -> Unit,
    onNotesClick: () -> Unit = {},
    onEditClick: () -> Unit  = {},
    isDownloading: Boolean   = false,
    onReadOffline: () -> Unit = {},
    onRemoveDownload: (syncFirst: Boolean) -> Unit = {},
    onSyncNotes: () -> Unit  = {},
    onMarkRead: (read: Boolean, finishedToday: Boolean) -> Unit = { _, _ -> }
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showRemoveDownloadDialog by remember { mutableStateOf(false) }
    var showMarkReadDialog by remember { mutableStateOf(false) }

    if (showMarkReadDialog) {
        MarkAsReadDialog(
            book      = book,
            onDismiss = { showMarkReadDialog = false },
            onConfirm = { today -> onMarkRead(true, today) }
        )
    }

    if (showRemoveDownloadDialog) {
        RemoveDownloadDialog(
            book      = book,
            onDismiss = { showRemoveDownloadDialog = false },
            onConfirm = onRemoveDownload
        )
    }

    // Context menu on long-press — same items as the main bookshelf
    DropdownMenu(
        expanded         = showContextMenu,
        onDismissRequest = { showContextMenu = false }
    ) {
        DropdownMenuItem(
            text        = { Text("Open") },
            onClick     = { showContextMenu = false; onClick() },
            leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) }
        )
        DropdownMenuItem(
            text        = { Text("Edit Info") },
            onClick     = { showContextMenu = false; onEditClick() },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
        )
        DropdownMenuItem(
            text        = { Text("View Notes") },
            onClick     = { showContextMenu = false; onNotesClick() },
            leadingIcon = { Icon(Icons.Default.Notes, contentDescription = null) }
        )
        if (book.isRead) {
            DropdownMenuItem(
                text        = { Text("Mark as Unread") },
                onClick     = { showContextMenu = false; onMarkRead(false, false) },
                leadingIcon = { Icon(Icons.Default.RemoveDone, contentDescription = null) }
            )
        } else {
            DropdownMenuItem(
                text        = { Text("Mark as Read") },
                onClick     = { showContextMenu = false; showMarkReadDialog = true },
                leadingIcon = { Icon(Icons.Default.TaskAlt, contentDescription = null) }
            )
        }
        if (book.source != BookSource.LOCAL && !book.isDownloaded && !isDownloading) {
            DropdownMenuItem(
                text        = { Text("Read Offline") },
                onClick     = { showContextMenu = false; onReadOffline() },
                leadingIcon = { Icon(Icons.Default.DownloadForOffline, contentDescription = null) }
            )
        }
        if (book.source != BookSource.LOCAL && book.isDownloaded && !isDownloading) {
            DropdownMenuItem(
                text        = { Text("Remove Download") },
                onClick     = { showContextMenu = false; showRemoveDownloadDialog = true },
                leadingIcon = { Icon(Icons.Default.FileDownloadOff, contentDescription = null) }
            )
        }
        if (book.source == BookSource.CALIBRE && book.calibreBookId != null && !isDownloading) {
            DropdownMenuItem(
                text        = { Text("Sync Notes to Calibre") },
                onClick     = { showContextMenu = false; onSyncNotes() },
                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { showContextMenu = true }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Reading order number
        Box(
            modifier         = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = "$readingOrder",
                color      = GoldLight,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Cover thumbnail
        Box(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .width(56.dp)
                .aspectRatio(0.67f)
        ) {
            SubcomposeAsyncImage(
                model              = book.coverUrl,
                contentDescription = "${book.title} cover",
                contentScale       = ContentScale.Crop,
                colorFilter        = coverColorFilter(book),
                modifier           = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (book.isRead) READ_COVER_ALPHA else 1f },
                loading = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A2560))
                    )
                },
                error = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A2560)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text     = book.title.take(2),
                            color    = GoldLight,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }

        // Metadata
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = book.title,
                color      = Color.White,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text     = book.author,
                color    = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (book.seriesIndex != null) {
                val indexLabel = book.seriesIndex.let { n ->
                    if (n == n.toLong().toFloat()) "Book ${n.toLong()}" else "Book $n"
                }
                Text(
                    text     = indexLabel,
                    color    = GoldLight.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            // Read status line
            if (book.isRead) {
                Text(
                    text       = "\u2713 Read",
                    color      = Color(0xFF7CD992),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            // Offline status line
            if (book.source != BookSource.LOCAL && (isDownloading || book.isDownloaded)) {
                Text(
                    text       = if (isDownloading) "Saving for offline\u2026" else "Saved offline",
                    color      = Color(0xFF7CD992),
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (book.readingProgress > 0f && !book.isRead) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress        = { book.readingProgress },
                    modifier        = Modifier.fillMaxWidth(0.6f).height(3.dp),
                    color           = GoldLight,
                    trackColor      = Color.White.copy(alpha = 0.15f)
                )
            }
        }
    }
}

// ── Shelf bar (thinner variant for the list layout) ───────────────────────────

@Composable
private fun SeriesShelfBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to ShelfRodTop,
                        0.30f to ShelfRodMid,
                        1.00f to ShelfRodBot
                    )
                )
            )
    )
}
