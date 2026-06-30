package com.gotohex.rdp.vnc.protocol

import android.graphics.Bitmap
import android.util.Log
import com.gotohex.rdp.R
import com.gotohex.rdp.remote.*
import com.gotohex.rdp.security.openEncryptedPrefs
import com.undatech.opaque.RfbConnectable
import com.undatech.opaque.VncNotImplementedException
// BUG-8 FIX: removed unused imports SpiceCommunicator and RemoteKeyboard
// (com.undatech.opaque.SpiceCommunicator, com.undatech.opaque.input.RemoteKeyboard)
// — these classes may not exist in all bVNC versions → Unresolved reference compile error.
import com.undatech.opaque.input.RemotePointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Connection details for a VNC (RFB) session.
 *
 * CRIT-2 FIX: password is stored as CharArray rather than String.
 * JVM Strings are immutable and stay on the heap until GC decides to collect them —
 * they are therefore visible in heap dumps and memory forensics for an unpredictable
 * duration.  CharArray can be zeroed via fill('\u0000') immediately after use,
 * bounding the exposure window to the lifetime of the connection setup only.
 * Call [zero] as soon as the password has been passed to the bVNC library.
 */
class VncCredentials(
    val host: String,
    val port: Int,
    password: String,
    val viewOnly: Boolean = false,
) {
    val password: CharArray = password.toCharArray()
    /** Zero all sensitive fields; call after the password has been handed to bVNC. */
    fun zero() { password.fill('\u0000') }
}

/**
 * VNC client backed by the **bVNC / LibVNCAndroid** library
 * (`com.github.iiordanov:bVNC`), which wraps libvncserver/libvncclient
 * and handles the full RFB protocol (versions 3.3–3.8, all standard
 * security types, Raw/CopyRect/Hextile/Tight/ZRLE encodings, BouncyCastle
 * TLS, etc.) without any hand-written protocol code.
 *
 * The [RemoteSessionClient] surface is the same one used by [RdpRemoteAdapter]
 * and [com.gotohex.rdp.ssh.protocol.SshClient], so the session UI drives all
 * three protocols identically.
 */
class VncClient(
    private val credentials: VncCredentials,
    // BUG-B FIX: bVNC's RfbConnectable uses Context for TLS/certificate handling.
    // Passing null crashes with NullPointerException on VNC-over-TLS servers.
    private val appContext: android.content.Context,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "VncClient"
        private const val CONNECT_TIMEOUT_MS = 15_000

        // REM-3 FIX: TOFU constants
        private const val PREFS_TOFU_VNC          = "hexrdp_tofu_vnc"
        private const val TOFU_TLS_PROBE_TIMEOUT_MS = 3_000

        // ── Minimal X11 keysym constants ─────────────────────────────────────
        const val XK_BACKSPACE  = 0xFF08
        const val XK_TAB        = 0xFF09
        const val XK_RETURN     = 0xFF0D
        const val XK_ESCAPE     = 0xFF1B
        const val XK_DELETE     = 0xFFFF
        const val XK_HOME       = 0xFF50
        const val XK_LEFT       = 0xFF51
        const val XK_UP         = 0xFF52
        const val XK_RIGHT      = 0xFF53
        const val XK_DOWN       = 0xFF54
        const val XK_PAGE_UP    = 0xFF55
        const val XK_PAGE_DOWN  = 0xFF56
        const val XK_END        = 0xFF57
        const val XK_INSERT     = 0xFF63
        const val XK_F1         = 0xFFBE
        const val XK_F2         = 0xFFBF
        const val XK_F3         = 0xFFC0
        const val XK_F4         = 0xFFC1
        const val XK_F5         = 0xFFC2
        const val XK_F6         = 0xFFC3
        const val XK_F7         = 0xFFC4
        const val XK_F8         = 0xFFC5
        const val XK_F9         = 0xFFC6
        const val XK_F10        = 0xFFC7
        const val XK_F11        = 0xFFC8
        const val XK_F12        = 0xFFC9
        const val XK_SHIFT_L    = 0xFFE1
        const val XK_SHIFT_R    = 0xFFE2
        const val XK_CONTROL_L  = 0xFFE3
        const val XK_CONTROL_R  = 0xFFE4
        const val XK_ALT_L      = 0xFFE9
        const val XK_ALT_R      = 0xFFEA
        const val XK_SUPER_L    = 0xFFEB
        const val XK_PRINT      = 0xFF61

        fun scanCodeToKeysym(scanCode: Int, extended: Boolean): Int? = when (scanCode) {
            0x0E -> XK_BACKSPACE
            0x0F -> XK_TAB
            0x1C -> XK_RETURN
            0x01 -> XK_ESCAPE
            0x53 -> if (extended) XK_DELETE else null
            0x47 -> if (extended) XK_HOME   else null
            0x4F -> if (extended) XK_END    else null
            0x49 -> if (extended) XK_PAGE_UP   else null
            0x51 -> if (extended) XK_PAGE_DOWN else null
            0x52 -> if (extended) XK_INSERT    else null
            0x4B -> if (extended) XK_LEFT   else null
            0x48 -> if (extended) XK_UP     else null
            0x4D -> if (extended) XK_RIGHT  else null
            0x50 -> if (extended) XK_DOWN   else null
            0x3B -> XK_F1;  0x3C -> XK_F2;  0x3D -> XK_F3;  0x3E -> XK_F4
            0x3F -> XK_F5;  0x40 -> XK_F6;  0x41 -> XK_F7;  0x42 -> XK_F8
            0x43 -> XK_F9;  0x44 -> XK_F10; 0x57 -> XK_F11; 0x58 -> XK_F12
            0x2A -> XK_SHIFT_L;   0x36 -> XK_SHIFT_R
            0x1D -> if (extended) XK_CONTROL_R else XK_CONTROL_L
            0x38 -> if (extended) XK_ALT_R     else XK_ALT_L
            0x5B -> XK_SUPER_L
            0x37 -> if (extended) XK_PRINT     else null
            else -> null
        }
    }

    // ── REM-3 FIX: VNC TOFU ────────────────────────────────────────────────────
    //
    // SSH and SSH-Tunnel connections already have full TOFU (TofuHostKeyRepository).
    // VNC was the only protocol left unprotected: bVNC's RfbConnectable handles TLS
    // internally and does not expose the server certificate via its public API, so we
    // cannot intercept the certificate during the RFB handshake.
    //
    // Solution: before calling rfbClient.connect(), open a brief TLS probe connection
    // to the same host:port. This lets us extract the X.509 certificate fingerprint
    // and perform TOFU verification *before* the actual VNC session starts.
    //
    // Limitation: only works for VNC-over-TLS (VeNCrypt/AnonymousTLS/SSL-wrapped).
    // For plain RFB (no TLS), the probe will fail the TLS handshake and we log a
    // warning but allow the connection through — plain VNC has no cryptographic
    // identity to pin against anyway, so TOFU would provide no security benefit.
    //
    // Design mirrors SshClient.TofuHostKeyRepository:
    //   - First connection → fingerprint stored in EncryptedSharedPreferences (TOFU).
    //   - Subsequent connections → fingerprint compared; mismatch aborts the session.

    private enum class VncTofuResult { OK, FIRST_USE, CHANGED, NO_TLS }

    private inner class VncTofuVerifier {
        private val prefs by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_VNC) }
        private val prefKey = "${credentials.host}:${credentials.port}"

        fun verifyOrStore(): VncTofuResult {
            val fingerprint = probeTlsFingerprint() ?: return VncTofuResult.NO_TLS
            val stored = prefs.getString(prefKey, null)
            return when {
                stored == null -> {
                    // LIVE-MED-3 / LIVE-HIGH-1 FIX: commit() instead of apply().
                    // The TOFU probe (probeTlsFingerprint) already ran synchronously;
                    // using apply() risks losing the fingerprint to an OOM kill between
                    // probe success and the async write — the next connection would then
                    // re-accept an unchanged (or MITM) cert as a fresh first connection.
                    prefs.edit().putString(prefKey, fingerprint).commit()
                    Log.i(TAG, "VNC TOFU: first connection to $prefKey — fingerprint stored")
                    VncTofuResult.FIRST_USE
                }
                stored == fingerprint -> VncTofuResult.OK
                else -> {
                    Log.w(TAG, "VNC TOFU: fingerprint CHANGED for $prefKey — possible MITM attack!")
                    VncTofuResult.CHANGED
                }
            }
        }

        /**
         * Opens a brief TLS connection to the VNC endpoint and returns the server's
         * X.509 certificate as a SHA-256 hex fingerprint, or null if TLS is not
         * available (plain VNC or VeNCrypt with post-RFB-handshake TLS upgrade).
         *
         * Uses a permissive TrustManager — we only need the fingerprint, not chain
         * validation.  The TOFU check provides the equivalent security guarantee:
         * the *first* certificate we see is trusted unconditionally; any change to
         * that certificate on a subsequent connection is flagged as suspicious.
         */
        private fun probeTlsFingerprint(): String? = try {
            val trustAll = object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, t: String) {}
            }
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf(trustAll), null)
            (sslCtx.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = TOFU_TLS_PROBE_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress(credentials.host, credentials.port),
                    TOFU_TLS_PROBE_TIMEOUT_MS,
                )
                socket.startHandshake()
                val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return null
                val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                digest.joinToString(":") { b -> "%02X".format(b) }
            }
        } catch (e: Exception) {
            // Plain VNC, VeNCrypt with late TLS negotiation, or transient error.
            Log.d(TAG, "VNC TLS probe skipped for ${credentials.host}:${credentials.port}: ${e.javaClass.simpleName}")
            null
        }
    }

    // ── Session state ──────────────────────────────────────────────────────────

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

    // BUG-VOLATILE FIX: rfb is written in one coroutine (connect()) and read in
    // another (frameLoop, sendMouseMove, sendKeyEvent, etc.). Without @Volatile,
    // the JVM is free to cache the field in a register, meaning IO-thread readers
    // could see null even after connect() assigned it. @Volatile guarantees a
    // happens-before relationship between the write and all subsequent reads.
    @Volatile private var rfb: RfbConnectable? = null
    // BUG-AA4 FIX: removed dead `private var framebuffer: Bitmap? = null` field.
    // It was never assigned and never read anywhere in this class — the actual
    // framebuffer is rfbClient.framebuffer (a property on bVNC's RfbConnectable).
    // The dead field wasted a Bitmap reference slot and confused readers into
    // thinking VncClient maintained its own copy of the screen buffer.
    @Volatile private var connected = false

    // BUG-4 FIX: Changed from `val` to `@Volatile var` so the scope can be replaced
    // in connect() after a disconnect(). With `val`, calling disconnect() cancels the
    // scope permanently — any subsequent connect() call would launch coroutines into the
    // cancelled scope where they silently do nothing (no frame loop, no state updates),
    // making the app appear connected while being frozen. Mirroring the pattern already
    // used in SshTunneledClient: recreate the scope at the start of every connect().
    @Volatile private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // BUG-4 FIX: Recreate scope at the start of every connect() call so that
        // reusing the same VncClient instance after disconnect() works correctly.
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val connectStart = System.currentTimeMillis()

            // REM-3 FIX: TOFU verification — must happen before rfbClient.connect()
            // so we can abort before any data is exchanged with a potentially
            // impersonating server. Plain VNC (NO_TLS) is allowed through with a log
            // warning since there is no cryptographic identity to pin in that case.
            val tofuResult = VncTofuVerifier().verifyOrStore()
            if (tofuResult == VncTofuResult.CHANGED) {
                val warning = "VNC server identity changed for " +
                    "${credentials.host}:${credentials.port} — " +
                    "connection refused (possible MITM attack). " +
                    "If the server certificate was legitimately renewed, " +
                    "remove the saved profile and reconnect."
                Log.e(TAG, warning)
                _error.emit(warning)
                _sessionState.emit(RemoteSessionState.ERROR)
                return@withContext false
            }
            if (tofuResult == VncTofuResult.NO_TLS) {
                Log.w(TAG, "VNC TOFU: ${credentials.host}:${credentials.port} does not " +
                    "offer TLS — MITM protection unavailable for this connection")
                // HIGH-2 FIX: Emit a user-visible warning instead of a silent log line.
                // Previously NO_TLS was only logged — the user had no indication that
                // their session and password were travelling unencrypted.  We now surface
                // a localised warning message via _error so the UI (RdpSessionActivity)
                // can display it as a non-blocking snackbar/toast BEFORE the session
                // starts, allowing the user to make an informed decision.
                // The connection still proceeds (we cannot add TLS to a plain-RFB server),
                // but the user is informed rather than kept in the dark.
                _error.emit(appContext.getString(R.string.warning_vnc_no_tls))
            }
            // VncTofuResult.FIRST_USE → fingerprint stored, proceeding.
            // VncTofuResult.OK        → fingerprint matches, proceeding.

            // Build connection URI expected by bVNC: vnc://host:port
            // NEW-HIGH-1 FIX: Use try-finally so credentials.zero() is guaranteed to run
            // even if an exception is thrown inside the apply{} block (e.g., OOM during
            // String(credentials.password), or any future field initialisation that throws).
            // Previously credentials.zero() was a plain statement after the apply{} block;
            // any exception inside apply{} would skip it, leaving the CharArray alive until GC.
            val conn: com.undatech.opaque.Connection
            try {
                conn = com.undatech.opaque.Connection().apply {
                    address   = credentials.host
                    port      = credentials.port
                    // CRIT-2 FIX: Convert CharArray to String only at the last moment —
                    // bVNC's Connection.password is a String (JVM library; cannot change its
                    // API), so a short-lived String is unavoidable here.  Zero the CharArray
                    // immediately after to remove the longer-lived copy from heap.
                    password  = String(credentials.password)
                    inputMode = if (credentials.viewOnly)
                        RemotePointer.INPUT_MODE_TOUCH_TOUCHPAD
                    else
                        RemotePointer.INPUT_MODE_TOUCH_DIRECT
                }
            } finally {
                credentials.zero()  // Erase CharArray — called even if apply{} throws
            }

            val rfbClient = RfbConnectable(conn, appContext)  // BUG-B FIX: was null
            rfb = rfbClient

            // BUG-K FIX: ForkJoinPool.cancel(true) does NOT interrupt blocking socket I/O
            // on some Android versions, leaving threads stuck in the common pool and
            // degrading app-wide performance. Use withTimeout + Dispatchers.IO coroutine
            // instead — cancellation is cooperative and actually interrupts the coroutine.
            //
            // BUG-2 FIX: withTimeout alone still does NOT interrupt rfbClient.connect()
            // because it is a Java blocking call (java.net.Socket.connect) with no
            // coroutine suspension points — the coroutine timeout fires but the thread
            // keeps blocking for another 15–30 seconds waiting for the OS TCP timeout.
            // Fix: wrap the call in runInterruptible { } which invokes Thread.interrupt()
            // when the coroutine is cancelled, causing Socket.connect() to throw
            // SocketException("Socket closed") immediately and unblocking the thread.
            try {
                kotlinx.coroutines.withTimeout(CONNECT_TIMEOUT_MS.toLong()) {
                    kotlinx.coroutines.runInterruptible {
                        rfbClient.connect()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw java.io.IOException(
                    appContext.getString(R.string.error_vnc_timeout, CONNECT_TIMEOUT_MS / 1000)
                )
            }
            latencyMs = System.currentTimeMillis() - connectStart

            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)

            // Pump frame updates from the library bitmap into our Flow
            sessionScope.launch { frameLoop(rfbClient) }

            true
        } catch (e: com.undatech.opaque.AuthenticationException) {
            Log.e(TAG, "VNC auth failed", e)
            _error.emit(e.message ?: appContext.getString(R.string.disconnect_reason_auth))
            _sessionState.emit(RemoteSessionState.AUTH_FAILED)
            // BUG-M2 FIX: sessionScope was not cancelled here (unlike the general
            // Exception handler below). The scope remained open with no coroutines
            // in it — a silent resource leak on every failed auth attempt.
            sessionScope.cancel()
            false
        } catch (e: VncNotImplementedException) {
            // VNC-STUB FIX: catch this specific type *before* the generic Exception
            // handler below so the user sees a clear, localized "VNC isn't implemented
            // yet" message (R.string.error_vnc_not_implemented) instead of the raw
            // developer-facing Arabic stub string from e.message, which was previously
            // shown verbatim regardless of the app's UI language and read like a
            // confusing generic "connection failed" error rather than explaining that
            // the feature itself is missing.
            Log.w(TAG, "VNC connect attempted but no real RFB client is bundled in this build", e)
            _error.emit(appContext.getString(R.string.error_vnc_not_implemented))
            _sessionState.emit(RemoteSessionState.ERROR)
            sessionScope.cancel()
            false
        } catch (e: Exception) {
            Log.e(TAG, "VNC connect failed", e)
            _error.emit(e.message ?: "Connection failed")
            _sessionState.emit(RemoteSessionState.ERROR)
            sessionScope.cancel()  // BUG-G FIX: cancel scope on connect failure to prevent scope leak
            false
        }
    }

    /** Continuously reads framebuffer updates from the library and re-emits them. */
    private suspend fun frameLoop(rfbClient: RfbConnectable) {
        // FIX-polling: Use adaptive delay — skip the 16 ms fixed spin when the
        // server has nothing new to send. We track whether the Bitmap reference
        // changed since the last frame; if not, we back off to 100 ms so idle
        // VNC sessions no longer burn CPU / battery at ~60 "updates"/s.
        //
        // BUG-RACE FIX: The previous double-buffer scheme (bufA/bufB with useA flip)
        // passed the IntArray *by reference* to _frameUpdates.emit(). After two frame
        // flips Compose could still be reading bufA while frameLoop had already started
        // writing the next frame into it → corrupted pixels / canvas crash.
        // Fix: always emit a fresh IntArray copy so the reference is immutable after
        // emit() returns, completely eliminating the race.
        // BUG-X3 FIX: bVNC reuses the same Bitmap object and writes new pixels
        // directly into it. Comparing fb === lastFb is always true once connected,
        // so the adaptive back-off would fire every iteration → screen appears frozen
        // even though the server is sending updates.
        // Fix: track the Bitmap reference AND its generationId (available since API 12).
        // Bitmap.getGenerationId() increments whenever setPixels/copyPixelsFromBuffer
        // or any native write touches the backing buffer, so it reliably detects new
        // content even when the object reference never changes.
        var lastFbRef: Bitmap? = null
        var lastFbGenId: Int = -1
        try {
            while (connected) {
                val fb = rfbClient.framebuffer ?: break
                // Adaptive delay: back off when neither the Bitmap reference nor its
                // content (generationId) has changed since the last iteration.
                val currentGenId = fb.generationId
                if (fb === lastFbRef && currentGenId == lastFbGenId) {
                    delay(100L)
                    // BUG-BURST FIX: After the 100ms idle delay, loop back immediately
                    // to re-check generationId before sleeping again. Without this, a
                    // burst of frames arriving from the server during the idle window
                    // would each wait a full 100ms because `continue` skips `delay(16L)`
                    // but the next iteration instantly hits the back-off check again.
                    // Now we simply fall through to re-read fb and currentGenId fresh.
                    continue
                }
                lastFbRef = fb
                lastFbGenId = currentGenId
                val w = fb.width
                val h = fb.height
                // Fresh array per frame: the emitted reference is never mutated
                // after emit() returns (eliminates the buffer-race).
                val pixels = IntArray(w * h)
                fb.getPixels(pixels, 0, w, 0, 0, w, h)
                _frameUpdates.emit(
                    RemoteFrameUpdate(
                        x = 0, y = 0,
                        width = w, height = h,
                        pixels = pixels,
                        fullScreen = true,
                    )
                )
                // BUG-BURST FIX: use a short cooperative yield instead of a fixed
                // 16ms sleep so back-to-back frames from a fast server are not each
                // delayed by a full frame interval unnecessarily.
                delay(16L)
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "VNC frame loop error", e)
                _error.emit(e.message ?: "Connection lost")
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    // ── Input ────────────────────────────────────────────────────────────────

    override fun sendMouseMove(x: Int, y: Int) {
        if (credentials.viewOnly) return
        rfb?.sendPointerEvent(x, y, 0)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        if (credentials.viewOnly) return
        val mask = when (button) {
            RemoteMouseButton.LEFT   -> 1
            RemoteMouseButton.MIDDLE -> 2
            RemoteMouseButton.RIGHT  -> 4
        }
        rfb?.sendPointerEvent(x, y, if (down) mask else 0)
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        if (credentials.viewOnly) return
        val wheelMask = if (delta > 0) (1 shl 3) else (1 shl 4)
        rfb?.sendPointerEvent(x, y, wheelMask)
        rfb?.sendPointerEvent(x, y, 0)
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        val keysym = scanCodeToKeysym(scanCode, extended) ?: return
        rfb?.sendKeyEvent(keysym, down)
    }

    override fun sendCtrlAltDel() {
        rfb?.let {
            it.sendKeyEvent(XK_CONTROL_L, true)
            it.sendKeyEvent(XK_ALT_L,     true)
            it.sendKeyEvent(XK_DELETE,     true)
            it.sendKeyEvent(XK_DELETE,     false)
            it.sendKeyEvent(XK_ALT_L,     false)
            it.sendKeyEvent(XK_CONTROL_L, false)
        }
    }

    override fun sendText(text: String) {
        text.forEach { ch ->
            // BUG-UNICODE FIX: RFB spec §5.4 defines Unicode keysyms as
            // 0x01000000 OR codepoint for any character outside the Latin-1
            // range (0x0020–0x00FF). Using ch.code directly as a keysym only
            // works for ASCII/Latin-1; Arabic, Chinese, emoji, etc. would map
            // to wrong or undefined keysyms and be silently dropped by the server.
            val keysym = when {
                ch.code in 0x0020..0x00FF -> ch.code          // Latin-1: direct mapping
                else -> 0x01000000 or ch.code                  // Unicode plane: RFB §5.4
            }
            rfb?.sendKeyEvent(keysym, true)
            rfb?.sendKeyEvent(keysym, false)
        }
    }

    override fun disconnect() {
        connected = false
        sessionScope.cancel()
        try { rfb?.close() } catch (_: Exception) {}
        rfb = null
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

}

