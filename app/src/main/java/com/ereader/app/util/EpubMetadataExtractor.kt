package com.ereader.app.util

import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Extracts embedded metadata from an EPUB file without any third-party libraries.
 *
 * ## How EPUB metadata works
 *
 * An EPUB is a ZIP archive with a specific directory structure:
 *
 *   META-INF/container.xml  ← points to the OPF package file
 *   OEBPS/content.opf       ← contains <metadata> with dc:title, dc:creator, etc.
 *   OEBPS/images/cover.jpg  ← the cover image (path varies by EPUB)
 *
 * ## Finding the cover image
 *
 * There is no single standard.  We try three methods in order:
 *  1. `<meta name="cover" content="item-id"/>` in `<metadata>` — links to a
 *     manifest `<item>` whose `href` is the cover file.  Used by most older EPUBs.
 *  2. `<item properties="cover-image" href="..."/>` in `<manifest>` — EPUB 3 standard.
 *  3. Heuristic: any image `<item>` whose href contains the word "cover".
 */
object EpubMetadataExtractor {

    /**
     * Result of reading an EPUB's embedded metadata.
     *
     * Any field can be null/empty if the EPUB omits it or the field cannot be parsed.
     * [coverBytes] is the raw image bytes (JPEG or PNG) ready to write to disk.
     */
    data class EpubMetadata(
        val title: String?,
        val author: String?,
        val coverBytes: ByteArray?
    )

    /**
     * Reads the EPUB at [epubFile] and returns whatever metadata is embedded.
     *
     * All exceptions are caught internally — a corrupt or non-standard EPUB
     * returns [EpubMetadata] with null fields rather than crashing the import.
     */
    fun extract(epubFile: File): EpubMetadata {
        return try {
            ZipFile(epubFile).use { zip ->

                // ── Step 1: find the OPF file ─────────────────────────────────
                val containerEntry = zip.getEntry("META-INF/container.xml")
                    ?: return EpubMetadata(null, null, null)
                val opfPath = parseOpfPath(zip.getInputStream(containerEntry))
                    ?: return EpubMetadata(null, null, null)

                // ── Step 2: read the OPF ──────────────────────────────────────
                val opfEntry = zip.getEntry(opfPath)
                    ?: return EpubMetadata(null, null, null)
                val opfContent = zip.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8)

                // OPF directory prefix (needed to resolve relative hrefs)
                val opfDir = opfPath
                    .substringBeforeLast('/')
                    .let { if (it == opfPath) "" else "$it/" }

                // ── Step 3: extract dc:title and dc:creator ───────────────────
                val title  = extractDcField(opfContent, "title")
                val author = extractDcField(opfContent, "creator")

                // ── Step 4: find and read cover image ─────────────────────────
                val coverZipPath = findCoverPath(opfContent, opfDir)
                val coverBytes   = if (coverZipPath != null) {
                    zip.getEntry(coverZipPath)
                        ?.let { entry ->
                            runCatching { zip.getInputStream(entry).readBytes() }
                                .onFailure { Timber.w(it, "EpubMetadata: could not read cover at $coverZipPath") }
                                .getOrNull()
                        }
                } else null

                Timber.d(
                    "EpubMetadata: '${epubFile.name}' → " +
                    "title='$title' author='$author' " +
                    "cover=${if (coverBytes != null) "${coverBytes.size} bytes" else "none"}"
                )
                EpubMetadata(title, author, coverBytes)
            }
        } catch (e: Exception) {
            Timber.w(e, "EpubMetadata: failed to read metadata from '${epubFile.name}'")
            EpubMetadata(null, null, null)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Parses `META-INF/container.xml` to find the OPF package document path.
     *
     * Expected content:
     * ```xml
     * <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
     * ```
     */
    private fun parseOpfPath(stream: InputStream): String? {
        val content = stream.readBytes().toString(Charsets.UTF_8)
        return Regex("""full-path="([^"]+)"""")
            .find(content)
            ?.groupValues
            ?.get(1)
    }

    /**
     * Extracts the text content of a `<dc:[field]>` element from the OPF.
     *
     * Handles optional namespace prefix and attributes, e.g.:
     *   `<dc:creator opf:role="aut">Author Name</dc:creator>`
     */
    private fun extractDcField(opf: String, field: String): String? =
        Regex("""<dc:$field(?:\s[^>]*)?>([^<]+)</dc:$field>""", RegexOption.IGNORE_CASE)
            .find(opf)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    /**
     * Tries three strategies to find the cover image path inside the EPUB ZIP.
     *
     * Returns a path relative to the ZIP root (e.g. "OEBPS/images/cover.jpg"),
     * or null if no cover is found.
     */
    private fun findCoverPath(opf: String, opfDir: String): String? {

        // Strategy 1 (EPUB 2): <meta name="cover" content="cover-item-id"/>
        // followed by <item id="cover-item-id" href="images/cover.jpg" .../>
        val coverItemId = Regex(
            """<meta\s+name="cover"\s+content="([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(opf)?.groupValues?.get(1)

        if (coverItemId != null) {
            val href = findItemHref(opf, id = coverItemId)
            if (href != null) return opfDir + href
        }

        // Strategy 2 (EPUB 3): <item properties="cover-image" href="..."/>
        val epubThreeHref = Regex(
            """<item\s[^>]*properties="cover-image"[^>]*href="([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(opf)?.groupValues?.get(1)
            ?: Regex(
                """<item\s[^>]*href="([^"]+)"[^>]*properties="cover-image"""",
                RegexOption.IGNORE_CASE
            ).find(opf)?.groupValues?.get(1)

        if (epubThreeHref != null) return opfDir + epubThreeHref

        // Strategy 3 (heuristic): any image item whose href contains "cover"
        val heuristic = Regex(
            """<item\s[^>]*href="([^"]*cover[^"]*\.(?:jpg|jpeg|png))"[^>]*media-type="image/""",
            RegexOption.IGNORE_CASE
        ).find(opf)?.groupValues?.get(1)

        return if (heuristic != null) opfDir + heuristic else null
    }

    /**
     * Finds the `href` of a manifest `<item>` by its `id` attribute.
     *
     * Tries both attribute orderings:
     *   `<item id="X" href="Y" .../>`
     *   `<item href="Y" id="X" .../>`
     */
    private fun findItemHref(opf: String, id: String): String? {
        val escapedId = Regex.escapeReplacement(id)
        return Regex(
            """<item\s[^>]*id="$escapedId"[^>]*href="([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(opf)?.groupValues?.get(1)
            ?: Regex(
                """<item\s[^>]*href="([^"]+)"[^>]*id="$escapedId"""",
                RegexOption.IGNORE_CASE
            ).find(opf)?.groupValues?.get(1)
    }
}
