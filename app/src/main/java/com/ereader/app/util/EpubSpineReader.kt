package com.ereader.app.util

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Reads the "spine" (the chapter reading order) out of an EPUB file.
 *
 * Calibre identifies where a note lives by the chapter's position in the
 * spine (spine_index) and its path inside the EPUB (spine_name). OmniTome's
 * reader stores each note's chapter as an href, so this helper converts the
 * href into Calibre's spine number.
 *
 * Every function here fails soft: a broken or unusual EPUB returns an empty
 * list / null instead of crashing the sync.
 */
object EpubSpineReader {

    /** Full in-zip paths of the spine items in reading order (empty on failure). */
    fun spinePaths(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml") ?: return@use emptyList()
            val opfPath = zip.getInputStream(container).use { findRootfile(it) }
                ?: return@use emptyList()
            val opfEntry = zip.getEntry(opfPath) ?: return@use emptyList()
            val opfDir   = opfPath.substringBeforeLast('/', "")

            val manifest = HashMap<String, String>()   // item id -> href
            val spineIds = ArrayList<String>()          // itemref idrefs, in order
            zip.getInputStream(opfEntry).use { parseOpf(it, manifest, spineIds) }

            spineIds.mapNotNull { manifest[it] }
                .map { href -> resolve(opfDir, Uri.decode(href.substringBefore('#'))) }
        }
    }.getOrDefault(emptyList())

    /**
     * Finds which spine entry [href] (as stored by the reader) points at.
     * Tries an exact path match first, then a path-suffix match, then a
     * plain file-name match. Returns null if nothing matches.
     */
    fun indexOf(spine: List<String>, href: String?): Int? {
        if (href.isNullOrBlank() || spine.isEmpty()) return null
        val clean = Uri.decode(href.substringBefore('#').substringBefore('?')).trimStart('/')
        spine.indexOf(clean).takeIf { it >= 0 }?.let { return it }
        spine.indexOfFirst { it.endsWith("/$clean") || clean.endsWith("/$it") }
            .takeIf { it >= 0 }?.let { return it }
        val name = clean.substringAfterLast('/')
        return spine.indexOfFirst { it.substringAfterLast('/') == name }.takeIf { it >= 0 }
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private fun newParser(input: InputStream): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

    /** Tag name without any "opf:" style prefix. */
    private fun XmlPullParser.localName(): String = name.substringAfter(':')

    private fun findRootfile(input: InputStream): String? {
        val p = newParser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.localName() == "rootfile") {
                return p.getAttributeValue(null, "full-path")
            }
        }
        return null
    }

    private fun parseOpf(input: InputStream, manifest: MutableMap<String, String>, spine: MutableList<String>) {
        val p = newParser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.localName()) {
                "item" -> {
                    val id   = p.getAttributeValue(null, "id")
                    val href = p.getAttributeValue(null, "href")
                    if (id != null && href != null) manifest[id] = href
                }
                "itemref" -> p.getAttributeValue(null, "idref")?.let { spine.add(it) }
            }
        }
    }

    /** Joins [dir] and a relative [href], collapsing "." and ".." segments. */
    private fun resolve(dir: String, href: String): String {
        val parts = ArrayList<String>()
        (if (dir.isEmpty()) href else "$dir/$href").split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".."    -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else    -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
