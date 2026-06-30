package com.gotohex.rdp.data.db

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.security.CryptoHelper
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration  // ← هذا السطر كان مفقوداً
@Dao
interface RdpProfileDao {
    @Query("SELECT * FROM rdp_profiles ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllProfiles(): Flow<List<RdpProfile>>

    @Query("SELECT * FROM rdp_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): RdpProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: RdpProfile)

    @Update
    suspend fun updateProfile(profile: RdpProfile)

    @Delete
    suspend fun deleteProfile(profile: RdpProfile)

    @Query("UPDATE rdp_profiles SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("UPDATE rdp_profiles SET lastScreenshotFilename = :filename WHERE id = :id")
    suspend fun updateScreenshotFilename(id: String, filename: String)
    // BUG-9 FIX: was "SET lastScreenshotPath = :path" — that column is deprecated and
    // the modern code uses lastScreenshotFilename. The old method was also an orphan
    // (never called anywhere); renamed to updateScreenshotFilename for clarity.

    @Query("UPDATE rdp_profiles SET isConnected = :connected WHERE id = :id")
    suspend fun updateConnectionState(id: String, connected: Boolean)

    // UX-03: Persist drag-to-reorder
    @Query("UPDATE rdp_profiles SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    // FIX B3: إعادة تهيئة جميع البطاقات كـ "غير متصل" عند إعادة تشغيل التطبيق.
    // بدون هذا، تظل البطاقات مُعلَّمة كـ isConnected=true بعد الكراش (مضلل للمستخدم).
    @Query("UPDATE rdp_profiles SET isConnected = 0 WHERE isConnected = 1")
    suspend fun resetAllConnectionStates()
}

/**
 * v1 -> v2: introduced multi-protocol support (RDP / VNC / SSH).
 * Adds protocolType plus RD Gateway, VNC, and SSH columns. All new columns
 * are given safe defaults so existing RDP profiles keep working unmodified
 * (they implicitly become protocolType = 'RDP').
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN protocolType TEXT NOT NULL DEFAULT 'RDP'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayPort INTEGER NOT NULL DEFAULT 443")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayPassword TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN gatewayDomain TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN vncViewOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshAuthType TEXT NOT NULL DEFAULT 'PASSWORD'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPrivateKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshPrivateKeyPassphrase TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v2 -> v3: Added ConnectionLog entity and advanced RDP fields
 * (colorDepth, width, height, performanceFlags, enableSound, enableClipboard,
 * enableDriveRedirect, sortOrder).
 *
 * NOTE: This is the single authoritative MIGRATION_2_3 — the duplicate in
 * ConnectionLogDao.kt has been removed to prevent the compile-time "duplicate
 * top-level declaration" error and the runtime schema mismatch it caused.
 * Schema matches ConnectionLog.kt exactly: profileId nullable, port present,
 * disconnectReason (not errorMessage), disconnectedAt NOT NULL DEFAULT 0.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Advanced RDP display/performance columns
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN colorDepth INTEGER NOT NULL DEFAULT 32")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN performanceFlags INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableSound INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableClipboard INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN enableDriveRedirect INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        // ConnectionLog table — schema must exactly match ConnectionLog.kt entity
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS connection_logs (
                id TEXT NOT NULL PRIMARY KEY,
                profileId TEXT,
                profileName TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL,
                protocolType TEXT NOT NULL DEFAULT 'RDP',
                connectedAt INTEGER NOT NULL,
                disconnectedAt INTEGER NOT NULL DEFAULT 0,
                disconnectReason TEXT,
                wasSuccessful INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

/**
 * v3 -> v4: Added SSH Tunnel fields for RDP/VNC profiles.
 * All columns default to safe empty/false values so existing profiles
 * continue working without modification.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelHost TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPort INTEGER NOT NULL DEFAULT 22")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelUsername TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelAuthType TEXT NOT NULL DEFAULT 'PASSWORD'")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPassword TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPrivateKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN sshTunnelPrivateKeyPassphrase TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v4 -> v5: Added Wake-on-LAN fields.
 * Safe defaults: WoL disabled, empty MAC, broadcast = 255.255.255.255.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolMacAddress TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN wolBroadcastAddress TEXT NOT NULL DEFAULT '255.255.255.255'")
    }
}

/**
 * v5 -> v6: Added lastScreenshotFilename column to rdp_profiles.
 * BUG-3 FIX: RdpProfile.kt added this field but no migration existed →
 * Room throws IllegalStateException on launch for all existing users.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN lastScreenshotFilename TEXT")
    }
}

/**
 * v6 -> v7: Added acceptSelfSignedCertificate field to RdpProfile.
 * BUG-3 FIX: ignoreCert was hard-wired to false after the MITM-vuln patch,
 * blocking all self-signed RDP servers. New column lets users opt-in per profile.
 * Default is 0 (false) so existing profiles keep the secure behaviour.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rdp_profiles ADD COLUMN acceptSelfSignedCertificate INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v7 -> v8 (CRIT-R1 FIX): Re-encrypt SSH Tunnel credentials that were stored as plaintext.
 *
 * MIGRATION_3_4 added sshTunnelPassword / sshTunnelPrivateKey / sshTunnelPrivateKeyPassphrase
 * with DEFAULT '' — plain text in the SQLCipher DB.  FIX S1 in RdpProfileRepository patched
 * new saves, but profiles created or last-saved on DB versions 3-6 still have unencrypted
 * values in those three columns.  This migration iterates every row where sshTunnelEnabled = 1
 * and encrypts any credential that CryptoHelper.decrypt() cannot successfully parse
 * (i.e. it was never passed through withEncryptedSecrets()).
 *
 * Detection strategy:
 *   • decrypt() succeeds           → value is already encrypted → leave unchanged.
 *   • decrypt() throws             → value is plaintext (or corrupt) → encrypt now.
 *   • encrypt() throws             → Keystore unavailable (Direct Boot, etc.)
 *                                    → leave current value; DAO will fix on next saveProfile().
 *   • value.isBlank()              → no credential to protect → skip.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query(
            "SELECT id, sshTunnelPassword, sshTunnelPrivateKey, sshTunnelPrivateKeyPassphrase " +
            "FROM rdp_profiles WHERE sshTunnelEnabled = 1"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id      = it.getString(0) ?: continue
                val pwd     = it.getString(1) ?: ""
                val privKey = it.getString(2) ?: ""
                val pkPass  = it.getString(3) ?: ""

                val encPwd     = encryptIfPlaintext(pwd)
                val encPrivKey = encryptIfPlaintext(privKey)
                val encPkPass  = encryptIfPlaintext(pkPass)

                // Only write back if at least one value actually changed,
                // to avoid unnecessary write-amplification on large profile sets.
                if (encPwd != pwd || encPrivKey != privKey || encPkPass != pkPass) {
                    db.execSQL(
                        "UPDATE rdp_profiles " +
                        "SET sshTunnelPassword = ?, sshTunnelPrivateKey = ?, " +
                        "    sshTunnelPrivateKeyPassphrase = ? " +
                        "WHERE id = ?",
                        arrayOf(encPwd, encPrivKey, encPkPass, id)
                    )
                }
            }
        }
    }

    /**
     * Try CryptoHelper.decrypt() — success means the value is already encrypted;
     * any exception means it is plaintext (or corrupt), so we encrypt it.
     * Returns the value unchanged if both decrypt AND encrypt fail (Keystore unavailable).
     */
    private fun encryptIfPlaintext(value: String): String {
        // NEW-BUG-3 FIX: Use isEmpty() instead of isBlank().
        // isBlank() returns true for whitespace-only strings (e.g. " "), causing
        // an SSH tunnel credential that is literally a space to bypass re-encryption.
        // isEmpty() only skips truly empty strings (unconfigured fields), which is
        // the correct semantic here. Consistent with the isBlank()→isEmpty() fix
        // already applied to CryptoHelper.encrypt() (BUG-2) and
        // AppSettingsRepository.updatePinLock (NEW-BUG-2).
        if (value.isEmpty()) return value
        return try {
            CryptoHelper.decrypt(value)   // succeeds → already encrypted
            value                         // return the encrypted blob unchanged
        } catch (_: Exception) {
            // SecurityException from decrypt() means value is plaintext (or corrupt).
            // Encrypt it; if encryption also fails leave the original so the DB
            // schema transition still completes (the DAO will re-encrypt on next save).
            try { CryptoHelper.encrypt(value) } catch (_: Exception) { value }
        }
    }
}

@Database(
    entities = [RdpProfile::class, com.gotohex.rdp.data.model.ConnectionLog::class],
    version = 8,   // CRIT-R1 FIX: bumped from 7 to 8 to apply SSH Tunnel re-encryption migration
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class HexRdpDatabase : RoomDatabase() {
    abstract fun rdpProfileDao(): RdpProfileDao
    abstract fun connectionLogDao(): ConnectionLogDao

    companion object {
        const val DATABASE_NAME = "hex_rdp_database"
    }
}
