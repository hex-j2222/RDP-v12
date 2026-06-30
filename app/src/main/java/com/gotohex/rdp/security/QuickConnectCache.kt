package com.gotohex.rdp.security

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FIX #1 (Security): Replaces passing Quick Connect credentials as plain-text
 * Intent extras — which are visible via `adb shell dumpsys activity activities`
 * and appear in bug reports / battery stats dumps.
 *
 * Caller stores credentials here under a one-time UUID token, passes only
 * the token through the Intent, and the receiver calls [take] to retrieve
 * and atomically remove them. Credentials are never written to disk.
 *
 * BUG-TTL FIX: The original implementation had no expiry — if the target
 * Activity crashed before calling take(), the credential object (including
 * the password) remained in the singleton's ConcurrentHashMap for the entire
 * process lifetime. Fix: wrap each entry with a creation timestamp and evict
 * stale entries (older than TTL_MS) on every [put] call.  [take] also
 * rejects expired entries so a token that somehow survives past the window
 * cannot be replayed.
 */
object QuickConnectCache {

    /** Credentials are valid for 30 seconds — ample time for the Activity to start. */
    private const val TTL_MS = 30_000L

    /**
     * MED-1 FIX: Store the password as CharArray instead of String.
     * Strings are immutable on the JVM and stay in memory until GC — on a rooted
     * device /proc/PID/mem or a heap dump can expose them.  CharArray can be
     * explicitly zeroed immediately after use, shrinking the exposure window to
     * the actual call lifetime rather than the GC lifetime.
     */
    private data class Entry(
        val host: String,
        val port: Int,
        val username: String,
        private val passwordChars: CharArray,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        /** Returns an ephemeral String copy; zero [passwordChars] via [clear] ASAP. */
        fun getPassword(): String = String(passwordChars)

        /**
         * MED-R2 FIX: Returns a copy of the raw CharArray so the caller can
         * construct QuickConnectParams without creating a transient String.
         * Caller must call QuickConnectParams.clear() as soon as possible.
         */
        fun getPasswordChars(): CharArray = passwordChars.copyOf()

        /** Overwrites the password buffer with null bytes. */
        fun clear() { passwordChars.fill('\u0000') }

        // CharArray doesn't implement equals/hashCode by value — override so
        // data-class comparisons still work correctly.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return host == other.host && port == other.port &&
                username == other.username &&
                passwordChars.contentEquals(other.passwordChars) &&
                createdAt == other.createdAt
        }
        override fun hashCode(): Int {
            var r = host.hashCode()
            r = 31 * r + port
            r = 31 * r + username.hashCode()
            r = 31 * r + passwordChars.contentHashCode()
            r = 31 * r + createdAt.hashCode()
            return r
        }
    }

    private val store = ConcurrentHashMap<String, Entry>()

    /**
     * Stores credentials and returns a one-time token suitable for Intent extras.
     * The token is a random UUID — unguessable and single-use.
     * Evicts any previously stale entries as a side-effect.
     */
    fun put(host: String, port: Int, username: String, password: String): String {
        evictExpired()
        val token = UUID.randomUUID().toString()
        store[token] = Entry(host, port, username, password.toCharArray())
        return token
    }

    /**
     * Retrieves AND removes credentials associated with [token].
     * The in-memory password buffer is zeroed immediately after the
     * QuickConnectParams object is built (MED-1 FIX).
     */
    fun take(token: String): QuickConnectParams? {
        val entry = store.remove(token) ?: return null
        return try {
            if (System.currentTimeMillis() - entry.createdAt > TTL_MS) return null   // expired
            QuickConnectParams(entry.host, entry.port, entry.username, entry.getPasswordChars())
        } finally {
            entry.clear()  // MED-1 FIX: zero password bytes regardless of outcome
        }
    }

    /** Removes all entries older than [TTL_MS]. Called automatically by [put]. */
    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - TTL_MS
        store.entries.removeIf { e ->
            if (e.value.createdAt < cutoff) {
                e.value.clear()  // MED-1 FIX: zero password before eviction
                true
            } else false
        }
    }
}

/**
 * MED-R2 FIX: Credential bundle returned by [QuickConnectCache.take].
 *
 * [password] is a [CharArray] instead of a String so the caller can zero it
 * (via [clear]) immediately after creating the connection — shrinking the
 * window during which a JVM heap dump could expose the credential.
 *
 * Strings on the JVM are immutable; once created they live until GC.
 * A CharArray can be overwritten in-place: the bytes disappear instantly.
 *
 * Usage pattern (RdpSessionActivity):
 *   val creds = QuickConnectCache.take(token)
 *   val pwd   = creds?.let { String(it.password) } ?: ""
 *   creds?.clear()   ← zero immediately after extracting
 *   viewModel.loadAndConnectQuick(... password = pwd ...)
 */
data class QuickConnectParams(
    val host: String,
    val port: Int,
    val username: String,
    val password: CharArray,   // MED-R2 FIX: was String
) {
    /** Zero the password buffer immediately after the last use. */
    fun clear() = password.fill('\u0000')

    // CharArray doesn't implement equals/hashCode by value — override so
    // data-class semantics remain correct.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuickConnectParams) return false
        return host == other.host && port == other.port &&
            username == other.username && password.contentEquals(other.password)
    }
    override fun hashCode(): Int {
        var r = host.hashCode()
        r = 31 * r + port
        r = 31 * r + username.hashCode()
        r = 31 * r + password.contentHashCode()
        return r
    }
    /** Never expose password in logs or toString(). */
    override fun toString() = "QuickConnectParams(host=$host, port=$port, username=$username, password=[REDACTED])"
}
