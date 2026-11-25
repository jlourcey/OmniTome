package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.data.repository.ReadPushResult
import com.ereader.app.domain.model.Book
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// STAR RATING — the "Rate Book" popup and the Times Read line.
// Ratings are stored in Book.calibreRating (the same field the 5-star fire
// badge uses) and sent to Calibre's built-in Rating field.
// Shared by the bookshelf, series screen and the reader ("You finished it!").
// ─────────────────────────────────────────────────────────────────────────────

/** Gold used for filled stars. */
internal val StarGold = Color(0xFFDDBB3A)

/** The book's rating as whole stars 1–5, or null when unrated (4.5 shows as 5). */
internal fun wholeStars(book: Book): Int? =
    book.calibreRating?.takeIf { it > 0f }?.roundToInt()?.coerceIn(1, 5)

/** "★★★★☆" — for menu labels. Empty string when there's no rating. */
internal fun starText(stars: Int?): String =
    if (stars == null || stars <= 0) "" else "★".repeat(stars) + "☆".repeat(5 - stars)

/** "Read 3 times" / "Read once" / "" — shown in the rating popup. */
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
    var stars by remember(book.id) { mutableIntStateOf(wholeStars(book) ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.Star, contentDescription = null, tint = StarGold) },
        title = { Text(if (justFinished) "You finished it!" else "Rate Book") },
        text  = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text      = if (justFinished) "How would you rate \"${book.title}\"?" else "\"${book.title}\"",
                    textAlign = TextAlign.Center
                )
                val times = timesReadText(book.timesRead)
                if (times.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        times,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
