package com.gotohex.rdp.data.repository

import com.gotohex.rdp.data.db.ConnectionLogDao
import com.gotohex.rdp.data.model.ConnectionLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionLogRepository @Inject constructor(
    private val dao: ConnectionLogDao
) {
    /** Live stream of the 50 most-recent log entries (newest first). */
    fun getRecentLogs(): Flow<List<ConnectionLog>> = dao.getRecentLogs()

    /** Insert a new in-progress log entry and return its id. */
    suspend fun start(log: ConnectionLog): String {
        dao.insert(log)
        return log.id
    }

    /** Close a log entry with the final result when the session ends. */
    suspend fun finish(
        id: String,
        disconnectReason: String?,
        wasSuccessful: Boolean
    ) {
        dao.finalise(
            id = id,
            disconnectedAt = System.currentTimeMillis(),
            reason = disconnectReason,
            wasSuccessful = wasSuccessful
        )
    }

    /** Called on app startup to tidy up logs from previous crashes. */
    suspend fun closeOrphanedLogs() = dao.closeOrphanedLogs()

    /** Remove logs older than 90 days AND cap the table at 500 rows to prevent unbounded growth. */
    suspend fun purgeOld() {
        val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        dao.purgeOlderThan(ninetyDaysAgo)
        dao.trimExcess(keep = 500)  // MED-3 FIX: hard cap regardless of age
    }

    suspend fun clearAll() = dao.clearAll()
}
