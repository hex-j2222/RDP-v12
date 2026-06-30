package com.gotohex.rdp.remote

import android.content.Context

import android.util.Log
import com.gotohex.rdp.ssh.protocol.SshTunnelCredentials
import com.gotohex.rdp.ssh.protocol.SshTunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A [RemoteSessionClient] decorator that transparently sets up an SSH
 * port-forwarding tunnel before the inner RDP/VNC client connects.
 *
 * Connection sequence on [connect]:
 *  1. Open SSH tunnel: localhost:[localPort] → [targetHost]:[targetPort]
 *     via the SSH jump-host in [tunnelCredentials].
 *  2. Build the inner client via [innerClientFactory](localPort).
 *  3. Call inner [RemoteSessionClient.connect].
 *
 * On [disconnect]: inner client is disconnected first, then the SSH tunnel
 * is torn down.
 */
class SshTunneledClient(
    private val tunnelCredentials: SshTunnelCredentials,
    private val targetHost: String,
    private val targetPort: Int,
    // BUG-H FIX: Context needed by SshTunnelManager to persist TOFU keys.
    private val appContext: Context,
    /** Factory receives the localhost forwarded port; returns the real RDP/VNC client. */
    private val innerClientFactory: (localPort: Int) -> RemoteSessionClient,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "SshTunneledClient"
    }

    // ── Flows ──────────────────────────────────────────────────────────────

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    // FIX-buffer: raised from 2 → 8 to match RdpRemoteAdapter and VncClient.
    // With capacity=2 the tunnel could drop frames under SSH latency bursts.
    private val _frameUpdates  = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override val latencyMs: Long
        get() = innerClient?.latencyMs ?: 0L

    // ── Internal state ─────────────────────────────────────────────────────

    // FIX-MED-R3-5: scope is now a var so it can be recreated in every connect() call.
    // A val CoroutineScope becomes permanently cancelled after the first disconnect()
    // or failed connect(). Any subsequent scope.launch{} on the same instance would
    // silently no-op, making reconnection impossible. Recreating the scope at the top
    // of connect() ensures a clean coroutine context on every attempt.
    @Volatile private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunnelManager = SshTunnelManager(tunnelCredentials, appContext)  // BUG-H FIX
    @Volatile private var innerClient: RemoteSessionClient? = null

    // ── RemoteSessionClient implementation ─────────────────────────────────

    override suspend fun connect(): Boolean {
        // FIX-MED-R3-5: always start with a fresh scope so re-connection after
        // disconnect() or a failed connect() works correctly.
        scope = newScope()
        return try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            // 1. Open SSH port-forward tunnel
            Log.i(TAG, "Opening SSH tunnel → ${tunnelCredentials.host}:${tunnelCredentials.port} for $targetHost:$targetPort")
            val tunnel = tunnelManager.openTunnel(targetHost, targetPort)
            Log.i(TAG, "Tunnel ready: localhost:${tunnel.localPort} → $targetHost:$targetPort")

            // 2. Build inner RDP/VNC client aimed at the loopback forwarded port
            val client = innerClientFactory(tunnel.localPort)
            innerClient = client

            // 3. Forward inner client's flows to our own so callers see one surface
            scope.launch { client.sessionState.collect { _sessionState.emit(it) } }
            scope.launch { client.frameUpdates.collect  { _frameUpdates.emit(it) } }
            scope.launch { client.terminalOutput.collect { _terminalOutput.emit(it) } }
            scope.launch { client.error.collect          { _error.emit(it) } }

            // 4. Connect inner client (goes to localhost:localPort via the tunnel)
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel/inner connect failed", e)
            _error.emit(e.message ?: "SSH tunnel failed")
            _sessionState.emit(RemoteSessionState.ERROR)
            // BUG-M1 FIX: innerClient was assigned before connect() threw but never
            // disconnected, leaving sockets/threads open indefinitely (resource leak).
            try { innerClient?.disconnect() } catch (_: Exception) {}
            tunnelManager.close()
            // BUG-X1 FIX: the 4 flow-forwarding coroutines launched above via scope.launch{}
            // remain active after tunnel/inner-connect failure unless the scope is explicitly
            // cancelled here. Without this, they keep collecting from a closed innerClient →
            // coroutine leak + silent exceptions.
            scope.cancel()
            false
        }
    }

    override fun disconnect() {
        scope.cancel()
        try { innerClient?.disconnect() } catch (e: Exception) { Log.w(TAG, "Inner disconnect error", e) }
        tunnelManager.close()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    // ── Input — delegate to inner client ──────────────────────────────────

    override fun sendMouseMove(x: Int, y: Int)                                             = innerClient?.sendMouseMove(x, y) ?: Unit
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean)  = innerClient?.sendMouseClick(x, y, button, down) ?: Unit
    override fun sendMouseScroll(x: Int, y: Int, delta: Int)                               = innerClient?.sendMouseScroll(x, y, delta) ?: Unit
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean)             = innerClient?.sendKeyEvent(scanCode, down, extended) ?: Unit
    override fun sendCtrlAltDel()                                                           = innerClient?.sendCtrlAltDel() ?: Unit
    override fun sendText(text: String)                                                     = innerClient?.sendText(text) ?: Unit
}
