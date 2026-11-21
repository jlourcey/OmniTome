package com.ereader.app.ui.notes

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationType
import com.ereader.app.domain.model.Annotation
import kotlinx.coroutines.launch

/**
 * Full-screen overlay that slides up over the Readium reader and shows all
 * annotations for the currently open book.
 *
 * Used exclusively from inside [ReaderActivity] via [ReaderControlsHud].
 * The annotations list and callbacks are passed down from [ReaderViewModel].
 *
 * Layout:
 *   TopAppBar  — title "Notes", share/export button, close button
 *   LazyColumn — annotations grouped by chapter; each item shows:
 *                  • Left color bar matching the highlight colour
 *                  • Page number + type badge
 *                  • Selected text in italic (HIGHLIGHT / NOTE)
 *                  • Written note in body text (NOTE / COMMENT)
 *                  • Delete icon button
 *
 * PDF export calls [AnnotationPdfExporter.export] on IO and then launches the
 * system share sheet using [Intent.createChooser].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesOverlay(
    visible: Boolean,
    annotations: List<Annotation>,
    bookTitle: String,
    onClose: () -> Unit,
    onDelete: (String) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter   = slideInVertically { it } + fadeIn(),
        exit    = slideOutVertically { it } + fadeOut()
    ) {
        NotesContent(
            annotations = annotations,
            bookTitle   = bookTitle,
            onClose     = onClose,
            onDelete    = onDelete
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Inner content — extracted so it can also be previewed in isolation
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesContent(
    annotations: List<Annotation>,
    bookTitle: String,
    onClose: () -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    // Group by chapter, preserve reading order (annotations are pre-sorted by
    // progressionInBook ASC from Room)
    val byChapter: List<Pair<String, List<Annotation>>> = remember(annotations) {
        annotations
            .groupBy { it.chapterTitle?.ifBlank { null } ?: "General Notes" }
            .entries
            .map { (k, v) -> k to v }
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
                    // Export PDF
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 12.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (annotations.isNotEmpty()) {
                                    exporting = true
                                    scope.launch {
                                        runCatching {
                                            val intent = AnnotationPdfExporter.export(
                                                context     = context,
                                                bookTitle   = bookTitle.ifBlank { "Book" },
                                                annotations = annotations
                                            )
                                            context.startActivity(
                                                Intent.createChooser(intent, "Share Notes")
                                            )
                                        }
                                        exporting = false
                                    }
                                }
                            },
                            enabled = annotations.isNotEmpty() && !exporting
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export notes as PDF")
                        }
                    }
                    // Close
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close notes")
                    }
                }
            )
        }
    ) { padding ->
        if (annotations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text  = "No notes yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text  = "Long-press any word to add a highlight or note.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start  = 16.dp,
                    end    = 16.dp,
                    top    = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                for ((chapter, items) in byChapter) {
                    item(key = "header_$chapter") {
                        Text(
                            text     = chapter,
                            style    = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color    = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp, bottom = 6.dp)
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(items = items, key = { it.id }) { annotation ->
                        AnnotationRow(
                            annotation = annotation,
                            onDelete   = { onDelete(annotation.id) }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single annotation row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AnnotationRow(
    annotation: Annotation,
    onDelete: () -> Unit
) {
    val barColor = remember(annotation.color) {
        annotation.color.toComposeColor().copy(alpha = 1f)
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
                .background(barColor, shape = RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Meta: page number + type
            val metaText = buildString {
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
                text  = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Selected text (italic)
            annotation.selectedText?.let { text ->
                Text(
                    text      = "“$text”",   // curly quotes
                    style     = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color     = MaterialTheme.colorScheme.onSurface
                )
            }

            // Written note
            annotation.note?.let { note ->
                if (annotation.selectedText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text  = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
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

// ── Extension re-export so NotesOverlay.kt can call toComposeColor() ─────────
private fun AnnotationColor.toComposeColor(): Color =
    Color(android.graphics.Color.parseColor(hex)).copy(alpha = 0.75f)
