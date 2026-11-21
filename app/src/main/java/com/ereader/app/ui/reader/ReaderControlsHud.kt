package com.ereader.app.ui.reader

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationType
import com.ereader.app.domain.model.Bookmark
import com.ereader.app.ui.notes.NotesOverlay
import com.ereader.app.ui.theme.ReaderDayBackground
import com.ereader.app.ui.theme.ReaderNightBackground
import com.ereader.app.ui.theme.ReaderPaperBackground
import com.ereader.app.ui.theme.ReaderPaperText
import com.ereader.app.ui.theme.ReaderSepiaBackground

// ── Reader preference types ───────────────────────────────────────────────────

enum class ReaderTheme      { DAY, NIGHT, SEPIA, PAPER }
enum class ReaderFontFamily { SERIF, SANS, MONO }

/** Background colour for the HUD bars — matches the active reading theme at 96% opacity. */
internal fun readerHudSurface(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.DAY   -> Color(1.000f, 1.000f, 1.000f, 0.96f)
    ReaderTheme.NIGHT -> Color(0.102f, 0.102f, 0.102f, 0.96f)
    ReaderTheme.SEPIA -> Color(0.957f, 0.925f, 0.847f, 0.96f)
    ReaderTheme.PAPER -> Color(0.941f, 0.922f, 0.878f, 0.96f)
}

/** Icon / text colour for the HUD bars — readable on the matching surface. */
internal fun readerHudOnSurface(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.DAY   -> Color(0.102f, 0.102f, 0.102f, 1f)
    ReaderTheme.NIGHT -> Color(0.878f, 0.878f, 0.878f, 1f)
    ReaderTheme.SEPIA -> Color(0.361f, 0.251f, 0.200f, 1f)
    ReaderTheme.PAPER -> Color(0.110f, 0.082f, 0.063f, 1f)
}

// ─────────────────────────────────────────────────────────────────────────────
// READER CONTROLS HUD
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Transparent overlay sitting on top of the Readium WebView.
 *
 * Touch handling strategy
 * ───────────────────────
 * The previous implementation used `Modifier.clickable` on a full-screen Box,
 * which consumed EVERY touch event — including long-presses — before the Readium
 * WebView could see them. That prevented text selection entirely.
 *
 * The fix: `pointerInteropFilter { … return@pointerInteropFilter false }`.
 * By always returning `false`, the ComposeView tells Android it did NOT consume
 * the event. Android's View dispatch then falls through to the next sibling in
 * the FrameLayout, which is the Readium fragment's root View (the WebView).
 * The WebView receives the long-press and shows text selection handles as normal.
 *
 * Meanwhile, `pointerInteropFilter` still sees every event, so we can detect a
 * short tap (DOWN quickly followed by UP) to toggle the HUD — without blocking
 * anything that goes to the WebView.
 *
 * Buttons inside the HUD (when visible) consume their own click events normally;
 * the WebView never receives those.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderControlsHud(
    viewModel: ReaderViewModel,
    onNavigateToLocator: (String) -> Unit,
    onStartReading: () -> Unit,
    onPauseReading: () -> Unit,
    onResumeReading: () -> Unit,
    onStopReading: () -> Unit,
    onClose: () -> Unit
) {
    // ── ViewModel state ───────────────────────────────────────────────────────
    val pendingAnnotation  by viewModel.pendingAnnotation.collectAsState()
    val showNotes          by viewModel.showNotes.collectAsState()
    val annotations        by viewModel.annotations.collectAsState()
    val bookTitle          by viewModel.bookTitle.collectAsState()
    val bookmarks          by viewModel.bookmarks.collectAsState()
    val isPageBookmarked   by viewModel.isCurrentPageBookmarked.collectAsState()
    val showBookmarks      by viewModel.showBookmarks.collectAsState()
    val ttsState           by viewModel.ttsState.collectAsState()
    val readingProgress    by viewModel.readingProgress.collectAsState()

    // Reader display preferences — sourced from ViewModel so the Activity can
    // observe the same values and push them into the WebView via JS injection.
    val fontSize   by viewModel.fontSizeSp.collectAsState()
    val theme      by viewModel.readerThemePref.collectAsState()
    val fontFamily by viewModel.fontFamilyPref.collectAsState()

    // HUD visibility — owned by ViewModel so the Activity's tap detector can
    // toggle it without going through the Compose event system.
    val hudVisible by viewModel.hudVisible.collectAsState()

    // ── Root: full-screen container ───────────────────────────────────────────
    // The ComposeView is only brought to the front of reader_container when the
    // HUD is visible OR briefly during a page-curl animation (see attachHud()).
    // When hidden, the Readium WebView sits on top so page-turn swipes reach it
    // unobstructed.

    // Fully-opaque reader theme colour — used as the ComposeView's own
    // background so every transparent pixel (including the status bar and
    // navigation bar inset zones) shows the correct colour rather than
    // whatever Android renders behind the View (which can be white).
    val readerBgOpaque = readerHudSurface(theme).copy(alpha = 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Solid background behind ALL Compose content.  When the HUD is
            // hidden the WebView sits on top and this is invisible; when the
            // HUD is visible (or during a brief page-curl lift) this colour
            // fills any area that Compose leaves transparent — most critically
            // the status-bar and navigation-bar strips at the edges.
            .background(readerBgOpaque)
    ) {

        // Page curl animation — drawn first (behind HUD bars) so the bars can
        // still be tapped when the curl fades out. Invisible when idle (the
        // Canvas is not even composed when animatable.value == 0f).
        PageCurlOverlay(
            pageTurnFlow = viewModel.pageTurnChannel,
            modifier     = Modifier.fillMaxSize()
        )

        // ── Top bar ───────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = hudVisible,
            enter    = slideInVertically { -it } + fadeIn(),
            exit     = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color           = readerHudSurface(theme),
                contentColor    = readerHudOnSurface(theme),
                shadowElevation = 4.dp,
                modifier        = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back to library
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to library")
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    // ── Add page-level note (no text selection required) ───────
                    IconButton(onClick = {
                        viewModel.onAddPageNote()
                        // Keep HUD visible — the note dialog renders inside the
                        // ComposeView and needs it to stay in front.
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Add page note")
                    }

                    // ── View consolidated notes ───────────────────────────────
                    val noteCount = annotations.size
                    Box {
                        IconButton(onClick = {
                            viewModel.openNotes()
                            // Do NOT hide HUD — NotesOverlay renders inside the
                            // ComposeView and needs it to remain in front.
                        }) {
                            Icon(Icons.Default.Notes, contentDescription = "View notes")
                        }
                        if (noteCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text  = if (noteCount < 100) noteCount.toString() else "99+",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7f
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                    IconButton(onClick = { /* TODO: TOC drawer */ }) {
                        Icon(Icons.Default.FormatListBulleted, contentDescription = "Table of contents")
                    }

                    // ── Read aloud ────────────────────────────────────────────
                    // Single speaker icon when idle → play/pause + stop when active.
                    when (ttsState) {
                        TtsState.IDLE -> {
                            IconButton(onClick = {
                                onStartReading()
                                viewModel.hideHud()
                            }) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "Read aloud")
                            }
                        }
                        TtsState.PLAYING -> {
                            IconButton(onClick = { onPauseReading() }) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause reading")
                            }
                            IconButton(onClick = { onStopReading() }) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop reading")
                            }
                        }
                        TtsState.PAUSED -> {
                            IconButton(onClick = { onResumeReading() }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume reading")
                            }
                            IconButton(onClick = { onStopReading() }) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop reading")
                            }
                        }
                    }

                    // ── Bookmark toggle — tap to add/remove current-page bookmark ──
                    IconButton(onClick = { viewModel.toggleBookmark() }) {
                        Icon(
                            imageVector       = if (isPageBookmarked) Icons.Default.Bookmark
                                               else Icons.Default.BookmarkBorder,
                            contentDescription = if (isPageBookmarked) "Remove bookmark"
                                               else "Bookmark this page",
                            tint = if (isPageBookmarked) MaterialTheme.colorScheme.primary
                                   else LocalContentColor.current
                        )
                    }

                    // ── Bookmarks list — open sheet showing all saved bookmarks ─────
                    Box {
                        IconButton(onClick = {
                            viewModel.openBookmarks()
                            // Do NOT hide HUD — the bookmarks sheet renders inside
                            // the ComposeView and needs it to remain in front.
                        }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "All bookmarks")
                        }
                        if (bookmarks.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp)
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text  = if (bookmarks.size < 100) bookmarks.size.toString() else "99+",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7f
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom controls bar ───────────────────────────────────────────────
        AnimatedVisibility(
            visible  = hudVisible,
            enter    = slideInVertically { it } + fadeIn(),
            exit     = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color           = readerHudSurface(theme),
                contentColor    = readerHudOnSurface(theme),
                shadowElevation = 8.dp,
                modifier        = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                ) {
                    // ── Reading progress ──────────────────────────────────────
                    if (readingProgress > 0f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier          = Modifier.fillMaxWidth()
                        ) {
                            LinearProgressIndicator(
                                progress   = { readingProgress },
                                modifier   = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text  = "${(readingProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Font size slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "Smaller text")
                        Slider(
                            value         = fontSize.toFloat(),
                            onValueChange = { viewModel.setFontSize(it.toInt()) },
                            valueRange    = 12f..28f,
                            steps         = 7,
                            modifier      = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Icon(Icons.Default.TextIncrease, contentDescription = "Larger text")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text  = "${fontSize}px",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Theme selector
                    Text(
                        text  = "Theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier              = Modifier.fillMaxWidth()
                    ) {
                        ThemeChip("Day",   ReaderDayBackground,   Color(0xFF1A1A1A), theme == ReaderTheme.DAY,   { viewModel.setTheme(ReaderTheme.DAY)   }, Modifier.weight(1f))
                        ThemeChip("Sepia", ReaderSepiaBackground, Color(0xFF5C4033), theme == ReaderTheme.SEPIA, { viewModel.setTheme(ReaderTheme.SEPIA) }, Modifier.weight(1f))
                        ThemeChip("Night", ReaderNightBackground, Color(0xFFE0E0E0), theme == ReaderTheme.NIGHT, { viewModel.setTheme(ReaderTheme.NIGHT) }, Modifier.weight(1f))
                        ThemeChip("Paper", ReaderPaperBackground, ReaderPaperText,   theme == ReaderTheme.PAPER, { viewModel.setTheme(ReaderTheme.PAPER) }, Modifier.weight(1f))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Font family selector
                    Text(
                        text  = "Font",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier              = Modifier.fillMaxWidth()
                    ) {
                        ReaderFontFamily.entries.forEach { fam ->
                            FilterChip(
                                selected = fontFamily == fam,
                                onClick  = { viewModel.setFontFamily(fam) },
                                label    = { Text(fam.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ── Notes full-screen overlay ─────────────────────────────────────────
        NotesOverlay(
            visible     = showNotes,
            annotations = annotations,
            bookTitle   = bookTitle,
            onClose     = { viewModel.closeNotes() },
            onDelete    = { id -> viewModel.deleteAnnotation(id) }
        )
    }

    // ── Annotation dialog ─────────────────────────────────────────────────────
    // Outside the touch-pass-through Box so it draws on top of everything and
    // its button taps are handled by Compose normally (not passed to WebView).
    pendingAnnotation?.let { pending ->
        AnnotationDialog(
            pending   = pending,
            onSave    = { color, note -> viewModel.saveAnnotation(color, note) },
            onDismiss = { viewModel.dismissAnnotationDialog() }
        )
    }

    // ── Bookmarks sheet ───────────────────────────────────────────────────────
    // Also outside the touch-pass-through Box for the same reason.
    if (showBookmarks) {
        BookmarksSheet(
            bookmarks        = bookmarks,
            isPageBookmarked = isPageBookmarked,
            onToggle         = { viewModel.toggleBookmark() },
            onNavigate       = { bookmark ->
                viewModel.closeBookmarks()
                onNavigateToLocator(bookmark.locatorJson)
            },
            onDelete         = { id -> viewModel.deleteBookmark(id) },
            onDismiss        = { viewModel.closeBookmarks() }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANNOTATION DIALOG
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationDialog(
    pending: PendingAnnotation,
    onSave: (AnnotationColor, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(AnnotationColor.YELLOW) }
    var noteText      by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle       = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            // Chapter + page context
            val contextLine = buildString {
                pending.chapterTitle?.let { append(it) }
                pending.pageNumber?.let {
                    if (isNotEmpty()) append("  ·  ")
                    append("Page $it")
                }
            }
            if (contextLine.isNotBlank()) {
                Text(
                    text  = contextLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Selected text preview
            if (!pending.isPageNote) {
                Text(
                    text      = "“${pending.selectedText}”",
                    style     = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color     = MaterialTheme.colorScheme.onSurface,
                    maxLines  = 4
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Text(
                    text  = "Page Note",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Color swatches
            Text(
                text  = "Color",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                AnnotationColor.entries.forEach { color ->
                    ColorSwatch(
                        annotationColor = color,
                        isSelected      = selectedColor == color,
                        onClick         = { selectedColor = color }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Note text field
            OutlinedTextField(
                value         = noteText,
                onValueChange = { noteText = it },
                modifier      = Modifier.fillMaxWidth(),
                label         = { Text("Add a note (optional)") },
                minLines      = 3,
                maxLines      = 6,
                shape         = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action buttons
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick  = { onSave(selectedColor, noteText.ifBlank { null }) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when {
                            pending.isPageNote    -> "Save Note"
                            noteText.isNotBlank() -> "Save Note"
                            else                  -> "Highlight"
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COLOR SWATCH
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColorSwatch(
    annotationColor: AnnotationColor,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = remember(annotationColor) {
        Color(android.graphics.Color.parseColor(annotationColor.hex)).copy(alpha = 0.85f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            // This button IS inside AnnotationDialog which is OUTSIDE the
            // pointerInteropFilter Box, so taps here are consumed by Compose
            // and never reach the WebView.
            .then(Modifier.let { m ->
                @Suppress("DEPRECATION")
                m   // clickable handled by Box's pointerInput below
            })
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "${annotationColor.displayName} selected",
                tint     = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        // Overlay a full-size clickable surface
        Surface(
            onClick = onClick,
            color   = Color.Transparent,
            modifier = Modifier.fillMaxSize()
        ) {}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BOOKMARKS SHEET
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A bottom sheet showing all bookmarks for the current book.
 *
 * Layout:
 *  - "Add / Remove bookmark" toggle button for the current page.
 *  - Scrollable list of all existing bookmarks, each showing the chapter name
 *    and progress percentage. Tap a row to navigate; trash icon to delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    isPageBookmarked: Boolean,
    onToggle: () -> Unit,
    onNavigate: (Bookmark) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor   = MaterialTheme.colorScheme.surface,
        dragHandle       = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text  = "Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Toggle current-page bookmark ──────────────────────────────────
            OutlinedButton(
                onClick  = onToggle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector        = if (isPageBookmarked) Icons.Default.BookmarkRemove
                                        else Icons.Default.BookmarkAdd,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isPageBookmarked) "Remove bookmark at this page" else "Add bookmark at this page")
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            if (bookmarks.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier         = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                ) {
                    Text(
                        text  = "No bookmarks yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalArrangement   = Arrangement.spacedBy(0.dp)
                ) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark  = bookmark,
                            onClick   = { onNavigate(bookmark) },
                            onDelete  = { onDelete(bookmark.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = Icons.Default.Bookmark,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = bookmark.displayName,
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text  = bookmark.progressPercent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector        = Icons.Default.DeleteOutline,
                contentDescription = "Delete bookmark",
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// THEME CHIP
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThemeChip(
    label: String,
    bgColor: Color,
    fgColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected  = isSelected,
        onClick   = onClick,
        label     = { Text(label, color = fgColor) },
        colors    = FilterChipDefaults.filterChipColors(
            containerColor         = bgColor,
            selectedContainerColor = bgColor
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled             = true,
            selected            = isSelected,
            borderColor         = fgColor.copy(alpha = 0.3f),
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            selectedBorderWidth = 2.dp
        ),
        modifier = modifier
    )
}
