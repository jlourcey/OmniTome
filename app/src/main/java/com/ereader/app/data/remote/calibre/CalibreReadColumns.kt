package com.ereader.app.data.remote.calibre

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Which Calibre columns the user picked in Settings → Read Tracking.
 * Each value is one of:
 *   [AUTO] ("")  — find the column by its name (the default)
 *   [OFF]  ("-") — don't use a column for this
 *   "#lookup"    — use exactly this column
 */
data class ReadColumnChoice(
    val read: String  = AUTO,
    val date: String  = AUTO,
    val times: String = AUTO
) {
    companion object {
        const val AUTO = ""
        const val OFF  = "-"
        val DEFAULT = ReadColumnChoice()
    }
}

/** One custom column in a Calibre library — shown in the Settings column pickers. */
data class CalibreColumnInfo(
    /** Lookup name including the '#', e.g. "#read". */
    val key: String,
    /** Display name, e.g. "Read". */
    val name: String,
    /** Calibre datatype: "bool", "datetime", "int", "text", … */
    val datatype: String
)

/**
 * Calibre has no built-in "Read" field — people add their own column for it
 * (Calibre → Preferences → Add your own columns). This finds that column, an
 * optional "Date Read" column and an optional "Times Read" counter column in
 * the metadata Calibre returns for a book.
 *
 * Calibre's /ajax/books and /ajax/book responses carry every custom column under
 * "user_metadata", keyed by lookup name:
 *   "user_metadata": {
 *     "#read":       {"datatype": "bool",     "name": "Read",       "#value#": true},
 *     "#date_read":  {"datatype": "datetime", "name": "Date Read",  "#value#": "2026-11-20T18:00:00+00:00"},
 *     "#times_read": {"datatype": "int",      "name": "Times Read", "#value#": 2}
 *   }
 *
 * Automatic matching rules (first match wins), used unless the user picked a
 * column in Settings:
 *   Read column       — a Yes/No column whose lookup name is "read", or whose
 *                       display name is "Read", "Have Read", "Is Read" or "Finished".
 *   Date Read column  — a Date column whose lookup name is "date_read", "read_date",
 *                       "dateread" or "date_finished", or whose display name is
 *                       "Date Read", "Read Date", "Date Finished" or "Finished On".
 *   Times Read column — a whole-number column whose lookup name is "times_read",
 *                       "timesread", "read_count" or "readcount", or whose display
 *                       name is "Times Read", "Read Count" or "Number of Times Read".
 */
data class CalibreReadColumns(
    /** Lookup name including the '#', e.g. "#read". Null = this library has no Read column. */
    val readKey: String?,
    /** Lookup name of the Date Read column, e.g. "#date_read". Null = none. */
    val dateKey: String?,
    /** Lookup name of the Times Read counter, e.g. "#times_read". Null = none. */
    val timesKey: String? = null
) {
    companion object {
        val NONE = CalibreReadColumns(null, null, null)

        private val READ_LABELS  = setOf("read", "haveread", "isread", "finished")
        private val READ_NAMES   = setOf("read", "have read", "is read", "finished")
        private val DATE_LABELS  = setOf("date_read", "read_date", "dateread", "date_finished")
        private val DATE_NAMES   = setOf("date read", "read date", "date finished", "finished on")
        private val TIMES_LABELS = setOf("times_read", "timesread", "read_count", "readcount")
        private val TIMES_NAMES  = setOf("times read", "read count", "number of times read", "# times read")

        /**
         * Finds the columns in one book's "user_metadata" object.
         * [choice] = what the user picked in Settings (automatic by default).
         */
        fun detect(userMetadata: JSONObject?, choice: ReadColumnChoice = ReadColumnChoice.DEFAULT): CalibreReadColumns {
            if (userMetadata == null) return NONE
            return CalibreReadColumns(
                readKey  = pick(userMetadata, choice.read,  "bool",     READ_LABELS,  READ_NAMES),
                dateKey  = pick(userMetadata, choice.date,  "datetime", DATE_LABELS,  DATE_NAMES),
                timesKey = pick(userMetadata, choice.times, "int",      TIMES_LABELS, TIMES_NAMES)
            )
        }

        /**
         * One column: the user's own pick if it exists in this library and has
         * the right type; otherwise (Automatic) a name match. A lookup-name match
         * beats a display-name match.
         */
        private fun pick(
            userMetadata: JSONObject,
            chosen: String,
            datatype: String,
            labels: Set<String>,
            names: Set<String>
        ): String? {
            if (chosen == ReadColumnChoice.OFF) return null
            if (chosen != ReadColumnChoice.AUTO) {
                val col = userMetadata.optJSONObject(chosen)
                // The picked column was deleted or changed type in Calibre → fall back to automatic.
                if (col != null && col.optString("datatype") == datatype) return chosen
            }
            for (byLabel in listOf(true, false)) {
                for (key in userMetadata.keys()) {
                    val col = userMetadata.optJSONObject(key) ?: continue
                    if (col.optString("datatype") != datatype) continue
                    val label = key.removePrefix("#").lowercase()
                    val name  = col.optString("name").trim().lowercase()
                    if (if (byLabel) label in labels else name in names) return key
                }
            }
            return null
        }

        /** Every custom column in a book's "user_metadata" — for the Settings pickers. */
        fun listColumns(userMetadata: JSONObject?): List<CalibreColumnInfo> {
            if (userMetadata == null) return emptyList()
            return userMetadata.keys().asSequence().mapNotNull { key ->
                val col = userMetadata.optJSONObject(key) ?: return@mapNotNull null
                CalibreColumnInfo(
                    key      = key,
                    name     = col.optString("name").ifBlank { key },
                    datatype = col.optString("datatype")
                )
            }.sortedBy { it.name.lowercase() }.toList()
        }

        /** The book's Read value: true / false. Calibre's "blank" counts as not read. */
        fun readValue(userMetadata: JSONObject?, key: String): Boolean {
            val col = userMetadata?.optJSONObject(key) ?: return false
            if (col.isNull("#value#")) return false
            return col.optBoolean("#value#", false)
        }

        /** The book's Times Read count. Blank counts as 0. */
        fun timesValue(userMetadata: JSONObject?, key: String?): Int {
            if (key == null) return 0
            val col = userMetadata?.optJSONObject(key) ?: return 0
            if (col.isNull("#value#")) return 0
            return col.optInt("#value#", 0).coerceAtLeast(0)
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

        /**
         * Calibre's built-in star rating. Calibre stores 0–10 (two points per
         * star, so half stars are possible); OmniTome shows whole stars 1–5.
         * Returns null when the book has no rating.
         */
        fun ratingStars(book: JSONObject): Int? {
            if (!book.has("rating") || book.isNull("rating")) return null
            val raw = book.optDouble("rating")
            if (raw.isNaN() || raw <= 0.0) return null
            return Math.round(raw / 2.0).toInt().coerceIn(1, 5)
        }
    }
}
