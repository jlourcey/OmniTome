package com.ereader.app.ui.bookshelf

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ereader.app.R
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
    CircleBadge(R.drawable.ic_badge_read, "Read", modifier, size)
}

// ── Cover badges (library grid) ──────────────────────────────────────────────

/** How long a book counts as "New" after it was added to Calibre. */
internal const val NEW_BADGE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

/** Added to Calibre less than 14 days ago AND not Read yet. */
internal fun isNewBook(book: Book, now: Long = System.currentTimeMillis()): Boolean {
    val added = book.calibreAddedAt ?: return false
    return !book.isRead && now - added < NEW_BADGE_WINDOW_MS
}

/** Rated 5 stars in Calibre (half stars don't count — 4.5 is not 5). */
internal fun isFiveStar(book: Book): Boolean = (book.calibreRating ?: 0f) >= 4.99f

/** One of the bundled badge drawables inside a dark circle. The drawable keeps its own colours. */
@Composable
private fun CircleBadge(@DrawableRes icon: Int, description: String, modifier: Modifier, size: Dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter            = painterResource(icon),
            contentDescription = description,
            modifier           = Modifier.size(size * 0.75f)
        )
    }
}

/** Fire icon — the book is rated 5 stars in Calibre. */
@Composable
internal fun FavoriteRatingBadge(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    CircleBadge(R.drawable.ic_badge_fire, "Rated 5 stars", modifier, size)
}

/** "✦ NEW" pill — added to Calibre in the last 14 days and not read yet. */
@Composable
internal fun NewBadge(modifier: Modifier = Modifier, height: Dp = 18.dp) {
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter            = painterResource(R.drawable.ic_badge_new),
            contentDescription = null,
            modifier           = Modifier.size(height * 0.5f)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text       = "NEW",
            color      = Color(0xFFFFD54F),
            fontSize   = 7.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 8.sp
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
        ReadPushResult.NoReadColumn -> {
            // Columns belong to one Calibre library, so say which one needs it.
            val lib = repository.libraryDisplayName(book.libraryKey)
            "✓ \"${book.title}\" $done in OmniTome. To sync it, add a Yes/No column " +
            "named \"Read\" to ${lib?.let { "the \"$it\" library" } ?: "this library"} in Calibre " +
            "(Preferences → Add your own columns). It syncs automatically once the column exists."
        }
        is ReadPushResult.Failed    ->
            "✓ \"${book.title}\" $done. It will sync to Calibre next time you're home. " +
            calibreErrorReason(r.error)
    }
}
