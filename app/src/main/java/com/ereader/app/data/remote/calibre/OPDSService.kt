package com.ereader.app.data.remote.calibre

import android.net.Uri
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.local.entity.FileType
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.domain.model.Book
import com.ereader.app.domain.model.normalize
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the book catalog from a Calibre Content Server via its OPDS Atom feed.
 *
 * How OPDS navigation works (2-step):
 *  Step 1 — Root catalog (/opds):
 *    Returns navigation entries. Each entry has a <link> pointing to a subcatalog
 *    (e.g. "By Title", "By Author"). We find the "all books" subcatalog URL here.
 *
 *  Step 2 — Book list (e.g. /opds/allbooks or wherever the catalog points):
 *    Returns actual book entries with download links. We paginate via
 *    <link rel="next"> until there are no more pages.
 *
 *  This two-step approach works with any Calibre version because we discover
 *  the correct URL from the server itself instead of hardcoding it.
 */
@Singleton
class OPDSService @Inject constructor(
    private val client: OkHttpClient,
    private val userPreferences: UserPreferences,
    private val digestAuthenticator: CalibreDigestAuthenticator
) {
    private suspend fun baseUrl(): String = userPreferences.calibreBaseUrl.first()

    /**
     * OkHttp client that automatically handles Calibre's Digest-MD5 auth.
     * Lazily built once; Calibre always uses the same nonce algorithm so a
     * single shared client is correct for the lifetime of the service.
     */
    private val authClient: OkHttpClient by lazy {
        client.newBuilder()
            .authenticator(digestAuthenticator)
            .build()
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * @param libraryId Calibre library to read (e.g. "Calibre_Library"); null = the
     *                  login's default library. Calibre carries the library_id on every
     *                  link it returns, so only the first request needs it.
     */
    suspend fun fetchAllBooks(libraryId: String? = null): List<Book> = withContext(Dispatchers.IO) {
        val baseUrl = baseUrl()
        if (baseUrl.isBlank()) {
            Timber.w("OPDSService: no Calibre URL configured — skipping fetch")
            return@withContext emptyList()
        }

        // Step 1: Fetch the root catalog and discover the all-books feed URL
        val rootUrl = "$baseUrl/opds" + (libraryId?.let { "?library_id=${Uri.encode(it)}" } ?: "")
        val rootXml = httpGet(rootUrl)
        if (rootXml == null) {
            Timber.e("OPDSService: could not reach $baseUrl/opds")
            return@withContext emptyList()
        }

        val allBooksUrl = findAllBooksUrl(baseUrl, rootXml)
            .let { if (it == "$baseUrl/opds") rootUrl else it }   // keep the library on the fallback
        Timber.d("OPDSService: all-books URL = $allBooksUrl")

        // Step 2: Paginate through the book list following <link rel="next">
        val allBooks = mutableListOf<Book>()
        var nextUrl: String? = allBooksUrl

        while (nextUrl != null) {
            val xml = httpGet(nextUrl) ?: break
            val result = parseBookFeed(baseUrl, xml)
            allBooks += result.books
            Timber.d("OPDSService: page had ${result.books.size} books, next=${result.nextUrl} (total: ${allBooks.size})")
            nextUrl = result.nextUrl
        }

        Timber.d("OPDSService: finished — ${allBooks.size} books total")
        allBooks
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun httpGet(url: String): String? {
        return try {
            val response = authClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful) {
                response.body?.string().also {
                    Timber.d("OPDSService: GET $url → ${response.code}, body=${it?.length} chars")
                }
            } else {
                Timber.w("OPDSService: GET $url → ${response.code} ${response.message}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "OPDSService: GET $url failed")
            null
        }
    }

    // ── Step 1: Discover the all-books URL from the root catalog ─────────────

    /**
     * Parses the root /opds feed and returns the href of the first navigation
     * entry that looks like an "all books" or "by title" subcatalog.
     *
     * Falls back to $baseUrl/opds if no nav link is found (some Calibre builds
     * return books directly from the root).
     */
    private fun findAllBooksUrl(baseUrl: String, rootXml: String): String {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser().apply { setInput(rootXml.reader()) }

        // We collect (title, href) pairs for nav entries and pick the best one
        data class NavEntry(val title: String, val href: String)
        val navEntries = mutableListOf<NavEntry>()

        var inEntry      = false
        var entryTitle   = ""
        var navHref      = ""
        var isNavEntry   = false   // true if at least one link is a subcatalog type

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name ?: ""
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "entry" -> { inEntry = true; entryTitle = ""; navHref = ""; isNavEntry = false }
                    "title" -> if (inEntry) entryTitle = parser.nextText().trim()
                    "link"  -> if (inEntry) {
                        val rel  = parser.getAttributeValue(null, "rel")  ?: ""
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        // Navigation/subsection links point to subcatalogs (not downloads)
                        if (type.contains("atom+xml") || rel.contains("subsection") || rel.contains("related")) {
                            isNavEntry = true
                            if (navHref.isEmpty() && href.isNotBlank()) {
                                navHref = if (href.startsWith("http")) href else "$baseUrl$href"
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (tag == "entry" && inEntry) {
                    if (isNavEntry && navHref.isNotBlank()) {
                        navEntries += NavEntry(entryTitle.lowercase(), navHref)
                        Timber.d("OPDSService: nav entry '${entryTitle}' → $navHref")
                    }
                    inEntry = false
                }
            }
            event = parser.next()
        }

        // Prefer a "recently added / by date" catalog so books appear in Calibre's order.
        // Fall back to "by title / all books" if no date catalog is found.
        val best = navEntries.firstOrNull { e ->
            e.title.contains("date") || e.title.contains("recent") || e.title.contains("new")
        } ?: navEntries.firstOrNull { e ->
            e.title.contains("title") || e.title.contains("all") || e.title.contains("book")
        } ?: navEntries.firstOrNull()  // last-resort: first nav entry found

        return best?.href ?: "$baseUrl/opds"
    }

    // ── Step 2: Parse a page of books + find the next-page URL ───────────────

    private data class FeedResult(val books: List<Book>, val nextUrl: String?)

    /**
     * Parses a book-list Atom feed.
     * Returns the books found on this page and the URL of the next page
     * (from <link rel="next">) or null if this is the last page.
     */
    private fun parseBookFeed(baseUrl: String, xml: String): FeedResult {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser().apply { setInput(xml.reader()) }

        val books    = mutableListOf<Book>()
        var nextUrl: String? = null

        // Per-entry state
        var inEntry      = false
        var title        = ""
        var author       = ""
        var entryId      = ""
        var isbn: String?          = null
        var calibreIdFromEntry: Int? = null   // parsed from <id> tag:host:port,YYYY:book:NNN
        var fullCoverUrl: String?  = null   // <link rel="…/image">       — high-res
        var thumbUrl: String?      = null   // <link rel="…/thumbnail">   — low-res
        var downloadUrl            = ""
        var series: String?        = null
        var seriesIndex: Float?    = null
        var description: String?   = null
        var tags                   = mutableListOf<String>()   // from <category> elements
        var fileType               = FileType.EPUB

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name ?: ""
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {

                    "link" -> {
                        val rel  = parser.getAttributeValue(null, "rel")  ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val type = parser.getAttributeValue(null, "type") ?: ""

                        if (!inEntry) {
                            // Feed-level <link rel="next"> → URL of the next page
                            if (rel == "next" && href.isNotBlank()) {
                                nextUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                            }
                        } else {
                            // Entry-level links → cover image or download
                            val fullHref = if (href.startsWith("http")) href else "$baseUrl$href"
                            when {
                                // Prefer full-size cover; fall back to thumbnail if that's all we have.
                                // Calibre emits both; "thumbnail" contains "image" so we check it first.
                                rel.contains("thumbnail")                          -> thumbUrl     = fullHref
                                rel.contains("image") && !rel.contains("thumbnail") -> fullCoverUrl = fullHref
                                type.contains("epub") -> {
                                    downloadUrl = fullHref
                                    fileType    = FileType.EPUB
                                }
                                type.contains("pdf") && downloadUrl.isEmpty() -> {
                                    downloadUrl = fullHref
                                    fileType    = FileType.PDF
                                }
                            }
                        }
                    }

                    "entry"    -> inEntry = true
                    "title"    -> if (inEntry) title = parser.nextText().trim()
                    "category" -> if (inEntry) {
                        // Atom <category term="Fantasy" label="Fantasy"/>
                        // Calibre uses this element for every tag assigned to a book.
                        // "label" is the human-readable name; "term" is the identifier.
                        val label = parser.getAttributeValue(null, "label")
                            ?: parser.getAttributeValue(null, "term")
                        if (!label.isNullOrBlank()) tags += label.trim()
                    }
                    "id"       -> if (inEntry) {
                        val raw = parser.nextText().trim()
                        isbn    = if (raw.startsWith("urn:isbn:")) raw.removePrefix("urn:isbn:") else null
                        entryId = raw
                        // Calibre OPDS <id> format: "tag:host:port,YYYY:book:NNN"
                        // Extract the integer after the last ":book:" segment.
                        calibreIdFromEntry = raw.substringAfterLast(":book:", "")
                            .toIntOrNull()
                    }
                    "name"         -> if (inEntry && author.isEmpty()) author = parser.nextText().trim()
                    "summary",
                    "content"      -> if (inEntry) description = readNestedText(parser).trim()
                                          .cleanCalibreDescription()
                    "series" -> if (inEntry) {
                        // Calibre uses either:
                        //   <schema:series name="Blood and Ash" number="4.10"/>  (attributes, self-closing)
                        //   <calibre:series>Blood and Ash</calibre:series>       (text content)
                        // We handle both. namespace prefix is stripped by isNamespaceAware=true,
                        // so parser.name is always just "series" regardless of prefix.
                        val ns        = parser.namespace ?: ""
                        val attrName  = parser.getAttributeValue(null, "name")
                        val attrIndex = parser.getAttributeValue(null, "number")
                            ?: parser.getAttributeValue(null, "index")
                        Timber.d("OPDSService: <series> tag found — ns='$ns' name-attr='$attrName' number-attr='$attrIndex'")
                        if (!attrName.isNullOrBlank()) {
                            series = attrName
                            if (!attrIndex.isNullOrBlank() && seriesIndex == null) {
                                seriesIndex = attrIndex.toFloatOrNull()
                            }
                            Timber.d("OPDSService: series (attr) = '$series' index=$seriesIndex")
                            // Self-closing element: don't call nextText() —
                            // the outer loop's parser.next() will advance correctly.
                        } else {
                            val text = parser.nextText().trim()
                            if (text.isNotEmpty()) {
                                series = text
                                Timber.d("OPDSService: series (text) = '$series'")
                            } else {
                                Timber.w("OPDSService: <series> tag found but both attr and text are blank — ns='$ns'")
                            }
                        }
                    }
                    "series_index" -> if (inEntry && seriesIndex == null) {
                        val attrIndex = parser.getAttributeValue(null, "number")
                            ?: parser.getAttributeValue(null, "index")
                        seriesIndex = if (!attrIndex.isNullOrBlank()) {
                            attrIndex.toFloatOrNull()
                        } else {
                            parser.nextText().trim().toFloatOrNull()
                        }
                        Timber.d("OPDSService: series_index = $seriesIndex")
                    }
                }

                XmlPullParser.END_TAG -> if (tag == "entry" && inEntry) {
                    if (title.isNotBlank() && downloadUrl.isNotBlank()) {
                        Timber.d("OPDSService: adding book '$title' — series='$series' index=$seriesIndex")
                        books += Book(
                            id             = entryId.ifBlank { "$title|$author" },
                            title          = title,
                            author         = author.ifBlank { "Unknown Author" },
                            isbn           = isbn,
                            coverUrl       = fullCoverUrl ?: thumbUrl,   // prefer full-size
                            tags           = tags.toList(),
                            fileUrl        = downloadUrl,
                            fileType       = fileType,
                            series         = series,
                            seriesIndex    = seriesIndex,
                            description    = description,
                            source         = BookSource.CALIBRE,
                            calibreBookId  = calibreIdFromEntry
                        )
                    }
                    // Reset state for next entry
                    inEntry = false; title = ""; author = ""; entryId = ""
                    isbn = null; calibreIdFromEntry = null
                    fullCoverUrl = null; thumbUrl = null; downloadUrl = ""
                    series = null; seriesIndex = null; description = null
                    tags = mutableListOf()
                    fileType = FileType.EPUB
                }
            }
            event = parser.next()
        }

        return FeedResult(books, nextUrl)
    }

    // ── Series metadata second pass ───────────────────────────────────────────

    /**
     * One piece of series metadata collected from Calibre's "By Series" catalog.
     * Matched back to the database using [normalizedTitle] + [normalizedAuthor]
     * — the same keys used by the deduplication engine.
     */
    data class SeriesInfo(
        val normalizedTitle: String,
        val normalizedAuthor: String,
        val seriesName: String,
        val seriesIndex: Float?,
        val tags: List<String> = emptyList(),
        /** Calibre's internal integer ID — stored so the app can push edits back. */
        val calibreBookId: Int = 0,
        /** The library's Read / Date Read columns ([CalibreReadColumns.NONE] if it has none). */
        val readColumns: CalibreReadColumns = CalibreReadColumns.NONE,
        /** Calibre's Read value (only meaningful when readColumns.readKey != null). */
        val isRead: Boolean = false,
        /** Calibre's Date Read as epoch ms, or null. */
        val readAt: Long? = null
    )

    /**
     * Fetches series name + index for every book in the library using
     * Calibre's JSON API (/ajax/search and /ajax/books).
     *
     * This is far more reliable than parsing OPDS XML because:
     *  - The JSON API returns exact series_index floats (e.g. 3.1, 4.0)
     *  - No XML namespace ambiguity (<calibre:series_index> local-name
     *    resolution is inconsistent across XmlPullParser versions)
     *  - Two HTTP requests vs N+1 OPDS sub-catalog fetches
     *
     * The two endpoints used:
     *  GET /ajax/search?query=&num=10000
     *    → returns {"book_ids": [1, 2, 3, ...]}
     *  GET /ajax/books?ids=1,2,3&fields=title,authors,series,series_index
     *    → returns {"1": {"title": "...", "series": "...", "series_index": 1.0}, ...}
     *
     * NOTE: query= (empty) returns ALL books. query=all is a keyword search
     * that only returns books whose metadata contains the word "all" — which
     * is why the JSON API was returning 262 of 312 books before this fix.
     */
    suspend fun fetchSeriesMetadataViaJson(libraryId: String? = null): List<SeriesInfo> = withContext(Dispatchers.IO) {
        val baseUrl = baseUrl()
        if (baseUrl.isBlank()) return@withContext emptyList()
        // /ajax/search/{library_id} and /ajax/books/{library_id}; empty = default library
        val libPath = libraryId?.let { "/${Uri.encode(it)}" } ?: ""

        // Step 1 — get all numeric book IDs from the library.
        // Empty query= is the Calibre convention for "return everything".
        val searchJson = httpGet("$baseUrl/ajax/search$libPath?query=&num=10000")
            ?: run {
                Timber.w("OPDSService: /ajax/search returned null — JSON API may not be available")
                return@withContext emptyList()
            }

        val bookIds: List<Int> = try {
            val arr = JSONObject(searchJson).getJSONArray("book_ids")
            (0 until arr.length()).map { arr.getInt(it) }
        } catch (e: Exception) {
            Timber.e(e, "OPDSService: failed to parse /ajax/search response")
            return@withContext emptyList()
        }

        if (bookIds.isEmpty()) {
            Timber.w("OPDSService: /ajax/search returned 0 book IDs")
            return@withContext emptyList()
        }
        Timber.d("OPDSService: JSON API found ${bookIds.size} total books, fetching series metadata…")

        // Step 2 — batch-fetch metadata in chunks of 100 (avoids huge URLs)
        val result = mutableListOf<SeriesInfo>()
        for (chunk in bookIds.chunked(100)) {
            val booksJson = httpGet(
                "$baseUrl/ajax/books$libPath?ids=${chunk.joinToString(",")}" +
                "&fields=title,authors,series,series_index,tags&category_urls=false"
            ) ?: continue

            try {
                val obj = JSONObject(booksJson)
                for (idStr in chunk.map { it.toString() }) {
                    if (!obj.has(idStr)) continue
                    val book        = obj.getJSONObject(idStr)
                    // org.json.JSONObject.optString(key, default) has a known gotcha:
                    // when the JSON value is JSON null (not absent), it returns the
                    // literal string "null" instead of the default.  Guard with isNull().
                    val title       = (if (book.isNull("title"))  "" else book.optString("title",  "")).trim()
                    val authorsArr  = book.optJSONArray("authors")
                    val author      = if (authorsArr != null && authorsArr.length() > 0)
                                          authorsArr.getString(0).trim()
                                      else ""
                    val series      = (if (book.isNull("series")) "" else book.optString("series", "")).trim()
                    val seriesIndex = if (book.isNull("series_index")) null
                                     else book.optDouble("series_index").let { d ->
                                         if (d.isNaN()) null else d.toFloat()
                                     }

                    // Tags come back as a JSON array: ["Fantasy", "Magic"]
                    val tagsArr = book.optJSONArray("tags")
                    val tags = if (tagsArr != null) {
                        (0 until tagsArr.length())
                            .map { tagsArr.getString(it).trim() }
                            .filter { it.isNotBlank() }
                    } else emptyList()

                    // Read status lives in a custom column ("user_metadata") — see CalibreReadColumns.
                    val userMeta    = book.optJSONObject("user_metadata")
                    val readColumns = CalibreReadColumns.detect(userMeta)
                    val isRead      = readColumns.readKey?.let { CalibreReadColumns.readValue(userMeta, it) } ?: false
                    val readAt      = CalibreReadColumns.dateValue(userMeta, readColumns.dateKey)

                    // Emit a SeriesInfo for every book — even if it has no series
                    // or tags — so we can always back-fill the calibreBookId.
                    if (title.isNotBlank()) {
                        result += SeriesInfo(
                            normalizedTitle  = title.normalize(),
                            normalizedAuthor = author.normalize(),
                            seriesName       = series,
                            seriesIndex      = seriesIndex,
                            tags             = tags,
                            calibreBookId    = idStr.toIntOrNull() ?: 0,
                            readColumns      = readColumns,
                            isRead           = isRead,
                            readAt           = readAt
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "OPDSService: failed to parse /ajax/books chunk ${chunk.take(5)}")
            }
        }

        Timber.d("OPDSService: JSON API collected metadata for ${result.size} books " +
                 "(${result.count { it.seriesName.isNotBlank() }} have series, " +
                 "${result.count { it.tags.isNotEmpty() }} have tags)")
        result
    }

    /**
     * Fetches Calibre's "By Series" navigation catalog and returns series
     * metadata for every book that belongs to a series.
     *
     * Why a second pass?
     *   Calibre's "Newest" / "By Date" feeds omit <calibre:series> elements
     *   entirely. The per-series sub-catalogs DO include series information.
     *   We walk those sub-catalogs and return what we find so the repository
     *   can back-fill the series column in Room.
     *
     * Matching strategy:
     *   Books are matched to DB rows by (normalizedTitle, normalizedAuthor)
     *   rather than by Calibre's internal UUID, because Room assigns its own
     *   UUIDs on first insert and does not preserve the OPDS <id> value.
     */
    suspend fun fetchSeriesMetadata(): List<SeriesInfo> = withContext(Dispatchers.IO) {
        val baseUrl = baseUrl()
        if (baseUrl.isBlank()) return@withContext emptyList()

        // Step 1 — find the "By Series" nav catalog URL from the root feed
        val rootXml = httpGet("$baseUrl/opds") ?: return@withContext emptyList()
        val seriesCatalogUrl = findSeriesCatalogUrl(baseUrl, rootXml)
        if (seriesCatalogUrl == null) {
            Timber.w("OPDSService: no 'By Series' entry found in root catalog — series view will be empty")
            return@withContext emptyList()
        }
        Timber.d("OPDSService: series catalog URL = $seriesCatalogUrl")

        // Step 2 — fetch the series list (one nav entry per series, paginated)
        val allSeriesEntries = mutableListOf<Pair<String, String>>() // (displayName, booksUrl)
        var seriesListUrl: String? = seriesCatalogUrl
        while (seriesListUrl != null) {
            val xml = httpGet(seriesListUrl) ?: break
            val (entries, nextUrl) = parseNavFeedEntries(baseUrl, xml)
            allSeriesEntries += entries
            seriesListUrl = nextUrl
        }
        Timber.d("OPDSService: found ${allSeriesEntries.size} series in catalog")

        // Step 3 — for each series, fetch its book list and emit SeriesInfo items
        val result = mutableListOf<SeriesInfo>()
        for ((rawSeriesName, seriesUrl) in allSeriesEntries) {
            // Strip any "(N books)" suffix Calibre may append to the nav title
            val seriesName = rawSeriesName
                .replace(Regex("""\s*\(\d+\s+books?\)\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()

            // Track feed position within this series so we can use it as a
            // fallback index when Calibre omits <calibre:series_index>.
            // Calibre serves books in reading order within a series catalog,
            // so position 1 = book 1, position 2 = book 2, etc.
            var position = 1
            var url: String? = seriesUrl
            while (url != null) {
                val xml = httpGet(url) ?: break
                val feedResult = parseBookFeed(baseUrl, xml)
                feedResult.books.forEach { book ->
                    result += SeriesInfo(
                        normalizedTitle  = book.title.normalize(),
                        normalizedAuthor = book.author.normalize(),
                        seriesName       = seriesName,
                        // Use explicit series index from the OPDS entry if present;
                        // fall back to feed position so Calibre's reading order is
                        // preserved even when <calibre:series_index> is absent.
                        seriesIndex      = book.seriesIndex ?: position.toFloat()
                    )
                    position++
                }
                url = feedResult.nextUrl
            }
        }
        Timber.d("OPDSService: series metadata ready — ${result.size} book-series mappings across ${allSeriesEntries.size} series")
        result
    }

    /**
     * Parses a navigation (catalog-of-catalogs) Atom feed and returns:
     *  - The list of (displayTitle, href) for each subsection nav entry
     *  - The next-page URL (from <link rel="next">) or null
     */
    private fun parseNavFeedEntries(
        baseUrl: String,
        xml: String
    ): Pair<List<Pair<String, String>>, String?> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser().apply { setInput(xml.reader()) }

        val entries  = mutableListOf<Pair<String, String>>()
        var nextUrl: String? = null

        var inEntry    = false
        var entryTitle = ""
        var navHref    = ""
        var isNavEntry = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name ?: ""
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "entry" -> { inEntry = true; entryTitle = ""; navHref = ""; isNavEntry = false }
                    "title" -> if (inEntry) entryTitle = parser.nextText().trim()
                    "link"  -> {
                        val rel  = parser.getAttributeValue(null, "rel")  ?: ""
                        val type = parser.getAttributeValue(null, "type") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        if (!inEntry) {
                            // Feed-level <link rel="next"> for pagination
                            if (rel == "next" && href.isNotBlank()) {
                                nextUrl = if (href.startsWith("http")) href else "$baseUrl$href"
                            }
                        } else if (href.isNotBlank()) {
                            if (type.contains("atom+xml") || rel.contains("subsection") || rel.contains("related")) {
                                isNavEntry = true
                                if (navHref.isEmpty()) {
                                    navHref = if (href.startsWith("http")) href else "$baseUrl$href"
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (tag == "entry" && inEntry) {
                    if (isNavEntry && navHref.isNotBlank() && entryTitle.isNotBlank()) {
                        entries += Pair(entryTitle, navHref)
                    }
                    inEntry = false
                }
            }
            event = parser.next()
        }
        return Pair(entries, nextUrl)
    }

    /**
     * Scans the root /opds catalog and returns the URL of the "By Series"
     * navigation entry, or null if Calibre doesn't expose one.
     */
    private fun findSeriesCatalogUrl(baseUrl: String, rootXml: String): String? {
        val (navEntries, _) = parseNavFeedEntries(baseUrl, rootXml)
        return navEntries.firstOrNull { (title, _) ->
            title.lowercase().let { t -> t.contains("series") || t.contains("serie") }
        }?.second
    }

    // ── XML text helpers ──────────────────────────────────────────────────────

    /**
     * Like [XmlPullParser.nextText] but works even when the element contains
     * nested child tags (e.g. Calibre's `<content type="xhtml"><div>…</div></content>`).
     *
     * Tracks open/close depth so we know when we've exited the element, and
     * collects all TEXT nodes along the way.
     *
     * Must be called immediately after the START_TAG event for the element —
     * the parser is left positioned on the END_TAG of the original element.
     */
    private fun readNestedText(parser: XmlPullParser): String {
        val sb    = StringBuilder()
        var depth = 1          // we've already consumed the opening START_TAG
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT       -> sb.append(parser.text)
                XmlPullParser.START_TAG  -> depth++
                XmlPullParser.END_TAG    -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    /**
     * Calibre's OPDS feed prepends metadata lines to the <summary> element:
     *
     *   TAGS: Fantasy, Magic, Romance
     *   SERIES: Blood and Ash [1]
     *
     *   The actual synopsis starts here…
     *
     * These lines are noise in the description field because tags and series
     * are now stored in their own columns. Strip any leading lines that start
     * with "TAGS:" or "SERIES:", plus any blank lines that follow them.
     */
    private fun String.cleanCalibreDescription(): String =
        lines()
            .dropWhile { line ->
                val t = line.trim()
                t.isEmpty()
                    || t.startsWith("TAGS:",   ignoreCase = true)
                    || t.startsWith("SERIES:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
}
