package com.ereader.app.domain.model

import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationEntity
import com.ereader.app.data.local.entity.AnnotationType

/**
 * The annotation domain model — what the UI and ViewModel work with.
 *
 * Mirrors [AnnotationEntity] field for field; kept separate so database schema
 * changes don't bleed into UI code.
 */
data class Annotation(
    val id: String,
    val bookId: String,
    val type: AnnotationType,
    val color: AnnotationColor,
    val selectedText: String?,
    val note: String?,
    val locatorJson: String,
    val chapterTitle: String?,
    val chapterHref: String?,
    val pageNumber: Int?,
    val progressionInBook: Double,
    val createdAt: Long,
    val updatedAt: Long
)

// ── Mappers ───────────────────────────────────────────────────────────────────

fun AnnotationEntity.toDomain() = Annotation(
    id                = id,
    bookId            = bookId,
    type              = type,
    color             = color,
    selectedText      = selectedText,
    note              = note,
    locatorJson       = locatorJson,
    chapterTitle      = chapterTitle,
    chapterHref       = chapterHref,
    pageNumber        = pageNumber,
    progressionInBook = progressionInBook,
    createdAt         = createdAt,
    updatedAt         = updatedAt
)

fun Annotation.toEntity() = AnnotationEntity(
    id                = id,
    bookId            = bookId,
    type              = type,
    color             = color,
    selectedText      = selectedText,
    note              = note,
    locatorJson       = locatorJson,
    chapterTitle      = chapterTitle,
    chapterHref       = chapterHref,
    pageNumber        = pageNumber,
    progressionInBook = progressionInBook,
    createdAt         = createdAt,
    updatedAt         = updatedAt
)
