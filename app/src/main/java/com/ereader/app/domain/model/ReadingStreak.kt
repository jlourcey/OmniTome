package com.ereader.app.domain.model

import java.time.LocalDate

/**
 * Your reading streak, worked out from the days you turned pages.
 *
 * @property current    Days in a row you've read, counting today. If you haven't
 *                      read yet today, yesterday's streak still counts — it only
 *                      breaks once a whole day passes with no reading.
 * @property readToday  True once you've turned a page today.
 * @property best       Your longest streak ever.
 */
data class ReadingStreak(
    val current: Int   = 0,
    val readToday: Boolean = false,
    val best: Int      = 0
) {
    companion object {
        /** [days] = "yyyy-MM-dd" strings, any order. */
        fun from(days: List<String>, today: LocalDate = LocalDate.now()): ReadingStreak {
            val set = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSortedSet()
            if (set.isEmpty()) return ReadingStreak()

            val readToday = today in set
            var current = 0
            var day = if (readToday) today else today.minusDays(1)
            while (day in set) { current++; day = day.minusDays(1) }

            var best = 0
            var run  = 0
            var prev: LocalDate? = null
            for (d in set) {                      // oldest → newest
                run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
                if (run > best) best = run
                prev = d
            }
            return ReadingStreak(current, readToday, maxOf(best, current))
        }
    }
}
