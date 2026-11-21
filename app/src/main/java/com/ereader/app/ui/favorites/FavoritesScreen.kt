package com.ereader.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

data class FavoritesUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: BookRepository
) : ViewModel() {

    val uiState: StateFlow<FavoritesUiState> = repository.observeAllFavorites()
        .map { books -> FavoritesUiState(books = books, isLoading = false) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = FavoritesUiState(isLoading = true)
        )

    fun onFavoriteToggled(book: Book) {
        viewModelScope.launch { repository.toggleFavorite(book.id, !book.isFavorite) }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * FavoritesScreen — shows all books the user has heart-ed, in a grid.
 * Uses the same BookCard composable as the main bookshelf for visual consistency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    windowWidthSizeClass: WindowWidthSizeClass,
    onBookClick: (Book) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                            text       = "Favorites",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.books.isNotEmpty()) {
                            Text(
                                text  = "${uiState.books.size} books",
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
        }
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

                uiState.books.isEmpty() -> {
                    EmptyFavoritesMessage(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    LazyVerticalGrid(
                        columns        = GridCells.Fixed(columns),
                        modifier       = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement   = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            // Re-use BookCard from bookshelf — import it
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
private fun EmptyFavoritesMessage(modifier: Modifier = Modifier) {
    Column(
        modifier              = modifier.padding(32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector        = Icons.Default.FavoriteBorder,
            contentDescription = null,
            tint               = Color(0xFFDDBB3A).copy(alpha = 0.6f),
            modifier           = Modifier.size(64.dp)
        )
        Text(
            text      = "No favorites yet",
            style     = MaterialTheme.typography.titleMedium,
            color     = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text      = "Tap the heart on any book to add it here.",
            style     = MaterialTheme.typography.bodySmall,
            color     = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center
        )
    }
}
