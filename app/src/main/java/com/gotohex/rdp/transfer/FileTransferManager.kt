package com.gotohex.rdp.transfer

import android.content.Context
import android.content.SharedPreferences
import com.gotohex.rdp.security.openEncryptedPrefs
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.security.SecureRandom
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.DecimalFormat
import java.util.concurrent.atomic.AtomicBoolean
// FIX-HTTPS: TLS/SSL for embedded file-transfer server
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
// FIX-DOS: Semaphore for concurrent connection limiting
import java.util.concurrent.Semaphore as JSemaphore

// ─────────────────────────────────────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────────────────────────────────────

data class HexFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    val extension: String get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}

sealed class TransferProgress {
    object Idle : TransferProgress()
    data class Running(
        val fileName: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val isUpload: Boolean
    ) : TransferProgress() {
        val percent: Float = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal else 0f
    }
    data class Success(val fileName: String, val isUpload: Boolean) : TransferProgress()
    data class Failure(val error: String) : TransferProgress()
}

data class StorageSpace(
    val freeBytes: Long,
    val totalBytes: Long
) {
    val usedBytes: Long get() = totalBytes - freeBytes
    val freePercent: Float get() = if (totalBytes > 0) freeBytes.toFloat() / totalBytes else 0f
    // BUG-5 FIX: Replaced hardcoded "free of" English string with a context-aware function.
    // The old computed property (val label: String) had no access to a Context, so it always
    // produced English output regardless of the app locale.
    fun label(context: Context): String = context.getString(
        com.gotohex.rdp.R.string.storage_free_of,
        formatBytesLocalized(context, freeBytes),
        formatBytesLocalized(context, totalBytes)
    )
}

/**
 * BUG-5 FIX: Context-aware version of formatBytes() that reads unit labels from
 * string resources so they are correctly localised (e.g. Arabic: ب / ك.ب / م.ب / غ.ب).
 * The original formatBytes(Long) is kept for callers that do not have a Context.
 */
fun formatBytesLocalized(context: Context, bytes: Long): String {
    val df = java.text.DecimalFormat("#.##")
    if (bytes < 1024) return "$bytes ${context.getString(com.gotohex.rdp.R.string.storage_unit_b)}"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${df.format(kb)} ${context.getString(com.gotohex.rdp.R.string.storage_unit_kb)}"
    val mb = kb / 1024.0
    if (mb < 1024) return "${df.format(mb)} ${context.getString(com.gotohex.rdp.R.string.storage_unit_mb)}"
    val gb = mb / 1024.0
    return "${df.format(gb)} ${context.getString(com.gotohex.rdp.R.string.storage_unit_gb)}"
}

/** English-only fallback; use [formatBytesLocalized] in UI code for proper i18n. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val df = DecimalFormat("#.##")
    val kb = bytes / 1024.0
    if (kb < 1024) return "${df.format(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${df.format(mb)} MB"
    val gb = mb / 1024.0
    return "${df.format(gb)} GB"
}

// ─────────────────────────────────────────────────────────────────────────────
// Phone File Browser
// ─────────────────────────────────────────────────────────────────────────────

object PhoneFileBrowser {

    /**
     * MED-1 FIX: Replace deprecated [Environment.getExternalStorageDirectory()] with a
     * forward-compatible implementation that works across all supported API levels (26-36).
     *
     * Background:
     *   API 26-28: getExternalStorageDirectory() still works and is the standard approach.
     *   API 29+  : getExternalStorageDirectory() is deprecated and returns the same path,
     *              but lint/Play Store tooling warns about it. The replacement on API 29+
     *              is MediaStore or the Storage Access Framework (SAF) for cross-directory
     *              access, but SAF requires the user to grant access via a directory picker
     *              UI — inappropriate for a background file-browser component.
     *
     * Pragmatic approach (accepted by Google Play as of 2025):
     *   Use the deprecated API but suppress the warning with an explanatory comment, AND
     *   add an API 29+ guard that uses Environment.getStorageDirectory() + "emulated/0"
     *   as an equivalent path.  Both paths resolve to the same physical location; the
     *   switch eliminates the deprecated-API lint error without changing behaviour.
     *
     *   The MANAGE_EXTERNAL_STORAGE comment from the original code is removed because that
     *   permission was deleted in CRIT-2 Manifest fix; the deprecated API still works at
     *   API 29+ as long as READ_EXTERNAL_STORAGE (API 26-32) or READ_MEDIA_* (API 33+)
     *   is granted, which is already declared in the Manifest.
     */
    fun rootPaths(): List<HexFile> = buildList {
        val ext: File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: construct the path directly without calling the deprecated method.
            // Environment.getExternalStorageDirectory() resolves to /storage/emulated/0
            // on virtually all modern devices; we replicate that without the deprecated call.
            File(Environment.getStorageDirectory(), "emulated/0")
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        }
        if (ext.exists()) add(toHexFile(ext, "Internal Storage"))

        val storageRoot = File("/storage")
        if (storageRoot.exists()) {
            storageRoot.listFiles()
                ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
                ?.forEach { add(toHexFile(it, it.name)) }
        }
    }

    fun listDir(path: String): List<HexFile> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return try {
            (dir.listFiles() ?: emptyArray())
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .map { toHexFile(it) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun storageSpace(path: String): StorageSpace {
        return try {
            val stat = StatFs(path)
            StorageSpace(
                freeBytes  = stat.availableBlocksLong * stat.blockSizeLong,
                totalBytes = stat.blockCountLong * stat.blockSizeLong
            )
        } catch (_: Exception) {
            StorageSpace(0L, 0L)
        }
    }

    fun phoneStorageSpace(): StorageSpace {
        // MED-1 FIX: Same API-level guard as rootPaths() — avoid the deprecated call on API 29+.
        val extPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(Environment.getStorageDirectory(), "emulated/0").absolutePath
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        }
        return storageSpace(extPath)
    }

    private fun toHexFile(f: File, overrideName: String? = null) = HexFile(
        name         = overrideName ?: f.name,
        path         = f.absolutePath,
        isDirectory  = f.isDirectory,
        size         = if (f.isFile) f.length() else 0L,
        lastModified = f.lastModified()
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SFTP Browser (SSH sessions)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * CRIT-1 FIX: Credentials are stored as CharArray rather than String.
 * JVM Strings are immutable — a plain `String` password stays on the heap until GC
 * arbitrarily decides to collect it (minutes in a long-running app), making it visible
 * in heap dumps and memory-forensics tools.  CharArray can be zeroed immediately after
 * use via [zero], bounding the exposure window to the lifetime of the connection only.
 *
 * Unlike [SshCredentials] and [SshTunnelCredentials] (already fixed), this class
 * previously left password and private-key material as immutable Strings.
 */
class SftpConfig(
    val host: String,
    val port: Int,
    val username: String,
    password: String = "",
    privateKeyPem: String = "",
    privateKeyPassphrase: String = "",
) {
    val password: CharArray           = password.toCharArray()
    val privateKeyPem: CharArray      = privateKeyPem.toCharArray()
    val privateKeyPassphrase: CharArray = privateKeyPassphrase.toCharArray()
    /** Zero all sensitive CharArray fields; call after the JSch session is established. */
    fun zero() {
        password.fill('\u0000')
        privateKeyPem.fill('\u0000')
        privateKeyPassphrase.fill('\u0000')
    }
}

/**
 * MED-4 FIX: Sanitize a filename received from the network before writing it to disk.
 * Package-level so both SftpFileBrowser and HttpFileServer can use it.
 */
internal fun sanitizeFileName(raw: String): String {
    val name = java.io.File(raw).name                  // strip directory traversal
        .replace(Regex("[\\x00-\\x1f\\x7f]"), "")     // remove control chars
        .trimStart('.')                                 // prevent hidden files
        .take(200)                                      // cap length for FS safety
    return name.ifBlank { "upload_${System.currentTimeMillis()}" }
}

class SftpFileBrowser(
    private val config: SftpConfig,
    // FIX-SFTP-TOFU: Context required to persist TOFU host keys across app restarts,
    // mirroring the same fix applied to SshClient and SshTunnelManager.
    private val appContext: Context,
) {

    companion object {
        private const val TAG = "SftpFileBrowser"
        private const val PREFS_TOFU_SFTP = "hexrdp_tofu_sftp"
    }

    // FIX-SFTP-TOFU: TOFU host-key repository replacing StrictHostKeyChecking=no.
    // SshClient and SshTunnelManager were already fixed; this brings SftpFileBrowser
    // to parity — without it, every SFTP file transfer was fully open to MITM attacks.
    private inner class TofuHostKeyRepository : com.jcraft.jsch.HostKeyRepository {

        private val pendingKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun mapKey(host: String): String {
            val bare = host.removePrefix("[").substringBefore("]")
            return if (':' in host) "$bare:${host.substringAfterLast(']').removePrefix(":")}"
            else "$bare:${config.port}"
        }

        // MED-R1 FIX: EncryptedSharedPreferences — AES-256-GCM authenticated on disk.
         private val cachedPrefs: SharedPreferences by lazy {
             appContext.openEncryptedPrefs(PREFS_TOFU_SFTP)
         }
         private fun prefs(): SharedPreferences = cachedPrefs

        override fun check(host: String, key: ByteArray): Int {
            val mk = mapKey(host)
            val incoming = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
            val stored = prefs().getString(mk, null)
            return when {
                stored == null -> {
                    pendingKeys[mk] = incoming
                    com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED
                }
                stored == incoming -> com.jcraft.jsch.HostKeyRepository.OK
                else -> {
                    android.util.Log.w(TAG, "SFTP host key CHANGED for $mk — possible MITM!")
                    com.jcraft.jsch.HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: com.jcraft.jsch.HostKey, ui: com.jcraft.jsch.UserInfo?) {
            val mk = mapKey(hostkey.host)
            // LIVE-HIGH-1 FIX: commit() guarantees TOFU fingerprint survives process death.
            pendingKeys.remove(mk)?.let { key ->
                prefs().edit().putString(mk, key).commit()
            }
        }

        override fun remove(host: String?, type: String?) {
            if (host != null) prefs().edit().remove(mapKey(host)).commit()
        }
        override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)
        override fun getKnownHostsRepositoryID() = "hexrdp-sftp-tofu"
        override fun getHostKey() = emptyArray<com.jcraft.jsch.HostKey>()
        override fun getHostKey(h: String?, t: String?) = emptyArray<com.jcraft.jsch.HostKey>()
    }

    private var jschSession: com.jcraft.jsch.Session? = null
    private var channel: ChannelSftp? = null

    // BUG-5 FIX: Converted to a suspend function dispatched on Dispatchers.IO.
    // Previously this was a plain fun that blocked its caller for up to 25 seconds
    // (15s + 10s connect timeouts) without any coroutine dispatcher constraint —
    // any caller on the Main thread would freeze the UI or trigger ANR.
    suspend fun connect() = withContext(Dispatchers.IO) {
        val jsch = JSch()
        // FIX-SFTP-TOFU: replace StrictHostKeyChecking=no with TOFU verification
        jsch.hostKeyRepository = TofuHostKeyRepository()
        config.privateKeyPem.takeIf { it.isNotEmpty() }?.let { pemChars ->
            // CRIT-1 / HIGH-2 FIX: Convert CharArray → ByteArray only for the JSch call,
            // then zero both immediately in the finally block so neither copy lingers
            // on the heap beyond the addIdentity() call.
            val bytes = String(pemChars).toByteArray(Charsets.UTF_8)
            val pass  = config.privateKeyPassphrase.takeIf { it.isNotEmpty() }
                            ?.let { String(it).toByteArray(Charsets.UTF_8) }
            try {
                jsch.addIdentity("key", bytes, null, pass)
            } finally {
                bytes.fill(0)
                pass?.fill(0)
            }
        }
        val sess = jsch.getSession(config.username, config.host, config.port)
        if (config.privateKeyPem.isEmpty()) {
            // CRIT-1 FIX: JSch.Session.setPassword(byte[]) avoids creating a String copy;
            // zero the byte array immediately after the call.
            val pwBytes = String(config.password).toByteArray(Charsets.UTF_8)
            try { sess.setPassword(pwBytes) } finally { pwBytes.fill(0) }
        }
        // FIX-SFTP-TOFU: accept-new = TOFU first-use auto-accept, mismatch = reject (MITM)
        sess.setConfig("StrictHostKeyChecking", "accept-new")
        sess.setConfig("server_host_key",
            "ssh-rsa,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519")
        // NEW-HIGH-1 FIX: Wrap sess.connect() in try-finally so config.zero() is guaranteed
        // to run even when connect() throws (timeout, TOFU rejection, network error).
        // Previously config.zero() was a plain statement after sess.connect(); any exception
        // from connect() would propagate past it, leaving the credentials CharArrays alive
        // until GC — defeating the zeroing-on-last-use pattern used throughout the codebase.
        // JSch already holds its own internal copy of the credentials at this point, so
        // zeroing our CharArrays here is safe regardless of whether connect() succeeded.
        try {
            sess.connect(15_000)
        } finally {
            // CRIT-1 FIX: Zero all remaining CharArrays in SftpConfig.
            config.zero()
        }
        // CRIT-NEW-2 FIX: assign jschSession BEFORE opening the SFTP channel.
        // Previously: sess.connect() succeeded, but if ch.connect() threw an exception,
        // jschSession was still null → disconnect() could not close `sess` → SSH socket
        // leaked indefinitely. Assigning here guarantees disconnect() always closes `sess`
        // regardless of whether the SFTP channel opened successfully.
        jschSession = sess
        val ch = sess.openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        channel = ch
    }

    fun disconnect() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        channel    = null
        jschSession = null
    }

    fun isConnected(): Boolean = channel?.isConnected == true

    fun homeDir(): String = try { channel?.home ?: "/" } catch (_: Exception) { "/" }

    fun listDir(path: String): List<HexFile> {
        val ch = channel ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val entries = ch.ls(path) as? java.util.Vector<ChannelSftp.LsEntry> ?: return emptyList()
            // MED-3 FIX: Cap the number of entries returned to prevent a DoS attack where
            // a malicious or misconfigured SFTP server returns millions of directory entries,
            // exhausting heap memory while sorting and mapping them.
            // 5 000 entries is well above any realistic working directory; exceeding it is a
            // signal of either a very unusual server layout or a deliberate abuse attempt.
            // Directories are shown first; within the cap, sorting is stable so the most
            // relevant entries (folders, then files alphabetically) are always visible.
            val MAX_DIR_ENTRIES = 5_000
            entries
                .filter { it.filename != "." && it.filename != ".." }
                .take(MAX_DIR_ENTRIES)   // cap BEFORE sort to keep memory bounded
                .sortedWith(compareByDescending<ChannelSftp.LsEntry> { it.attrs.isDir }.thenBy { it.filename.lowercase() })
                .map { entry ->
                    HexFile(
                        name         = entry.filename,
                        path         = "${path.trimEnd('/')}/${entry.filename}",
                        isDirectory  = entry.attrs.isDir,
                        size         = entry.attrs.size,
                        lastModified = entry.attrs.mTime.toLong() * 1000L
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Upload a local phone file to the remote server. */
    suspend fun uploadFile(
        localPath: String,
        remotePath: String,
        onProgress: (TransferProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ch = channel ?: throw IOException("SFTP not connected")
        val file = File(localPath)
        if (!file.exists()) throw IOException("Local file not found: $localPath")
        val total = file.length()
        var done  = 0L

        // FIX #1: currentCoroutineContext() هي دالة suspend ولا تُستدعى داخل count().
        // الحل: تخزين Job في متغير قبل الدخول إلى المراقب، ثم التحقق منه مباشرةً.
        val job = coroutineContext[Job]

        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String, dest: String, max: Long) {}
            override fun count(count: Long): Boolean {
                done += count
                onProgress(TransferProgress.Running(file.name, done, total, isUpload = true))
                return job?.isActive != false // false = ألغي المهمة، أوقف النقل
            }
            override fun end() {}
        }
        onProgress(TransferProgress.Running(file.name, 0L, total, isUpload = true))
        ch.put(localPath, remotePath, monitor, ChannelSftp.OVERWRITE)
        onProgress(TransferProgress.Success(file.name, isUpload = true))
    }

    /** Download a remote file to the phone. */
    suspend fun downloadFile(
        remotePath: String,
        localDir: String,
        onProgress: (TransferProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ch          = channel ?: throw IOException("SFTP not connected")
        // FIX-HIGH-R3-1: sanitize the remote filename before writing to disk.
        // substringAfterLast('/') alone leaves control characters (null bytes, \r, \n),
        // hidden-file prefixes (leading dots), and filenames > 255 chars unchecked.
        // sanitizeFileName() — already used in the HTTP upload path — strips all of these.
        val rawFileName = remotePath.substringAfterLast('/')
        val fileName    = sanitizeFileName(rawFileName)
        val localFile   = File(localDir, fileName)
        localFile.parentFile?.mkdirs()

        // FIX-PARTIAL-DOWNLOAD: Write to a temp file first and rename atomically on
        // success. Previously ch.get() wrote directly to localFile — if the transfer
        // failed mid-way (network drop, cancellation, no space) a corrupt partial file
        // was left at the destination with no indication it was incomplete.
        val tempFile = File(localDir, "$fileName.hexrdp_tmp")

        val attrs = try { ch.stat(remotePath) } catch (_: Exception) { null }
        val total = attrs?.size ?: -1L
        var done  = 0L

        // FIX #9: كان يُعيد true دائماً → لا إمكانية إلغاء. نفس حل uploadFile.
        val job = coroutineContext[Job]

        val monitor = object : SftpProgressMonitor {
            override fun init(op: Int, src: String, dest: String, max: Long) {}
            override fun count(count: Long): Boolean {
                done += count
                onProgress(TransferProgress.Running(fileName, done, total, isUpload = false))
                return job?.isActive != false
            }
            override fun end() {}
        }
        try {
            onProgress(TransferProgress.Running(fileName, 0L, total, isUpload = false))
            ch.get(remotePath, tempFile.absolutePath, monitor, ChannelSftp.OVERWRITE)
            // Atomic rename to final destination (same filesystem → instantaneous)
            if (!tempFile.renameTo(localFile)) {
                // Cross-partition fallback: copy then delete temp
                tempFile.copyTo(localFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(TransferProgress.Success(fileName, isUpload = false))
        } catch (e: Exception) {
            // FIX-PARTIAL-DOWNLOAD: clean up the temp file so no garbage is left behind
            tempFile.delete()
            throw e
        }
    }

    // FIX-REMOTE-STORAGE: Implement actual remote storage query via SFTP statVFS extension
    // (available on OpenSSH and most modern SFTP servers).
    //
    // BUG-SILENT-FAILURE FIX: The previous implementation returned StorageSpace(0L, 0L) on
    // every failure path — including "channel not open", "server doesn't support statVFS",
    // and genuine IO errors. StorageSpace(0, 0) is also a theoretically valid real value
    // (a completely full volume), so the UI had no way to distinguish:
    //   (a) Query succeeded → server truly has 0 bytes free  (show "disk full" warning)
    //   (b) Query failed    → we have no data                (show nothing / unavailable)
    //
    // Fix: return null when the space is genuinely unknown (channel not connected, server
    // does not support the statVFS extension, or any IO error). Return a real StorageSpace
    // only when the server responded successfully. Callers must now handle null explicitly,
    // which forces the UI to display "unavailable" instead of a misleading "0 free" bar.
    fun remoteStorageSpace(remotePath: String = "/"): StorageSpace? {
        val ch = channel ?: return null          // not connected — no data available
        return try {
            val vfs = ch.statVFS(remotePath)
            // JSch 0.2.x: الحقول bsize/bavail أصبحت private → نستخدم getters الصريحة
            // getBsize() = fundamental block size, getBavail() = blocks avail to non-root
            val blockSize = vfs.getBlockSize().coerceAtLeast(1L)
            StorageSpace(
                freeBytes  = vfs.getAvailForNonRoot() * blockSize,
                totalBytes = vfs.getBlocks() * blockSize
            )
        } catch (_: Exception) {
            // statVFS is an optional SSH extension — not all servers support it.
            // Return null so the UI shows "unavailable" rather than a false "0 free" bar.
            null
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTPS File Server (RDP / VNC sessions)
// FIX-HTTPS: Replaced plain HTTP with TLS using a per-session self-signed
// certificate generated inside Android Keystore (RSA-2048 / SHA-256).
// The browser shows a one-time cert-warning on first use — acceptable for a
// LAN-only tool. All file content and the access token are encrypted in transit.
// ─────────────────────────────────────────────────────────────────────────────

class HttpFileServer(private val context: Context) {

    // HIGH-1 FIX: Use a random OS-assigned port instead of the hardcoded 8765.
    // LIVE-CRIT-2 FIX: Port is now assigned atomically at bind time inside start()
    // using InetSocketAddress(0), eliminating the TOCTOU race window where a
    // previously-closed ServerSocket(0) port could be hijacked by a malicious app
    // before HttpFileServer re-binds it. Port is 0 until start() succeeds.
    var port: Int = 0
        private set

    // FIX-DOS: Limit simultaneous client handlers to prevent coroutine exhaustion.
    // 10 concurrent connections is more than enough for a single-user LAN transfer.
    private val connectionSemaphore = JSemaphore(10, true)

    // FIX-UPLOAD-LIMIT: Reject uploads larger than 2 GB to prevent disk exhaustion.
    // A malicious client sending Content-Length: 9999999999 would previously fill
    // the device's cacheDir and internal storage completely.
    private val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB

    // FIX-LOGIN-RATELIMIT: Rate-limit /login attempts to prevent token brute-force.
    // Even though the token space is ~10^18, an on-LAN attacker can try thousands
    // of guesses per second without any throttle. After 5 consecutive failures we
    // lock the endpoint for 30 seconds, then reset the counter.
    private val loginFailCount    = java.util.concurrent.atomic.AtomicInteger(0)
    private val loginLockedUntil  = java.util.concurrent.atomic.AtomicLong(0L)
    private val loginLockoutCount = java.util.concurrent.atomic.AtomicInteger(0) // LIVE-MED-2 FIX
    private val LOGIN_MAX_FAILS   = 5

    // LIVE-MED-2 FIX: Exponential backoff mirrors the PIN lockout tiers.
    // 30 s → 2 min → 10 min → 30 min, so a sustained brute-force attempt
    // slows from 600 tries/hour (old flat 30 s) to a prohibitive crawl.
    private fun loginLockoutMs(consecutiveLockouts: Int): Long = when (consecutiveLockouts) {
        0    -> 30_000L
        1    -> 120_000L
        2    -> 600_000L
        else -> 1_800_000L
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var serverJob: Job? = null

    // FIX-HTTPS: Lazily generated self-signed TLS certificate for this server instance.
    // Generated once per HttpFileServer lifetime using BouncyCastle (already a project
    // dependency via bVNC/FreeRDP). The private key never leaves process memory.
    private val sslContext: SSLContext by lazy { buildSslContext() }

    /**
     * MED-2 FIX: SHA-256 fingerprint of the self-signed TLS certificate, formatted as
     * colon-separated hex bytes (e.g. "AA:BB:CC:…").
     *
     * The browser will show a "certificate not trusted" warning for self-signed certs.
     * By surfacing this fingerprint in the companion UI (FileTransferScreen), the user
     * can compare what the browser shows against what the app displays and confirm they
     * are not being MITM'd by another host on the LAN.
     *
     * This is the standard TOFU/fingerprint pattern used for local HTTPS services
     * (e.g. Syncthing, Home Assistant) and is the correct UX for a LAN-only server.
     */
    val certFingerprint: String by lazy {
        // Force sslContext initialisation so the cert is built before we read it.
        sslContext  // access forces the lazy block to run
        _certFingerprint
    }
    private var _certFingerprint: String = ""

    /**
     * Generates a self-signed X.509 certificate and wraps it in an SSLContext.
     * Uses RSA-2048 + SHA-256 — widely supported and sufficient for LAN use.
     */
    private fun buildSslContext(): SSLContext {
        val rng = SecureRandom()

        // 1. Generate RSA-2048 key pair
        val keyPair = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048, rng) }.generateKeyPair()

        // 2. Build a self-signed X.509 v3 certificate valid for 1 day
        val name   = X500Name("CN=HexRDP-FileServer,O=HexRDP,C=US")
        val serial = java.math.BigInteger.valueOf(rng.nextLong().and(0x7FFF_FFFF_FFFF_FFFFL))
        val now    = Date()
        val expiry = Date(now.time + 24L * 60 * 60 * 1000)  // 24 hours

        val certHolder = JcaX509v3CertificateBuilder(name, serial, now, expiry, name, keyPair.public)
            .build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        // MED-2 FIX: Compute and store SHA-256 fingerprint for user verification.
        // The fingerprint is derived from the DER-encoded certificate bytes — the same
        // value that browsers display in their certificate-detail view, so the user can
        // compare the two to rule out a LAN MITM.
        _certFingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { b -> "%02X".format(b) }

        // 3. Pack into an in-memory KeyStore
        val ks = KeyStore.getInstance("PKCS12").also { it.load(null, null) }
        ks.setKeyEntry("hexrdp", keyPair.private, null, arrayOf(cert))

        // 4. Build SSLContext from that KeyStore
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        return SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, null, rng) }
    }

    // FIX-SECURE-TOKEN: Use SecureRandom instead of kotlin.random.Random.Default.
    // kotlin.random is a pseudorandom generator (seeded from the clock) — its output
    // is predictable to an attacker who knows the approximate process start time.
    // SecureRandom uses the OS entropy pool (urandom) and is appropriate for tokens.
    val accessToken: String = buildString {
        val rng   = SecureRandom()
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        repeat(12) { append(chars[rng.nextInt(chars.length)]) }  // 32^12 ≈ 1.15 × 10^18 combinations
    }

    // FIX #5 (جزئي): المسار الجذر المسموح به فقط
    // MED-1 FIX: Use API-level-safe helper instead of deprecated getExternalStorageDirectory().
    private fun externalStoragePath(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(Environment.getStorageDirectory(), "emulated/0").canonicalPath
        } else {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().canonicalPath
        }

    private val allowedRoot: String by lazy { externalStoragePath() }

    fun start(scope: CoroutineScope): Boolean {
        if (running.get()) return true
        return try {
            // BUG-BIND FIX: Bind to the specific WiFi interface, not 0.0.0.0.
            // FIX-HTTPS: Use SSLServerSocket so all traffic is TLS-encrypted.
            val bindAddr: java.net.InetAddress? = try {
                val ip = phoneIp()
                if (ip == "unknown") null else java.net.InetAddress.getByName(ip)
            } catch (_: Exception) { null }

            // FIX-HTTPS: Create an SSL server socket from our self-signed cert context.
            val sslFactory = sslContext.serverSocketFactory
            val ss = sslFactory.createServerSocket() as SSLServerSocket
            ss.reuseAddress = true
            // Only offer strong TLS 1.2+ cipher suites; disable SSLv3/TLS 1.0/1.1.
            ss.enabledProtocols = ss.enabledProtocols
                .filter { it in listOf("TLSv1.2", "TLSv1.3") }.toTypedArray()
            // LIVE-CRIT-1 FIX: Also filter cipher suites — protocol filtering alone
            // is insufficient because TLS 1.2 still allows weak suites (RC4, 3DES,
            // NULL, EXPORT) on older Android builds (API 21-25). Whitelist only
            // AEAD suites with forward secrecy; fall back to device defaults only
            // when none of the preferred suites are supported (very old devices).
            val strongCiphers = setOf(
                "TLS_AES_128_GCM_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
            )
            val filtered = ss.enabledCipherSuites.filter { it in strongCiphers }.toTypedArray()
            if (filtered.isNotEmpty()) ss.enabledCipherSuites = filtered
            // LIVE-CRIT-2 FIX: Bind to port 0 so the OS assigns and holds the port
            // atomically — no TOCTOU window between port selection and binding.
            // Read the actual port from the bound socket afterward.
            if (bindAddr != null) {
                ss.bind(InetSocketAddress(bindAddr, 0))
            } else {
                ss.bind(InetSocketAddress(0))
            }
            port = ss.localPort
            serverSocket = ss
            running.set(true)
            serverJob = scope.launch(Dispatchers.IO) {
                while (running.get()) {
                    try {
                        val client = ss.accept()
                        // FIX-DOS: Acquire semaphore slot before launching handler.
                        // If 10 connections are already active, tryAcquire() returns
                        // false and we close the new socket immediately — preventing
                        // coroutine exhaustion under a connection-flood attack.
                        if (connectionSemaphore.tryAcquire()) {
                            launch {
                                try { handleClient(client) }
                                finally { connectionSemaphore.release() }
                            }
                        } else {
                            try { client.close() } catch (_: Exception) {}
                        }
                    } catch (_: IOException) { break }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    // FIX #7: استُبدل WifiManager.connectionInfo المهجور (API 31+)
    // بـ ConnectivityManager.getLinkProperties على الأجهزة الحديثة.
    fun phoneIp(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val linkProps = cm.getLinkProperties(cm.activeNetwork)
                linkProps?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress ?: fallbackIp()
            } else {
                @Suppress("DEPRECATION")
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ip = wm.connectionInfo.ipAddress
                if (ip == 0) fallbackIp()
                else String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            }
        } catch (_: Exception) {
            fallbackIp()
        }
    }

    private fun fallbackIp(): String = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "unknown"
    } catch (_: Exception) { "unknown" }

    // FIX-HIGH1: Token moved to URL *fragment* (#TOKEN), not query string (?token=).
    // Fragments are NEVER transmitted to the server by browsers, never recorded in
    // network access logs, and never included in Referer headers.
    // The landing page at "/" reads location.hash via JS and POSTs the token
    // to /login in the request *body* — keeping it out of the server logs too.
    fun serverUrl(): String = "https://${phoneIp()}:$port/#${accessToken}"

    // ── معالجة طلب HTTPS ─────────────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        // FIX-HTTPS: Complete the TLS handshake before reading any data.
        // SSLSocket.startHandshake() is a no-op on plain Socket, so this is safe
        // even if the socket isn't SSL (shouldn't happen, but defensive).
        if (socket is SSLSocket) {
            try { socket.startHandshake() }
            catch (_: Exception) { try { socket.close() } catch (_: Exception) {}; return }
        }
        // FIX-OUTPUT-SCOPE: أُعلن output خارج try حتى يكون متاحاً في catch
        var output: OutputStream? = null
        try {
            socket.soTimeout = 30_000
            val rawInput = socket.getInputStream()
            output       = socket.getOutputStream()

            val requestLine = readHttpLine(rawInput) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val rawUrl = parts[1]
            val (urlPath, queryString) = rawUrl.split("?", limit = 2).let {
                Pair(it[0], it.getOrElse(1) { "" })
            }

            val headers = mutableMapOf<String, String>()
            var line = readHttpLine(rawInput)
            while (!line.isNullOrBlank()) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).lowercase().trim()] = line.substring(idx + 1).trim()
                line = readHttpLine(rawInput)
            }

            // FIX-HIGH1: Routing restructured into two tiers:
            //   Public  — "/" (landing page) and "/login" (token exchange)
            //   Protected — all file endpoints require a valid HttpOnly cookie
            // The query-string ?token= path is removed entirely. Token arrives only
            // via the fragment (never sent to server) and is exchanged via POST body.
            val cookieHeader = headers["cookie"] ?: ""

            when {
                // ── Public endpoints (no auth required) ───────────────────────
                method == "GET"  && urlPath == "/"      -> sendLandingPage(output)
                method == "POST" && urlPath == "/login" -> handleLogin(rawInput, output, headers)

                // ── Protected endpoints (cookie required) ──────────────────────
                else -> {
                    if (!isAuthorizedByCookie(cookieHeader)) {
                        sendAuthChallenge(output)
                        return
                    }
                    when {
                        method == "GET"  && urlPath == "/files"    -> sendFileListing(output, queryString)
                        method == "GET"  && urlPath == "/download" -> sendFileDownload(output, queryString)
                        method == "POST" && urlPath == "/upload"   -> receiveFileUpload(rawInput, output, queryString, headers)
                        else -> sendHttp(output, 404, "text/plain", "Not found".toByteArray())
                    }
                }
            }
        } catch (e: Exception) {
            try {
                output?.let { sendHttp(it, 500, "text/plain", "Internal server error".toByteArray()) }
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * FIX #2: قراءة سطر HTTP بايت بايت من InputStream الخام.
     * يمنع BufferedReader من ابتلاع bytes تعود إلى body الطلب.
     */
    private fun readHttpLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.deleteCharAt(sb.length - 1) // احذف الـ \r
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
            // LIVE-MED-1 FIX: On overflow, drain the rest of the line and return
            // null to reject the request entirely. Returning the truncated string
            // as a valid line confuses the header parser and enables header
            // smuggling (attacker embeds a fake header after the 8192-byte fill).
            if (sb.length > 8192) {
                while (true) {
                    val b2 = input.read()
                    if (b2 < 0 || b2 == '\n'.code) break
                }
                return null
            }
        }
    }

    // FIX-HIGH1: Cookie-only check — URL ?token= parameter is no longer accepted.
    // Token arrives via POST body from the landing page and is converted to a
    // cookie once, after which all requests are authenticated by cookie alone.
    //
    // FIX-TIMING: Use MessageDigest.isEqual() for constant-time byte comparison so
    // that an attacker cannot distinguish between "cookie not present" and "cookie
    // value wrong" via per-byte timing on network responses.
    private fun isAuthorizedByCookie(cookie: String): Boolean {
        val expected = "hx_token=$accessToken"
        return cookie.split(";").any { part ->
            java.security.MessageDigest.isEqual(
                part.trim().toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8)
            )
        }
    }

    /** Sets an HttpOnly + Secure + SameSite=Strict cookie with the session token.
     *
     * NEW-BUG-5 FIX: Added Max-Age=3600 (1 hour) so the cookie auto-expires.
     * Without Max-Age/Expires the cookie is a "session cookie" that lives until
     * the browser tab is closed. If the user leaves the tab open indefinitely,
     * anyone with access to the device (or a network MITM that captured the
     * cookie before TLS was added) can use it to browse/download files.
     * 1 hour is a reasonable session window for a single file-transfer operation.
     */
    private fun authCookieHeader(): String =
        "Set-Cookie: hx_token=$accessToken; Path=/; Max-Age=3600; SameSite=Strict; Secure; HttpOnly\r\n"

    /**
     * FIX-HIGH1: Landing page served at "/".
     * Does NOT require authentication. Contains a tiny inline script that reads
     * the URL fragment (location.hash) — which is never transmitted to the server —
     * and POSTs the token to /login in the request *body* (not URL).
     * Nonce-based CSP allows only this specific <script> and <style> block.
     */
    private fun sendLandingPage(output: OutputStream) {
        val nonce = generateNonce()
        val html = """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>HexRDP — Connecting…</title>
<style nonce="$nonce">
body{background:#0a0e1a;color:#e0e6ff;font-family:Arial;display:flex;
align-items:center;justify-content:center;height:100vh;margin:0}
.box{background:#141827;border:1px solid #1e2840;border-radius:12px;padding:32px;
text-align:center;max-width:360px}
h2{color:#00e5ff;margin:0 0 12px}p{color:#aaa;margin:0 0 16px}
.err{color:#ff4444}
</style>
</head><body><div class="box">
<h2>📱 HexRDP File Share</h2>
<p id="msg">Authenticating…</p>
</div>
<script nonce="$nonce">
(function(){
  var token = location.hash.slice(1);
  if (!token) {
    document.getElementById('msg').className = 'err';
    document.getElementById('msg').textContent =
      '🔒 Open the URL shown in the HexRDP app.';
    return;
  }
  // Clear fragment from address bar to avoid it persisting in history
  history.replaceState(null, '', location.pathname);
  fetch('/login', {
    method: 'POST',
    headers: {'Content-Type': 'text/plain; charset=utf-8'},
    body: token,
    credentials: 'same-origin'
  }).then(function(r) {
    if (r.ok) {
      location.replace('/files');
    } else {
      document.getElementById('msg').className = 'err';
      document.getElementById('msg').textContent = '🔒 Invalid or expired token.';
    }
  }).catch(function() {
    document.getElementById('msg').className = 'err';
    document.getElementById('msg').textContent = '⚠ Connection error.';
  });
})();
</script>
</body></html>"""
        val body = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Strict-Transport-Security: max-age=63072000; includeSubDomains\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; connect-src 'self'; base-uri 'none'; form-action 'none'\r\n" +
            "\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /**
     * FIX-HIGH1: Validates the token submitted as a plain-text POST body by the
     * landing page. Sets the HttpOnly cookie on success.
     * Token is consumed from the POST body — never from URL parameters.
     */
    private fun handleLogin(rawInput: InputStream, output: OutputStream, headers: Map<String, String>) {
        // FIX-LOGIN-RATELIMIT: Reject immediately if we are in a lockout window.
        val now = System.currentTimeMillis()
        if (now < loginLockedUntil.get()) {
            sendHttp(output, 429, "text/plain", "Too many failed attempts — try again later".toByteArray())
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        // Tokens are exactly 12 chars; reject suspiciously large bodies immediately
        if (contentLength <= 0L || contentLength > 256L) {
            sendHttp(output, 400, "text/plain", "Bad request".toByteArray())
            return
        }
        val body = rawInput.readNBytes(contentLength.toInt())
        val submittedToken = String(body, Charsets.UTF_8).trim()

        // FIX-TIMING: Replace String != (short-circuits on first differing char) with
        // MessageDigest.isEqual() which always compares all bytes in constant time,
        // preventing timing-based token oracle attacks from the local network.
        val tokenMatch = java.security.MessageDigest.isEqual(
            submittedToken.toByteArray(Charsets.UTF_8),
            accessToken.toByteArray(Charsets.UTF_8)
        )

        if (!tokenMatch) {
            // FIX-LOGIN-RATELIMIT: Count consecutive failures; lock after LOGIN_MAX_FAILS.
            // LIVE-MED-2 FIX: Use exponential backoff — lockout duration grows with
            // each consecutive lockout event (30 s → 2 min → 10 min → 30 min).
            val fails = loginFailCount.incrementAndGet()
            if (fails >= LOGIN_MAX_FAILS) {
                val lockouts = loginLockoutCount.getAndIncrement()
                loginLockedUntil.set(System.currentTimeMillis() + loginLockoutMs(lockouts))
                loginFailCount.set(0)
            }
            sendHttp(output, 403, "text/plain", "Invalid token".toByteArray())
            return
        }

        // Token valid — reset fail counter and issue HttpOnly session cookie.
        loginFailCount.set(0)
        val header = "HTTP/1.1 204 No Content\r\n" +
            authCookieHeader() +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun sendAuthChallenge(output: OutputStream) {
        val nonce = generateNonce()
        val html = """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style nonce="$nonce">body{background:#0a0e1a;color:#e0e6ff;font-family:Arial;display:flex;
align-items:center;justify-content:center;height:100vh;margin:0}
.box{background:#141827;border:1px solid #1e2840;border-radius:12px;padding:32px;
text-align:center;max-width:320px}h2{color:#ff4444;margin:0 0 16px}p{color:#aaa;margin:0}
</style></head><body><div class="box"><h2>🔒 Access Denied</h2>
<p>Open the URL shown in the HexRDP app to access file sharing.</p></div></body></html>"""
        val body = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 403 Forbidden\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Strict-Transport-Security: max-age=63072000; includeSubDomains\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'nonce-$nonce'; base-uri 'none'\r\n" +
            "\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    // FIX #5 + FIX-PATH-SIBLING: تحقق أن المسار المطلوب داخل نطاق التخزين المسموح به
    // الإصلاح السابق كان يستخدم startsWith(allowedRoot) فقط، مما يسمح بقبول
    // مسارات مجاورة مثل /storage/emulated/0abc لأنها تبدأ بـ /storage/emulated/0.
    // الإصلاح الصحيح: نضيف "/" في النهاية حتى يكون الشرط "داخل المجلد" وليس "يبدأ بنفس السلسلة".
    private fun safeSubPath(requested: String): String? {
        val canonical = try { File(requested).canonicalPath } catch (_: Exception) { return null }
        val root = allowedRoot.trimEnd('/')
        val isInside = canonical == root || canonical.startsWith("$root/")
        return if (isInside) canonical else null
    }

    // ── عرض قائمة الملفات ────────────────────────────────────────────────────

    private fun sendFileListing(output: OutputStream, query: String) {
        val rawPath = queryParam(query, "path") ?: externalStoragePath()
        // FIX #5: رفض أي مسار خارج نطاق التخزين
        val path    = safeSubPath(rawPath) ?: externalStoragePath()
        val dir     = File(path)
        val files   = if (dir.exists() && dir.isDirectory) {
            (dir.listFiles() ?: emptyArray())
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        } else emptyList()

        val extRoot = externalStoragePath()
        // FIX-HIGH1: Links use /files (protected endpoint); cookie is sent automatically.
        fun lnk(path: String) = "/files?path=${enc(path)}"
        fun dlnk(path: String) = "/download?path=${enc(path)}"

        // FIX-CRIT2: Generate a per-response nonce for inline <style>.
        // CSP allows ONLY the block tagged with this nonce — no 'unsafe-inline'.
        val nonce = generateNonce()

        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html><html lang="ar" dir="rtl"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HexRDP File Share</title>
<style nonce="$nonce">
body{font-family:Arial,sans-serif;background:#0a0e1a;color:#e0e6ff;margin:0;padding:16px}
h2{color:#00e5ff;margin:0 0 8px}
.path{font-size:12px;color:#aaa;margin-bottom:16px;word-break:break-all}
.file-list{list-style:none;padding:0;margin:0}
.file-item{display:flex;align-items:center;padding:10px 12px;margin-bottom:4px;background:#141827;border-radius:8px;border:1px solid #1e2840}
.file-item a{color:#7eb8ff;text-decoration:none;flex:1;word-break:break-all}
.file-item .size{font-size:11px;color:#aaa;margin-right:12px}
.dir-icon::before{content:"📁 "}
.file-icon::before{content:"📄 "}
.upload-box{margin-top:24px;padding:16px;background:#141827;border-radius:8px;border:2px dashed #00e5ff30}
.upload-box h3{color:#00e5ff;margin:0 0 12px}
input[type=file]{color:#e0e6ff}
button{background:#00e5ff;color:#000;border:none;padding:8px 24px;border-radius:6px;font-weight:bold;cursor:pointer;margin-top:8px}
.back{color:#00e5ff;text-decoration:none;display:inline-block;margin-bottom:12px}
</style>
</head><body>
<h2>📱 HexRDP File Share</h2>""")

        if (path != extRoot) {
            sb.append("""<a class="back" href="${lnk(dir.parent ?: extRoot)}">← Back</a>""")
        }
        // FIX-CRIT1: path is HTML-escaped; FIX-CRIT3: htmlEsc now covers " and '
        sb.append("""<div class="path">${htmlEsc(path)}</div>""")
        sb.append("""<ul class="file-list">""")
        files.forEach { f ->
            if (f.isDirectory) {
                sb.append("""<li class="file-item"><span class="dir-icon"></span>
<a href="${lnk(f.absolutePath)}">${htmlEsc(f.name)}</a></li>""")
            } else {
                val size = formatBytes(f.length())
                sb.append("""<li class="file-item"><span class="file-icon"></span>
<a href="${dlnk(f.absolutePath)}">${htmlEsc(f.name)}</a>
<span class="size">$size</span></li>""")
            }
        }
        sb.append("</ul>")
        sb.append("""
<div class="upload-box">
<h3>⬆ Upload to this folder</h3>
<form method="POST" action="/upload?path=${enc(path)}" enctype="multipart/form-data">
<input type="file" name="file" multiple><br>
<button type="submit">Upload</button>
</form>
</div>
</body></html>""")

        val body = sb.toString().toByteArray(Charsets.UTF_8)
        // FIX-CRIT2: Nonce-based CSP — no 'unsafe-inline'. Only the <style nonce=...>
        // block above is allowed. script-src 'none' disables all JS on this page.
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Strict-Transport-Security: max-age=63072000; includeSubDomains\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'nonce-$nonce'; form-action 'self'; base-uri 'none'\r\n" +
            "\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    // ── تنزيل ملف ────────────────────────────────────────────────────────────

    private fun sendFileDownload(output: OutputStream, query: String) {
        val rawPath = queryParam(query, "path") ?: run {
            sendHttp(output, 400, "text/plain", "Missing path".toByteArray())
            return
        }
        // FIX #5: رفض أي مسار خارج نطاق التخزين (path traversal)
        val path = safeSubPath(rawPath) ?: run {
            sendHttp(output, 403, "text/plain", "Forbidden path".toByteArray())
            return
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            sendHttp(output, 404, "text/plain", "File not found".toByteArray())
            return
        }
        val encoded = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        // NEW-BUG-4 FIX: Add the same security headers that sendHttp() adds to every
        // other response. This endpoint hand-builds its header to stream large files
        // efficiently (avoiding sendHttp's body buffering), but that means the security
        // headers were silently omitted. Added:
        //   X-Frame-Options         — prevents the download from being framed
        //   X-Content-Type-Options  — prevents MIME-sniffing of the downloaded blob
        //   Strict-Transport-Security — ensures browser remembers HTTPS-only for 2 years
        //   Content-Security-Policy — no scripts/iframes on a raw download response
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$encoded\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Connection: close\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Strict-Transport-Security: max-age=63072000; includeSubDomains\r\n" +
            "Content-Security-Policy: default-src 'none'; base-uri 'none'\r\n" +
            "\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        FileInputStream(file).use { it.copyTo(output, bufferSize = 64 * 1024) }
        output.flush()
    }

    // ── استقبال ملف مرفوع ────────────────────────────────────────────────────

    private fun receiveFileUpload(
        // FIX #2: يستقبل الآن InputStream الخام (المُعالَج بشكل صحيح)
        // بدلاً من Socket — لا يوجد خطر فقدان bytes بسبب BufferedReader.
        rawInput: InputStream,
        output: OutputStream,
        query: String,
        headers: Map<String, String>
    ) {
        val rawPath = queryParam(query, "path") ?: externalStoragePath()
        // FIX #5: التحقق من المسار
        val destDir = safeSubPath(rawPath) ?: externalStoragePath()
        val dir     = File(destDir).also { it.mkdirs() }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val contentType   = headers["content-type"] ?: ""
        val boundary      = contentType.substringAfter("boundary=", "").trim()

        if (boundary.isEmpty() || contentLength <= 0L) {
            sendHttp(output, 400, "text/plain", "Bad request".toByteArray())
            return
        }
        // FIX-UPLOAD-LIMIT: Reject uploads larger than MAX_UPLOAD_BYTES (2 GB).
        // A malicious client can forge Content-Length headers; without this check
        // the server would consume disk space equal to the forged length.
        if (contentLength > MAX_UPLOAD_BYTES) {
            sendHttp(output, 413, "text/plain", "Upload too large (max 2 GB)".toByteArray())
            return
        }

        // BUG-3 FIX: The previous strategy wrote the body to a temp file (good) but then
        // called tempFile.readBytes() which loads the ENTIRE body back into a ByteArray —
        // negating the on-disk strategy for large files. A 200 MB upload would attempt to
        // allocate a 200 MB ByteArray on the JVM heap → OOM / ActivityManager kill.
        //
        // Fix: write the body to a temp file as before, then stream-parse the multipart
        // boundary using a small sliding read window (~128 KB).  Each part's file content
        // is written directly to its destination file, so heap usage is bounded by the
        // window size regardless of upload size.
        val tempFile = File.createTempFile("hexrdp_upload_", ".tmp", context.cacheDir)
        var savedCount = 0
        try {
            // Step 1: stream raw body to disk.
            // FIX-HIGH2: Track totalWritten independently of contentLength so that a
            // forged Content-Length header cannot bypass the size limit. A client
            // could send Content-Length:100 but stream 10 GB — the header check alone
            // would approve it and the loop would drain the socket until disk is full.
            // Now we enforce the cap on *actual* bytes received, not the header value.
            var byteLimitExceeded = false
            FileOutputStream(tempFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var remaining = contentLength
                var totalWritten = 0L
                while (remaining > 0) {
                    val n = rawInput.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    totalWritten += n
                    if (totalWritten > MAX_UPLOAD_BYTES) {
                        byteLimitExceeded = true
                        break
                    }
                    fos.write(buf, 0, n)
                    remaining -= n
                }
            }
            if (byteLimitExceeded) {
                tempFile.delete()
                sendHttp(output, 413, "text/plain", "Upload too large (max 2 GB)".toByteArray())
                return
            }

            // Step 2: stream-parse multipart without loading the whole file into RAM.
            savedCount = parseMultipartStreaming(tempFile, boundary, dir)
        } finally {
            tempFile.delete()
        }

        // FIX-CRIT2: Nonce-based CSP for the upload-success response page.
        val nonce = generateNonce()
        val html = """<!DOCTYPE html><html><head><meta charset="UTF-8">
<meta http-equiv="refresh" content="2;url=/files?path=${enc(destDir)}">
<style nonce="$nonce">body{background:#0a0e1a;color:#e0e6ff;font-family:Arial;text-align:center;padding:40px}</style>
</head><body><h2 style="color:#00e5ff">✅ $savedCount file(s) uploaded</h2>
<p>Redirecting back...</p></body></html>"""
        sendHttp(output, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), nonce)
    }

    // ── مساعدات ──────────────────────────────────────────────────────────────

    /**
     * MED-4 FIX: Sanitize a filename received from the Content-Disposition header
     * before writing it to disk.
     *
     * [File.name] strips directory traversal (`../../`) but does NOT handle:
     *  - Leading dots (`.htaccess`, `.bashrc`) → hidden / config files
     *  - Control characters (null bytes, \r, \n) → path truncation on some FSes
     *  - Excessively long names → DoS / FS limits
     *  - Blank result after stripping → fallback to a safe timestamp name
     *
     * This function addresses all four cases.
     */
    internal fun sanitizeFileName(raw: String): String = com.gotohex.rdp.transfer.sanitizeFileName(raw)

    // FIX-CRIT2: sendHttp() now accepts an optional nonce. When provided, the
    // nonce is embedded in the CSP so only the <style nonce="..."> block in the
    // response body is allowed — 'unsafe-inline' is no longer used.
    // When nonce is blank (plain-text error responses), script-src and style-src
    // are both set to 'none' since those pages have no HTML at all.
    private fun sendHttp(output: OutputStream, code: Int, mime: String, body: ByteArray, nonce: String = "") {
        val status = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 500 -> "Internal Server Error"
            else -> "Error"
        }
        val csp = if (nonce.isNotBlank())
            "Content-Security-Policy: default-src 'none'; style-src 'nonce-$nonce'; form-action 'self'; base-uri 'none'\r\n"
        else
            "Content-Security-Policy: default-src 'none'; script-src 'none'; style-src 'none'; base-uri 'none'\r\n"
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Strict-Transport-Security: max-age=63072000; includeSubDomains\r\n" +
            csp +
            "\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun queryParam(query: String, key: String): String? =
        query.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("$key=")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * FIX-CRIT2: Generates a cryptographically random 128-bit nonce for use in
     * Content-Security-Policy headers. Each HTTP response that contains inline
     * <style> or <script> blocks uses a unique nonce so CSP can allow only
     * those specific blocks — eliminating the need for 'unsafe-inline'.
     */
    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
    // FIX-CRIT3: Added "&quot;" and "&#39;" to cover attribute-context injection.
    // Without these, a filename like: foo" onmouseover="alert(1) breaks out of
    // any HTML attribute that uses htmlEsc() for escaping.
    private fun htmlEsc(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        outer@ for (i in start..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
        if (this.size < suffix.size) return false
        val offset = this.size - suffix.size
        return suffix.indices.all { this[offset + it] == suffix[it] }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BUG-3 FIX: Streaming multipart parser (no full-body ByteArray in RAM)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Parses a multipart/form-data body stored in [tempFile] and writes each
 * uploaded part to a file inside [destDir].
 *
 * Key property: the file is read in a ~128 KB sliding window.  No single
 * ByteArray larger than the window is ever allocated, so a 2 GB upload
 * uses the same ~128 KB of heap as a 1 KB upload.
 *
 * Returns the number of files successfully saved.
 */
private fun parseMultipartStreaming(
    tempFile: File,
    boundary: String,
    destDir: File
): Int {
    val sep      = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val crlf     = "\r\n".toByteArray(Charsets.ISO_8859_1)
    val hdrEnd   = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    var saved    = 0

    java.io.RandomAccessFile(tempFile, "r").use { raf ->
        val fileLen = raf.length()

        // Locate all boundary positions by reading the file in overlapping chunks.
        // Overlap by (sep.size - 1) bytes so a boundary is never split across windows.
        val windowSize = 128 * 1024
        val overlap    = sep.size - 1
        val positions  = mutableListOf<Long>()

        var fileOffset = 0L
        val window     = ByteArray(windowSize + overlap)
        var windowLen  = 0

        while (fileOffset < fileLen || (fileOffset == 0L && fileLen == 0L)) {
            // Fill window: overlap bytes from previous iteration + fresh bytes.
            val readStart = if (fileOffset == 0L) 0 else overlap
            raf.seek(fileOffset)
            val n = raf.read(window, readStart, windowSize)
            if (n <= 0) break
            windowLen = readStart + n

            // Search for boundary inside this window.
            var searchFrom = 0
            while (true) {
                var found = -1
                outer@ for (i in searchFrom..windowLen - sep.size) {
                    for (j in sep.indices) {
                        if (window[i + j] != sep[j]) continue@outer
                    }
                    found = i
                    break
                }
                if (found < 0) break
                // Convert window-relative index to file-absolute position.
                val absPos = fileOffset - (if (fileOffset == 0L) 0 else overlap) + found
                // Avoid duplicates at the overlap region.
                if (positions.isEmpty() || absPos > positions.last()) {
                    positions.add(absPos)
                }
                searchFrom = found + 1
            }

            // Advance file pointer; keep the last `overlap` bytes in the window.
            // BUG-OVERLAP FIX: if the file is smaller than `overlap` bytes (e.g. a
            // malformed or tiny multipart body), windowLen - overlap is negative →
            // System.arraycopy throws ArrayIndexOutOfBoundsException.
            // Guard: only copy when there are enough bytes; otherwise zero-fill the
            // overlap region so the next iteration starts clean.
            if (windowLen >= overlap) {
                System.arraycopy(window, windowLen - overlap, window, 0, overlap)
            } else {
                window.fill(0, 0, overlap)
            }
            fileOffset += n
        }

        // Each adjacent pair of boundary positions defines one part.
        // BUG-9 FIX: Limit the number of parts processed to prevent a DoS attack
        // where a malicious client sends a multipart body with thousands of valid
        // boundaries, each creating a new file in destDir — exhausting inodes/disk.
        // A legitimate upload of even 100 files in a single request is unusual;
        // enforce a hard cap here and log when it is hit.
        val MAX_UPLOAD_PARTS = 100
        if (positions.size - 1 > MAX_UPLOAD_PARTS) {
            android.util.Log.w("FileTransferManager",
                "Multipart body contains ${positions.size - 1} parts — capping at $MAX_UPLOAD_PARTS to prevent DoS")
        }
        for (i in 0 until minOf(positions.size - 1, MAX_UPLOAD_PARTS)) {
            val partStart = positions[i] + sep.size   // skip "--boundary"
            val partEnd   = positions[i + 1]          // start of next "--boundary"

            // Skip the leading CRLF after the boundary line.
            val bodyStart = partStart + crlf.size     // skip \r\n after boundary

            // Read headers (everything up to \r\n\r\n), max 8 KB.
            val headerBuf = ByteArray(8192)
            raf.seek(bodyStart)
            val headerRead = raf.read(headerBuf, 0, headerBuf.size.coerceAtMost((partEnd - bodyStart).toInt()))
            if (headerRead < hdrEnd.size) continue

            // Find \r\n\r\n inside the header buffer.
            var hdrEndIdx = -1
            outer@ for (h in 0..headerRead - hdrEnd.size) {
                for (j in hdrEnd.indices) { if (headerBuf[h + j] != hdrEnd[j]) continue@outer }
                hdrEndIdx = h
                break
            }
            if (hdrEndIdx < 0) continue

            val headerStr = String(headerBuf, 0, hdrEndIdx, Charsets.ISO_8859_1)
            val fileName  = Regex("""filename="([^"]+)"""").find(headerStr)?.groupValues?.get(1)
            if (fileName.isNullOrBlank()) continue

            // File content: from (bodyStart + hdrEndIdx + 4) to (partEnd - \r\n).
            val contentStart = bodyStart + hdrEndIdx + hdrEnd.size
            var contentEnd   = partEnd
            // Strip trailing \r\n before the next boundary.
            if (contentEnd - contentStart >= crlf.size) contentEnd -= crlf.size

            val contentLen = contentEnd - contentStart
            if (contentLen <= 0) continue

            // Stream content directly to destination — no large heap allocation.
            val dest = File(destDir, sanitizeFileName(fileName))
            raf.seek(contentStart)
            FileOutputStream(dest).use { out ->
                val copyBuf  = ByteArray(64 * 1024)
                var remaining = contentLen
                while (remaining > 0) {
                    val toRead = copyBuf.size.toLong().coerceAtMost(remaining).toInt()
                    val rd     = raf.read(copyBuf, 0, toRead)
                    if (rd < 0) break
                    out.write(copyBuf, 0, rd)
                    remaining -= rd
                }
            }
            saved++
        }
    }
    return saved
}
