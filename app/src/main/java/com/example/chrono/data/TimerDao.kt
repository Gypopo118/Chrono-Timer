package com.example.chrono.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers ORDER BY durationMs ASC, id ASC") fun observeAll(): Flow<List<Timer>>
    @Query("SELECT * FROM timers WHERE id = :id") suspend fun get(id: Long): Timer?
    @Query("SELECT * FROM timers WHERE running = 1 OR alarming = 1") suspend fun running(): List<Timer>
    @Upsert suspend fun put(timer: Timer)
    @Query("DELETE FROM timers WHERE id = :id") suspend fun delete(id: Long)
}
