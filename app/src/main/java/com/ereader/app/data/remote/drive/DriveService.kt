package com.ereader.app.data.remote.drive

import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.domain.model.Book
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// MIME type constants — Google Drive uses these to identify file types
private const val EPUB_MIME  = "application/epub+zip"
private const val PDF_MIME   = "application/pdf"

// Fields we request from the Drive API — only fetching what we need saves bandwidth
private const val FILE_FIELDS = "nextPageToken, files(id, name, mimeType, thumbnailLink, description, createdTime)"

/**
 * Fetches EPUB and PDF files from the user's Google Drive.
 *
 * How this works:
 *  1. We call the Drive Files.list() API with a query that filters for
 *     EPUB and PDF MIME types.
 *  2. The API returns results in pages of up to 100 files each.
 *     We follow nextPageToken until there are no more pages.
 *  3. Each file is converted into our Book domain model.
 *
 * The [Drive] client lives in [DriveClientHolder] and is set after the user
 * completes Google sign-in. Until then, Drive sync is silently skipped.
 */
@Singleton
class DriveService @Inject constructor(
    private val driveHolder: DriveClientHolder
) {

    suspend fun fetchAllEpubsAndPdfs(): List<Book> = withContext(Dispatchers.IO) {
        val drive = driveHolder.client ?: run {
            Timber.w("DriveService: no Drive client — user not signed in, skipping")
            return@withContext emptyList()
        }

        val files     = mutableListOf<File>()
        var pageToken: String? = null

        do {
            val result = drive.files().list()
                .setQ("mimeType='$EPUB_MIME' or mimeType='$PDF_MIME'")
                .setSpaces("drive")
                .setFields(FILE_FIELDS)
                .setPageToken(pageToken)
                .setPageSize(100)
                .execute()

            files     += result.files ?: emptyList()
            pageToken  = result.nextPageToken
            Timber.d("Drive: fetched ${result.files?.size ?: 0} files (pageToken=$pageToken)")

        } while (pageToken != null)

        Timber.d("Drive: total files found = ${files.size}")

        files.mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            val (author, title) = parseDriveFileName(name)
            val fileType = if (file.mimeType == EPUB_MIME) FileType.EPUB else FileType.PDF

            Book(
                id       = file.id,
                title    = title,
                author   = author,
                // Direct download URL for the file content
                fileUrl  = "https://www.googleapis.com/drive/v3/files/${file.id}?alt=media",
                coverUrl = file.thumbnailLink,
                fileType = fileType,
                source   = BookSource.GOOGLE_DRIVE
            )
        }
    }

    /**
     * Best-effort filename parser.
     *
     * Conventions we handle:
     *   "Frank Herbert - Dune.epub"       → author="Frank Herbert", title="Dune"
     *   "Dune - Frank Herbert.epub"       → author="Dune", title="Frank Herbert" (imperfect, but consistent)
     *   "Dune.epub"                       → author="Unknown", title="Dune"
     *   "Dune (Frank Herbert) [Sci-Fi].epub" → strips brackets, parses remainder
     */
    private fun parseDriveFileName(name: String): Pair<String, String> {
        // Strip extension
        var clean = name.substringBeforeLast(".")
        // Remove anything in square brackets like [epub] [calibre] [sci-fi]
        clean = clean.replace(Regex("\\[.*?]"), "").trim()

        return if (" - " in clean) {
            val parts = clean.split(" - ", limit = 2)
            parts[0].trim() to parts[1].trim()
        } else {
            "Unknown Author" to clean.trim()
        }
    }
}
