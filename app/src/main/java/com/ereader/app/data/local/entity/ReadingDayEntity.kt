package com.ereader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per calendar day on which you read (turned at least one page).
 * Drives the reading streak on the bookshelf.
 *
 * [day] is the phone's local date as "yyyy-MM-dd" (e.g. "2026-11-22"), so a
 * day always means your own midnight-to-midnight, wherever you are.
 */
@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    @PrimaryKey
    val day: String,
    /** Last time a page was turned on that day (epoch ms). */
    val lastAt: Long
)
