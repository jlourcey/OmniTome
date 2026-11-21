package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Book

/**
 * The popup shown after tapping "Remove Download".
 *
 * Calibre books linked to Calibre get three choices:
 *   Sync & Remove           — send notes + page to Calibre, then delete the phone copy
 *   Remove Without Syncing  — delete the phone copy now (notes stay in the app
 *                             and sync automatically next time you're home)
 *   Cancel
 * Other books (e.g. Google Drive) just get Remove / Cancel.
 */
@Composable
internal fun RemoveDownloadDialog(
    book: Book,
    onDismiss: () -> Unit,
    onConfirm: (syncFirst: Boolean) -> Unit
) {
    val canSync = book.source == BookSource.CALIBRE && book.calibreBookId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.FileDownloadOff, contentDescription = null) },
        title = { Text("Remove download?") },
        text  = {
            Text(
                if (canSync)
                    "Do you want to sync your notes and page read first?\n\n" +
                    "\"${book.title}\" stays in your library — only the copy saved on this phone is removed."
                else
                    "The copy of \"${book.title}\" saved on this phone will be removed. " +
                    "The book stays in your library, and your notes stay in the app."
            )
        },
        confirmButton = {
            if (canSync) {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onDismiss(); onConfirm(true) }) { Text("Sync & Remove") }
                    TextButton(onClick = { onDismiss(); onConfirm(false) }) {
                        Text("Remove Without Syncing", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                TextButton(onClick = { onDismiss(); onConfirm(false) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Shared by the bookshelf and series screens: optionally syncs, then removes the
 * phone copy. Returns the message to show at the bottom of the screen.
 * If "sync first" fails, the download is KEPT so nothing can be lost.
 */
internal suspend fun removeDownloadWithMessage(
    repository: BookRepository,
    book: Book,
    syncFirst: Boolean
): String {
    if (syncFirst) {
        val sync = repository.syncNotesToCalibre(book)
        if (sync.isFailure) {
            return "Couldn't sync \"${book.title}\", so the download was kept and nothing was lost. " +
                calibreErrorReason(sync.exceptionOrNull()) +
                " Try again at home, or choose Remove Without Syncing."
        }
    }
    return runCatching { repository.removeOfflineCopy(book) }.fold(
        onSuccess = {
            if (syncFirst) "✓ Notes and page synced to Calibre, and \"${book.title}\" was removed from your phone"
            else "✓ \"${book.title}\" was removed from your phone"
        },
        onFailure = { "Couldn't remove the download: ${it.localizedMessage}" }
    )
}

/** Shared by the bookshelf and series screens for the "Sync Notes to Calibre" menu item. */
internal suspend fun syncNotesWithMessage(repository: BookRepository, book: Book): String =
    repository.syncNotesToCalibre(book).fold(
        onSuccess = { "✓ Notes and page for \"${book.title}\" synced to Calibre" },
        onFailure = { "Couldn't sync \"${book.title}\". " + calibreErrorReason(it) }
    )

/** Plain-English reason for a Calibre network failure. */
internal fun calibreErrorReason(error: Throwable?): String = when (error) {
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException,
    is java.net.NoRouteToHostException ->
        "Can't reach your Calibre server. Make sure you're on your home network."
    null -> "Unknown error."
    else -> error.localizedMessage ?: "Unknown error."
}
