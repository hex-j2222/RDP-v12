package com.gotohex.rdp.data.db

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * HIGH-3 FIX: One-shot migration helper that encrypts the plain Room database
 * the first time a build with SQLCipher is installed on top of an existing
 * installation.
 *
 * Problem: When [SupportOpenHelperFactory] is passed to Room's [openHelperFactory], SQLCipher
 * tries to open the database file with the given passphrase.  If the file is an
 * unencrypted SQLite database (magic bytes = "SQLite format 3"), SQLCipher rejects
 * it with a "file is not a database" error.
 *
 * Solution: Before Room initialises, [migrate] inspects the magic bytes.  If the
 * file is plain SQLite, it uses SQLCipher's built-in `sqlcipher_export` to write an
 * encrypted copy, then atomically replaces the original file.
 *
 * This function is idempotent — if the file is already encrypted (or doesn't exist)
 * it returns immediately.
 *
 * 🔴 CRITICAL FIX (build break, 2nd round): this file targets the NEW
 * `net.zetetic:sqlcipher-android` artifact (package `net.zetetic.database.sqlcipher`),
 * which is NOT a drop-in API match for the deprecated `net.zetetic:android-database-sqlcipher`
 * artifact (package `net.sqlcipher.database`) the original code was written against.
 * Two APIs used below do not exist on the new SQLiteDatabase class and had to be replaced:
 *
 *   1. `SQLiteDatabase.loadLibs(context)` → does not exist in the new library.
 *      Replaced with `System.loadLibrary("sqlcipher")`, which is the documented way
 *      to load the native core in net.zetetic.database.sqlcipher.
 *      See: https://github.com/sqlcipher/sqlcipher-android (README "Using the native
 *      SQLCipher for Android classes").
 *
 *   2. `SQLiteDatabase.changePassword(ByteArray)` → not part of the new library's
 *      public/confirmed API surface (only present on the legacy net.sqlcipher.database
 *      class). Re-introducing an unverified symbol here risks yet another
 *      "Unresolved reference" build failure, so the two-step
 *      "export plain → reopen → changePassword" dance has been replaced with the
 *      single documented `ATTACH ... KEY "x'<hex>'"` + `sqlcipher_export()` pattern,
 *      which is the officially documented plaintext→encrypted migration recipe for
 *      sqlcipher-android (see sqlcipher/sqlcipher-android-tests,
 *      ExportToUnencryptedDatabase.java, and the inverse direction used here).
 *
 *   3. The new library's `rawExecSQL()` does NOT accept a `SELECT` statement — it
 *      throws "Queries can be performed using SQLiteDatabase query or rawQuery
 *      methods only." `SELECT sqlcipher_export(...)` must therefore go through
 *      `rawQuery()` and the returned Cursor must actually be iterated (Android
 *      Cursors are lazy) before being closed, or the export never runs.
 *      See: https://github.com/sqlcipher/sqlcipher-android/issues/16
 *
 * Note on key material exposure: building the `ATTACH ... KEY "x'<hex>'"` SQL string
 * necessarily materialises the passphrase as a JVM String for the duration of this
 * call (SQL is textual). This mirrors the official sqlcipher-android examples. The
 * ByteArray we receive is still zeroed out immediately after use; we cannot zero the
 * transient hex String (Java/Kotlin Strings are immutable), the same limitation the
 * original two-step approach was trying to avoid via `changePassword(ByteArray)` —
 * but that method cannot be relied upon to exist on this artifact, so correctness
 * (a build that actually compiles and works) takes priority here.
 */
object DatabaseEncryptionMigrator {

    private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.US_ASCII)

    /**
     * Encrypts the Room database in-place if it is currently unencrypted.
     *
     * Must be called **before** [Room.databaseBuilder] so the file is in the
     * right state when Room first opens it.
     *
     * @param context   Application context.
     * @param passphrase 32-byte SQLCipher passphrase from [DatabaseKeyProvider].
     */
    fun migrate(context: Context, passphrase: ByteArray) {
        // 🔴 FIX: SQLiteDatabase.loadLibs(context) does not exist in
        // net.zetetic.database.sqlcipher.SQLiteDatabase — use System.loadLibrary().
        System.loadLibrary("sqlcipher")

        val dbFile = context.getDatabasePath(HexRdpDatabase.DATABASE_NAME)
        if (!dbFile.exists()) return          // fresh install — nothing to migrate
        if (!isUnencrypted(dbFile)) return    // already encrypted

        android.util.Log.i("DBEncMigrator", "Encrypting plain database: ${dbFile.name}")

        val tmpFile = File(dbFile.parent, "${HexRdpDatabase.DATABASE_NAME}_enc_tmp")
        tmpFile.delete()

        try {
            // Open the plain (unencrypted) database with SQLCipher using an empty key.
            val plain = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                ByteArray(0),          // empty key → opens as plain SQLite
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null
            )

            // CRIT-4 FIX (original): path is sanitised to prevent SQL injection.
            val escapedPath = tmpFile.absolutePath.replace("'", "''")

            // 🔴 FIX: single-step export directly to the final encrypted file using
            // the documented ATTACH ... KEY "x'<hex>'" + sqlcipher_export() pattern
            // (changePassword(ByteArray) is not a confirmed-available API on this
            // artifact — see class doc above for why the two-step approach was dropped).
            val hexKey = passphrase.toHexLiteral()
            try {
                plain.rawExecSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY \"$hexKey\"")
                // 🔴 FIX: must use rawQuery (not rawExecSQL) for a SELECT statement on
                // this artifact, and the cursor must be iterated to actually run it.
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", null)?.use { cursor ->
                    cursor.moveToFirst()
                }
                plain.rawExecSQL("DETACH DATABASE encrypted")
            } finally {
                plain.close()
            }

            // Zero the passphrase immediately — it has been consumed by the export above.
            passphrase.fill(0)

            // Atomically replace the plain file with the encrypted one.
            // Also remove WAL/SHM sidecars so Room doesn't trip over stale state.
            context.getDatabasePath("${HexRdpDatabase.DATABASE_NAME}-wal").delete()
            context.getDatabasePath("${HexRdpDatabase.DATABASE_NAME}-shm").delete()
            if (!dbFile.delete()) throw IllegalStateException("Cannot delete original DB")
            if (!tmpFile.renameTo(dbFile)) throw IllegalStateException("Cannot rename encrypted DB")

            android.util.Log.i("DBEncMigrator", "Database encrypted successfully")
        } catch (e: Exception) {
            tmpFile.delete()
            android.util.Log.e(
                "DBEncMigrator",
                "Encryption migration failed — deleting plain DB to prevent data leak at rest",
                e
            )
            // Delete the plain-text file so it doesn't persist unencrypted.
            // Room will trigger fallbackToDestructiveMigration and start fresh.
            dbFile.delete()
            context.getDatabasePath("${HexRdpDatabase.DATABASE_NAME}-wal").delete()
            context.getDatabasePath("${HexRdpDatabase.DATABASE_NAME}-shm").delete()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true if [file] starts with the plain SQLite magic bytes. */
    private fun isUnencrypted(file: File): Boolean {
        if (file.length() < SQLITE_MAGIC.size) return false
        val header = ByteArray(SQLITE_MAGIC.size)
        file.inputStream().use { it.read(header) }
        return header.contentEquals(SQLITE_MAGIC)
    }

    /**
     * Renders this ByteArray as a SQLCipher raw-key blob literal, e.g. `x'1A2B...'`,
     * suitable for use inside an `ATTACH DATABASE ... KEY "..."` statement.
     * See: https://www.zetetic.net/sqlcipher/sqlcipher-api/#key (raw key data).
     */
    private fun ByteArray.toHexLiteral(): String {
        val sb = StringBuilder(this.size * 2 + 3)
        sb.append("x'")
        for (b in this) sb.append(String.format("%02x", b))
        sb.append("'")
        return sb.toString()
    }
}
