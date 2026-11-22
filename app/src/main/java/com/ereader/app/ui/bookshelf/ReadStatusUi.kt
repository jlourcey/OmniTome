package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.data.repository.ReadPushResult
import com.ereader.app.domain.model.Book

// ─────────────────────────────────────────────────────────────────────────────
// READ STATUS — how finished books look, and the "Mark as Read" popup.
// Shared by the bookshelf, series and favorites screens.
// ─────────────────────────────────────────────────────────────────────────────

/** How see-through a Read book's cover is (1 = solid). */
internal const val READ_COVER_ALPHA = 0.55f

/** Mostly grey: keeps 25 % of the cover's colour so it's still recognisable. */
internal val ReadCoverColorFilter: ColorFilter =
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.25f) })

/** Colour filter for a cover: faded for Read books, normal otherwise. */
internal fun coverColorFilter(book: Book): ColorFilter? =
    if (book.isRead) ReadCoverColorFilter else null

/** Small green tick in a dark circle — marks a book as Read. */
@Composable
internal fun ReadBadge(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = Icons.Default.Check,
            contentDescription = "Read",
            tint               = Color(0xFF7CD992),
            modifier           = Modifier.size(size * 0.75f)
        )
    }
}

/**
 * The popup after tapping "Mark as Read".
 *   Finished Today — dated today, so it shows on this year's shelf.
 *   A While Ago    — just marked Read; doesn't count toward this year.
 */
@Composable
internal fun MarkAsReadDialog(
    book: Book,
    onDismiss: () -> Unit,
    onConfirm: (finishedToday: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.TaskAlt, contentDescription = null) },
        title = { Text("Mark as Read") },
        text  = {
            Text(
                "When did you finish \"${book.title}\"?\n\n" +
                "Books finished today are added to your Finished This Year shelf."
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onDismiss(); onConfirm(true) })  { Text("Finished Today") }
                TextButton(onClick = { onDismiss(); onConfirm(false) }) { Text("A While Ago") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Shared by every screen with the Mark as Read / Unread menu item.
 * Saves the change, tries to send it to Calibre, and returns the message to show.
 */
internal suspend fun setReadWithMessage(
    repository: BookRepository,
    book: Book,
    read: Boolean,
    finishedToday: Boolean
): String {
    val done = if (read) "marked as read" else "marked as unread"
    return when (val r = repository.setReadStatus(book, read, finishedToday)) {
        ReadPushResult.Synced       -> "✓ \"${book.title}\" $done and synced to Calibre"
        ReadPushResult.NotLinked    -> "✓ \"${book.title}\" $done"
        ReadPushResult.NoReadColumn ->
            "✓ \"${book.title}\" $done in OmniTome. To sync it, add a Yes/No column " +
            "named \"Read\" in Calibre (Preferences → Add your own columns)."
        is ReadPushResult.Failed    ->
            "✓ \"${book.title}\" $done. It will sync to Calibre next time you're home. " +
            calibreErrorReason(r.error)
    }
}
