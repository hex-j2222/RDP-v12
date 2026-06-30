package com.gotohex.rdp

/**
 * BUG-L FIX: Lightweight event bus for onTrimMemory notifications.
 * Activities / ViewModels can register a lambda here; HexRDPApp calls
 * notifyTrim() when the OS signals memory pressure.
 */
object TrimMemoryBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    fun register(listener: (level: Int) -> Unit) { listeners.add(listener) }
    fun unregister(listener: (level: Int) -> Unit) { listeners.remove(listener) }
    fun notifyTrim(level: Int) { listeners.forEach { it(level) } }
}
