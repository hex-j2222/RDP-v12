package com.gotohex.rdp.rdp.protocol

import com.gotohex.rdp.data.model.RdpCredentials
import com.gotohex.rdp.data.model.RdpPerformance
import com.gotohex.rdp.remote.*
import com.gotohex.rdp.rdp.native.AFreeRdpBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Adapts RDP connectivity to the common [RemoteSessionClient] surface.
 *
 * Relies exclusively on the native **aFreeRDP** backend ([AFreeRdpBridge])
 * built via the NDK/CMake pipeline (see app/src/main/cpp/CMakeLists.txt and
 * the CI workflow). The pure-Kotlin hand-written RDP parser has been removed;
 * FreeRDP is the only supported backend.
 *
 * If the native `.so` has not been built yet (e.g. first-time local checkout
 * without running CI), [connect] will return false and emit an error message
 * directing the developer to build the native library.
 */
class RdpRemoteAdapter(
    private val credentials: RdpCredentials,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val performanceMode: Int = RdpPerformance.AUTO,    // FIX #8: was @Suppress("UNUSED_PARAMETER")
    private val colorDepth: Int = 32,                          // FIX #3: wired through from profile.colorDepth
    private val compressionQuality: Int = 75,                  // FIX #4: wired through from AppSettings
) : RemoteSessionClient {

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 1)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    private var nativeBridge: AFreeRdpBridge? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(): Boolean {
        if (!AFreeRdpBridge.isAvailable) {
            _error.emit(
                "Native FreeRDP library (libhexrdp_jni.so) not found. " +
                "Build it by running the CI pipeline or following app/src/main/cpp/SETUP.md."
            )
            _sessionState.emit(RemoteSessionState.ERROR)
            return false
        }

        val bridge = AFreeRdpBridge().also { nativeBridge = it }
        bridge.init()

        adapterScope.launch {
            bridge.frames.collect { f ->
                _frameUpdates.emit(
                    RemoteFrameUpdate(f.x, f.y, f.width, f.height, f.pixels, f.fullScreen)
                )
            }
        }
        adapterScope.launch {
            bridge.errors.collect { msg -> _error.emit(msg) }
        }
        // FIX #2: Subscribe to stateChanges from the native bridge so that
        // autoReconnect on DISCONNECTED actually fires for RDP sessions.
        // Native state codes: 0 = disconnected, 1 = connecting, 2 = connected,
        // 3 = auth_failed (CRIT-NEW-1 FIX: added to stop reconnect on wrong password).
        adapterScope.launch {
            bridge.stateChanges.collect { nativeState ->
                when (nativeState) {
                    0 -> _sessionState.emit(RemoteSessionState.DISCONNECTED)
                    1 -> _sessionState.emit(RemoteSessionState.CONNECTING)
                    2 -> _sessionState.emit(RemoteSessionState.CONNECTED)
                    // CRIT-NEW-1 FIX: native code emits state=3 when FreeRDP returns a
                    // credential-specific error (ERRCONNECT_LOGON_FAILURE and similar).
                    // Without this mapping, auth failures arrive as ERROR, triggering
                    // the auto-reconnect loop and retrying 3 times with the same wrong
                    // password — potentially locking the user's account on the server.
                    3 -> _sessionState.emit(RemoteSessionState.AUTH_FAILED)
                }
            }
        }

        _sessionState.value = RemoteSessionState.CONNECTING
        val ok = withContext(Dispatchers.IO) {
            bridge.connect(
                host             = credentials.host,
                port             = credentials.port,
                username         = credentials.username,
                password         = credentials.password,
                domain           = credentials.domain,
                width            = displayWidth,
                height           = displayHeight,
                useNla           = credentials.useNla,
                gatewayEnabled   = credentials.gatewayEnabled,
                gatewayHost      = credentials.gatewayHost,
                gatewayPort      = credentials.gatewayPort,
                gatewayUsername  = credentials.gatewayUsername,
                gatewayPassword  = credentials.gatewayPassword,
                gatewayDomain    = credentials.gatewayDomain,
                colorDepth       = colorDepth,          // FIX #3
                compressionQuality = compressionQuality, // FIX #4
                performanceMode  = performanceMode,     // FIX #8
                ignoreCert       = credentials.acceptSelfSignedCertificate,  // BUG-3 FIX: was always using default (false)
            )
        }
        // BUG-D FIX: bridge.stateChanges already emits CONNECTED (native state 2).
        // Emitting it again causes duplicate state transitions → double log entries.
        // Only handle the ERROR case that stateChanges does not emit on bridge.connect() failure.
        if (!ok) {
            _sessionState.value = RemoteSessionState.ERROR
            // BUG-Y2 FIX: the 3 coroutines launched above (frames / errors / stateChanges
            // collectors) remain active when bridge.connect() returns false, because adapterScope
            // is only cancelled in disconnect(). If connect() fails, disconnect() is never called
            // by the caller → the 3 coroutines collect indefinitely from a failed bridge →
            // coroutine leak + silent resource waste. Cancel here to match the successful-path
            // cleanup already done in disconnect().
            adapterScope.cancel()
            nativeBridge?.free()
            nativeBridge = null
        }
        return ok
    }

    override fun sendMouseMove(x: Int, y: Int) {
        nativeBridge?.sendMouse(x, y, MOUSE_FLAG_MOVE)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        nativeBridge?.sendMouse(x, y, mouseClickFlags(button, down))
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        nativeBridge?.sendMouse(
            x, y,
            if (delta > 0) MOUSE_FLAG_WHEEL_UP else MOUSE_FLAG_WHEEL_DOWN
        )
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        nativeBridge?.sendKey(scanCode, down, extended)
    }

    override fun sendCtrlAltDel() {
        nativeBridge?.let {
            sendKeyEvent(0x1D, true)
            sendKeyEvent(0x38, true)
            sendKeyEvent(0x53, true, extended = true)
            sendKeyEvent(0x53, false, extended = true)
            sendKeyEvent(0x38, false)
            sendKeyEvent(0x1D, false)
        }
    }

    override fun sendText(text: String) {
        // RDP has no terminal text channel; input goes through key events only.
    }

    override fun disconnect() {
        nativeBridge?.let { it.disconnect(); it.free() }
        nativeBridge = null
        adapterScope.cancel()
        // FIX #2: Emit DISCONNECTED so the autoReconnect logic in
        // RdpSessionActivity can react when the user (or the OS) closes
        // the session — previously this state was never emitted for RDP.
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private companion object {
        const val MOUSE_FLAG_MOVE      = 0x0800
        const val MOUSE_FLAG_BUTTON1   = 0x1000 // left
        const val MOUSE_FLAG_BUTTON2   = 0x2000 // right
        const val MOUSE_FLAG_BUTTON3   = 0x4000 // middle
        const val MOUSE_FLAG_DOWN      = 0x8000
        // CRIT-NEW-2 FIX: MS-RDPBCGR §2.2.8.1.1.3.1.1.3 — the WheelRotationMask
        // occupies bits 0–7 of the flags word and encodes the rotation magnitude.
        // Standard Windows wheel delta is 120 per click.  The previous values
        // (0x0200 and 0x0300) left the rotation field at 0, so most RDP servers
        // treated every wheel event as "no movement" — scroll up did nothing and
        // scroll down behaviour was undefined.
        const val MOUSE_FLAG_WHEEL_UP   = 0x0200 or 120   // PTRFLAGS_WHEEL          | 120 = 0x0278
        const val MOUSE_FLAG_WHEEL_DOWN = 0x0300 or 120   // PTRFLAGS_WHEEL_NEGATIVE | 120 = 0x0378

        fun mouseClickFlags(button: RemoteMouseButton, down: Boolean): Int {
            val base = when (button) {
                RemoteMouseButton.LEFT   -> MOUSE_FLAG_BUTTON1
                RemoteMouseButton.RIGHT  -> MOUSE_FLAG_BUTTON2
                RemoteMouseButton.MIDDLE -> MOUSE_FLAG_BUTTON3
            }
            return if (down) base or MOUSE_FLAG_DOWN else base
        }
    }
}
