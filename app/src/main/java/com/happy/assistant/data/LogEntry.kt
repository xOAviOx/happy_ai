package com.happy.assistant.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One line in Happy's on-device diary.
 *
 * Section 12 of the spec: nothing in the service may silently swallow an
 * exception. Everything interesting - and every failure - lands here so it can
 * be read back on the phone itself from [com.happy.assistant.ui.LogActivity].
 */
@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    /** Stage latency in ms, when this entry is a timing measurement. */
    val durationMs: Long? = null,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry): Long

    @Query("SELECT * FROM log_entries ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<LogEntry>>

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int

    @Query("DELETE FROM log_entries")
    suspend fun clear()

    /** Keeps the diary bounded; called after every insert. */
    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
