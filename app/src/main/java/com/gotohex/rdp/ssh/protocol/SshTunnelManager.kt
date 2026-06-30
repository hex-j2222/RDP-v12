package com.gotohex.rdp.ssh.protocol

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.SharedPreferences
import com.gotohex.rdp.security.openEncryptedPrefs
import java.net.ServerSocket

/**
 * Credentials for setting up an SSH tunnel jump-host.
 */
// REM-2 FIX: Sensitive credentials stored as CharArray instead of String.
// See SshCredentials in SshClient.kt for the full rationale.
// Not a data class — see the comment on SshCredentials for why.
class SshTunnelCredentials(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: SshAuthMode,
    password: String = "",
    privateKeyPem: String = "",
    privateKeyPassphrase: String = "",
) {
    val password: CharArray            = password.toCharArray()
    val privateKeyPem: CharArray       = privateKeyPem.toCharArray()
    val privateKeyPassphrase: CharArray = privateKeyPassphrase.toCharArray()

    /** Zero all sensitive fields. Must be called after JSch has consumed the secrets. */
    fun zero() {
        password.fill('\u0000')
        privateKeyPem.fill('\u0000')
        privateKeyPassphrase.fill('\u0000')
    }
}

/**
 * Result of a successfully established SSH tunnel.
 *
 * @param localPort  The localhost port that forwards to [remoteHost]:[remotePort]
 *                   through the SSH server.
 * @param remoteHost The final destination host (as seen from the SSH server).
 * @param remotePort The final destination port.
 */
data class SshTunnelResult(
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/**
 * Manages an SSH port-forwarding (local tunnel) session via JSch.
 *
 * Usage:
 * ```
 * val mgr = SshTunnelManager(credentials)
 * val result = mgr.openTunnel(remoteHost = "10.0.0.5", remotePort = 3389)
 * // connect RDP/VNC to localhost:result.localPort
 * // ...
 * mgr.close()
 * ```
 *
 * The tunnel is kept alive by the underlying JSch [Session]; call [close]
 * when the RDP/VNC session ends to release the SSH connection and the
 * allocated local port.
 */
class SshTunnelManager(
    private val credentials: SshTunnelCredentials,
    // BUG-H FIX: Context needed to persist TOFU keys across app restarts.
    private val appContext: Context,
) {

    companion object {
        private const val TAG = "SshTunnelManager"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val PREFS_TOFU_TUNNEL = "hexrdp_tofu_tunnel"
        // BUG-L1 FIX: tofuPendingKeys removed from companion object.
        // Same issue as SshClient: static shared map caused cross-instance key
        // collisions on simultaneous SSH tunnel connections to the same host.
        // The map is now a per-instance field inside TofuHostKeyRepository.
    }

    // ── TOFU host-key repository ───────────────────────────────────────────

    private inner class TofuHostKeyRepository : com.jcraft.jsch.HostKeyRepository {

        // BUG-L1 FIX: per-instance pending-key map (was companion object static).
        private val pendingKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun mapKey(host: String): String {
            val bare = host.removePrefix("[").substringBefore("]")
            return if (':' in host) "$bare:${host.substringAfterLast(']').removePrefix(":")}"
            else "$bare:${credentials.port}"
        }

        // MED-R1 FIX: EncryptedSharedPreferences — AES-256-GCM authenticated on disk.
        private val cachedPrefs: SharedPreferences by lazy {
            appContext.openEncryptedPrefs(PREFS_TOFU_TUNNEL)
        }
        private fun prefs(): SharedPreferences = cachedPrefs

        override fun check(host: String, key: ByteArray): Int {
            val mk = mapKey(host)
            val incoming = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
            // BUG-H FIX: read from SharedPreferences instead of in-memory map
            val stored = prefs().getString(mk, null)
            return when {
                stored == null     -> { pendingKeys[mk] = incoming; com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED }
                stored == incoming -> com.jcraft.jsch.HostKeyRepository.OK
                else -> {
                    Log.w(TAG, "SSH tunnel host key CHANGED for $mk — possible MITM!")
                    com.jcraft.jsch.HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: com.jcraft.jsch.HostKey, ui: com.jcraft.jsch.UserInfo?) {
            val mk = mapKey(hostkey.host)
            // BUG-H FIX: persist to SharedPreferences so TOFU survives restarts
            // LIVE-HIGH-1 FIX: commit() guarantees write survives process death.
            pendingKeys.remove(mk)?.let { key ->
                prefs().edit().putString(mk, key).commit()
            }
        }

        override fun remove(host: String?, type: String?) {
            if (host != null) prefs().edit().remove(mapKey(host)).commit()
        }
        override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)
        override fun getKnownHostsRepositoryID() = "hexrdp-tunnel-tofu"
        override fun getHostKey()                = emptyArray<com.jcraft.jsch.HostKey>()
        override fun getHostKey(h: String?, t: String?) = emptyArray<com.jcraft.jsch.HostKey>()
    }

    // ── State ──────────────────────────────────────────────────────────────

    @Volatile private var session: Session? = null

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Establishes an SSH connection to the jump-host and sets up a local
     * port-forwarding rule:
     *
     *   localhost:[localPort]  →  SSH server  →  [remoteHost]:[remotePort]
     *
     * A free ephemeral local port is chosen automatically.
     *
     * @throws Exception if the SSH handshake or port-forwarding setup fails.
     */
    suspend fun openTunnel(remoteHost: String, remotePort: Int): SshTunnelResult =
        withContext(Dispatchers.IO) {

            // BUG-3 FIX: Close any previously open session before creating a new one.
            // Without this, calling openTunnel() twice (race condition or double-call)
            // silently orphans the old Session, leaving its socket and port-forwarding
            // rule active indefinitely — a resource and security leak.
            session?.let { old ->
                try { old.disconnect() } catch (_: Exception) {}
            }
            session = null

            val localPort = pickFreePort()

            val jsch = JSch()
            jsch.hostKeyRepository = TofuHostKeyRepository()

            if (credentials.authMode == SshAuthMode.PRIVATE_KEY && credentials.privateKeyPem.isNotEmpty()) {
                // REM-2 FIX: Encode CharArray → ByteArray without an intermediate String.
                // Byte arrays are zeroed immediately after addIdentity().
                val pemBuf   = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(credentials.privateKeyPem))
                val pemBytes = ByteArray(pemBuf.remaining()).also { pemBuf.get(it) }
                val passBytes: ByteArray? = if (credentials.privateKeyPassphrase.isNotEmpty()) {
                    val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(credentials.privateKeyPassphrase))
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } else null
                try {
                    jsch.addIdentity("hexrdp-tunnel-key", pemBytes, null, passBytes)
                } finally {
                    pemBytes.fill(0)
                    passBytes?.fill(0)
                    credentials.privateKeyPem.fill('\u0000')
                    credentials.privateKeyPassphrase.fill('\u0000')
                }
            }

            val sess = jsch.getSession(credentials.username, credentials.host, credentials.port)
            if (credentials.authMode == SshAuthMode.PASSWORD) {
                // REM-2 FIX: Convert CharArray to String only for JSch's String-only API,
                // then zero the array immediately after.
                sess.setPassword(String(credentials.password))
                credentials.password.fill('\u0000')
            }
            // FIX-HIGH-R3-2: Use "accept-new" — mirrors the fix applied to SshClient.
            // "yes" caused JSch to reject NOT_INCLUDED immediately without ever calling
            // TofuHostKeyRepository.add(), so no first connection to a new tunnel host
            // could succeed. "accept-new" is the correct TOFU mode: check() is called
            // first; only on NOT_INCLUDED does JSch call add(), which persists the key.
            // CHANGED keys are still rejected by the repository regardless of this mode.
            sess.setConfig("StrictHostKeyChecking", "accept-new")
            sess.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            sess.setConfig("ServerAliveInterval", "30")
            sess.setConfig("ServerAliveCountMax", "3")
            sess.timeout = CONNECT_TIMEOUT_MS

            sess.connect(CONNECT_TIMEOUT_MS)

            // Set up the local port forwarding rule.
            // JSch forwards traffic arriving on localhost:localPort to
            // remoteHost:remotePort through the authenticated SSH session.
            sess.setPortForwardingL(localPort, remoteHost, remotePort)

            session = sess

            // SEC-LOG FIX: Do NOT log hostnames, IPs, or ports — any app holding READ_LOGS
            // (or adb logcat) can harvest internal server addresses from logcat.
            // Log only the local port (ephemeral, not sensitive) and a one-way hash of
            // the jump-host identity so repeated connections to the same server are
            // correlatable in debug builds without exposing the actual address.
            val jumpId = Integer.toHexString(credentials.host.hashCode()).takeLast(6)
            Log.i(TAG, "SSH tunnel open: local:$localPort → remote (jump#$jumpId)")
            SshTunnelResult(localPort = localPort, remoteHost = remoteHost, remotePort = remotePort)
        }

    /**
     * Tears down the SSH session and releases the forwarded local port.
     * Safe to call multiple times.
     */
    fun close() {
        try {
            session?.disconnect()
            Log.i(TAG, "SSH tunnel closed")
        } catch (e: Exception) {
            // BUG-10 FIX: JSch disconnect errors can include hostname/port in their message.
            // Log class name only to avoid leaking server identity in logcat.
            Log.w(TAG, "Error closing SSH tunnel: ${e.javaClass.simpleName}")
        } finally {
            session = null
        }
    }

    val isConnected: Boolean
        get() = session?.isConnected == true

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns an unused TCP port number.
     *
     * FIX-TOCTOU: The previous approach (ServerSocket(0).use { } then return port)
     * still left a race window: after close(), another process could grab the port
     * before JSch called setPortForwardingL(). reuseAddress=true helped with
     * TIME_WAIT sockets but did NOT eliminate the race.
     *
     * We minimise the window by retrying on BindException (EADDRINUSE) up to 5
     * times with SO_REUSEADDR, which on Linux guarantees the port re-enters LISTEN
     * state faster.
     *
     * FIX-RETRY: The previous implementation used `repeat(5) { return port }`.
     * In Kotlin, `return` inside an inline lambda is a non-local return — it exits
     * `pickFreePort()` on the very first iteration, making the retry loop dead code.
     * Replaced with a `for` loop so `continue` correctly moves to the next attempt.
     *
     * The remaining window (close → JSch bind) is unavoidable without JSch API
     * changes; however it is extremely narrow (<1 ms) in practice on a loopback
     * interface with no other process scanning ephemeral ports.
     */
    private fun pickFreePort(): Int {
        for (attempt in 1..5) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                val port = ss.localPort
                ss.close()
                return port
            } catch (e: java.net.BindException) {
                Log.w(TAG, "pickFreePort attempt $attempt failed: ${e.message}")
                if (attempt == 5) throw e
            }
        }
        // The for-loop above either returns a port or throws BindException on the
        // 5th attempt. This line is unreachable, but the compiler requires a return
        // value. Throwing here is safer than the old `ServerSocket(0).use { }` pattern
        // which had the same TOCTOU race we fixed above.
        throw java.net.BindException("Unable to find a free local port after 5 attempts")
    }
}
