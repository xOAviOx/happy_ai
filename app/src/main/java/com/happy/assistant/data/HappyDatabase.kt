package com.happy.assistant.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [LogEntry::class], version = 1, exportSchema = false)
abstract class HappyDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}
