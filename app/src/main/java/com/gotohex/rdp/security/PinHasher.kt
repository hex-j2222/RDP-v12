package com.gotohex.rdp.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * HIGH-1 FIX: PBKDF2-based PIN hashing.
 *
 * Previously, the PIN was stored as:
 *   CryptoHelper.encrypt(rawPin)
 * so obtaining the Keystore key (rooted device, Keystore vulnerability) immediately
 * revealed the PIN in plaintext — no computation required.
 *
 * Now the PIN is processed through PBKDF2-HMAC-SHA256 with a random 16-byte salt
 * and 120 000 iterations before being wrapped in CryptoHelper.encrypt().  Even with
 * the Keystore key, an attacker must run 120 000 SHA-256 operations per guess.
 *
 * Storage format (stored INSIDE the CryptoHelper ciphertext):
 *   "v2:<pinLen>:<base64Salt>:<base64Hash>"
 *
 * The stored PIN length lets AppLockScreen show the correct number of dot-slots
 * (HIGH-2 FIX) without knowing the PIN itself.
 *
 * Legacy detection: if the decrypted value does NOT start with "v2:", it is the raw
 * PIN from the old format.  The verifier falls back to a direct comparison so
 * existing users are not locked out, then the caller should re-hash and save the PIN.
 */
object PinHasher {

    private const val VERSION          = "v2"
    private const val ALGORITHM        = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS       = 120_000
    private const val KEY_LENGTH_BITS  = 256
    private const val SALT_BYTES       = 16

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Derives a PBKDF2 hash of [pin] and returns the storage payload
     * (the string that will be encrypted by CryptoHelper before persisting).
     */
    fun hash(pin: String): String {
        require(pin.isNotBlank()) { "PIN must not be blank" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt)
        return "$VERSION:${pin.length}:" +
            "${Base64.encodeToString(salt, Base64.NO_WRAP)}:" +
            Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Returns true if [pin] matches [storedPayload] (the decrypted storage value).
     *
     * Supports both the new v2 PBKDF2 format and the legacy raw-PIN format.
     */
    fun verify(pin: String, storedPayload: String): Boolean {
        return try {
            if (storedPayload.startsWith("$VERSION:")) {
                verifyV2(pin, storedPayload)
            } else {
                // Legacy: storedPayload IS the raw PIN.
                // Use constant-time comparison to prevent timing attacks.
                MessageDigest.isEqual(
                    pin.toByteArray(Charsets.UTF_8),
                    storedPayload.toByteArray(Charsets.UTF_8)
                )
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if [storedPayload] is in the v2 format.
     * Used by the caller to decide whether to upgrade the stored hash.
     */
    fun isLegacy(storedPayload: String): Boolean = !storedPayload.startsWith("$VERSION:")

    /**
     * Extracts the PIN length embedded in a v2 payload.
     * Returns 6 for legacy payloads (safe upper-bound for dot-slot display).
     *
     * HIGH-2 FIX: makes AppLockScreen show the right number of dots.
     */
    fun extractLength(storedPayload: String): Int {
        if (!storedPayload.startsWith("$VERSION:")) return 6
        return storedPayload.split(":").getOrNull(1)?.toIntOrNull()?.coerceIn(4, 6) ?: 6
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun verifyV2(pin: String, payload: String): Boolean {
        val parts = payload.split(":")
        if (parts.size != 4) return false
        val salt         = Base64.decode(parts[2], Base64.NO_WRAP)
        val expectedHash = Base64.decode(parts[3], Base64.NO_WRAP)
        val actualHash   = pbkdf2(pin.toCharArray(), salt)
        return MessageDigest.isEqual(actualHash, expectedHash)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray {
        val spec    = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()   // zero the password copy held inside PBEKeySpec
        }
    }
}
