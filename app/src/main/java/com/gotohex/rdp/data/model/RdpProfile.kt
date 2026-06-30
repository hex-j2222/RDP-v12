package com.gotohex.rdp.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * The remote protocol a profile connects with. Determines which fields in
 * [RdpProfile] are relevant and which client implementation handles the
 * session (see com.gotohex.rdp.remote.RemoteSessionClient).
 */
enum class ProtocolType(val defaultPort: Int, val label: String) {
    RDP(3389, "RDP"),
    VNC(5900, "VNC"),
    SSH(22, "SSH");

    companion object {
        fun fromName(name: String): ProtocolType =
            entries.firstOrNull { it.name == name } ?: RDP
    }
}

/** SSH authentication method. */
enum class SshAuthType { PASSWORD, PRIVATE_KEY }

/**
 * RDP Connection Profile stored in local database.
 * Supports multiple simultaneous sessions, and — despite the historical name
 * kept for migration simplicity — now supports RDP, VNC, and SSH connections
 * via [protocolType].
 */
@Entity(tableName = "rdp_profiles")
data class RdpProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,                          // Custom display name e.g. "Work Server"
    val protocolType: ProtocolType = ProtocolType.RDP,
    val host: String,                          // IP or hostname
    val port: Int = 3389,                      // Default RDP port
    val username: String,
    val password: String,                      // Stored encrypted

    // ── RDP-specific ───────────────────────────────────────────────────────
    val domain: String = "",                   // Windows domain
    val width: Int = 0,                        // 0 = auto detect
    val height: Int = 0,                       // 0 = auto detect
    val colorDepth: Int = 32,                  // 16, 24, or 32 bit
    val enableSound: Boolean = false,
    val enableClipboard: Boolean = true,
    val enableDriveRedirect: Boolean = false,
    val useNla: Boolean = true,                // NLA authentication
    // BUG-3 FIX: ignoreCert was hard-wired to false after the MITM-vuln fix, which broke
    // connections to home/office RDP servers with self-signed certificates.
    // Adding an explicit opt-in field lets users accept a specific server's cert
    // without opening a blanket MITM hole.
    val acceptSelfSignedCertificate: Boolean = false,
    // BUG-N2 FIX: Default was RdpPerformance.LAN (= 3). Because addProfile() guards
    // with `performanceFlags != 0`, and LAN = 3 ≠ 0, getRecommendedPerformance()
    // was NEVER called — every new profile silently got LAN regardless of the actual
    // network. Changing the default to AUTO (= 4) means new profiles reach the
    // `else` branch in addProfile() and receive the proper network-aware recommendation.
    // Existing DB profiles keep their stored value (which is ≠ AUTO) and are unaffected.
    val performanceFlags: Int = RdpPerformance.AUTO,

    // ── RD Gateway (RDP only) ──────────────────────────────────────────────
    val gatewayEnabled: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",
    val gatewayDomain: String = "",

    // ── VNC-specific ────────────────────────────────────────────────────────
    // VNC classically authenticates with a session password only (no
    // username). `password` above is reused as that VNC password.
    val vncViewOnly: Boolean = false,

    // ── SSH-specific ────────────────────────────────────────────────────────
    val sshAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshPrivateKey: String = "",            // PEM contents, if PRIVATE_KEY
    val sshPrivateKeyPassphrase: String = "",

    // ── SSH Tunnel (for RDP / VNC) ──────────────────────────────────────────
    // Forwards the RDP/VNC connection through an SSH tunnel, eliminating the
    // need for an RD Gateway in many environments.
    val sshTunnelEnabled: Boolean = false,
    val sshTunnelHost: String = "",           // SSH jump-host IP or hostname
    val sshTunnelPort: Int = 22,              // SSH server port (usually 22)
    val sshTunnelUsername: String = "",
    val sshTunnelAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshTunnelPassword: String = "",
    val sshTunnelPrivateKey: String = "",
    val sshTunnelPrivateKeyPassphrase: String = "",

    // ── Wake-on-LAN ─────────────────────────────────────────────────────────────
    // Sends a UDP "Magic Packet" to wake the target machine before connecting.
    val wolEnabled: Boolean = false,
    val wolMacAddress: String = "",            // e.g. "AA:BB:CC:DD:EE:FF"
    val wolBroadcastAddress: String = "255.255.255.255", // subnet broadcast

    // BUG-M FIX: Store only the filename (relative to filesDir/screenshots/), NOT an
    // absolute path. Absolute paths like /storage/emulated/0/... break after backup
    // restore to a different device or factory reset. Callers must resolve to
    // context.filesDir/screenshots/<lastScreenshotFilename> at display time.
    val lastScreenshotFilename: String? = null,  // filename only, e.g. "preview_<id>.png"
    // BUG-DEPRECATED-COL FIX: The @Deprecated field must carry @ColumnInfo so Room
    // knows the column is nullable with no default — this prevents a schema mismatch
    // crash on devices that somehow skipped a migration. Also suppresses the Kotlin
    // deprecation warning at the Room-generated accessor site.
    @Deprecated("Replaced by lastScreenshotFilename; kept for Room migration only")
    @androidx.room.ColumnInfo(defaultValue = "")
    @Suppress("DEPRECATION")
    val lastScreenshotPath: String? = null,
    val lastConnected: Long = 0L,
    val isConnected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

object RdpPerformance {
    const val LOW_BANDWIDTH = 0    // 2G / very slow
    const val MEDIUM = 1           // 3G
    const val WIFI = 2             // WiFi
    const val LAN = 3              // LAN / Fast
    const val AUTO = 4             // Auto-detect and adapt
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class RdpCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val domain: String,
    val useNla: Boolean = true,
    val acceptSelfSignedCertificate: Boolean = false,  // BUG-3 FIX
    val gatewayEnabled: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",
    val gatewayDomain: String = "",
)

data class RdpSessionInfo(
    val profileId: String,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val bandwidthKbps: Int = 0
)
