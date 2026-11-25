package com.ereader.app.ui.bookshelf

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.ereader.app.R
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.domain.model.Book

// ─────────────────────────────────────────────────────────────────────────────
// BOOKSHELF THEME COLORS
// Matches the navy + gold bookshelf aesthetic in the design reference.
// ─────────────────────────────────────────────────────────────────────────────

private val ShelfNavy    = Color(0xFF060D30)   // back panel of the bookcase
private val ShelfRodTop  = Color(0xFF4270D8)   // bright highlight on top of rod
private val ShelfRodMid  = Color(0xFF1B3C96)   // main rod body colour
private val ShelfRodBot  = Color(0xFF0B1E56)   // shadow underneath rod
private val GoldLight    = Color(0xFFDDBB3A)   // top of gold cap
private val GoldMid      = Color(0xFFB8960C)   // mid gold
private val GoldDark     = Color(0xFF7A620E)   // shadow at bottom of cap

// ─────────────────────────────────────────────────────────────────────────────
// BOOKSHELF SCREEN — top-level composable
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfScreen(
    windowWidthSizeClass: WindowWidthSizeClass,
    onBookClick: (Book) -> Unit,
    onNotesClick: (Book) -> Unit = {},
    onEditClick: (Book) -> Unit = {},
    onSeriesClick: (String) -> Unit = {},
    /** Opens "Search inside books". */
    onSearchInsideBooks: () -> Unit = {},
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val uiState          by viewModel.uiState.collectAsStateWithLifecycle()
    val currentlyReading by viewModel.currentlyReading.collectAsStateWithLifecycle()
    // rawSearchQuery updates on every keystroke (no debounce) so the text field
    // always shows what the user has typed. The debounced version inside uiState
    // drives the actual Room query to avoid hammering the DB on every character.
    val rawSearchQuery   by viewModel.rawSearchQuery.collectAsStateWithLifecycle()
    // Books currently being saved for offline reading (shows a spinner on the cover)
    val downloadingIds   by viewModel.downloadingIds.collectAsStateWithLifecycle()
    // Calibre libraries this login can open (switch shown only when there are 2+)
    val libraryChoices   by viewModel.libraryChoices.collectAsStateWithLifecycle()
    // Reading streak + books finished this year
    val readingStreak    by viewModel.readingStreak.collectAsStateWithLifecycle()
    val finishedThisYear by viewModel.finishedThisYear.collectAsStateWithLifecycle()
    val showCoverBadges  by viewModel.showCoverBadges.collectAsStateWithLifecycle()

    // Regular views: 4/5/6 columns by screen width
    // Series view: 3 columns so the stacked-cover bundles have room to breathe
    val columnCount = when (windowWidthSizeClass) {
        WindowWidthSizeClass.Compact -> 4
        WindowWidthSizeClass.Medium  -> 5
        else                         -> 6
    }
    val effectiveColumnCount =
        if (uiState.sortOrder == SortOrder.BY_SERIES) 3 else columnCount

    Scaffold(
        topBar = {
            BookshelfTopBar(
                searchQuery   = rawSearchQuery,   // immediate — no debounce lag
                onQueryChange = viewModel::onSearchQueryChange,
                isSyncing     = uiState.isSyncing,
                onSyncClick   = viewModel::onSyncRequested,
                onSearchInsideBooks = onSearchInsideBooks
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Continue Reading banner ───────────────────────────────────
                currentlyReading?.let { book ->
                    ContinueReadingBanner(
                        book    = book,
                        onClick = { onBookClick(book) }
                    )
                }

                // ── Reading streak + Finished this year shelf ─────────────────
                // Hidden until there's something to show (a streak or a finished book).
                if (readingStreak.current > 0 || finishedThisYear.isNotEmpty()) {
                    ReadingStatsBar(
                        streak      = readingStreak,
                        finished    = finishedThisYear,
                        onBookClick = onBookClick
                    )
                }

                // ── Library switch ────────────────────────────────────────────
                if (libraryChoices.libraries.size >= 2) {
                    LibraryPickerBar(
                        choices  = libraryChoices,
                        onSelect = viewModel::onLibrarySelected
                    )
                }

                // ── Sort order chips ──────────────────────────────────────────
                SortOrderBar(
                    current   = uiState.sortOrder,
                    ascending = uiState.sortAscending,
                    onSelect  = viewModel::onSortOrderSelected
                )

                // ── Main content ──────────────────────────────────────────────
                when {
                    uiState.isLoading        -> FullScreenLoading()
                    uiState.items.isEmpty()  -> EmptyLibraryPlaceholder(
                        sortOrder   = uiState.sortOrder,
                        onSyncClick = viewModel::onSyncRequested
                    )
                    else -> BookshelfGrid(
                        items              = uiState.items,
                        columnCount        = effectiveColumnCount,
                        onBookClick        = onBookClick,
                        onNotesClick       = onNotesClick,
                        onEditClick        = onEditClick,
                        onDeleteClick      = viewModel::onDeleteBook,
                        onUploadToCalibre  = viewModel::onUploadToCalibre,
                        onSeriesClick      = onSeriesClick,
                        onFavoriteToggle   = viewModel::onFavoriteToggled,
                        downloadingIds     = downloadingIds,
                        onReadOffline      = viewModel::onReadOffline,
                        onRemoveDownload   = viewModel::onRemoveDownload,
                        onSyncNotes        = viewModel::onSyncNotes,
                        onMarkRead         = viewModel::onMarkRead,
                        onMessage          = viewModel::showMessage,
                        showBadges         = showCoverBadges,
                        onRate             = viewModel::onRate
                    )
                }
            }

            uiState.error?.let { errorMessage ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::onErrorDismissed) { Text("Dismiss") }
                    }
                ) { Text(errorMessage) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP APP BAR
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfTopBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
    onSearchInsideBooks: () -> Unit = {}
) {
    var searchExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            AnimatedContent(
                targetState  = searchExpanded,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label = "SearchToggle"
            ) { expanded ->
                if (expanded) {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        // Wait for the AnimatedContent transition (200 ms) to finish
                        // before requesting focus, otherwise the FocusRequester may
                        // not be attached to the layout yet and the call is ignored.
                        delay(250)
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }
                    OutlinedTextField(
                        value         = searchQuery,
                        onValueChange = onQueryChange,
                        placeholder   = { Text("Title, author, series, tag…") },
                        singleLine    = true,
                        leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon  = {
                            IconButton(onClick = {
                                onQueryChange("")
                                searchExpanded = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors   = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                } else {
                    Image(
                        painter            = painterResource(R.drawable.logo_omnitome),
                        contentDescription = "OmniTome",
                        modifier           = Modifier.height(36.dp),
                        contentScale       = ContentScale.Fit
                    )
                }
            }
        },
        actions = {
            if (!searchExpanded) {
                IconButton(onClick = { searchExpanded = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
            // Search the text inside saved books (not just titles/authors)
            IconButton(onClick = onSearchInsideBooks) {
                Icon(Icons.Default.ManageSearch, contentDescription = "Search inside books")
            }
            IconButton(onClick = onSyncClick, enabled = !isSyncing) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync library")
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SORT ORDER BAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SortOrderBar(
    current: SortOrder,
    ascending: Boolean,
    onSelect: (SortOrder) -> Unit
) {
    val options = listOf(
        SortOrder.DATE_ADDED to "Date Added",
        SortOrder.BY_TITLE   to "Title",
        SortOrder.BY_AUTHOR  to "Author",
        SortOrder.BY_SERIES  to "Series"
    )
    LazyRow(
        contentPadding        = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(options, key = { it.first.name }) { (order, label) ->
            val selected = current == order
            FilterChip(
                selected    = selected,
                onClick     = { onSelect(order) },
                label       = { Text(label) },
                leadingIcon = if (selected && order != SortOrder.BY_SERIES) {
                    // Show ↑ (ascending) or ↓ (descending). Tap again to toggle.
                    {
                        Icon(
                            imageVector        = if (ascending) Icons.Default.ArrowUpward
                                                else Icons.Default.ArrowDownward,
                            contentDescription = if (ascending) "Ascending" else "Descending",
                            modifier           = Modifier.size(16.dp)
                        )
                    }
                } else if (selected) {
                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CONTINUE READING BANNER
// Shows the most recently opened in-progress book at the top of the shelf.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A horizontal card that appears above the sort bar when the user has a book
 * in progress. Shows the cover thumbnail, title/author, a progress bar, and
 * a "Continue Reading →" label.  Tapping anywhere on the card opens the book.
 */
@Composable
private fun ContinueReadingBanner(
    book: Book,
    onClick: () -> Unit
) {
    Surface(
        onClick   = onClick,
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape     = RoundedCornerShape(12.dp),
        color     = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier          = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Cover thumbnail ───────────────────────────────────────────────
            SubcomposeAsyncImage(
                model              = book.coverUrl,
                contentDescription = "${book.title} cover",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .width(48.dp)
                    .aspectRatio(0.67f)
                    .clip(RoundedCornerShape(6.dp)),
                loading = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                },
                error = { CoverPlaceholder(title = book.title, author = book.author) }
            )

            Spacer(modifier = Modifier.width(12.dp))

            // ── Title, author, progress ───────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = "Continue Reading",
                    style      = MaterialTheme.typography.labelSmall,
                    color      = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text      = book.title,
                    style     = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis
                )
                Text(
                    text     = book.author,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Progress bar + percentage
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress    = { book.readingProgress },
                        modifier    = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        trackColor  = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text  = "${(book.readingProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // ── Arrow ─────────────────────────────────────────────────────────
            Icon(
                imageVector        = Icons.Default.ArrowForwardIos,
                contentDescription = "Open book",
                modifier           = Modifier.size(16.dp),
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BOOKSHELF GRID
// Renders books in rows that sit on illustrated blue-and-gold shelves.
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfGrid(
    items: List<ShelfItem>,
    columnCount: Int,
    onBookClick: (Book) -> Unit,
    onNotesClick: (Book) -> Unit,
    onEditClick: (Book) -> Unit,
    onDeleteClick: (Book) -> Unit,
    onUploadToCalibre: (Book) -> Unit,
    onSeriesClick: (String) -> Unit,
    onFavoriteToggle: (Book) -> Unit,
    downloadingIds: Set<String> = emptySet(),
    onReadOffline: (Book) -> Unit = {},
    onRemoveDownload: (Book, Boolean) -> Unit = { _, _ -> },
    onSyncNotes: (Book) -> Unit = {},
    onMarkRead: (Book, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onMessage: (String) -> Unit = {},
    showBadges: Boolean = true,
    onRate: (Book, Int?) -> Unit = { _, _ -> }
) {
    val rows = remember(items, columnCount) { items.chunked(columnCount) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ShelfNavy)
    ) {
        LazyColumn(
            modifier       = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { ShelfBar() }

            items(rows) { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    rowItems.forEach { item ->
                        when (item) {
                            is ShelfItem.SingleBook -> BookCoverItem(
                                book              = item.book,
                                modifier          = Modifier.weight(1f),
                                onClick           = { onBookClick(item.book) },
                                onNotesClick      = { onNotesClick(item.book) },
                                onEditClick       = { onEditClick(item.book) },
                                onDeleteClick     = { onDeleteClick(item.book) },
                                onUploadToCalibre = { onUploadToCalibre(item.book) },
                                onFavoriteToggle  = { onFavoriteToggle(item.book) },
                                isDownloading     = item.book.id in downloadingIds,
                                onReadOffline     = { onReadOffline(item.book) },
                                onRemoveDownload  = { syncFirst -> onRemoveDownload(item.book, syncFirst) },
                                onSyncNotes       = { onSyncNotes(item.book) },
                                onMarkRead        = { read, today -> onMarkRead(item.book, read, today) },
                                onMessage         = onMessage,
                                showBadges        = showBadges,
                                onRate            = { stars -> onRate(item.book, stars) }
                            )
                            is ShelfItem.SeriesBundle -> SeriesBundleCard(
                                bundle   = item,
                                modifier = Modifier.weight(1f),
                                onClick  = { onSeriesClick(item.series) }
                            )
                        }
                    }
                    // Pad the last incomplete row
                    repeat(columnCount - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                ShelfBar()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SERIES BUNDLE CARD — realistic fanned-cover stack
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Renders a series as a fan of up to 3 book covers, each slightly rotated
 * and offset so they look like a physical stack of books.
 *
 * Visual layering (drawn back-to-front):
 *   Back  : book 3 — rotated +12°, shifted right, slightly smaller
 *   Middle: book 2 — rotated +6°,  shifted right a bit
 *   Front : book 1 — flat, centered, full size
 *
 * The rotation pivots from the bottom-center of each cover so the books
 * appear to fan upward — the natural direction when paging through a stack.
 */
@Composable
private fun SeriesBundleCard(
    bundle: ShelfItem.SeriesBundle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // Up to 3 covers; book index 0 = first in series = front of stack
    val stackCovers = bundle.books.take(3)

    Column(
        modifier            = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Stacked cover area ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
        ) {
            // Render back-to-front: last item in the loop lands on top
            for (i in stackCovers.indices.reversed()) {
                val distFromFront = i           // 0 = front, 1 = mid, 2 = back
                val rotDeg  =  distFromFront * 6f    // 0° / 6° / 12°
                val transX  =  distFromFront * 8f    // shift right so they peek out
                val scale   =  1f - distFromFront * 0.04f   // slightly smaller toward back

                SubcomposeAsyncImage(
                    model              = stackCovers[i].coverUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    colorFilter        = coverColorFilter(stackCovers[i]),
                    modifier           = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha           = if (stackCovers[i].isRead) READ_COVER_ALPHA else 1f
                            rotationZ       = rotDeg
                            // Pivot from bottom-center → covers fan upward
                            transformOrigin = TransformOrigin(0.5f, 1f)
                            translationX    = transX
                            scaleX          = scale
                            scaleY          = scale
                            // Front cover casts the deepest shadow
                            shadowElevation = (3 - distFromFront) * 6f
                        }
                        .shadow(
                            elevation = (3 - distFromFront).dp * 2,
                            shape     = RoundedCornerShape(3.dp)
                        )
                        .clip(RoundedCornerShape(3.dp)),
                    loading = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1A2560))
                        )
                    },
                    error = { CoverPlaceholder(title = stackCovers[i].title, author = "") }
                )
            }

            // ── Bottom gradient + series name ─────────────────────────────────
            // Drawn after the covers so it sits on top of the front cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xDD000000))
                        )
                    )
                    .padding(horizontal = 5.dp, vertical = 5.dp)
            ) {
                Column {
                    Text(
                        text       = bundle.series,
                        color      = Color.White,
                        fontSize   = 8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis,
                        lineHeight = 10.sp
                    )
                    Text(
                        text     = "${bundle.books.size} book${if (bundle.books.size != 1) "s" else ""}",
                        color    = GoldLight,
                        fontSize = 7.sp
                    )
                }
            }

            // ── Book count badge — top-right ──────────────────────────────────
            Surface(
                shape    = CircleShape,
                color    = GoldMid,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(17.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text       = "${bundle.books.size}",
                        fontSize   = 7.sp,
                        color      = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHELF BAR — the blue cylindrical rod with gold end caps
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws a single shelf as a rounded blue rod with brass end-caps, just like
 * the bookshelf in the design reference image.
 *
 * The vertical gradient fakes the 3-D cylinder illusion:
 *  - bright blue at the top (light source hitting the round top of the rod)
 *  - mid blue in the centre
 *  - dark blue at the bottom (shadow underneath the rod)
 */
@Composable
private fun ShelfBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
    ) {
        // ── Cylindrical rod body ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .align(Alignment.Center)
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to ShelfRodTop,   // bright highlight
                            0.25f to ShelfRodMid,   // main colour
                            0.75f to ShelfRodMid,   // main colour
                            1.00f to ShelfRodBot    // shadow
                        )
                    )
                )
        )

        // ── Thin specular line on top edge of rod ───────────────────────────
        // This tiny bright strip reinforces the "shiny cylinder" look.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.TopCenter)
                .background(Color.White.copy(alpha = 0.18f))
        )

        // ── Gold end caps ───────────────────────────────────────────────────
        GoldCap(modifier = Modifier
            .size(26.dp)
            .align(Alignment.CenterStart))
        GoldCap(modifier = Modifier
            .size(26.dp)
            .align(Alignment.CenterEnd))
    }
}

/**
 * A single cylindrical brass end-cap for the shelf rod.
 * The vertical gradient gives it a metallic sheen.
 */
@Composable
private fun GoldCap(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to GoldLight,
                    0.5f to GoldMid,
                    1.0f to GoldDark
                )
            ),
            shape = CircleShape
        )
    ) {
        // Fine horizontal accent lines on the cap (decorative detail)
        Column(
            modifier            = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            repeat(3) {
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(1.dp)
                        .align(Alignment.CenterHorizontally)
                        .background(Color.Black.copy(alpha = 0.25f))
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BOOK COVER ITEM — compact cover that sits on the shelf
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A compact book cover designed to sit on the bookshelf.
 * No Card wrapper — the cover fills its slot edge-to-edge.
 * Text overlay at the bottom keeps titles readable at small sizes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCoverItem(
    book: Book,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onNotesClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onUploadToCalibre: () -> Unit = {},
    onFavoriteToggle: () -> Unit,
    isDownloading: Boolean = false,
    onReadOffline: () -> Unit = {},
    onRemoveDownload: (syncFirst: Boolean) -> Unit = {},
    onSyncNotes: () -> Unit = {},
    onMarkRead: (read: Boolean, finishedToday: Boolean) -> Unit = { _, _ -> },
    onMessage: (String) -> Unit = {},
    showBadges: Boolean = true,
    onRate: (stars: Int?) -> Unit = {}
) {
    var showShelfDialog  by remember { mutableStateOf(false) }
    if (showShelfDialog) {
        com.ereader.app.ui.shelves.ShelfPickerDialog(
            book      = book,
            onDismiss = { showShelfDialog = false },
            onMessage = onMessage
        )
    }
    var showContextMenu  by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRemoveDownloadDialog by remember { mutableStateOf(false) }
    var showMarkReadDialog by remember { mutableStateOf(false) }
    var showRatingDialog   by remember { mutableStateOf(false) }
    var ratingAfterFinish  by remember { mutableStateOf(false) }

    // ── Mark as Read popup ("when did you finish it?") ────────────────────────
    // Afterwards, ask for a rating — even if Calibre already has one, because that
    // may have come with the book's downloaded metadata, not from the reader.
    if (showMarkReadDialog) {
        MarkAsReadDialog(
            book      = book,
            onDismiss = { showMarkReadDialog = false },
            onConfirm = { today ->
                onMarkRead(true, today)
                ratingAfterFinish = true; showRatingDialog = true
            }
        )
    }

    // ── Rate Book popup ───────────────────────────────────────────────────────
    if (showRatingDialog) {
        RatingDialog(
            book         = book,
            justFinished = ratingAfterFinish,
            onDismiss    = { showRatingDialog = false; ratingAfterFinish = false },
            onSave       = onRate
        )
    }

    // ── Remove Download popup ("sync your notes and page read first?") ────────
    if (showRemoveDownloadDialog) {
        RemoveDownloadDialog(
            book      = book,
            onDismiss = { showRemoveDownloadDialog = false },
            onConfirm = onRemoveDownload
        )
    }

    // ── Delete confirmation dialog ────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove Book") },
            text  = { Text("Remove \"${book.title}\" from your library? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDeleteClick() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(
        modifier = modifier
            // shadow must come before clip so it renders outside the clipped area
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .combinedClickable(
                onClick     = onClick,
                onLongClick = { showContextMenu = true }
            )
    ) {
        // ── Context menu (long-press) ─────────────────────────────────────────
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
            DropdownMenuItem(
                text        = { Text("Add to Shelf\u2026") },
                onClick     = { showContextMenu = false; showShelfDialog = true },
                leadingIcon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) }
            )
            // "Mark as Read" (asks when) / "Mark as Unread" (instant)
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
            // "Rate Book" — 1 to 5 stars, synced with Calibre's Rating
            DropdownMenuItem(
                text        = {
                    val s = wholeStars(book)
                    Text(if (s != null) "Rate Book  ${starText(s)}" else "Rate Book")
                },
                onClick     = { showContextMenu = false; ratingAfterFinish = false; showRatingDialog = true },
                leadingIcon = { Icon(Icons.Default.StarOutline, contentDescription = null) }
            )
            // "Read Offline" — saves a Calibre/Drive book onto the phone.
            // Hidden for imported books (they already live on the phone),
            // for books already saved, and while a save is in progress.
            if (book.source != BookSource.LOCAL && !book.isDownloaded && !isDownloading) {
                DropdownMenuItem(
                    text        = { Text("Read Offline") },
                    onClick     = { showContextMenu = false; onReadOffline() },
                    leadingIcon = { Icon(Icons.Default.DownloadForOffline, contentDescription = null) }
                )
            }
            // "Remove Download" — only for books saved for offline reading
            if (book.source != BookSource.LOCAL && book.isDownloaded && !isDownloading) {
                DropdownMenuItem(
                    text        = { Text("Remove Download") },
                    onClick     = { showContextMenu = false; showRemoveDownloadDialog = true },
                    leadingIcon = { Icon(Icons.Default.FileDownloadOff, contentDescription = null) }
                )
            }
            // "Sync Notes to Calibre" — Calibre-linked books only
            if (book.source == BookSource.CALIBRE && book.calibreBookId != null && !isDownloading) {
                DropdownMenuItem(
                    text        = { Text("Sync Notes to Calibre") },
                    onClick     = { showContextMenu = false; onSyncNotes() },
                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
                )
            }
            // "Upload to Calibre" — only for locally imported books
            if (book.source == BookSource.LOCAL) {
                DropdownMenuItem(
                    text        = { Text("Upload to Calibre") },
                    onClick     = { showContextMenu = false; onUploadToCalibre() },
                    leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) }
                )
            }
            DropdownMenuItem(
                text        = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                onClick     = { showContextMenu = false; showDeleteDialog = true },
                leadingIcon = {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
        // ── Cover image ───────────────────────────────────────────────────
        SubcomposeAsyncImage(
            model              = book.coverUrl,
            contentDescription = "${book.title} cover",
            contentScale       = ContentScale.Crop,
            // Read books: greyed out and faded so unread ones stand out.
            colorFilter        = coverColorFilter(book),
            modifier           = Modifier
                .fillMaxWidth()
                .aspectRatio(0.67f)
                .graphicsLayer { alpha = if (book.isRead) READ_COVER_ALPHA else 1f },
            loading = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A2560)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color       = Color.White.copy(alpha = 0.4f)
                    )
                }
            },
            error = {
                CoverPlaceholder(title = book.title, author = book.author)
            }
        )

        // ── Title / author gradient overlay ───────────────────────────────
        // Dark gradient at the bottom so white text stays readable.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                        startY = 0f
                    )
                )
                .padding(horizontal = 4.dp, vertical = 5.dp)
        ) {
            Column {
                Text(
                    text       = book.title,
                    color      = Color.White,
                    fontSize   = 8.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                    lineHeight = 10.sp
                )
                Text(
                    text     = book.author,
                    color    = Color.White.copy(alpha = 0.72f),
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.series != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    SeriesBadge(series = book.series, index = book.seriesIndex)
                }
            }
        }

        // ── Favorite icon (top-right corner) ─────────────────────────────
        IconButton(
            onClick  = onFavoriteToggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(26.dp)
        ) {
            Icon(
                imageVector        = if (book.isFavorite) Icons.Default.Favorite
                                     else Icons.Default.FavoriteBorder,
                contentDescription = if (book.isFavorite) "Remove from favorites"
                                     else "Add to favorites",
                tint     = if (book.isFavorite) Color(0xFFFF4D6D) else Color.White,
                modifier = Modifier.size(13.dp)
            )
        }

        // ── Offline badge (top-right, just under the heart) ──────────────
        // Spinner while saving; green pin once the book is on the phone.
        // Not shown for imported (LOCAL) books — those are always on the phone.
        if (book.source != BookSource.LOCAL && (isDownloading || book.isDownloaded)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 26.dp, end = 6.dp)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color       = Color.White
                    )
                } else {
                    Icon(
                        imageVector        = Icons.Default.OfflinePin,
                        contentDescription = "Saved for offline reading",
                        tint               = Color(0xFF7CD992),
                        modifier           = Modifier.size(12.dp)
                    )
                }
            }
        }

        // ── Cover badges (top-left corner, stacked top to bottom) ─────────
        //   1. Read tick (or the reading-progress arc while reading)
        //   2. NEW  — added to Calibre < 14 days ago and not read
        //   3. Fire — rated 5 stars in Calibre
        // Settings → "Show cover badges" hides 1-3; the progress arc always shows.
        val showRead = showBadges && book.isRead
        val showArc  = !book.isRead && book.readingProgress > 0f
        val showNew  = showBadges && isNewBook(book)
        val showFire = showBadges && isFiveStar(book)
        if (showRead || showArc || showNew || showFire) {
            Column(
                modifier            = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (showRead) ReadBadge(size = 18.dp)
                if (showArc) {
                    ReadingProgressArc(
                        progress = book.readingProgress,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (showNew)  NewBadge(height = 18.dp)
                if (showFire) FavoriteRatingBadge(size = 18.dp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// READING PROGRESS ARC
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadingProgressArc(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress    = { progress },
            modifier    = Modifier.fillMaxSize(),
            strokeWidth = 2.5.dp,
            color       = MaterialTheme.colorScheme.primary,
            trackColor  = Color.White.copy(alpha = 0.3f)
        )
        Text(
            text       = "${(progress * 100).toInt()}%",
            fontSize   = 5.sp,
            color      = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SERIES BADGE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SeriesBadge(series: String, index: Float?) {
    val indexLabel = index?.let { n ->
        if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()
    }
    val label = if (indexLabel != null) "$series #$indexLabel" else series

    Surface(
        shape    = RoundedCornerShape(4.dp),
        color    = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
        modifier = Modifier.wrapContentSize()
    ) {
        Text(
            text     = label,
            fontSize = 7.sp,
            color    = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COVER PLACEHOLDER (no image available)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CoverPlaceholder(title: String, author: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.padding(6.dp)
        ) {
            Icon(
                Icons.Default.MenuBook,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text       = title,
                fontSize   = 8.sp,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign  = TextAlign.Center,
                maxLines   = 3,
                overflow   = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text      = author,
                fontSize  = 7.sp,
                color     = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EMPTY STATE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyLibraryPlaceholder(
    sortOrder: SortOrder,
    onSyncClick: () -> Unit
) {
    // Choose icon, headline, and body text based on why the list is empty
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val headline: String
    val body: String

    when (sortOrder) {
        SortOrder.BY_SERIES -> {
            icon     = Icons.Default.Collections
            headline = "No series found"
            body     = "Your books don't have series metadata yet.\n\nTap Sync below — OmniTome will pull the latest information from Calibre, including series names and reading order."
        }
        else -> {
            icon     = Icons.Default.LibraryBooks
            headline = "Your library is empty"
            body     = "Connect your Calibre server or Google Drive in Settings, then sync to see your books here."
        }
    }

    Column(
        modifier              = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text      = headline,
            style     = MaterialTheme.typography.headlineSmall,
            color     = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text      = body,
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSyncClick) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sync Library")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FULL-SCREEN LOADING SPINNER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FullScreenLoading() {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LEGACY BookCard — kept for any future reference/reuse outside the shelf grid
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Press-and-hold action (e.g. "Remove from shelf"); none if null. */
    onLongClick: (() -> Unit)? = null
) {
    Card(
        modifier  = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape     = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box {
            SubcomposeAsyncImage(
                model              = book.coverUrl,
                contentDescription = "${book.title} cover",
                contentScale       = ContentScale.Crop,
                colorFilter        = coverColorFilter(book),
                modifier           = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.67f)
                    .graphicsLayer { alpha = if (book.isRead) READ_COVER_ALPHA else 1f },
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                },
                error = { CoverPlaceholder(title = book.title, author = book.author) }
            )
            if (book.isRead) {
                ReadBadge(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    size     = 22.dp
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        text       = book.title,
                        color      = Color.White,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        text     = book.author,
                        color    = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick  = onFavoriteToggle,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector        = if (book.isFavorite) Icons.Default.Favorite
                                        else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint     = if (book.isFavorite) Color(0xFFFF4D6D) else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
