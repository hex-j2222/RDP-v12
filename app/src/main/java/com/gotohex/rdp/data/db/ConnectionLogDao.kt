package com.gotohex.rdp.data.db

import androidx.room.*
import com.gotohex.rdp.data.model.ConnectionLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionLogDao {

    /** Stream of the 50 most-recent log entries, newest first. */
    @Query("SELECT * FROM connection_logs ORDER BY connectedAt DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<ConnectionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ConnectionLog)

    @Query(
        """UPDATE connection_logs
           SET disconnectedAt = :disconnectedAt,
               disconnectReason = :reason,
               wasSuccessful = :wasSuccessful
           WHERE id = :id"""
    )
    suspend fun finalise(
        id: String,
        disconnectedAt: Long,
        reason: String?,
        wasSuccessful: Boolean
    )

    /** Mark any logs that are still "active" (disconnectedAt == 0) as crashed.
     *  Called on app start to clean up logs from a previous crash. */
    @Query(
        """UPDATE connection_logs
           SET disconnectedAt = :now, disconnectReason = 'App restarted'
           WHERE disconnectedAt = 0"""
    )
    suspend fun closeOrphanedLogs(now: Long = System.currentTimeMillis())

    /** Remove all entries older than [cutoff] epoch-millis. */
    @Query("DELETE FROM connection_logs WHERE connectedAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long)

    /**
     * MED-3 FIX: Trim the table to the most recent [keep] rows.
     * Prevents the log from growing indefinitely on long-lived devices,
     * which would build an intelligence-grade connection history.
     */
    @Query("DELETE FROM connection_logs WHERE id NOT IN (SELECT id FROM connection_logs ORDER BY connectedAt DESC LIMIT :keep)")
    suspend fun trimExcess(keep: Int = 500)

    @Query("DELETE FROM connection_logs")
    suspend fun clearAll()
}

// NOTE: MIGRATION_2_3 has been consolidated into HexRdpDatabase.kt to eliminate
// the duplicate top-level declaration that caused a compile error and the
// schema mismatch (wrong columns) that caused a Room crash on v2→v3 upgrade.
// The authoritative migration now lives alongside the other migrations so
// AppModule.kt can import all of them from a single file.
