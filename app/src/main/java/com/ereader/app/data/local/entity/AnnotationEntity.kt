package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── Annotation type — what kind of mark the user created ─────────────────────

/**
 * HIGHLIGHT  — colored text selection with no written note.
 * NOTE       — colored text selection with an attached written note.
 * COMMENT    — page-level note with no text selection (used for PDFs or freeform
 *              thoughts added via the "Add Note" HUD button).
 */
enum class AnnotationType { HIGHLIGHT, NOTE, COMMENT }

// ── Available highlight colors ────────────────────────────────────────────────

/**
 * Five pastel highlight colors.
 * [hex] is the full-opacity hex string; actual rendering in the reader uses ~40%
 * alpha so the text underneath stays readable.
 */
enum class AnnotationColor(val displayName: String, val hex: String) {
    YELLOW("Yellow", "#FFD54F"),
    GREEN ("Green",  "#66BB6A"),
    BLUE  ("Blue",   "#90CAF9"),
    PINK  ("Pink",   "#F48FB1"),
    PURPLE("Purple", "#CE93D8")
}

/**
 * Android color int passed to Readium's decoration renderer.
 *
 * Use fully-opaque values (alpha = 255). Readium 3.x applies its own
 * transparency layer on top of the tint we supply, so if we also apply
 * alpha here the effective opacity compounds to near-zero — making every
 * colour except bright yellow invisible. The pastel palette is inherently
 * soft, so fully-opaque pastels still read comfortably over body text.
 */
fun AnnotationColor.toHighlightColorInt(): Int {
    val base = android.graphics.Color.parseColor(hex)
    return android.graphics.Color.argb(
        255,   // fully opaque — Readium adds its own alpha on top
        android.graphics.Color.red(base),
        android.graphics.Color.green(base),
        android.graphics.Color.blue(base)
    )
}

/** Compose color at 60% alpha — used for the color swatch UI in the dialog. */
fun AnnotationColor.toComposeColor(): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex)).copy(alpha = 0.75f)

// ── Room entity ───────────────────────────────────────────────────────────────

/**
 * One row per annotation (highlight, note, or comment).
 *
 * [locatorJson]         — the Readium Locator serialized to JSON; canonical
 *                         position reference used when re-rendering decorations.
 * [pageNumber]          — [Locator.locations.position] stored redundantly so we
 *                         can ORDER BY it in SQL without JSON parsing.
 * [progressionInBook]   — [Locator.locations.totalProgression] (0.0–1.0); used
 *                         to sort annotations in reading order.
 * [chapterHref]         — used to group annotations by chapter on the Notes page.
 */
@Entity(
    tableName = "annotations",
    indices   = [Index("bookId")]
)
data class AnnotationEntity(

    @PrimaryKey
    val id: String,

    /** The book this annotation belongs to — matches [BookEntity.id]. */
    val bookId: String,

    val type:  AnnotationType,
    val color: AnnotationColor,

    /** The text the user selected; null for standalone COMMENT annotations. */
    val selectedText: String?,

    /** The user's written note or comment. */
    val note: String?,

    /** Readium Locator serialised to JSON. */
    val locatorJson: String,

    /** Display name of the chapter (e.g., "Chapter 12 — The Storm"). */
    val chapterTitle: String?,

    /** Relative href of the chapter spine item (e.g., "chapter12.xhtml"). */
    val chapterHref: String?,

    /** 1-indexed page number from Locator.locations.position; null if unknown. */
    val pageNumber: Int?,

    /** 0.0–1.0 total book progression; drives ORDER BY in the consolidated view. */
    val progressionInBook: Double,

    val createdAt: Long,
    val updatedAt: Long
)
