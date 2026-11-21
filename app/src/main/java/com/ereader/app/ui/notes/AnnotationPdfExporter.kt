package com.ereader.app.ui.notes

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.ereader.app.data.local.entity.AnnotationType
import com.ereader.app.domain.model.Annotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Renders a list of [Annotation]s to a PDF document (A4, 72-point coordinates)
 * and returns an [Intent] ready for [android.app.Activity.startActivity] via the
 * Android share sheet.
 *
 * Annotations are grouped by chapter, shown in reading order (ascending
 * [Annotation.progressionInBook]). Each annotation block displays:
 *  - Page number + annotation type in small gray text
 *  - Selected text in italic (HIGHLIGHT / NOTE only)
 *  - Written note in normal body text (NOTE / COMMENT only)
 *  - A 3pt colored left bar matching the highlight color
 *
 * Threading: the heavy work (PDF rendering + file I/O) runs on [Dispatchers.IO].
 *
 * FileProvider authority must match the one declared in AndroidManifest.xml:
 *   `${applicationId}.fileprovider`
 */
object AnnotationPdfExporter {

    // A4 page dimensions (72 dpi = 1 pt per unit)
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 50f

    suspend fun export(
        context: Context,
        bookTitle: String,
        annotations: List<Annotation>
    ): Intent = withContext(Dispatchers.IO) {

        // ── Paints ────────────────────────────────────────────────────────────
        val titlePaint = TextPaint().apply {
            color        = Color.BLACK
            textSize     = 20f
            typeface     = Typeface.DEFAULT_BOLD
            isAntiAlias  = true
        }
        val datePaint = TextPaint().apply {
            color       = Color.GRAY
            textSize    = 10f
            isAntiAlias = true
        }
        val chapterPaint = TextPaint().apply {
            color        = Color.rgb(30, 30, 30)
            textSize     = 13f
            typeface     = Typeface.DEFAULT_BOLD
            isAntiAlias  = true
        }
        val metaPaint = TextPaint().apply {
            color       = Color.GRAY
            textSize    = 9f
            isAntiAlias = true
        }
        val quotePaint = TextPaint().apply {
            color        = Color.rgb(50, 50, 50)
            textSize     = 11f
            typeface     = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            isAntiAlias  = true
        }
        val bodyPaint = TextPaint().apply {
            color       = Color.rgb(139, 0, 0)   // dark red — user's written note
            textSize    = 11f
            isAntiAlias = true
        }
        val dividerPaint = Paint().apply {
            color       = Color.LTGRAY
            strokeWidth = 0.5f
            style       = Paint.Style.STROKE
        }

        // ── Document setup ────────────────────────────────────────────────────
        val doc  = PdfDocument()
        var pgNo = 1
        var page = doc.startPage(
            PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pgNo).create()
        )
        var cv: Canvas = page.canvas
        var y  = MARGIN

        // ── Helper: finish current page and start a fresh one ─────────────────
        fun newPage() {
            doc.finishPage(page)
            pgNo++
            page = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pgNo).create()
            )
            cv = page.canvas
            y  = MARGIN
        }

        fun ensureRoom(neededPts: Float) {
            if (y + neededPts > PAGE_H - MARGIN) newPage()
        }

        val contentW = (PAGE_W - 2 * MARGIN).toInt()

        // ── Cover section ─────────────────────────────────────────────────────
        val titleLayout = makeLayout(bookTitle, titlePaint, contentW)
        ensureRoom(titleLayout.height + 30f)
        cv.save(); cv.translate(MARGIN, y); titleLayout.draw(cv); cv.restore()
        y += titleLayout.height + 4f

        val dateStr    = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())
        val dateLayout = makeLayout("Reading Notes — $dateStr", datePaint, contentW)
        cv.save(); cv.translate(MARGIN, y); dateLayout.draw(cv); cv.restore()
        y += dateLayout.height + 12f

        cv.drawLine(MARGIN, y, PAGE_W - MARGIN, y, dividerPaint)
        y += 20f

        // ── Annotations grouped by chapter ────────────────────────────────────
        val grouped: Map<String, List<Annotation>> = annotations
            .groupBy { it.chapterTitle?.ifBlank { null } ?: "General Notes" }

        for ((chapter, items) in grouped) {
            ensureRoom(chapterPaint.textSize + 24f)
            val chLayout = makeLayout(chapter, chapterPaint, contentW)
            cv.save(); cv.translate(MARGIN, y); chLayout.draw(cv); cv.restore()
            y += chLayout.height + 8f

            for (annotation in items) {
                // Build all text layouts first so we know the total height
                val metaText = buildMeta(annotation)
                val metaL    = makeLayout(metaText, metaPaint, contentW)

                val quoteL = annotation.selectedText?.let {
                    makeLayout("\"$it\"", quotePaint, contentW)
                }
                val noteL = annotation.note?.let {
                    makeLayout(it, bodyPaint, contentW)
                }

                val blockH = metaL.height + (quoteL?.height ?: 0) + (noteL?.height ?: 0) +
                        8f * listOfNotNull(quoteL, noteL).size + 18f

                ensureRoom(blockH)

                // Left color bar
                val barColor = runCatching {
                    Color.parseColor(annotation.color.hex)
                }.getOrDefault(Color.YELLOW)
                val barPaint = Paint().apply {
                    color       = barColor
                    strokeWidth = 3f
                    style       = Paint.Style.STROKE
                }
                cv.drawLine(MARGIN - 8f, y, MARGIN - 8f, y + blockH - 10f, barPaint)

                // Meta line (page + type)
                cv.save(); cv.translate(MARGIN, y); metaL.draw(cv); cv.restore()
                y += metaL.height + 4f

                // Selected quote
                quoteL?.let {
                    cv.save(); cv.translate(MARGIN, y); it.draw(cv); cv.restore()
                    y += it.height + 6f
                }

                // Written note
                noteL?.let {
                    cv.save(); cv.translate(MARGIN, y); it.draw(cv); cv.restore()
                    y += it.height + 6f
                }

                y += 12f  // gap between annotations
            }

            y += 8f  // extra gap between chapters
        }

        doc.finishPage(page)

        // ── Write to cache ────────────────────────────────────────────────────
        val safe  = bookTitle.replace("[^A-Za-z0-9\\-_ ]".toRegex(), "").trim()
        val dir   = File(context.cacheDir, "notes_export").also { it.mkdirs() }
        val file  = File(dir, "${safe}_notes.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()

        // ── Share intent ──────────────────────────────────────────────────────
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Intent(Intent.ACTION_SEND).apply {
            type     = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "$bookTitle — Reading Notes")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildMeta(a: Annotation): String {
        val parts = mutableListOf<String>()
        a.pageNumber?.let { parts += "Page $it" }
        parts += when (a.type) {
            AnnotationType.HIGHLIGHT -> "Highlight"
            AnnotationType.NOTE      -> "Note"
            AnnotationType.COMMENT   -> "Comment"
        }
        return parts.joinToString("  ·  ")
    }

    @Suppress("DEPRECATION")
    private fun makeLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return if (android.os.Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.15f)
                .build()
        } else {
            StaticLayout(
                text, paint, width, Layout.Alignment.ALIGN_NORMAL, 1.15f, 2f, false
            )
        }
    }
}
