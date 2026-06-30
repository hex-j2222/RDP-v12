package com.gotohex.rdp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Feature-06 · سجل الاتصالات
 * Persists every connection attempt so the user can review history,
 * see how long each session lasted, what caused a disconnect, and
 * quickly reconnect to any past host with a single tap.
 */
@Entity(tableName = "connection_logs")
data class ConnectionLog(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** The profile that was used (null for Quick Connect sessions). */
    val profileId: String?,

    /** Display name shown in the history list. */
    val profileName: String,

    /** Host / IP at the time of connection (stored separately so it remains
     *  accurate even if the profile is later edited or deleted). */
    val host: String,
    val port: Int,
    val protocolType: ProtocolType = ProtocolType.RDP,

    /** Epoch millis when the connection was initiated. */
    val connectedAt: Long = System.currentTimeMillis(),

    /** Epoch millis when the session ended (0 if still active). */
    val disconnectedAt: Long = 0L,

    /**
     * Human-readable reason for the disconnect.
     * null  → session is still active / ended cleanly by user action
     * ""    → user disconnected intentionally
     * other → error message from the remote client
     */
    val disconnectReason: String? = null,

    /** True when the session actually reached CONNECTED state at least once. */
    val wasSuccessful: Boolean = false
) {
    /** Session duration in milliseconds, or 0 while still active. */
    val durationMs: Long
        get() = if (disconnectedAt > 0L) disconnectedAt - connectedAt else 0L
}
