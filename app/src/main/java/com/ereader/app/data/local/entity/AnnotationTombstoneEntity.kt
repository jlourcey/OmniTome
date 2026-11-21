package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "tombstone" remembers that a note or highlight was DELETED on the phone.
 *
 * Why do we need this?
 *   Once an annotation row is deleted it is gone, so the next sync would have
 *   no way of knowing it ever existed — and the copy already sent to Calibre
 *   would live on forever. Instead, deleting an annotation leaves this small
 *   record behind. The next sync tells Calibre "this one was removed", then
 *   the tombstone is cleared.
 *
 * [id] is the same id the annotation had (Calibre knows it as the "uuid").
 */
@Entity(
    tableName = "annotation_tombstones",
    indices   = [Index("bookId")]
)
data class AnnotationTombstoneEntity(
    @PrimaryKey
    val id: String,
    val bookId: String,
    val deletedAt: Long
)
