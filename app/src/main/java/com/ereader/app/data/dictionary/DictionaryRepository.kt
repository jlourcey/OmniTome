package com.ereader.app.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One meaning of a word. */
data class DictionarySense(
    val definition: String,
    val examples: List<String>,
    /** Other words with this meaning (the looked-up word itself left out). */
    val synonyms: List<String>
)

/** All the meanings of a word for one part of speech ("noun", "verb", …). */
data class DictionaryEntry(
    val partOfSpeech: String,
    val senses: List<DictionarySense>,
    /** The dictionary word these meanings belong to, e.g. "run" for the verb in "running". */
    val headword: String
)

/** Result of a lookup. [headword] is the dictionary form found, e.g. "run" for "ran". */
data class DictionaryResult(
    val query: String,
    val headword: String?,
    val entries: List<DictionaryEntry>
) {
    val found: Boolean get() = entries.isNotEmpty()
}

/** Short Wikipedia summary for the definition card. */
data class WikipediaSummary(
    val title: String,
    val extract: String,
    val url: String?
)

/**
 * Offline English dictionary for the reader's "Define" action.
 *
 * Data: Princeton WordNet® 3.1 — about 155,000 words and phrases, 117,000
 * meanings, with examples and synonyms — shipped inside the app
 * (assets/dictionary/wordnet31.db, built from the official WordNet files).
 * No internet needed. WordNet's license (assets/dictionary/WORDNET_LICENSE.txt)
 * allows redistribution with its copyright notice, which the card shows.
 *
 * Finding the dictionary form of what you selected:
 *   1. Clean it up: trim punctuation and quotes, lower-case, drop a trailing 's.
 *   2. Look it up as is ("serendipity", "ice cream").
 *   3. Irregular forms from WordNet's own exception lists ("ran" → run,
 *      "mice" → mouse, "better" → good / well).
 *   4. Regular endings, the same rules WordNet uses ("libraries" → library,
 *      "running" → run, "happier" → happy).
 *
 * The first time it's used, the database is copied from the app into private
 * storage (about 22 MB, a second or two). After that, lookups are instant.
 */
@Singleton
class DictionaryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient
) {
    private companion object {
        const val ASSET     = "dictionary/wordnet31.db"
        /** Bump if the bundled database changes, so the old copy gets replaced. */
        const val DB_VERSION = 1
        const val MAX_SENSES_PER_POS = 6
        val POS_ORDER = listOf("noun", "verb", "adjective", "adverb")

        /** WordNet's "morphy" suffix rules: (ending, replacement) per part of speech. */
        val RULES = mapOf(
            "noun"      to listOf("s" to "", "ses" to "s", "ves" to "f", "xes" to "x", "zes" to "z",
                                  "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
            "verb"      to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e",
                                  "ed" to "", "ing" to "e", "ing" to ""),
            "adjective" to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
            "adverb"    to emptyList()
        )
    }

    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    /** No Calibre password handling here — Wikipedia is a public site. */
    private val webClient = okHttpClient.newBuilder()
        .authenticator(okhttp3.Authenticator.NONE)
        .connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private suspend fun database(): SQLiteDatabase = mutex.withLock {
        db?.let { return it }
        withContext(Dispatchers.IO) {
            val dir  = File(context.noBackupFilesDir, "dictionary").apply { mkdirs() }
            val file = File(dir, "wordnet31-v$DB_VERSION.db")
            if (!file.exists() || file.length() == 0L) {
                dir.listFiles()?.forEach { it.delete() }          // old versions
                val tmp = File(dir, "copying.tmp")
                context.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(file)
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
        }
    }

    /** Looks up [selection] (a word or short phrase the reader selected). Works offline. */
    suspend fun lookup(selection: String): DictionaryResult = withContext(Dispatchers.IO) {
        val query = clean(selection)
        if (query.isEmpty()) return@withContext DictionaryResult(selection.trim(), null, emptyList())
        val database = database()

        // The selected form itself often has its own entry ("running" the noun,
        // "stopped" the adjective), but the reader usually means the base word
        // ("run", "stop"). Show the first match, then add the base word's meanings
        // for any part of speech the first one didn't cover.
        val found = mutableListOf<DictionaryEntry>()
        var headword: String? = null
        for (candidate in candidates(query, database)) {
            val entries = entriesFor(candidate, database)
            if (entries.isEmpty()) continue
            if (headword == null) {
                headword = candidate
                found += entries
            } else {
                found += entries.filter { e -> found.none { it.partOfSpeech == e.partOfSpeech } }
                break
            }
        }
        DictionaryResult(query, headword, found)
    }

    /** Trims punctuation/quotes, lower-cases, removes a possessive 's. */
    private fun clean(text: String): String =
        text.trim()
            .replace('’', '\'')
            .trim { !it.isLetterOrDigit() }
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
            .removeSuffix("'s")
            .take(60)

    /** Possible dictionary forms, best first, without duplicates. */
    private fun candidates(word: String, database: SQLiteDatabase): List<String> {
        val out = linkedSetOf(word)
        database.rawQuery("SELECT base FROM exceptions WHERE inflected = ?", arrayOf(word)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        for ((_, rules) in RULES) for ((ending, replacement) in rules) {
            if (word.length > ending.length + 1 && word.endsWith(ending)) {
                val stem = word.dropLast(ending.length) + replacement
                out += stem
                // Doubled last letter: "hopping" → hopp → hop, "bigger" → bigg → big
                if (replacement.isEmpty() && stem.length > 2 && stem.last() == stem[stem.length - 2] &&
                    stem.last() !in "aeiouls") out += stem.dropLast(1)
            }
        }
        // Hyphens: "well-known" ↔ "well known"
        if ('-' in word) out += word.replace('-', ' ')
        return out.toList()
    }

    private fun entriesFor(word: String, database: SQLiteDatabase): List<DictionaryEntry> {
        val byPos = linkedMapOf<String, MutableList<DictionarySense>>()
        database.rawQuery(
            """SELECT s.pos, s.gloss, s.examples, s.synonyms FROM senses x
               JOIN synsets s ON s.id = x.synset
               WHERE x.word = ? ORDER BY x.rank""",
            arrayOf(word)
        ).use { c ->
            while (c.moveToNext()) {
                val list = byPos.getOrPut(c.getString(0)) { mutableListOf() }
                if (list.size >= MAX_SENSES_PER_POS) continue
                list += DictionarySense(
                    definition = c.getString(1).replaceFirstChar { it.uppercase() },
                    examples   = c.getString(2)?.split('\n')?.filter { it.isNotBlank() }.orEmpty().take(2),
                    synonyms   = c.getString(3)?.split(", ")
                        ?.filter { !it.equals(word, ignoreCase = true) }.orEmpty().take(6)
                )
            }
        }
        return byPos.entries
            .sortedBy { (pos, _) -> POS_ORDER.indexOf(pos).let { if (it < 0) 99 else it } }
            .map { (pos, senses) -> DictionaryEntry(pos, senses, word) }
    }

    /**
     * Short Wikipedia summary — the only part of "Define" that needs internet.
     * Uses the phone's language edition (falls back to English).
     * Throws IOException when offline so the card can say so.
     */
    suspend fun wikipedia(term: String): WikipediaSummary? = withContext(Dispatchers.IO) {
        val lang = Locale.getDefault().language.takeIf { it.length == 2 } ?: "en"
        fetchWiki(lang, term) ?: if (lang != "en") fetchWiki("en", term) else null
    }

    private fun fetchWiki(lang: String, term: String): WikipediaSummary? {
        val title = URLEncoder.encode(term.trim().replace(' ', '_'), "UTF-8").replace("+", "%20")
        val req = Request.Builder()
            .url("https://$lang.wikipedia.org/api/rest_v1/page/summary/$title?redirect=true")
            .header("User-Agent", "OmniTome (Android e-reader; https://github.com/jlourcey/OmniTome)")
            .header("Accept", "application/json")
            .build()
        webClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("Wikipedia returned HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val extract = json.optString("extract").trim()
            if (extract.isEmpty()) return null
            val url = json.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
            return WikipediaSummary(json.optString("title", term), extract, url?.takeIf { it.isNotBlank() })
                .also { Timber.d("Wikipedia summary for '$term' ($lang)") }
        }
    }
}
