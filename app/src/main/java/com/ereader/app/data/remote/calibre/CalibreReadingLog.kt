package com.ereader.app.data.remote.calibre

/**
 * The reading log that makes the reading streak work across devices.
 *
 * Calibre has no place for "days you read", so OmniTome keeps one small entry
 * per Calibre user in the library itself:
 *
 *   Title:    OmniTome Reading Log (james)     ← "(username)"; just "OmniTome Reading Log"
 *                                                 when the server has no logins
 *   Author:   OmniTome
 *   Tags:     OmniTome
 *   Format:   a tiny TXT file (Calibre needs a file to create an entry)
 *   Comments: OMNITOME-READING-DAYS:2026-11-20,2026-11-21,2026-11-22
 *
 * Every device sends the days it has read and takes the days the others sent,
 * so all of them end up with the same list — and the same streak. Days are only
 * ever added, never removed, so two devices syncing at once can't lose anything
 * (each re-sends its own days next time).
 *
 * OmniTome hides these entries from its own shelves. In Calibre they show up
 * as normal entries; deleting one just starts that log fresh.
 */
object CalibreReadingLog {
    const val TITLE_PREFIX = "OmniTome Reading Log"
    const val AUTHOR       = "OmniTome"
    const val TAG          = "OmniTome"
    private const val MARKER = "OMNITOME-READING-DAYS:"
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

    /** True for log entries, so they can be kept off the bookshelf. */
    fun isLogTitle(title: String?): Boolean =
        title?.trim()?.startsWith(TITLE_PREFIX, ignoreCase = true) == true

    /** "OmniTome Reading Log (james)" — one log per Calibre login. */
    fun titleFor(username: String): String =
        username.trim().lowercase().takeIf { it.isNotEmpty() }
            ?.let { "$TITLE_PREFIX ($it)" } ?: TITLE_PREFIX

    /** Reads the days out of the entry's comments (works even if Calibre wrapped them in HTML). */
    fun parseDays(comments: String?): Set<String> {
        if (comments.isNullOrBlank()) return emptySet()
        val start = comments.indexOf(MARKER)
        if (start < 0) return emptySet()
        return DAY.findAll(comments.substring(start + MARKER.length)).map { it.value }.toSet()
    }

    /** Builds the comments text: a short note for anyone looking in Calibre, then the days. */
    fun buildComments(days: Set<String>): String =
        "<p>OmniTome uses this entry to keep your reading streak the same on all your " +
        "devices. Please leave it in the library and don't edit it.</p>" +
        "<p>$MARKER${days.sorted().joinToString(",")}</p>"

    /** Contents of the tiny TXT file Calibre needs to create the entry. */
    const val FILE_TEXT = "OmniTome reading log. OmniTome uses this entry to sync your reading streak. Please don't delete it."
}
