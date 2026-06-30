package com.gotohex.rdp.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تشفير كلمات المرور باستخدام Android Keystore + AES-GCM.
 * المفتاح محفوظ داخل Keystore ولا يخرج منه أبداً.
 */
object CryptoHelper {

    private const val KEY_ALIAS      = "hexrdp_profile_key"
    private const val PROVIDER       = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LEN    = 128
    private const val IV_LEN         = 12

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .also { it.init(spec) }.generateKey()
    }

    fun encrypt(plaintext: String): String {
        // Empty string = optional field not configured (gatewayPassword, sshPrivateKey, etc.).
        // Symmetric with decrypt("") → "". Returning "" here is NOT a security issue because
        // there is no credential to protect. The real risk was a NON-BLANK value silently
        // falling back to plaintext on Keystore failure — that path now throws SecurityException
        // (see the catch block below), so the empty-string fast-path is safe to keep.
        //
        // BUG-2 FIX: Use isEmpty() instead of isBlank(). isBlank() returns true for strings
        // that contain only whitespace (spaces, tabs, etc.), causing a password like " "
        // (a single space) to be returned as-is without AES-GCM encryption. isEmpty() only
        // skips truly empty strings — intentional unconfigured fields — which is the correct
        // semantic here. Any non-empty credential, including whitespace-only ones, must be
        // encrypted before storage.
        if (plaintext.isEmpty()) return plaintext
        return try {
            val key    = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv         = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined   = ByteArray(IV_LEN + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, IV_LEN)
            System.arraycopy(ciphertext, 0, combined, IV_LEN, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoHelper", "encrypt failed", e)
            // FIX #1: Never return plaintext on failure — that silently stores
            // passwords in clear text when Keystore is unavailable (common after
            // factory reset or on old ARMv7 devices). Throw so the caller can
            // surface an error and abort the save operation instead.
            throw SecurityException("Encryption failed: cannot save credentials safely", e)
        }
    }

    /** بيانات قديمة غير مشفرة تُعاد كما هي (توافق مع الوراء). */
    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return encoded
        return try {
            // BUG-2 FIX: If the stored value was saved before encryption was introduced
            // (plain-text password from an older build), Base64.decode() throws
            // IllegalArgumentException which was previously swallowed and re-thrown as
            // SecurityException — causing every upgrade from an old build to lose all
            // saved credentials. We catch it here and return the original string so
            // existing plain-text passwords continue to work after an upgrade.
            val combined = try {
                Base64.decode(encoded, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                // CRIT-3 FIX: Returning plaintext silently is a security defect — the caller
                // has no way to distinguish a successfully decrypted value from a legacy
                // plain-text one, so it proceeds with an unencrypted credential as if it were
                // valid. Throw instead; the caller (RdpProfileRepository) will surface a
                // "please re-enter your password" prompt, which is the correct UX.
                throw SecurityException(
                    "Stored value is not encrypted — please edit this profile and re-enter your password."
                )
            }
            // CRIT-3 FIX: A ciphertext shorter than the IV is either corrupt or a forged
            // value injected into the database. Returning it as-is would let an attacker
            // plant an arbitrary short Base64 string and have the app use it as a credential.
            if (combined.size <= IV_LEN) throw SecurityException(
                "Ciphertext too short to be valid — possible data corruption or tampering."
            )
            val iv         = combined.copyOfRange(0, IV_LEN)
            val ciphertext = combined.copyOfRange(IV_LEN, combined.size)
            val key    = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoHelper", "decrypt failed", e)
            // BUG-DECRYPT FIX: Returning "" on failure silently propagates an empty
            // password to the server, producing a generic "Auth failed" error with no
            // user-visible explanation. This happens after reinstall, backup restore, or
            // any event that destroys the Android Keystore key.
            // Fix: throw a SecurityException so the caller (RdpProfileRepository) can
            // catch it and surface a meaningful "Credentials lost — please re-enter your
            // password" dialog, exactly as encrypt() already does on its failure path.
            throw SecurityException(
                "Decryption failed: saved credentials may be corrupted or the Keystore key was lost. " +
                "Please re-enter your password for this profile.", e
            )
        }
    }
}
