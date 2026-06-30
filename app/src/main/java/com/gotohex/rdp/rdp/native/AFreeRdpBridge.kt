package com.gotohex.rdp.rdp.native

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thin JNI bridge to the native aFreeRDP library (see
 * `app/src/main/cpp/hexrdp_jni.c` and `app/src/main/cpp/SETUP.md`).
 *
 * The native `.so` is only present once you've followed SETUP.md and built
 * locally with the FreeRDP submodule checked out (or via CI — see
 * .github/workflows/main.yml), since it cannot be produced in this sandboxed
 * environment (no internet access / NDK toolchain). [isAvailable] safely
 * detects whether the library loaded.
 *
 * DOC FIX: this comment previously claimed the app "transparently falls back
 * to the pure-Kotlin RDP implementation" via a class called
 * `com.gotohex.rdp.rdp.protocol.RdpSessionFactory` — neither of those exists.
 * There is no Kotlin-only RDP implementation anymore (see the class doc on
 * [com.gotohex.rdp.rdp.protocol.RdpRemoteAdapter]: "the pure-Kotlin
 * hand-written RDP parser has been removed; FreeRDP is the only supported
 * backend"). The real consumer of [isAvailable] is
 * [com.gotohex.rdp.rdp.protocol.RdpRemoteAdapter.connect], which — when
 * `isAvailable` is false — emits a user-facing error explaining that the
 * native library must be built (see SETUP.md) rather than connecting through
 * any fallback engine.
 */
class AFreeRdpBridge {

    companion object {
        private const val TAG = "AFreeRdpBridge"

        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("hexrdp_jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "Native aFreeRDP library not present — RDP connections will fail until " +
                    "it is built. See app/src/main/cpp/SETUP.md.")
                false
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native aFreeRDP library", e)
                false
            }
        }
    }

    // Callback channels — bridged from native code into Kotlin Flows that
    // RdpRemoteAdapter (built by RemoteSessionFactory) collects and re-emits
    // through the common RemoteSessionClient surface (frameUpdates / error /
    // sessionState) shared with the VNC and SSH clients.
    val frames = MutableSharedFlow<NativeFrame>(extraBufferCapacity = 4)
    val stateChanges = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    data class NativeFrame(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val pixels: IntArray, val fullScreen: Boolean
    )

    private var handle: Long = 0L

    fun init() {
        handle = nativeInit()
    }

    fun connect(
        host: String, port: Int, username: String, password: String, domain: String,
        width: Int, height: Int, useNla: Boolean,
        gatewayEnabled: Boolean, gatewayHost: String, gatewayPort: Int,
        gatewayUsername: String, gatewayPassword: String, gatewayDomain: String,
        colorDepth: Int = 32,          // FIX #3: was never passed to native layer
        compressionQuality: Int = 75,  // FIX #4: was never passed to native layer
        performanceMode: Int = 3,      // FIX #8: was silently discarded (UNUSED_PARAMETER)
        ignoreCert: Boolean = false,   // BUG-4 FIX: was always TRUE in C → MITM vulnerability
    ): Boolean {
        if (handle == 0L) return false
        return nativeConnect(
            handle, host, port, username, password, domain, width, height, useNla,
            gatewayEnabled, gatewayHost, gatewayPort, gatewayUsername, gatewayPassword, gatewayDomain,
            colorDepth, compressionQuality, performanceMode, ignoreCert,
        )
    }

    fun sendMouse(x: Int, y: Int, flags: Int) {
        if (handle != 0L) nativeSendMouse(handle, x, y, flags)
    }

    fun sendKey(scanCode: Int, down: Boolean, extended: Boolean) {
        if (handle != 0L) nativeSendKey(handle, scanCode, down, extended)
    }

    fun disconnect() {
        if (handle != 0L) nativeDisconnect(handle)
    }

    fun free() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    // ── Called from native code (hexrdp_jni.c) ──────────────────────────────
    @Suppress("unused")
    fun onNativeFrame(x: Int, y: Int, width: Int, height: Int, pixels: IntArray, fullScreen: Boolean) {
        frames.tryEmit(NativeFrame(x, y, width, height, pixels, fullScreen))
    }

    @Suppress("unused")
    fun onNativeState(state: Int) {
        stateChanges.tryEmit(state)
    }

    @Suppress("unused")
    fun onNativeError(message: String) {
        errors.tryEmit(message)
    }

    // ── Native methods (implemented in hexrdp_jni.c) ─────────────────────────
    private external fun nativeInit(): Long
    private external fun nativeConnect(
        handle: Long, host: String, port: Int, username: String, password: String, domain: String,
        width: Int, height: Int, useNla: Boolean,
        gatewayEnabled: Boolean, gatewayHost: String, gatewayPort: Int,
        gatewayUsername: String, gatewayPassword: String, gatewayDomain: String,
        colorDepth: Int, compressionQuality: Int, performanceMode: Int,
        ignoreCert: Boolean,  // BUG-4 FIX
    ): Boolean
    private external fun nativeSendMouse(handle: Long, x: Int, y: Int, flags: Int)
    private external fun nativeSendKey(handle: Long, scanCode: Int, down: Boolean, extended: Boolean)
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeFree(handle: Long)
}
