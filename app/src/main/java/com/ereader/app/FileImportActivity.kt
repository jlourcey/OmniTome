package com.ereader.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.data.repository.BookRepository
import com.ereader.app.domain.model.Book
import com.ereader.app.ui.reader.ReaderActivity
import com.ereader.app.util.EpubMetadataExtractor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * FileImportActivity — handles "Open with OmniTome" from Downloads, email, file managers, etc.
 *
 * When the user downloads an EPUB or PDF and taps "Open with OmniTome", Android delivers
 * an ACTION_VIEW intent here. This activity:
 *   1. Copies the file into app-private storage (filesDir/local_books/) — same place
 *      as the Local tab import, so the book appears in your library.
 *   2. Extracts EPUB metadata (title, author, cover art) if the file is an EPUB.
 *   3. Saves the book to the Room database via BookRepository.
 *   4. Immediately launches ReaderActivity so the book opens right away.
 *   5. Finishes itself — it has no visible UI and never appears in your Recents list.
 *
 * Why a separate activity?
 *   Android intent filters require the activity to be exported="true" (visible to other apps).
 *   ReaderActivity stays unexported and private — external apps can never reach it directly.
 *   This shim is the only exported entry point for file opening.
 */
@AndroidEntryPoint
class FileImportActivity : AppCompatActivity() {

    // Hilt injects BookRepository — the same one used by LocalImportViewModel
    @Inject lateinit var repository: BookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri    = intent?.data
        val action = intent?.action

        // If we were not launched with a proper ACTION_VIEW + URI, do nothing
        if (action != Intent.ACTION_VIEW || uri == null) {
            finish()
            return
        }

        // Run the import on the IO thread, then launch the reader on the main thread
        lifecycleScope.launch {
            val book = importUri(uri, intent.type)
            if (book != null) {
                // Open the reader exactly the same way as tapping a book on the shelf
                val readerIntent = Intent(this@FileImportActivity, ReaderActivity::class.java).apply {
                    // FLAG_ACTIVITY_NEW_TASK puts the reader in OmniTome's task,
                    // not in the external app's task (Downloads, Gmail, etc.)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(ReaderActivity.EXTRA_BOOK_ID,    book.id)
                    putExtra(ReaderActivity.EXTRA_BOOK_TITLE, book.title)
                }
                startActivity(readerIntent)
            } else {
                // Something went wrong — guide the user to the manual import path
                Toast.makeText(
                    this@FileImportActivity,
                    "Couldn't open that file. Try importing it from the Local tab instead.",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Always close this shim — it should never sit in the back stack
            finish()
        }
    }

    // ── Import logic ──────────────────────────────────────────────────────────

    /**
     * Copies the incoming content:// URI to private storage, extracts metadata,
     * and saves the book record.  Runs entirely on [Dispatchers.IO].
     *
     * @param uri      The content:// URI delivered in the intent.
     * @param mimeType The MIME type string from the intent (may be null for some apps).
     * @return The newly saved [Book] domain object, or null if import failed.
     */
    private suspend fun importUri(uri: Uri, mimeType: String?): Book? =
        withContext(Dispatchers.IO) {
            try {
                // ── Step 1: Resolve the human-readable filename ──────────────────
                // ContentResolver can give us the original filename the other app
                // chose (e.g. "The Great Gatsby.epub"). We use that as the working title.
                val displayName = contentResolver
                    .query(uri, null, null, null, null)
                    ?.use { cursor ->
                        val col = cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )
                        if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
                    }
                    ?: uri.lastPathSegment   // fallback: last segment of the URI path
                    ?: "Imported Book"

                // Strip the file extension to get an initial title ("The Great Gatsby")
                val title = displayName.substringBeforeLast('.').ifBlank { displayName }

                // ── Step 2: Determine EPUB vs PDF ────────────────────────────────
                // Some apps pass the correct MIME type; others omit it.
                // We fall back to checking the file extension as a safety net.
                val fileType = when {
                    mimeType?.contains("epub", ignoreCase = true) == true  -> FileType.EPUB
                    mimeType?.contains("pdf",  ignoreCase = true) == true  -> FileType.PDF
                    displayName.endsWith(".epub", ignoreCase = true)        -> FileType.EPUB
                    displayName.endsWith(".pdf",  ignoreCase = true)        -> FileType.PDF
                    else                                                     -> FileType.EPUB
                }
                val ext = if (fileType == FileType.PDF) "pdf" else "epub"

                // ── Step 3: Copy to app-private storage ──────────────────────────
                // We copy to filesDir/local_books/ — the exact same folder that the
                // Local tab import uses, so all imported books live in one place.
                val destDir  = File(filesDir, "local_books").also { it.mkdirs() }
                val destFile = File(destDir, "${title.take(60)}_${System.currentTimeMillis()}.$ext")

                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Cannot open input stream for URI: $uri")

                // ── Step 4: Extract EPUB metadata ────────────────────────────────
                // For EPUBs we read the OPF package inside the ZIP to get the real
                // title, author name, and cover image. Falls back to filename title.
                var finalTitle   = title
                var finalAuthor: String?      = null
                var coverBytes: ByteArray? = null

                if (fileType == FileType.EPUB) {
                    val meta = EpubMetadataExtractor.extract(destFile)
                    if (!meta.title.isNullOrBlank())  finalTitle  = meta.title
                    if (!meta.author.isNullOrBlank()) finalAuthor = meta.author
                    coverBytes = meta.coverBytes
                }

                // ── Step 5: Save to the database ─────────────────────────────────
                // saveLocalBook() returns the Book domain object with the new ID.
                // That ID is what ReaderActivity needs to load the book.
                repository.saveLocalBook(finalTitle, destFile, fileType, finalAuthor, coverBytes)
                    .also {
                        Timber.d(
                            "FileImportActivity: imported '${it.title}' " +
                            "by '${it.author}' → ${destFile.absolutePath}"
                        )
                    }

            } catch (e: Exception) {
                Timber.e(e, "FileImportActivity: import failed for $uri")
                null
            }
        }
}
