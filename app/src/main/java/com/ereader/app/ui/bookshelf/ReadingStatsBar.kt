package com.ereader.app.ui.bookshelf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.ReadingStreak
import java.time.LocalDate

private val StreakOrange = Color(0xFFFF8A3D)
private val StreakGrey   = Color(0xFF9AA0B4)

/**
 * The reading-stats strip under "Continue Reading":
 *
 *   🔥 5-day streak  ·  📖 7 finished in 2026          ▾
 *   [cover][cover][cover][cover] …   ← tap ▾ to show / hide
 *
 * - The flame is orange once you've read today, grey when today's reading is
 *   still to do ("read today to keep it going").
 * - The shelf lists books finished this year, newest first, in full colour —
 *   it's your trophy shelf. Tap a cover to open the book.
 */
@Composable
internal fun ReadingStatsBar(
    streak: ReadingStreak,
    finished: List<Book>,
    onBookClick: (Book) -> Unit
) {
    // Remembered across rotation; starts collapsed to keep the shelf compact.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val year = LocalDate.now().year

    Surface(
        modifier       = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape          = RoundedCornerShape(12.dp),
        color          = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column {
            // ── Summary row (tap to show / hide the shelf) ────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = finished.isNotEmpty()) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector        = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint               = if (streak.readToday) StreakOrange else StreakGrey,
                    modifier           = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text       = streakTitle(streak),
                        style      = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    streakSubtitle(streak)?.let {
                        Text(
                            text  = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector        = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "${finished.size} finished in $year",
                    style = MaterialTheme.typography.labelLarge
                )
                if (finished.isNotEmpty()) {
                    Icon(
                        imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide finished books" else "Show finished books",
                        modifier           = Modifier.size(20.dp)
                    )
                }
            }

            // ── Finished this year shelf ──────────────────────────────────────
            AnimatedVisibility(visible = expanded && finished.isNotEmpty()) {
                LazyRow(
                    contentPadding        = PaddingValues(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(finished, key = { it.id }) { book ->
                        FinishedBookThumb(book = book, onClick = { onBookClick(book) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FinishedBookThumb(book: Book, onClick: () -> Unit) {
    Column(
        modifier            = Modifier
            .width(52.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            SubcomposeAsyncImage(
                model              = book.coverUrl,
                contentDescription = "${book.title} cover",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .width(52.dp)
                    .aspectRatio(0.67f)
                    .clip(RoundedCornerShape(4.dp)),
                loading = {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1A2560)))
                },
                error = {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xFF1A2560)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(book.title.take(2), color = Color(0xFFDDBB3A), fontSize = 14.sp)
                    }
                }
            )
            ReadBadge(modifier = Modifier.align(Alignment.TopEnd).padding(2.dp), size = 14.dp)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text      = book.readAt?.let { finishedLabel(it) } ?: "",
            fontSize  = 9.sp,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** "5-day streak", "1-day streak", or "No streak yet". */
private fun streakTitle(s: ReadingStreak): String = when (s.current) {
    0    -> "No streak yet"
    else -> "${s.current}-day streak"
}

/** Second line: a nudge if today isn't done yet, otherwise your best streak. */
private fun streakSubtitle(s: ReadingStreak): String? = when {
    s.current > 0 && !s.readToday -> "Read today to keep it going"
    s.current == 0                -> "Read a few pages to start one"
    s.best > s.current            -> "Best: ${s.best} days"
    else                          -> "Your best yet!"
}

/** "Nov 20" style date under each finished cover. */
private fun finishedLabel(epochMs: Long): String {
    val d = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
}
