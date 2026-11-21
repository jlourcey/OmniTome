package com.ereader.app.ui.notes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationType
import com.ereader.app.domain.model.Annotation

/**
 * Standalone "Notes" screen — navigated to from the bookshelf via a long-press
 * on a book cover and selecting "View Notes".
 *
 * Nav route: `notes/{bookId}`
 *
 * Shows:
 *  - TopAppBar with book title, share/export PDF button, back button
 *  - LazyColumn of annotations grouped by chapter in reading order
 *    - Chapter header
 *    - Per annotation: colored left bar, page number, type label, selected text
 *      (italic), written note, delete icon
 *
 * When the user taps the export icon, a share sheet is launched with the PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onNavigateBack: () -> Unit,
    viewModel: NotesViewModel = hiltViewModel()
) {
    val context     = LocalContext.current
    val annotations by viewModel.annotations.collectAsState()
    val bookTitle   by viewModel.bookTitle.collectAsState()
    val isLoading   by viewModel.isLoading.collectAsState()
    val error       by viewModel.error.collectAsState()

    var exporting   by remember { mutableStateOf(false) }

    // Launch share sheet when the ViewModel emits a share intent
    LaunchedEffect(viewModel) {
        viewModel.shareEvent.collect { intent ->
            exporting = false
            context.startActivity(Intent.createChooser(intent, "Share Notes"))
        }
    }

    // Show error snackbar — stays visible until the user taps Dismiss
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(
                message     = it,
                actionLabel = "Dismiss",
                duration    = SnackbarDuration.Indefinite
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text  = "Notes",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (bookTitle.isNotBlank()) {
                            Text(
                                text  = bookTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier    = Modifier
                                .size(24.dp)
                                .padding(end = 16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick  = {
                                exporting = true
                                viewModel.exportPdf()
                            },
                            enabled  = annotations.isNotEmpty() && !isLoading
                        ) {
                            Icon(
                                imageVector        = Icons.Default.IosShare,
                                contentDescription = "Export notes as PDF"
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->

        when {
            isLoading -> {
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            annotations.isEmpty() -> {
                Box(
                    modifier         = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text  = "No notes for this book yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text  = "Open the book and long-press any word to highlight it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                val byChapter: List<Pair<String, List<Annotation>>> = remember(annotations) {
                    annotations
                        .groupBy { it.chapterTitle?.ifBlank { null } ?: "General Notes" }
                        .entries
                        .map { (k, v) -> k to v }
                }

                LazyColumn(
                    contentPadding = PaddingValues(
                        start  = 16.dp,
                        end    = 16.dp,
                        top    = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    for ((chapter, items) in byChapter) {
                        item(key = "header_$chapter") {
                            Text(
                                text       = chapter,
                                style      = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color      = MaterialTheme.colorScheme.primary,
                                modifier   = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp, bottom = 6.dp)
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        items(items = items, key = { it.id }) { annotation ->
                            NotesAnnotationRow(
                                annotation = annotation,
                                onDelete   = { viewModel.deleteAnnotation(annotation.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single annotation row (same layout as NotesOverlay — kept separate so each
// file stays self-contained and independently previewable)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun NotesAnnotationRow(
    annotation: Annotation,
    onDelete: () -> Unit
) {
    val barColor = remember(annotation.color) {
        Color(android.graphics.Color.parseColor(annotation.color.hex))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        // Left color bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 32.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Page number + annotation type
            val meta = buildString {
                annotation.pageNumber?.let { append("Page $it  ·  ") }
                append(
                    when (annotation.type) {
                        AnnotationType.HIGHLIGHT -> "Highlight"
                        AnnotationType.NOTE      -> "Note"
                        AnnotationType.COMMENT   -> "Comment"
                    }
                )
            }
            Text(
                text  = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Selected text in italic
            annotation.selectedText?.let { text ->
                Text(
                    text      = "“$text”",   // curly quotes
                    style     = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color     = MaterialTheme.colorScheme.onSurface
                )
            }

            // Written note — larger than the italic highlight text, dark red so it reads
            // as a distinct "voice" from the quoted passage
            annotation.note?.let { note ->
                if (annotation.selectedText != null) Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text  = note,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF8B0000)   // dark red ink
                )
            }
        }

        // Delete button
        IconButton(
            onClick  = onDelete,
            modifier = Modifier
                .size(36.dp)
                .align(Alignment.Top)
        ) {
            Icon(
                imageVector        = Icons.Default.Delete,
                contentDescription = "Delete annotation",
                modifier           = Modifier.size(18.dp),
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    HorizontalDivider(
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

// Extension kept local — avoids cross-module dependency on the entity helper
private val AnnotationColor.composeColor: Color
    get() = Color(android.graphics.Color.parseColor(hex))
