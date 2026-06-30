package com.gotohex.rdp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Persists a small thumbnail of the last frame seen during an RDP session,
 * keyed by profile ID, so the profile list can show "what the system looked
 * like last time" as a faint background blended into the connection card.
 *
 * MED-2 FIX: Thumbnails are now stored as AES-256-GCM encrypted JPEG files
 * using androidx.security.crypto.EncryptedFile.  Although these files sit in
 * cacheDir (app-private), an attacker with root access could read plain JPEGs
 * and see remote-desktop content (documents, visible passwords, corporate data).
 * Encryption ensures that even on a rooted device the pixel data stays protected
 * by the Android Keystore key.
 *
 * Trade-off: EncryptedFile cannot be appended to — existing files must be
 * deleted before a new encrypted file can be written.  The save() helper does
 * this atomically (delete → write) so a crash between the two steps at worst
 * leaves no thumbnail (cosmetic degradation, never a data loss or crash).
 */
object LastFrameStore {

    private const val DIR_NAME     = "last_frames"
    private const val MAX_DIMENSION = 480
    private const val JPEG_QUALITY  = 70

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * HIGH-3 FIX: Sanitise [profileId] before embedding it in a file path.
     *
     * Profile IDs are generated as UUIDs internally, but a crafted import (e.g. a
     * modified .rdp file whose `full address` produces an unusual ID) could in
     * theory contain path-separator characters such as `../`, allowing a write to
     * escape the `last_frames/` directory.
     *
     * We retain only letters, digits, and hyphens — the exact character set used
     * by java.util.UUID.randomUUID().toString() — and reject anything else.
     * An ID that is empty after sanitisation falls back to a safe constant so the
     * method always returns a valid path inside the expected directory.
     *
     * The canonical-path check that follows is a second layer of defence: it
     * asserts that the resolved file path still starts with the expected parent
     * directory, aborting with IllegalStateException if not (this should never
     * happen after the character filter above, but defence-in-depth is warranted
     * for any file-path construction that accepts external input).
     */
    private fun fileFor(context: Context, profileId: String): File {
        // Strip anything that is not a UUID-safe character.
        val safeId = profileId.filter { it.isLetterOrDigit() || it == '-' }
            .take(64)   // hard cap so an enormous ID cannot create an absurdly long path
            .ifEmpty { "unknown" }

        val dir  = dir(context)
        val file = File(dir, "$safeId.jpg.enc")

        // Canonical-path defence: confirm the resolved path is still inside dir.
        val dirCanonical  = dir.canonicalPath
        val fileCanonical = file.canonicalPath
        check(fileCanonical.startsWith(dirCanonical + File.separator)) {
            "Path traversal detected for profileId='$profileId' → resolved='$fileCanonical'"
        }
        return file
    }

    /** Returns a configured EncryptedFile instance for [profileId]. */
    private fun encryptedFile(context: Context, profileId: String): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            fileFor(context, profileId),
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    /**
     * Downscales [bitmap] and saves it as an encrypted JPEG for [profileId].
     * Any failure is swallowed — this is a purely cosmetic feature.
     */
    fun save(context: Context, profileId: String, bitmap: Bitmap) {
        try {
            val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width  * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap

            // MED-2 FIX: EncryptedFile cannot be overwritten — delete first.
            fileFor(context, profileId).delete()

            encryptedFile(context, profileId).openFileOutput().use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (thumb !== bitmap) thumb.recycle()
        } catch (_: Exception) {
            // Best-effort only — cosmetic feature must never affect the session.
        }
    }

    /** Returns the decrypted thumbnail for [profileId], or null on any error. */
    fun load(context: Context, profileId: String): Bitmap? {
        return try {
            val file = fileFor(context, profileId)
            if (!file.exists()) return null
            encryptedFile(context, profileId).openFileInput().use { inp ->
                BitmapFactory.decodeStream(inp)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun exists(context: Context, profileId: String): Boolean =
        fileFor(context, profileId).exists()

    fun delete(context: Context, profileId: String) {
        try { fileFor(context, profileId).delete() } catch (_: Exception) { }
    }
}

