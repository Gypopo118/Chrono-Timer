package com.example.chrono.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timers")
data class Timer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMs: Long,
    val remainingMs: Long = durationMs,
    val endsAtMs: Long? = null,
    val running: Boolean = false,
    val alarming: Boolean = false
)
