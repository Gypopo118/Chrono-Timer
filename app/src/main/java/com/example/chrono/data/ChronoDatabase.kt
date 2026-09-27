package com.example.chrono.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Timer::class], version = 1, exportSchema = false)
abstract class ChronoDatabase : RoomDatabase() {
    abstract fun timers(): TimerDao
}
