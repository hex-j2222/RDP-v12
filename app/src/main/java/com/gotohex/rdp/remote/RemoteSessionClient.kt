package com.gotohex.rdp.remote

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified state shared by every protocol implementation. RDP and VNC map
 * their richer internal states down into this; SSH (a text/terminal
 * protocol, not a framebuffer) also reports through here so the same
 * Activity/ViewModel scaffolding in [com.gotohex.rdp.ui.screens.RdpSessionActivity]
 * can drive any of the three.
 */
enum class RemoteSessionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, AUTH_FAILED, ERROR }

enum class RemoteMouseButton { LEFT, RIGHT, MIDDLE }

/** A rectangular framebuffer update, used by RDP and VNC. */
data class RemoteFrameUpdate(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val pixels: IntArray, val fullScreen: Boolean = false
)

/** A chunk of raw terminal output, used by SSH. */
data class TerminalOutput(val text: String)

/**
 * Common surface implemented by [com.gotohex.rdp.rdp.protocol.RdpClient],
 * [com.gotohex.rdp.vnc.protocol.VncClient], and
 * [com.gotohex.rdp.ssh.protocol.SshClient].
 *
 * Framebuffer-based protocols (RDP/VNC) emit [frameUpdates]; the terminal
 * protocol (SSH) emits [terminalOutput] instead and treats key events as
 * raw byte input rather than scan codes. Methods that don't apply to a given
 * protocol are simply no-ops (e.g. mouse events on an SSH session).
 */
interface RemoteSessionClient {
    val sessionState: StateFlow<RemoteSessionState>
    val frameUpdates: SharedFlow<RemoteFrameUpdate>
    val terminalOutput: SharedFlow<TerminalOutput>
    val error: SharedFlow<String>
    val latencyMs: Long

    suspend fun connect(): Boolean

    fun sendMouseMove(x: Int, y: Int)
    fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean)
    fun sendMouseScroll(x: Int, y: Int, delta: Int)
    fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean = false)
    fun sendCtrlAltDel()

    /** For terminal sessions: send raw text typed/pasted by the user. */
    fun sendText(text: String)

    fun disconnect()
}
