package com.gotohex.rdp.data.repository

import com.gotohex.rdp.data.db.RdpProfileDao
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.security.CryptoHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RdpProfileRepository @Inject constructor(
    private val dao: RdpProfileDao
) {
    // ── helpers ──────────────────────────────────────────────────────────────

    private fun RdpProfile.withEncryptedSecrets(): RdpProfile = copy(
        password               = CryptoHelper.encrypt(password),
        gatewayPassword        = CryptoHelper.encrypt(gatewayPassword),
        sshPrivateKey          = CryptoHelper.encrypt(sshPrivateKey),
        sshPrivateKeyPassphrase = CryptoHelper.encrypt(sshPrivateKeyPassphrase),
        // FIX S1: SSH Tunnel credentials were stored in plaintext — encrypt them too
        sshTunnelPassword             = CryptoHelper.encrypt(sshTunnelPassword),
        sshTunnelPrivateKey           = CryptoHelper.encrypt(sshTunnelPrivateKey),
        sshTunnelPrivateKeyPassphrase = CryptoHelper.encrypt(sshTunnelPrivateKeyPassphrase),
    )

    private fun RdpProfile.withDecryptedSecrets(): RdpProfile = try {
        copy(
            password               = CryptoHelper.decrypt(password),
            gatewayPassword        = CryptoHelper.decrypt(gatewayPassword),
            sshPrivateKey          = CryptoHelper.decrypt(sshPrivateKey),
            sshPrivateKeyPassphrase = CryptoHelper.decrypt(sshPrivateKeyPassphrase),
            // FIX S1: Decrypt SSH Tunnel credentials on read
            sshTunnelPassword             = CryptoHelper.decrypt(sshTunnelPassword),
            sshTunnelPrivateKey           = CryptoHelper.decrypt(sshTunnelPrivateKey),
            sshTunnelPrivateKeyPassphrase = CryptoHelper.decrypt(sshTunnelPrivateKeyPassphrase),
        )
    } catch (e: SecurityException) {
        // BUG-DECRYPT FIX: CryptoHelper.decrypt() now throws SecurityException when the
        // Keystore key is lost (after reinstall / backup restore / factory reset) rather
        // than returning "" silently. Re-throw with context so the ViewModel can catch it
        // and show a "Please re-enter your password" prompt instead of a generic auth error.
        android.util.Log.e("RdpProfileRepository", "Failed to decrypt secrets for profile ${this.id}", e)
        // MED-1 FIX: Profile names may contain sensitive infrastructure details (client name,
        // server role, company name). Logging or surfacing them in exception messages leaks
        // that information to logcat and crash-reporting tools. Use the opaque UUID instead.
        throw SecurityException("Credentials for profile ID ${this.id} could not be decrypted. " +
            "Please edit the profile and re-enter your password.", e)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getAllProfiles(): Flow<List<RdpProfile>> =
        dao.getAllProfiles().map { list ->
            // BUG-ALLPROFILES FIX: withDecryptedSecrets() throws SecurityException when the
            // Android Keystore key is lost (after reinstall / backup restore / factory reset).
            // Previously this exception escaped the Flow into viewModelScope.launch with no
            // handler, crashing the app whenever the home screen opened.
            // Fix: catch per-profile and return a sanitised copy (empty password) instead of
            // crashing. The profile remains visible in the list with a broken-lock indicator
            // so the user can re-enter credentials via the edit dialog.
            list.mapNotNull { profile ->
                try {
                    profile.withDecryptedSecrets()
                } catch (e: SecurityException) {
                    android.util.Log.e("RdpProfileRepository",
                        "getAllProfiles: could not decrypt profile ID ${profile.id}", e)
                    // Return a sanitised copy so the card is still shown; empty secrets prevent
                    // an accidental connect attempt with garbled credentials.
                    profile.copy(
                        password                      = "",
                        gatewayPassword               = "",
                        sshPrivateKey                 = "",
                        sshPrivateKeyPassphrase       = "",
                        sshTunnelPassword             = "",
                        sshTunnelPrivateKey           = "",
                        sshTunnelPrivateKeyPassphrase = "",
                    )
                }
            }
        }

    suspend fun getProfileById(id: String): RdpProfile? {
        // BUG-GETBYID FIX: getAllProfiles() already catches SecurityException per-profile
        // (Keystore key lost after reinstall / backup restore), but getProfileById() let
        // the exception propagate uncaught. RdpSessionActivity calls this before every
        // connection attempt, so a lost Keystore key crashed the app on the session
        // screen with no user-visible explanation.
        // Fix: mirror the same try/catch pattern from getAllProfiles() — return null so
        // the caller's existing "profile == null → show error_profile_not_found" path
        // fires, which is a cleaner UX than a raw crash. The SecurityException is logged
        // so developers can diagnose the Keystore state from Logcat.
        return try {
            dao.getProfileById(id)?.withDecryptedSecrets()
        } catch (e: SecurityException) {
            android.util.Log.e("RdpProfileRepository",
                "getProfileById: could not decrypt profile id=$id (Keystore key lost?)", e)
            null
        }
    }

    suspend fun saveProfile(profile: RdpProfile) =
        dao.insertProfile(profile.withEncryptedSecrets())

    suspend fun updateProfile(profile: RdpProfile) =
        dao.updateProfile(profile.withEncryptedSecrets())

    suspend fun deleteProfile(profile: RdpProfile) = dao.deleteProfile(profile)

    suspend fun updateLastConnected(id: String) =
        dao.updateLastConnected(id, System.currentTimeMillis())

    // BUG-9 FIX: the DAO method was renamed to updateScreenshotFilename; the old
    // name (updateScreenshot / updateScreenshotPath) no longer exists and caused
    // an "Unresolved reference" compile error. Parameter renamed path → filename
    // to match the DAO signature and the DB column (lastScreenshotFilename).
    suspend fun updateScreenshot(id: String, filename: String) =
        dao.updateScreenshotFilename(id, filename)

    suspend fun updateConnectionState(id: String, connected: Boolean) =
        dao.updateConnectionState(id, connected)

    // UX-03: Persist new sort order after drag-to-reorder
    suspend fun reorderProfiles(profiles: List<RdpProfile>) {
        profiles.forEachIndexed { index, profile ->
            dao.updateSortOrder(profile.id, index)
        }
    }

    // FIX B3: تُستدعى عند بدء التطبيق لإزالة علامات isConnected المتبقية من جلسات سابقة
    suspend fun resetAllConnectionStates() = dao.resetAllConnectionStates()
}
