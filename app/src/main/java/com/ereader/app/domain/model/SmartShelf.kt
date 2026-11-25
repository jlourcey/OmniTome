package com.ereader.app.domain.model

/**
 * A Smart Shelf: a saved Calibre search that shows as its own shelf.
 *
 * The [query] uses Calibre's normal search language, exactly as you'd type it
 * in Calibre's search bar, for example:
 *   tags:"Fantasy" and #read:false     — unread fantasy
 *   rating:>=4                         — 4 stars and up
 *   search:"My saved search"           — one of your Calibre saved searches
 *
 * [calibreIds] are the books Calibre matched the last time it was asked, so the
 * shelf still works when the phone can't reach Calibre. They're refreshed on
 * every library sync.
 */
data class SmartShelf(
    val id: String,
    val name: String,
    val query: String,
    val calibreIds: List<Int> = emptyList(),
    /** When [calibreIds] was last refreshed from Calibre (epoch ms). */
    val updatedAt: Long = 0L
)
