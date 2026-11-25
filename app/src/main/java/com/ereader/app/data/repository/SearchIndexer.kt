package com.ereader.app.data.repository

import android.text.Html
import androidx.room.withTransaction
import com.ereader.app.data.local.AppDatabase
import com.ereader.app.data.local.dao.SearchDao
import com.ereader.app.data.local.entity.BookTextEntity
import com.ereader.app.data.local.entity.SearchIndexedEntity
import com.ereader.app.data.local.entity.TextSearchHit
import com.ereader.app.util.EpubSpineReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searching inside books.
 *
 * Only books saved on the phone are searchable (Read Offline, the automatic
 * copy of the book you're reading, and imported books), and only EPUBs —
 * PDF pages are pictures to Android's PDF engine, so their text can't be read.
 * The search screen says so.
 *
 * Each chapter's text is pulled out of the EPUB (tags removed) and added to a
 * SQLite full-text index when the book is saved; removing the download removes
 * it from the index too.
 */
@Singleton
class SearchIndexer @Inject constructor(
    private val appDatabase: AppDatabase,
    private val searchDao: SearchDao
) {
    private val lock = Mutex()

    val indexedCount: Flow<Int> = searchDao.observeIndexedCount()

    /** Adds one saved EPUB to the index (replacing anything there before). */
    suspend fun index(bookId: String, file: File) = withContext(Dispatchers.IO) {
        lock.withLock {
            val rows = runCatching { extractChapters(bookId, file) }
                .onFailure { Timber.w(it, "Couldn't read $file for search") }
                .getOrNull() ?: return@withLock
            appDatabase.withTransaction {
                searchDao.deleteText(bookId)
                if (rows.isNotEmpty()) searchDao.insertAll(rows)
                searchDao.markIndexed(SearchIndexedEntity(bookId))
            }
            Timber.d("Search index: ${rows.size} chapter(s) for $bookId")
        }
    }

    /** Takes a book out of the index (its download was removed or it was deleted). */
    suspend fun remove(bookId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            appDatabase.withTransaction {
                searchDao.deleteText(bookId)
                searchDao.unmark(bookId)
            }
        }
    }

    /** Indexes saved books that aren't in the index yet (e.g. saved before this feature). */
    suspend fun indexMissing() {
        searchDao.booksNeedingIndex().forEach { b ->
            val f = b.localFilePath?.let { File(it) }
            if (f != null && f.exists()) index(b.id, f)
        }
    }

    /**
     * Searches every indexed book. Words are matched as word starts
     * ("storm" finds "stormy"); a phrase in "quotes" is matched exactly.
     */
    suspend fun search(query: String): List<TextSearchHit> = withContext(Dispatchers.IO) {
        val match = toMatch(query) ?: return@withContext emptyList()
        runCatching { searchDao.search(match) }
            .onFailure { Timber.w(it, "Search failed for '$query'") }
            .getOrDefault(emptyList())
    }

    /** How far into the chapter (0–1) the first match is — to open the book near it. */
    suspend fun matchPosition(bookId: String, href: String, query: String): Double? = withContext(Dispatchers.IO) {
        val text = searchDao.chapterText(bookId, href) ?: return@withContext null
        val word = words(query).firstOrNull() ?: return@withContext null
        val i = text.indexOf(word, ignoreCase = true)
        if (i < 0 || text.isEmpty()) null else i.toDouble() / text.length
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun words(query: String): List<String> =
        Regex("[\\p{L}\\p{N}']+").findAll(query).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()

    private fun toMatch(query: String): String? {
        val q = query.trim()
        if (q.length >= 2 && q.startsWith('"') && q.endsWith('"')) {
            val phrase = words(q).joinToString(" ")
            return if (phrase.isEmpty()) null else "\"$phrase\""
        }
        val w = words(q)
        return if (w.isEmpty()) null else w.joinToString(" ") { "$it*" }
    }

    private fun extractChapters(bookId: String, file: File): List<BookTextEntity> {
        val spine = EpubSpineReader.spinePaths(file)
        if (spine.isEmpty()) return emptyList()
        val rows = mutableListOf<BookTextEntity>()
        ZipFile(file).use { zip ->
            for (path in spine) {
                val entry = zip.getEntry(path) ?: continue
                val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val title = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1)?.let { plain(it) }?.take(120)?.takeIf { it.isNotBlank() }
                    ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(html)?.groupValues?.get(1)?.let { plain(it) }?.take(120)?.takeIf { it.isNotBlank() }
                val body = html.substringAfter("<body", html)
                val text = plain(body.substringAfter('>'))
                if (text.length >= 20) rows += BookTextEntity(bookId, path, title, text)
            }
        }
        return rows
    }

    /** HTML → plain text (scripts/styles dropped, entities decoded, spaces tidied). */
    private fun plain(html: String): String {
        val noCode = html
            .replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<(br|/p|/div|/h\\d|/li)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        @Suppress("DEPRECATION")
        val text = Html.fromHtml(noCode, Html.FROM_HTML_MODE_LEGACY).toString()
        return text.replace('￼', ' ').replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    }
}
