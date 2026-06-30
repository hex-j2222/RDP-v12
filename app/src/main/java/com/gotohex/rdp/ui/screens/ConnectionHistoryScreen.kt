package com.gotohex.rdp.ui.screens

import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.gotohex.rdp.data.model.ConnectionLog
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.security.QuickConnectCache
import com.gotohex.rdp.ui.MainViewModel
import androidx.compose.ui.res.stringResource
import com.gotohex.rdp.R
import com.gotohex.rdp.ui.components.SpaceButton
import com.gotohex.rdp.ui.components.ButtonVariant
import com.gotohex.rdp.ui.theme.*
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionHistoryScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val logs     by viewModel.connectionLogs.collectAsState(initial = emptyList())
    val context  = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    // FIX #5: عند إعادة الاتصال بجلسة Quick Connect من السجل، كان username/password
    // يُرسَلان فارغَين → AUTH فوري. نحفظ الـ log الذي طُلبت إعادة اتصاله
    // ونُظهر dialog لإدخال credentials قبل بدء الجلسة.
    var reconnectLog      by remember { mutableStateOf<ConnectionLog?>(null) }
    var quickUser         by remember { mutableStateOf("") }
    var quickPass         by remember { mutableStateOf("") }
    var quickPassVisible  by remember { mutableStateOf(false) }

    // ── Quick Connect credentials dialog ─────────────────────────────────────
    reconnectLog?.let { log ->
        AlertDialog(
            onDismissRequest = { reconnectLog = null },
            containerColor   = NebulaSurface,
            shape            = RoundedCornerShape(20.dp),
            title = {
                Column {
                    Text(stringResource(R.string.history_reconnect), color = StarDust, fontWeight = FontWeight.Bold)
                    Text(
                        "${log.host}:${log.port}",
                        color = CometTail.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.history_quickconnect_prompt),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value         = quickUser,
                        onValueChange = { quickUser = it },
                        label         = { Text(stringResource(R.string.username), color = CometTail) },
                        singleLine    = true,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = PulsarCyan,
                            unfocusedBorderColor = HorizonGray,
                            focusedTextColor     = StarDust,
                            unfocusedTextColor   = StarDust,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value           = quickPass,
                        onValueChange   = { quickPass = it },
                        label           = { Text(stringResource(R.string.password), color = CometTail) },
                        singleLine      = true,
                        visualTransformation = if (quickPassVisible)
                            VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon    = {
                            IconButton(onClick = { quickPassVisible = !quickPassVisible }) {
                                Icon(
                                    if (quickPassVisible) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    contentDescription = null,
                                    tint = CometTail
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = PulsarCyan,
                            unfocusedBorderColor = HorizonGray,
                            focusedTextColor     = StarDust,
                            unfocusedTextColor   = StarDust,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                SpaceButton(stringResource(R.string.connect), onClick = {
                    // FIX #HIST-SEC: Use QuickConnectCache token instead of plaintext password
                    // in Intent extras (visible via adb shell dumpsys activity and bug reports).
                    // Matches the same secure approach already used in HomeScreen.kt.
                    val token = QuickConnectCache.put(
                        log.host, log.port, quickUser.trim(), quickPass
                    )
                    val intent = Intent(context, RdpSessionActivity::class.java).apply {
                        putExtra("profile_id",  "__quick__")
                        putExtra("quick_token", token)
                    }
                    context.startActivity(intent)
                    reconnectLog = null
                    quickUser = ""; quickPass = ""
                }, variant = ButtonVariant.PRIMARY)
            },
            dismissButton = {
                SpaceButton(stringResource(R.string.cancel), onClick = {
                    reconnectLog = null
                    quickUser = ""; quickPass = ""
                }, variant = ButtonVariant.GHOST)
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = NebulaSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.history_clear_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.history_clear_confirm), color = CometTail) },
            confirmButton = {
                SpaceButton(stringResource(R.string.clear), onClick = {
                    viewModel.clearConnectionHistory()
                    showClearDialog = false
                }, variant = ButtonVariant.DANGER)
            },
            dismissButton = {
                SpaceButton(stringResource(R.string.cancel), onClick = { showClearDialog = false }, variant = ButtonVariant.GHOST)
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, // BUG-A FIX: was hardcoded DeepSpace — breaks light themes
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = CometTail)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.History, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.connection_history), color = StarDust, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Outlined.DeleteSweep, null, tint = CometTail)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.HistoryToggleOff,
                        null,
                        tint = CometTail.copy(alpha = 0.4f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.history_empty), color = CometTail, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.history_empty_subtitle),
                        color = CometTail.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                modifier       = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // FIX-LIMIT: Show a non-intrusive note when the list has hit the 50-entry
                // cap so users know older entries exist but are not displayed.
                // LIMIT is defined by ConnectionLogDao (LIMIT 50).
                if (logs.size >= 50) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(HorizonGray.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Info, null,
                                tint     = CometTail.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                stringResource(R.string.history_limit_note),
                                color = CometTail.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                items(logs, key = { it.id }) { log ->
                    ConnectionLogCard(
                        log = log,
                        onReconnect = {
                            if (log.profileId != null) {
                                // جلسة عادية — profileId موجود، أرسله مباشرة
                                val intent = Intent(context, RdpSessionActivity::class.java)
                                    .putExtra("profile_id", log.profileId)
                                context.startActivity(intent)
                            } else {
                                // FIX #5: جلسة Quick Connect — كانت تُرسَل credentials فارغة
                                // مما يُسبب فشلًا فوريًا بخطأ AUTH. نفتح dialog لطلب credentials.
                                quickUser    = ""
                                quickPass    = ""
                                reconnectLog = log
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ConnectionLogCard(
    log: ConnectionLog,
    onReconnect: () -> Unit
) {
    val accent  = PulsarCyan
    val surface = NebulaSurface

    val isSuccess = log.wasSuccessful
    val hasError  = log.disconnectReason != null && log.disconnectReason.isNotBlank()

    val statusColor = when {
        hasError   -> SolarFlare
        isSuccess  -> PlasmaGreen
        else       -> CometTail
    }

    Surface(
        color  = surface,
        shape  = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HorizonGray.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header row ────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol badge + name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProtocolBadge(log.protocolType)
                    Column {
                        Text(
                            log.profileName,
                            color     = StarDust,
                            style     = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines  = 1,
                            overflow  = TextOverflow.Ellipsis
                        )
                        Text(
                            "${log.host}:${log.port}",
                            color = CometTail.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                // Status dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, CircleShape)
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = HorizonGray.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))

            // ── Meta row ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                MetaItem(
                    icon  = Icons.Outlined.AccessTime,
                    label = formatTime(log.connectedAt),
                    modifier = Modifier.weight(1f)
                )
                MetaItem(
                    icon  = Icons.Outlined.Timer,
                    label = formatDuration(log.durationMs),
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Disconnect reason ─────────────────────────────────────────────
            if (hasError) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SolarFlare.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline, null,
                        tint     = SolarFlare,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        log.disconnectReason ?: "",
                        color   = SolarFlare,
                        style   = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Reconnect button ──────────────────────────────────────────────
            SpaceButton(
                text     = stringResource(R.string.history_reconnect),
                onClick  = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                variant  = ButtonVariant.PRIMARY
            )
        }
    }
}

@Composable
private fun ProtocolBadge(protocol: ProtocolType) {
    val (color, label) = when (protocol) {
        ProtocolType.RDP -> QuantumBlue to "RDP"
        ProtocolType.VNC -> VoidPurple to "VNC"
        ProtocolType.SSH -> PlasmaGreen to "SSH"
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetaItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = CometTail.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
        Text(label, color = CometTail, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Formatters ─────────────────────────────────────────────────────────────────

// BUG-B FIX: removed file-level SimpleDateFormat singleton — it is not thread-safe
// and captured Locale.getDefault() at init time (ignores runtime locale changes).
// Now created fresh per call inside formatTime() so locale is always current.

private fun formatTime(epochMs: Long): String {
    if (epochMs == 0L) return "—"
    return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val hours   = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        hours > 0   -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else        -> "${seconds}s"
    }
}
