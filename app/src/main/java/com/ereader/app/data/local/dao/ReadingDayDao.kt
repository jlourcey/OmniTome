package com.ereader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Reading-day log behind the reading streak. */
@Dao
interface ReadingDayDao {

    /** Records that you read today. Safe to call on every page turn. */
    @Query("INSERT OR REPLACE INTO reading_days (day, lastAt) VALUES (:day, :at)")
    suspend fun markDay(day: String, at: Long = System.currentTimeMillis())

    /** Every day you read, newest first ("yyyy-MM-dd" sorts correctly as text). */
    @Query("SELECT day FROM reading_days ORDER BY day DESC")
    fun observeDays(): Flow<List<String>>

    /** Every day you read, as "yyyy-MM-dd" (one-shot, for syncing). */
    @Query("SELECT day FROM reading_days")
    suspend fun getAllDays(): List<String>

    /** Last time a page was turned on this phone (0 if never). */
    @Query("SELECT COALESCE(MAX(lastAt), 0) FROM reading_days")
    suspend fun lastReadAt(): Long

    /**
     * Adds days read on your other devices (from Calibre's reading log).
     * lastAt = 0 marks them as "came from elsewhere", so they don't make
     * this phone think it has something new to send.
     */
    @Query("INSERT OR IGNORE INTO reading_days (day, lastAt) VALUES (:day, 0)")
    suspend fun addSyncedDay(day: String)
}
