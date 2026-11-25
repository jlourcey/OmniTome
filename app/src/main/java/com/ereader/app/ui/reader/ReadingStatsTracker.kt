package com.ereader.app.ui.reader

import kotlin.math.ceil
import kotlin.math.roundToInt

/** What the reader's bottom bar shows next to the percentage. */
data class ReadingStats(
    /** "Page X of Y" for the current layout (font size, font, screen). */
    val page: Int,
    val pageCount: Int,
    val chapterMinutesLeft: Int,
    val bookMinutesLeft: Int,
    /** False until enough page turns have been timed in this book. */
    val speedIsPersonal: Boolean
)

/**
 * Works out page numbers and time left for an EPUB.
 *
 * PAGES. Readium splits a book into "positions" (about 1,024 characters each),
 * which don't change with font size. The reader measures how many screens the
 * current chapter really takes ([onLayoutMeasured]), giving "screens per
 * position" for this layout. That ratio, averaged over every chapter measured
 * so far (bigger chapters count more), turns the book's positions into a page
 * count for the current layout. It's reset when the font, size or screen changes.
 *
 * TIME. Your reading speed in this book (characters per minute) is learned from
 * page turns: each forward turn of one page taken between 4 seconds and
 * 5 minutes counts. Quick flips and long pauses are ignored. Until 5 turns are
 * timed, a typical 250 words per minute is assumed. After that, the speed moves
 * only gradually (each new turn counts 8 %, capped at double/half), so
 * estimates don't jump around.
 */
class ReadingStatsTracker(
    /** Positions per chapter, in reading order: href → (positions before it, positions in it). */
    private val chapters: Map<String, Pair<Int, Int>>,
    private val totalPositions: Int,
    savedSpeedCpm: Float?,
    savedSamples: Int
) {
    companion object {
        const val CHARS_PER_POSITION = 1024f
        /** 250 words/minute × ~5.7 characters per word (including the space). */
        const val DEFAULT_CPM = 1425f
        private const val MIN_TURN_MS = 4_000L
        private const val MAX_TURN_MS = 5 * 60_000L
        private const val WARMUP_SAMPLES = 5
        private const val SMOOTHING = 0.08f
    }

    var speedCpm: Float = savedSpeedCpm ?: DEFAULT_CPM; private set
    var samples: Int = savedSamples; private set
    private val warmup = mutableListOf<Float>()

    private var layoutKey: String? = null
    /** Measured screens per chapter for the current layout: href → pages. */
    private val measured = mutableMapOf<String, Int>()

    private var lastHref: String? = null
    private var lastPage = 0
    private var lastTurnAt = 0L

    fun chapterFor(href: String): String? =
        chapters.keys.firstOrNull { it == href } ?: chapters.keys.firstOrNull { href.endsWith(it) || it.endsWith(href) }

    /**
     * A page of [chapterHref] is showing: [pageInChapter] (1-based) of [pagesInChapter]
     * screens, laid out with [layout] (font size + font + screen width).
     * @return true if the reading speed changed (worth saving).
     */
    fun onLayoutMeasured(chapterHref: String, pageInChapter: Int, pagesInChapter: Int, layout: String, now: Long): Boolean {
        val href = chapterFor(chapterHref) ?: return false
        if (layout != layoutKey) { layoutKey = layout; measured.clear() }
        measured[href] = pagesInChapter.coerceAtLeast(1)

        // ── Reading speed from this page turn ─────────────────────────────────
        var speedChanged = false
        val forwardOne = (href == lastHref && pageInChapter == lastPage + 1) ||
                         (href != lastHref && pageInChapter == 1 && lastHref != null && isNextChapter(lastHref!!, href))
        val elapsed = now - lastTurnAt
        if (forwardOne && lastTurnAt > 0 && elapsed in MIN_TURN_MS..MAX_TURN_MS) {
            val prev = lastHref!!
            val prevPages = measured[prev] ?: pagesInChapter
            val charsPerPage = (chapters[prev]?.second ?: 1) * CHARS_PER_POSITION / prevPages
            val sample = (charsPerPage / (elapsed / 60_000f)).coerceIn(300f, 6_000f)
            if (samples < WARMUP_SAMPLES) {
                warmup += sample
                samples++
                if (samples == WARMUP_SAMPLES) { speedCpm = warmup.average().toFloat(); speedChanged = true }
            } else {
                // One unusual page (a skim, a re-read) can pull the speed at most to
                // double or half — and only 8 % of the way — so estimates stay steady.
                speedCpm += SMOOTHING * (sample.coerceIn(speedCpm / 2, speedCpm * 2) - speedCpm)
                samples++
                speedChanged = true
            }
        }
        lastHref = href; lastPage = pageInChapter; lastTurnAt = now
        return speedChanged
    }

    private fun isNextChapter(from: String, to: String): Boolean {
        val keys = chapters.keys.toList()
        return keys.indexOf(to) == keys.indexOf(from) + 1
    }

    /** Current numbers, or null if this chapter hasn't been measured yet. */
    fun stats(chapterHref: String, pageInChapter: Int): ReadingStats? {
        val href = chapterFor(chapterHref) ?: return null
        val (before, count) = chapters[href] ?: return null
        val pagesHere = measured[href] ?: return null

        // Screens per position, weighted by chapter size. Chapters of a single
        // position (title pages etc.) are only used if nothing else is measured.
        val solid = measured.filterKeys { (chapters[it]?.second ?: 0) >= 2 }.ifEmpty { measured }
        val ratio = solid.entries.sumOf { it.value }.toFloat() /
                    solid.keys.sumOf { chapters[it]?.second ?: 1 }.coerceAtLeast(1)

        val pageCount = (totalPositions * ratio).roundToInt().coerceAtLeast(1)
        val page = ((before * ratio).roundToInt() + pageInChapter).coerceIn(1, pageCount)

        val chapterChars = count * CHARS_PER_POSITION
        val chapterLeft  = chapterChars * (pagesHere - pageInChapter + 1) / pagesHere
        val afterChapter = (totalPositions - before - count).coerceAtLeast(0) * CHARS_PER_POSITION
        return ReadingStats(
            page               = page,
            pageCount          = pageCount,
            chapterMinutesLeft = ceil(chapterLeft / speedCpm).toInt(),
            bookMinutesLeft    = ceil((chapterLeft + afterChapter) / speedCpm).toInt(),
            speedIsPersonal    = samples >= WARMUP_SAMPLES
        )
    }
}

/** "12 min", "1 h 5 min", "4 h 10 min" (rounded to 5 min past an hour), "under 1 min". */
fun formatMinutes(minutes: Int): String = when {
    minutes < 1  -> "under 1 min"
    minutes < 60 -> "$minutes min"
    else -> {
        val rounded = ((minutes + 2) / 5) * 5
        val h = rounded / 60; val m = rounded % 60
        if (m == 0) "$h h" else "$h h $m min"
    }
}
