package com.example.chrono

import android.app.Application
import androidx.room.Room
import com.example.chrono.data.ChronoDatabase

class ChronoApp : Application() {
    val database: ChronoDatabase by lazy { Room.databaseBuilder(this, ChronoDatabase::class.java, "chrono.db").build() }
}
