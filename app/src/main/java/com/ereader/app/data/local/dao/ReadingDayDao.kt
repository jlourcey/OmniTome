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
}
