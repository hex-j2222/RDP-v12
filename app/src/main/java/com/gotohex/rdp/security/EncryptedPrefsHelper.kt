package com.gotohex.rdp.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * MED-R1 FIX / LOW-R1 FIX — Helper for EncryptedSharedPreferences.
 *
 * Plain SharedPreferences can be read AND modified by any root-privileged
 * process or tool (e.g. `sqlite3`, `cat`, `sed`).  Replacing them with
 * EncryptedSharedPreferences (AES-256-SIV keys, AES-256-GCM values) stores
 * all data authenticated and encrypted under an Android Keystore-backed key.
 * Modification is detected at read-time via GCM tag verification.
 *
 * **Migration strategy**: if an existing plain-text prefs file is present,
 * [openEncryptedPrefs] will fail to decrypt the unencrypted values and throw.
 * In that case we delete the stale plain file and start fresh.
 *   - TOFU fingerprints: next connection is treated as first-use — acceptable.
 *   - PIN lockout: counter resets — acceptable; integrity is protected going forward.
 *
 * @param name  The SharedPreferences file name (same as used by Context.getSharedPreferences).
 */
fun Context.openEncryptedPrefs(name: String): SharedPreferences {
    val masterKey = MasterKey.Builder(this)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    fun createEncPrefs() = EncryptedSharedPreferences.create(
        this, name, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    return try {
        createEncPrefs()
    } catch (e: Exception) {
        // The existing file is plain-text (incompatible) or corrupted.
        // Delete it and create fresh encrypted prefs.
        Log.w("EncryptedPrefs",
            "Could not open EncryptedSharedPreferences for '$name' — deleting stale file and retrying. " +
            "Cause: ${e.javaClass.simpleName}: ${e.message}")
        deleteSharedPreferences(name)  // API 24+ (same as security-crypto minimum)
        createEncPrefs()
    }
}
