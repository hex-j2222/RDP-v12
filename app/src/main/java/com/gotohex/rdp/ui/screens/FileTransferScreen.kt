package com.gotohex.rdp.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.media.MediaScannerConnection
import androidx.core.content.ContextCompat
import com.gotohex.rdp.R
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.transfer.*
import com.gotohex.rdp.ui.theme.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Entry point: FileTransferDialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FileTransferDialog(
    profile: RdpProfile,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        FileTransferScreen(profile = profile, onDismiss = onDismiss)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Screen Composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FileTransferScreen(
    profile: RdpProfile,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val isSsh   = profile.protocolType == ProtocolType.SSH

    // ── Phone file state ─────────────────────────────────────────────────────
    var phonePath    by remember { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    var phoneFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var phoneSpace   by remember { mutableStateOf(PhoneFileBrowser.phoneStorageSpace()) }
    var phoneLoading by remember { mutableStateOf(false) }
    // FIX #4: كان selectedFile مشتركاً بين اللوحتين، مما يجعل زر Download
    // يُرسل مسار الهاتف كـ remotePath والعكس. الحل: متغيران منفصلان.
    var phoneSelected  by remember { mutableStateOf<HexFile?>(null) }
    var remoteSelected by remember { mutableStateOf<HexFile?>(null) }

    // ── Remote / SSH SFTP state ───────────────────────────────────────────────
    var remotePath    by remember { mutableStateOf("/") }
    var remoteFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError   by remember { mutableStateOf<String?>(null) }
    val sftp          = remember {
        if (isSsh) SftpFileBrowser(SftpConfig(
            host                 = profile.host,
            port                 = profile.port,
            username             = profile.username,
            password             = profile.password,
            // CRIT-1 FIX: SftpConfig now uses non-nullable CharArray fields with "" default;
            // convert null (field not configured) to "" so the CharArray is empty rather than null.
            privateKeyPem        = profile.sshPrivateKey.takeIf { it.isNotBlank() } ?: "",
            privateKeyPassphrase = profile.sshPrivateKeyPassphrase.takeIf { it.isNotBlank() } ?: ""
        ), appContext = context) else null  // FIX-SFTP-TOFU: pass context for TOFU key persistence
    }

    // ── HTTP server state (for RDP / VNC) ────────────────────────────────────
    val httpServer = remember { if (!isSsh) HttpFileServer(context) else null }
    var serverRunning by remember { mutableStateOf(false) }
    var serverUrl     by remember { mutableStateOf("") }

    // ── Transfer progress ────────────────────────────────────────────────────
    var transferProgress by remember { mutableStateOf<TransferProgress>(TransferProgress.Idle) }

    // ── Storage permission state ─────────────────────────────────────────────
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasStoragePermission = results.values.any { it }
        if (hasStoragePermission) loadPhoneFiles(scope, phonePath) { phoneFiles = it; phoneLoading = false }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = checkStoragePermission(context)
    }

    // ── Load phone files ─────────────────────────────────────────────────────
    fun reloadPhone() {
        if (!hasStoragePermission) return
        phoneLoading = true
        phoneSpace   = PhoneFileBrowser.phoneStorageSpace()
        loadPhoneFiles(scope, phonePath) { phoneFiles = it; phoneLoading = false }
    }

    LaunchedEffect(phonePath, hasStoragePermission) { reloadPhone() }

    // ── SSH SFTP connect + load ───────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (isSsh && sftp != null) {
            remoteLoading = true
            remoteError   = null
            withContext(Dispatchers.IO) {
                try {
                    sftp.connect()
                    remotePath  = sftp.homeDir()
                    remoteFiles = sftp.listDir(remotePath)
                } catch (e: Exception) {
                    remoteError = e.message ?: context.getString(R.string.ft_error_sftp_connect)
                }
            }
            remoteLoading = false
        }
    }

    fun reloadRemote() {
        if (!isSsh || sftp == null) return
        remoteLoading = true
        remoteError   = null
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (!sftp.isConnected()) sftp.connect()
                    remoteFiles = sftp.listDir(remotePath)
                } catch (e: Exception) {
                    remoteError = e.message ?: context.getString(R.string.ft_error_list_dir)
                }
            }
            remoteLoading = false
        }
    }

    LaunchedEffect(remotePath) { if (isSsh) reloadRemote() }

    // ── HTTP server lifecycle ─────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (!isSsh && httpServer != null) {
            serverRunning = httpServer.start(scope)
            serverUrl     = httpServer.serverUrl()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            httpServer?.stop()
            // BUG-2 FIX: scope (rememberCoroutineScope) is cancelled at the same moment
            // the composable leaves the composition — i.e. exactly when onDispose runs.
            // Calling scope.launch() on an already-cancelled scope silently throws
            // CancellationException and the disconnect never executes, leaving the
            // JSch session open until the server times it out.
            // Fix: use a plain Thread so the disconnect runs outside the Compose scope.
            val sftpRef = sftp
            if (sftpRef != null) {
                Thread { try { sftpRef.disconnect() } catch (_: Exception) {} }.start()
            }
        }
    }

    // ── Upload (phone → remote SSH) ───────────────────────────────────────────
    fun uploadSelected() {
        // FIX #4: يستخدم phoneSelected فقط — ملف الهاتف دائماً
        val sel = phoneSelected ?: return
        if (!isSsh || sftp == null) return
        transferProgress = TransferProgress.Running(sel.name, 0L, sel.size, true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    sftp.uploadFile(sel.path, "$remotePath/${sel.name}") { p ->
                        transferProgress = p
                    }
                }
                reloadRemote()
            } catch (e: Exception) {
                transferProgress = TransferProgress.Failure(e.message ?: context.getString(R.string.ft_error_upload))
            }
            phoneSelected = null
        }
    }

    // ── Download (remote SSH → phone) ─────────────────────────────────────────
    fun downloadSelected() {
        // FIX #4: يستخدم remoteSelected فقط — ملف الخادم دائماً
        val sel = remoteSelected ?: return
        if (!isSsh || sftp == null) return
        transferProgress = TransferProgress.Running(sel.name, 0L, sel.size, false)
        scope.launch {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ).absolutePath
                withContext(Dispatchers.IO) {
                    sftp.downloadFile(sel.path, downloadDir) { p -> transferProgress = p }
                }
                // BUG-5 FIX: On API >= 29, files written directly to the filesystem are
                // not visible in Files / Gallery apps until MediaStore is notified.
                // scanFile() is asynchronous and cheap; it triggers a media database
                // insert so the file appears immediately without a device reboot.
                val savedFile = File(downloadDir, sel.name)
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(savedFile.absolutePath),
                    null,   // let MediaScanner detect MIME type from extension
                    null    // no callback needed
                )
                reloadPhone()
            } catch (e: Exception) {
                transferProgress = TransferProgress.Failure(e.message ?: context.getString(R.string.ft_error_download))
            }
            remoteSelected = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp),
        color  = DeepSpace,
        shape  = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HorizonGray)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Header ───────────────────────────────────────────────────────
            FtHeader(
                protocolLabel = profile.protocolType.label,
                profileName   = profile.name,
                onClose       = onDismiss
            )

            // ── Permission banner ─────────────────────────────────────────────
            if (!hasStoragePermission) {
                PermissionBanner(
                    onRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            !Environment.isExternalStorageManager()
                        ) {
                            manageStorageLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"))
                            )
                        } else {
                            storagePermLauncher.launch(storagePermissions())
                        }
                    }
                )
            }

            // ── Transfer progress bar ─────────────────────────────────────────
            AnimatedVisibility(
                visible = transferProgress !is TransferProgress.Idle,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                TransferProgressBanner(
                    progress  = transferProgress,
                    onDismiss = { transferProgress = TransferProgress.Idle }
                )
            }

            // ── Main content ──────────────────────────────────────────────────
            // UI-FIX: a fixed 50/50 side-by-side split made each panel only
            // ~170-200dp wide on a typical phone — too narrow for a file name +
            // icon + size + path bar. This was a desktop split-pane pattern
            // ported as-is to a phone screen. Now: BoxWithConstraints picks the
            // layout — tabs (single full-width panel) below the tablet/landscape
            // breakpoint, the original side-by-side split above it.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val isWide = maxWidth >= 600.dp
                var activeTab by remember { mutableStateOf(0) } // 0 = phone, 1 = remote/server

                val phonePanel: @Composable ColumnScope.() -> Unit = {
                    FilePanelHeader(
                        title       = stringResource(R.string.ft_phone_files),
                        path        = phonePath,
                        rootPath    = Environment.getExternalStorageDirectory().absolutePath,
                        space       = phoneSpace,
                        icon        = Icons.Default.PhoneAndroid,
                        accentColor = PulsarCyan
                    )
                    if (phoneLoading) {
                        LoadingIndicator()
                    } else {
                        FileList(
                            files          = phoneFiles,
                            selectedFile   = phoneSelected,
                            isPhoneSide    = true,
                            onNavigate     = { f ->
                                if (f.isDirectory) phonePath = f.path
                                else phoneSelected = if (phoneSelected == f) null else f
                            },
                            onSelectToggle = { f -> phoneSelected = if (phoneSelected == f) null else f }
                        )
                    }
                }

                val remotePanel: @Composable ColumnScope.() -> Unit = {
                    if (isSsh) {
                        FilePanelHeader(
                            title       = stringResource(R.string.ft_remote_files),
                            path        = remotePath,
                            rootPath    = "/",
                            space       = null,
                            icon        = Icons.Default.Computer,
                            accentColor = NovaPink
                        )
                        when {
                            remoteLoading -> LoadingIndicator()
                            remoteError != null -> remoteError?.let { ErrorPanel(it) { reloadRemote() } }
                            else -> FileList(
                                files          = remoteFiles,
                                selectedFile   = remoteSelected,
                                isPhoneSide    = false,
                                onNavigate     = { f ->
                                    if (f.isDirectory) remotePath = f.path
                                    else remoteSelected = if (remoteSelected == f) null else f
                                },
                                onSelectToggle = { f -> remoteSelected = if (remoteSelected == f) null else f }
                            )
                        }
                    } else {
                        HttpServerPanel(
                            running   = serverRunning,
                            url       = serverUrl,
                            phonePath = phonePath
                        )
                    }
                }

                val transferActions: @Composable ColumnScope.() -> Unit = {
                    if (isSsh) {
                        val canUp = phoneSelected != null && phoneSelected?.isDirectory == false
                        IconButton(
                            onClick  = { if (canUp) uploadSelected() },
                            enabled  = canUp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.ft_upload_tip),
                                tint   = if (canUp) PlasmaGreen else HorizonGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val canDn = remoteSelected != null && remoteSelected?.isDirectory == false
                        IconButton(
                            onClick  = { if (canDn) downloadSelected() },
                            enabled  = canDn,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.ft_download_tip),
                                tint   = if (canDn) PulsarCyan else HorizonGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.SyncAlt,
                            contentDescription = null,
                            tint     = HorizonGray,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (isWide) {
                    // ── Tablet / landscape: original side-by-side split ────────
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxHeight(), content = phonePanel)
                        Column(
                            modifier            = Modifier.width(48.dp).fillMaxHeight().background(NebulaSurface),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            content             = transferActions
                        )
                        Column(Modifier.weight(1f).fillMaxHeight(), content = remotePanel)
                    }
                } else {
                    // ── Phone: full-width tabs, one panel visible at a time ─────
                    Column(Modifier.fillMaxSize()) {
                        TabRow(
                            selectedTabIndex = activeTab,
                            containerColor   = Color.Transparent,
                            contentColor     = PulsarCyan
                        ) {
                            Tab(
                                selected = activeTab == 0,
                                onClick  = { activeTab = 0 },
                                text     = { Text(stringResource(R.string.ft_phone_files), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            Tab(
                                selected = activeTab == 1,
                                onClick  = { activeTab = 1 },
                                text     = {
                                    Text(
                                        if (isSsh) stringResource(R.string.ft_remote_files) else stringResource(R.string.ft_server_info),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                        if (isSsh) {
                            Row(
                                modifier              = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, content = transferActions)
                            }
                        }
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            if (activeTab == 0) phonePanel() else remotePanel()
                        }
                    }
                }
            }

            // ── Bottom path bar ───────────────────────────────────────────────
            BottomPathBar(
                phonePath    = phonePath,
                remotePath   = if (isSsh) remotePath else null,
                onPhoneUp    = {
                    val parent = File(phonePath).parent
                    val extRoot = Environment.getExternalStorageDirectory().absolutePath
                    if (parent != null && phonePath != extRoot) phonePath = parent
                },
                onRemoteUp   = if (isSsh) ({
                    val parent = remotePath.substringBeforeLast('/', "")
                    remotePath = parent.ifEmpty { "/" }
                }) else null
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FtHeader(
    protocolLabel: String,
    profileName: String,
    onClose: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(NebulaSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            tint     = PulsarCyan,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.ft_title),
                style      = MaterialTheme.typography.titleSmall,
                color      = StarDust,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "$protocolLabel • $profileName",
                style = MaterialTheme.typography.labelSmall,
                color = CometTail
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = CometTail, modifier = Modifier.size(18.dp))
        }
    }
    HorizontalDivider(color = HorizonGray, thickness = 1.dp)
}

@Composable
private fun FilePanelHeader(
    title: String,
    path: String,
    rootPath: String,
    // BUG-SILENT-FAILURE FIX: was StorageSpace (non-null). Callers that have no
    // storage data (e.g. remote SFTP when statVFS is unsupported) passed
    // StorageSpace(0, 0) — visually indistinguishable from a genuinely full disk.
    // Nullable type forces every call site to make an explicit decision:
    //   • null  → don't render the storage bar at all (data unavailable)
    //   • 0 / 0 → render the bar showing "disk full" (server reported real values)
    space: StorageSpace?,
    icon: ImageVector,
    accentColor: Color
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DeepSpace.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accentColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style      = MaterialTheme.typography.labelMedium,
                color      = accentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            path.removePrefix(rootPath).ifEmpty { "/" },
            style    = MaterialTheme.typography.labelSmall,
            color    = CometTail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // BUG-SILENT-FAILURE FIX: Previously used `if (space.totalBytes > 0)` on a
        // non-null StorageSpace. Callers that had no real data passed StorageSpace(0, 0),
        // which made the condition false — accidentally correct, but only by coincidence.
        // A server genuinely reporting 0 total bytes (edge case: quota-limited account with
        // 0 allocation) would also be silently suppressed.
        //
        // With the nullable type the intent is explicit:
        //   • null  → no data; show nothing (no misleading "0 free" label)
        //   • non-null with totalBytes == 0 → show "disk full" or suppress as before
        if (space != null && space.totalBytes > 0) {
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(
                progress  = { 1f - space.freePercent },
                modifier  = Modifier.fillMaxWidth().height(2.dp).clip(CircleShape),
                color     = accentColor,
                trackColor = HorizonGray
            )
            Text(
                // BUG-5 FIX: "free" was hardcoded English; now uses string resource for i18n
                "${formatBytesLocalized(LocalContext.current, space.freeBytes)} ${stringResource(R.string.storage_free)}",
                style = MaterialTheme.typography.labelSmall,
                color = CometTail
            )
        }
    }
    HorizontalDivider(color = HorizonGray, thickness = 0.5.dp)
}

@Composable
private fun FileList(
    files: List<HexFile>,
    selectedFile: HexFile?,
    isPhoneSide: Boolean,
    onNavigate: (HexFile) -> Unit,
    onSelectToggle: (HexFile) -> Unit
) {
    if (files.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.ft_empty_folder),
                style = MaterialTheme.typography.bodySmall,
                color = CometTail
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state          = listState,
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(files, key = { it.path }) { file ->
            val isSelected = selectedFile == file
            FileRow(
                file         = file,
                isSelected   = isSelected,
                onClick      = { if (file.isDirectory) onNavigate(file) else onSelectToggle(file) },
                onLongClick  = { if (!file.isDirectory) onSelectToggle(file) }
            )
        }
    }
}

@Composable
private fun FileRow(
    file: HexFile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = when {
        isSelected           -> PulsarCyan.copy(alpha = 0.15f)
        else                 -> Color.Transparent
    }
    val borderColor = if (isSelected) PulsarCyan.copy(alpha = 0.4f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // File icon
        Text(
            text     = fileIcon(file),
            fontSize = 14.sp,
            modifier = Modifier.width(22.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style     = MaterialTheme.typography.bodySmall,
                color     = if (isSelected) PulsarCyan else StarDust,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                fontWeight = if (file.isDirectory) FontWeight.Medium else FontWeight.Normal
            )
            if (!file.isDirectory && file.size > 0) {
                Text(
                    formatBytes(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = CometTail
                )
            }
        }
        if (isSelected && !file.isDirectory) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint     = PulsarCyan,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun TransferProgressBanner(
    progress: TransferProgress,
    onDismiss: () -> Unit
) {
    val uploadingLabel   = stringResource(R.string.ft_progress_uploading)
    val downloadingLabel = stringResource(R.string.ft_progress_downloading)
    val uploadedLabel    = stringResource(R.string.ft_progress_uploaded)
    val downloadedLabel  = stringResource(R.string.ft_progress_downloaded)
    val (bgColor, icon, text) = when (progress) {
        is TransferProgress.Running -> Triple(
            NebulaSurface,
            Icons.Default.SwapVert,
            if (progress.isUpload) "$uploadingLabel ${progress.fileName}" else "$downloadingLabel ${progress.fileName}"
        )
        is TransferProgress.Success -> Triple(
            PlasmaGreen.copy(alpha = 0.12f),
            Icons.Default.CheckCircle,
            if (progress.isUpload) "${progress.fileName} $uploadedLabel ✓" else "${progress.fileName} $downloadedLabel ✓"
        )
        is TransferProgress.Failure -> Triple(
            ErrorRed.copy(alpha = 0.12f),
            Icons.Default.Error,
            (progress as TransferProgress.Failure).error
        )
        else -> Triple(Color.Transparent, Icons.Default.Info, "")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = when (progress) {
                is TransferProgress.Success -> PlasmaGreen
                is TransferProgress.Failure -> ErrorRed
                else -> PulsarCyan
            }, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style    = MaterialTheme.typography.labelSmall,
                color    = StarDust,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress !is TransferProgress.Running) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = CometTail, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (progress is TransferProgress.Running) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress  = { progress.percent },
                modifier  = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color     = PulsarCyan,
                trackColor = HorizonGray
            )
            if (progress.bytesTotal > 0) {
                Text(
                    "${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CometTail
                )
            }
        }
    }
    HorizontalDivider(color = HorizonGray, thickness = 0.5.dp)
}

@Composable
private fun HttpServerPanel(
    running: Boolean,
    url: String,
    phonePath: String
) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(
            if (running) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = null,
            tint     = if (running) PlasmaGreen else HorizonGray,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (running) stringResource(R.string.ft_server_running) else stringResource(R.string.ft_server_starting),
            style      = MaterialTheme.typography.titleSmall,
            color      = if (running) PlasmaGreen else CometTail,
            fontWeight = FontWeight.SemiBold
        )
        if (running && url.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ft_server_hint),
                style   = MaterialTheme.typography.bodySmall,
                color   = CometTail,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                color  = NebulaSurface,
                shape  = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PulsarCyan.copy(alpha = 0.4f))
            ) {
                Text(
                    url,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = PulsarCyan,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                color  = NebulaSurface,
                shape  = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, HorizonGray)
            ) {
                Column(Modifier.padding(12.dp)) {
                    InfoRow(Icons.Default.Folder, stringResource(R.string.ft_phone_files), phonePath)
                    Spacer(Modifier.height(4.dp))
                    // FIX-I18N: was hardcoded English "Click any file to download"
                    InfoRow(Icons.Default.ArrowDownward, stringResource(R.string.ft_download_tip), stringResource(R.string.ft_http_download_hint))
                    Spacer(Modifier.height(4.dp))
                    // FIX-I18N: was hardcoded English "Use upload form at bottom of page"
                    InfoRow(Icons.Default.ArrowUpward, stringResource(R.string.ft_upload_tip), stringResource(R.string.ft_http_upload_hint))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ft_rdp_vnc_note),
            style   = MaterialTheme.typography.labelSmall,
            color   = CometTail.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = CometTail)
            Text(value, style = MaterialTheme.typography.labelSmall, color = StarDust, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConnectingAmber.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, null, tint = ConnectingAmber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.ft_permission_needed),
            style    = MaterialTheme.typography.labelSmall,
            color    = ConnectingAmber,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRequest) {
            Text(stringResource(R.string.ft_grant), color = PulsarCyan, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PulsarCyan, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style   = MaterialTheme.typography.bodySmall,
            color   = CometTail,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRetry,
            border  = BorderStroke(1.dp, PulsarCyan),
            shape   = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.Refresh, null, tint = PulsarCyan, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ft_retry), color = PulsarCyan, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BottomPathBar(
    phonePath: String,
    remotePath: String?,
    onPhoneUp: () -> Unit,
    onRemoteUp: (() -> Unit)?
) {
    HorizontalDivider(color = HorizonGray, thickness = 0.5.dp)
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(NebulaSurface)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPhoneUp, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.ArrowUpward, null, tint = CometTail, modifier = Modifier.size(14.dp))
        }
        Text(
            File(phonePath).name.takeIf { it.isNotEmpty() } ?: "Storage",
            style    = MaterialTheme.typography.labelSmall,
            color    = CometTail,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (remotePath != null && onRemoteUp != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                remotePath.substringAfterLast('/').ifEmpty { "/" },
                style    = MaterialTheme.typography.labelSmall,
                color    = CometTail,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            IconButton(onClick = onRemoteUp, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ArrowUpward, null, tint = CometTail, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun fileIcon(file: HexFile): String = when {
    file.isDirectory -> "📁"
    else -> when (file.extension) {
        "pdf"                          -> "📄"
        "jpg", "jpeg", "png", "gif",
        "webp", "bmp", "heic"          -> "🖼"
        "mp4", "mkv", "avi", "mov",
        "wmv", "flv", "webm"           -> "🎬"
        "mp3", "m4a", "aac", "ogg",
        "flac", "wav"                  -> "🎵"
        "zip", "rar", "7z", "tar",
        "gz", "bz2"                    -> "🗜"
        "apk"                          -> "📦"
        "txt", "md", "log"             -> "📝"
        "kt", "java", "py", "js",
        "ts", "html", "css", "xml",
        "json", "sh", "c", "cpp"       -> "💻"
        "xls", "xlsx", "csv"           -> "📊"
        "doc", "docx"                  -> "📃"
        "ppt", "pptx"                  -> "📑"
        else                           -> "📄"
    }
}

private fun loadPhoneFiles(
    scope: CoroutineScope,
    path: String,
    onResult: (List<HexFile>) -> Unit
) {
    scope.launch {
        val files = withContext(Dispatchers.IO) { PhoneFileBrowser.listDir(path) }
        onResult(files)
    }
}

private fun checkStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun storagePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        emptyArray() // handled via MANAGE_ALL_FILES intent
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
