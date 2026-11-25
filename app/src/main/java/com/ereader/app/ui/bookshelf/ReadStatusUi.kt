package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
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

// ─────────────────────────────────────────────────────────────────────────────
// STAR RATING — the "Rate Book" popup. Synced with Calibre's built-in Rating.
// ─────────────────────────────────────────────────────────────────────────────

/** Gold used for filled stars. */
internal val StarGold = Color(0xFFDDBB3A)

/** "★★★★☆" — for menu labels. Empty string when there's no rating. */
internal fun starText(stars: Int?): String =
    if (stars == null || stars <= 0) "" else "★".repeat(stars) + "☆".repeat(5 - stars)

/** "Read 3 times" / "Read once" / "" — for the rating popup. */
internal fun timesReadText(times: Int): String = when {
    times <= 0 -> ""
    times == 1 -> "Read once"
    times == 2 -> "Read twice"
    else       -> "Read $times times"
}

/** Five tappable stars. Tapping the star that's already the rating clears it. */
@Composable
internal fun StarPicker(stars: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.Center) {
        for (i in 1..5) {
            IconButton(onClick = { onChange(if (stars == i) 0 else i) }) {
                Icon(
                    imageVector        = if (i <= stars) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "$i star${if (i > 1) "s" else ""}",
                    tint               = if (i <= stars) StarGold else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(36.dp)
                )
            }
        }
    }
}

/**
 * The "Rate Book" popup.
 * [justFinished] = shown straight after finishing the book (different wording,
 * and "Not Now" instead of "Cancel").
 * [onSave] gets 1–5, or null for "no rating".
 */
@Composable
internal fun RatingDialog(
    book: Book,
    justFinished: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (stars: Int?) -> Unit
) {
    var stars by remember(book.id) { mutableStateOf(book.rating ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.Star, contentDescription = null, tint = StarGold) },
        title = { Text(if (justFinished) "You finished it!" else "Rate Book") },
        text  = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text      = if (justFinished) "How would you rate \"${book.title}\"?"
                                else "\"${book.title}\"",
                    textAlign = TextAlign.Center
                )
                val times = timesReadText(book.timesRead)
                if (times.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(times, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                StarPicker(stars = stars, onChange = { stars = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSave(stars.takeIf { it > 0 }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (justFinished) "Not Now" else "Cancel") }
        }
    )
}

/**
 * Shared by every screen with the "Rate Book" menu item.
 * Saves the rating, tries to send it to Calibre, and returns the message to show.
 */
internal suspend fun setRatingWithMessage(
    repository: BookRepository,
    book: Book,
    stars: Int?
): String {
    val done = if (stars == null) "rating cleared" else "rated ${starText(stars)}"
    return when (val r = repository.setRating(book, stars)) {
        ReadPushResult.Synced       -> "✓ \"${book.title}\" $done and synced to Calibre"
        ReadPushResult.NotLinked,
        ReadPushResult.NoReadColumn -> "✓ \"${book.title}\" $done"
        is ReadPushResult.Failed    ->
            "✓ \"${book.title}\" $done. It will sync to Calibre next time you're home. " +
            calibreErrorReason(r.error)
    }
}
