package com.ereader.app.data.remote.calibre

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Calibre has no built-in "Read" field — people add their own column for it
 * (Calibre → Preferences → Add your own columns). This finds that column, and an
 * optional "Date Read" column, in the metadata Calibre returns for a book.
 *
 * Calibre's /ajax/books and /ajax/book responses carry every custom column under
 * "user_metadata", keyed by lookup name:
 *   "user_metadata": {
 *     "#read":      {"datatype": "bool",     "name": "Read",      "#value#": true},
 *     "#date_read": {"datatype": "datetime", "name": "Date Read", "#value#": "2026-11-20T18:00:00+00:00"}
 *   }
 *
 * Matching rules (first match wins):
 *   Read column      — a Yes/No column whose lookup name is "read", or whose
 *                      display name is "Read", "Have Read", "Is Read" or "Finished".
 *   Date Read column — a Date column whose lookup name is "date_read", "read_date",
 *                      "dateread" or "date_finished", or whose display name is
 *                      "Date Read", "Read Date", "Date Finished" or "Finished On".
 */
data class CalibreReadColumns(
    /** Lookup name including the '#', e.g. "#read". Null = this library has no Read column. */
    val readKey: String?,
    /** Lookup name of the Date Read column, e.g. "#date_read". Null = none. */
    val dateKey: String?
) {
    companion object {
        val NONE = CalibreReadColumns(null, null)

        private val READ_LABELS  = setOf("read", "haveread", "isread", "finished")
        private val READ_NAMES   = setOf("read", "have read", "is read", "finished")
        private val DATE_LABELS  = setOf("date_read", "read_date", "dateread", "date_finished")
        private val DATE_NAMES   = setOf("date read", "read date", "date finished", "finished on")

        /** Finds the columns in one book's "user_metadata" object. */
        fun detect(userMetadata: JSONObject?): CalibreReadColumns {
            if (userMetadata == null) return NONE
            var readKey: String? = null
            var dateKey: String? = null
            // Two passes so a lookup-name match beats a display-name match.
            for (byLabel in listOf(true, false)) {
                for (key in userMetadata.keys()) {
                    val col      = userMetadata.optJSONObject(key) ?: continue
                    val datatype = col.optString("datatype")
                    val label    = key.removePrefix("#").lowercase()
                    val name     = col.optString("name").trim().lowercase()
                    if (readKey == null && datatype == "bool" &&
                        (if (byLabel) label in READ_LABELS else name in READ_NAMES)) readKey = key
                    if (dateKey == null && datatype == "datetime" &&
                        (if (byLabel) label in DATE_LABELS else name in DATE_NAMES)) dateKey = key
                }
            }
            return CalibreReadColumns(readKey, dateKey)
        }

        /** The book's Read value: true / false. Calibre's "blank" counts as not read. */
        fun readValue(userMetadata: JSONObject?, key: String): Boolean {
            val col = userMetadata?.optJSONObject(key) ?: return false
            if (col.isNull("#value#")) return false
            return col.optBoolean("#value#", false)
        }

        /** The book's Date Read as epoch ms, or null if blank. */
        fun dateValue(userMetadata: JSONObject?, key: String?): Long? {
            if (key == null) return null
            val col = userMetadata?.optJSONObject(key) ?: return null
            if (col.isNull("#value#")) return null
            val raw = col.optString("#value#").takeIf { it.isNotBlank() } ?: return null
            val ms = runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(raw).toEpochMilli() }
                .getOrNull() ?: return null
            // Calibre stores "no date" as the year 101 — treat anything before 1900 as blank.
            return ms.takeIf { it > -2_208_988_800_000L }
        }
    }
}
