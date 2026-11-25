package com.ereader.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.data.repository.SearchIndexer
import com.ereader.app.domain.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One result row: a chapter of a book with an excerpt. */
data class TextSearchResult(
    val book: Book,
    val chapterHref: String,
    val chapterTitle: String?,
    val snippet: String
)

data class SearchInBooksState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<TextSearchResult> = emptyList(),
    val indexedBooks: Int = 0
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchInBooksViewModel @Inject constructor(
    private val indexer: SearchIndexer,
    private val books: BookRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchInBooksState())
    val state: StateFlow<SearchInBooksState> = _state

    init {
        viewModelScope.launch { indexer.indexedCount.collect { n -> _state.update { it.copy(indexedBooks = n) } } }
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 2) { _state.update { it.copy(results = emptyList(), searching = false) }; return@collectLatest }
                _state.update { it.copy(searching = true) }
                val hits = indexer.search(q)
                val cache = mutableMapOf<String, Book?>()
                val results = hits.mapNotNull { h ->
                    val b = cache.getOrPut(h.bookId) { books.getBookById(h.bookId) } ?: return@mapNotNull null
                    TextSearchResult(b, h.chapterHref, h.chapterTitle, h.snippet)
                }
                _state.update { it.copy(results = results, searching = false) }
            }
        }
        // Catch up on any saved books that aren't searchable yet.
        viewModelScope.launch { runCatching { indexer.indexMissing() } }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        query.value = q
    }

    /** Where in the chapter the first match is (0–1), to open the book there. */
    suspend fun positionOf(result: TextSearchResult): Double? =
        indexer.matchPosition(result.book.id, result.chapterHref, _state.value.query)

    fun searchWords(): List<String> = indexer.words(_state.value.query)
}

/**
 * "Search inside books": finds words in the text of the books saved on this
 * phone. Results show the book, the chapter and an excerpt; tapping one opens
 * the book near that spot with the words highlighted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchInBooksScreen(
    onBack: () -> Unit,
    onOpenResult: (book: Book, chapterHref: String, progression: Double?, words: List<String>) -> Unit,
    viewModel: SearchInBooksViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    OutlinedTextField(
                        value         = state.query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder   = { Text("Search inside books") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus)
                    )
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Be clear about what is (and isn't) searched.
            Text(
                "Searches the text of books saved on this phone (Read Offline, the book you're " +
                "reading, and imported books) — ${state.indexedBooks} book${if (state.indexedBooks == 1) "" else "s"} " +
                "so far. EPUB only: PDF pages can't be searched.",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when {
                state.searching -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                state.query.trim().length >= 2 && state.results.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.ManageSearch, contentDescription = null, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No matches in your saved books.")
                }
            }
            if (state.results.isNotEmpty()) {
                val bookCount = state.results.map { it.book.id }.distinct().size
                Text(
                    "${state.results.size} match${if (state.results.size == 1) "" else "es"} in $bookCount book${if (bookCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.results, key = { it.book.id + it.chapterHref }) { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    onOpenResult(r.book, r.chapterHref, viewModel.positionOf(r), viewModel.searchWords())
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        AsyncImage(
                            model = r.book.coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(40.dp).aspectRatio(0.67f).clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.book.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            r.chapterTitle?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(highlighted(r.snippet), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** The index marks matched words as [word]; show them bold instead of with brackets. */
@Composable
private fun highlighted(snippet: String) = buildAnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    var i = 0
    while (i < snippet.length) {
        val open = snippet.indexOf('[', i)
        val close = if (open >= 0) snippet.indexOf(']', open) else -1
        if (open < 0 || close < 0) { append(snippet.substring(i)); break }
        append(snippet.substring(i, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(snippet.substring(open + 1, close)) }
        i = close + 1
    }
}
