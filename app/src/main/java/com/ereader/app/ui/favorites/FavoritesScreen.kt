package com.ereader.app.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.local.entity.ShelfWithCount
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.data.repository.ShelfRepository
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.SmartShelf
import com.ereader.app.ui.shelves.ShelfEditorState
import com.ereader.app.ui.shelves.SmartShelfDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Which shelf is showing: Favorites (the hearts), one of your own shelves
 * (its id), or a Smart Shelf ([SMART] + its id).
 */
private const val FAVORITES = "__favorites__"
private const val SMART = "smart:"

data class FavoritesUiState(
    val shelves: List<ShelfWithCount> = emptyList(),
    val smartShelves: List<SmartShelf> = emptyList(),
    val selectedId: String = FAVORITES,
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true
) {
    val isFavorites: Boolean get() = selectedId == FAVORITES
    val selectedShelf: ShelfWithCount? get() = shelves.firstOrNull { it.id == selectedId }
    val selectedSmart: SmartShelf? get() =
        if (selectedId.startsWith(SMART)) smartShelves.firstOrNull { SMART + it.id == selectedId } else null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: BookRepository,
    private val shelfRepository: ShelfRepository
) : ViewModel() {

    private val selected = MutableStateFlow(FAVORITES)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val uiState: StateFlow<FavoritesUiState> = combine(
        shelfRepository.observeShelves(), repository.observeSmartShelves(), selected
    ) { shelves, smart, sel ->
        // A deleted shelf falls back to Favorites.
        val valid = sel == FAVORITES || shelves.any { it.id == sel } || smart.any { SMART + it.id == sel }
        Triple(shelves, smart, if (valid) sel else FAVORITES)
    }.flatMapLatest { (shelves, smart, sel) ->
        val books = when {
            sel == FAVORITES      -> repository.observeAllFavorites()
            sel.startsWith(SMART) -> repository.observeSmartShelfBooks(sel.removePrefix(SMART))
            else                  -> shelfRepository.observeBooksOnShelf(sel)
        }
        books.map { FavoritesUiState(shelves, smart, sel, it, isLoading = false) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    // ── Smart Shelves (saved Calibre searches) ────────────────────────────────

    /** The New / Edit Smart Shelf popup — null when closed. */
    private val _smartEditor = MutableStateFlow<ShelfEditorState?>(null)
    val smartEditor: StateFlow<ShelfEditorState?> = _smartEditor.asStateFlow()

    fun selectSmart(shelf: SmartShelf) { selected.value = SMART + shelf.id }

    /** "Smart shelf" chip (shelf = null) or press-and-hold a Smart Shelf (edit). */
    fun openSmartEditor(shelf: SmartShelf?) { _smartEditor.value = ShelfEditorState(editing = shelf) }

    fun closeSmartEditor() { _smartEditor.value = null }

    /** "Save" in the popup: asks Calibre to run the search, then saves the shelf. */
    fun saveSmartShelf(name: String, query: String) {
        val editor = _smartEditor.value ?: return
        if (editor.isSaving) return
        _smartEditor.value = editor.copy(isSaving = true, error = null)
        viewModelScope.launch {
            repository.saveSmartShelf(editor.editing?.id, name, query)
                .onSuccess { shelf ->
                    _smartEditor.value = null
                    selected.value = SMART + shelf.id
                    val n = shelf.calibreIds.size
                    _message.value = "✓ Smart Shelf \"${shelf.name}\" saved — $n book${if (n == 1) "" else "s"} in Calibre match"
                }
                .onFailure {
                    _smartEditor.value = editor.copy(
                        isSaving = false,
                        error    = when (it) {
                            is java.net.UnknownHostException,
                            is java.net.ConnectException,
                            is java.net.SocketTimeoutException ->
                                "Can't reach your Calibre server. Smart Shelves are made by Calibre's " +
                                "search, so you need to be on your home network to create or edit one."
                            else -> it.localizedMessage ?: "Couldn't save the shelf."
                        }
                    )
                }
        }
    }

    fun deleteSmartShelf(shelf: SmartShelf) {
        viewModelScope.launch {
            repository.deleteSmartShelf(shelf.id)
            if (selected.value == SMART + shelf.id) selected.value = FAVORITES
            _smartEditor.value = null
            _message.value = "Smart Shelf \"${shelf.name}\" deleted"
        }
    }

    fun select(id: String?) { selected.value = id ?: FAVORITES }

    fun onFavoriteToggled(book: Book) {
        viewModelScope.launch { repository.toggleFavorite(book.id, !book.isFavorite) }
    }

    fun createShelf(name: String) {
        viewModelScope.launch { shelfRepository.createShelf(name)?.let { selected.value = it.id } }
    }

    fun renameShelf(id: String, name: String) {
        viewModelScope.launch {
            if (!shelfRepository.renameShelf(id, name)) _message.value = "There's already a shelf called \"${name.trim()}\""
        }
    }

    fun deleteShelf(id: String) {
        viewModelScope.launch { shelfRepository.deleteShelf(id); selected.value = FAVORITES }
    }

    fun removeFromShelf(book: Book) {
        val shelf = selected.value.takeIf { it != FAVORITES } ?: return
        viewModelScope.launch {
            shelfRepository.removeFromShelf(shelf, book)
            _message.value = "✓ \"${book.title}\" removed from this shelf"
        }
    }

    fun dismissMessage() { _message.value = null }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Shelves — Favorites (the books you've hearted) plus shelves you create.
 *  - Tap a shelf chip to see its books. "+ New shelf" makes one.
 *  - Press and hold one of your shelves to rename or delete it.
 *  - Press and hold a book on your own shelf to take it off that shelf.
 *  - Put books on shelves from the library: press and hold a book → Add to Shelf.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    windowWidthSizeClass: WindowWidthSizeClass,
    onBookClick: (Book) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val smartEditor by viewModel.smartEditor.collectAsStateWithLifecycle()

    var showNewShelf   by remember { mutableStateOf(false) }
    var editingShelf   by remember { mutableStateOf<ShelfWithCount?>(null) }
    var confirmDelete  by remember { mutableStateOf<ShelfWithCount?>(null) }
    var removingBook   by remember { mutableStateOf<Book?>(null) }

    val columns = when (windowWidthSizeClass) {
        WindowWidthSizeClass.Compact  -> 3
        WindowWidthSizeClass.Medium   -> 4
        else                          -> 5
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text       = when {
                                uiState.isFavorites         -> "Favorites"
                                uiState.selectedSmart != null -> uiState.selectedSmart!!.name
                                else                        -> uiState.selectedShelf?.name ?: "Shelves"
                            },
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.books.isNotEmpty()) {
                            Text(
                                text  = "${uiState.books.size} book${if (uiState.books.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A1540))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF060D30))
        ) {
            // ── Shelf chips ───────────────────────────────────────────────────
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected    = uiState.isFavorites,
                        onClick     = { viewModel.select(null) },
                        label       = { Text("Favorites") },
                        leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                items(uiState.shelves, key = { it.id }) { shelf ->
                    // Hold a shelf chip to rename or delete it.
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        ShelfChip(
                            label       = "${shelf.name} (${shelf.bookCount})",
                            selected    = uiState.selectedId == shelf.id,
                            onClick     = { viewModel.select(shelf.id) },
                            onLongClick = { menu = true }
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename shelf") }, onClick = { menu = false; editingShelf = shelf })
                            DropdownMenuItem(
                                text = { Text("Delete shelf", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = shelf }
                            )
                        }
                    }
                }
                // Smart Shelves — hold one to edit or delete it.
                items(uiState.smartShelves, key = { "smart_" + it.id }) { shelf ->
                    ShelfChip(
                        label       = "\u2728 ${shelf.name}",
                        selected    = uiState.selectedId == SMART + shelf.id,
                        onClick     = { viewModel.selectSmart(shelf) },
                        onLongClick = { viewModel.openSmartEditor(shelf) }
                    )
                }
                item {
                    AssistChip(
                        onClick     = { showNewShelf = true },
                        label       = { Text("New shelf") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                item {
                    AssistChip(
                        onClick     = { viewModel.openSmartEditor(null) },
                        label       = { Text("Smart shelf") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color    = Color(0xFFDDBB3A)
                    )
                    uiState.books.isEmpty() -> EmptyShelfMessage(
                        favorites = uiState.isFavorites,
                        smart     = uiState.selectedSmart,
                        modifier  = Modifier.align(Alignment.Center)
                    )
                    else -> LazyVerticalGrid(
                        columns        = GridCells.Fixed(columns),
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement   = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            com.ereader.app.ui.bookshelf.BookCard(
                                book             = book,
                                onClick          = { onBookClick(book) },
                                onFavoriteToggle = { viewModel.onFavoriteToggled(book) },
                                // Smart Shelves fill themselves, so there's nothing to take off.
                                onLongClick      = if (uiState.isFavorites || uiState.selectedSmart != null) null
                                                   else ({ removingBook = book })
                            )
                        }
                    }
                }

                message?.let { text ->
                    Snackbar(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        action   = { TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") } }
                    ) { Text(text) }
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    smartEditor?.let { editor ->
        SmartShelfDialog(
            state     = editor,
            onDismiss = viewModel::closeSmartEditor,
            onSave    = viewModel::saveSmartShelf,
            onDelete  = viewModel::deleteSmartShelf
        )
    }
    if (showNewShelf) {
        ShelfNameDialog(title = "New shelf", initial = "", onDismiss = { showNewShelf = false }) {
            viewModel.createShelf(it); showNewShelf = false
        }
    }
    editingShelf?.let { shelf ->
        ShelfNameDialog(title = "Rename shelf", initial = shelf.name, onDismiss = { editingShelf = null }) {
            viewModel.renameShelf(shelf.id, it); editingShelf = null
        }
    }
    confirmDelete?.let { shelf ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${shelf.name}\"?") },
            text  = { Text("The shelf is removed. The ${shelf.bookCount} book(s) on it stay in your library.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteShelf(shelf.id); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
    removingBook?.let { book ->
        AlertDialog(
            onDismissRequest = { removingBook = null },
            title = { Text("Remove from shelf?") },
            text  = { Text("Take \"${book.title}\" off \"${uiState.selectedShelf?.name}\"? The book stays in your library.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeFromShelf(book); removingBook = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingBook = null }) { Text("Cancel") } }
        )
    }
}

/** A chip that responds to both tap (open the shelf) and press-and-hold (rename/delete). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    Surface(
        shape  = shape,
        color  = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ShelfNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
        title = { Text(title) },
        text  = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) },
                label = { Text("Shelf name") }, singleLine = true)
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyShelfMessage(favorites: Boolean, smart: SmartShelf? = null, modifier: Modifier = Modifier) {
    Column(
        modifier              = modifier.padding(32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector        = if (favorites) Icons.Default.FavoriteBorder else Icons.Default.CollectionsBookmark,
            contentDescription = null,
            tint               = Color(0xFFDDBB3A).copy(alpha = 0.6f),
            modifier           = Modifier.size(64.dp)
        )
        Text(
            text      = when {
                favorites     -> "No favorites yet"
                smart != null -> "No books match yet"
                else          -> "This shelf is empty"
            },
            style     = MaterialTheme.typography.titleMedium,
            color     = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text      = when {
                favorites     -> "Tap the heart on any book to add it here."
                smart != null -> "Calibre's search \"${smart.query.ifBlank { "(everything)" }}\" didn't match any " +
                                 "books in this library. Press and hold the shelf to change its search."
                else          -> "In the library, press and hold a book and choose Add to Shelf."
            },
            style     = MaterialTheme.typography.bodySmall,
            color     = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center
        )
    }
}
