package com.gotohex.rdp.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.gotohex.rdp.R
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.data.repository.AppSettingsRepository
import com.gotohex.rdp.data.repository.AppSettings
import com.gotohex.rdp.data.repository.RdpProfileRepository
import com.gotohex.rdp.remote.*
import com.gotohex.rdp.rdp.protocol.RdpRemoteAdapter
import com.gotohex.rdp.ssh.protocol.SshClient
import com.gotohex.rdp.ssh.protocol.SshKeyMap
import com.gotohex.rdp.ui.components.ButtonVariant
import com.gotohex.rdp.ui.components.LocalSoundManager
import com.gotohex.rdp.ui.components.SpaceButton
import com.gotohex.rdp.ui.screens.terminal.TerminalScreen
import com.gotohex.rdp.audio.SoundManager
import com.gotohex.rdp.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import androidx.compose.runtime.CompositionLocalProvider

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class RdpSessionActivity : AppCompatActivity() {

    private val viewModel: RdpSessionViewModel by viewModels()

    // CRASH BUG-C FIX: LocalSoundManager was never provided in RdpSessionActivity's
    // composition tree (unlike MainActivity which wraps in CompositionLocalProvider).
    // Any Composable calling LocalSoundManager.current got null; future code without
    // null-safe ?. would crash. Injecting here and providing below solves this.
    @Inject lateinit var soundManager: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // FIX-FLAG_SECURE: Prevent the remote desktop framebuffer from appearing
        // in Android's Recent Apps thumbnail or being captured by screen-recording
        // apps. The remote desktop content may include confidential corporate data,
        // and a background screen-recorder could silently exfiltrate every frame.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // BUG 4 FIX: POST_NOTIFICATIONS must be requested at runtime on Android 13+
        // (API 33+). Without this, the foreground service notification is silently
        // suppressed and may throw SecurityException on some devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }

        // Feature-05: if this Activity is being brought back to the front via
        // FLAG_ACTIVITY_REORDER_TO_FRONT with a close_tab signal, just disconnect.
        if (intent.getBooleanExtra("close_tab", false)) {
            viewModel.disconnect()
            finish()
            return
        }

        val metrics     = resources.displayMetrics
        val deviceWidth  = metrics.widthPixels
        val deviceHeight = metrics.heightPixels

        setContent {
            val settings by viewModel.settings.collectAsState()
            val state    by viewModel.state.collectAsState()

            // CRASH BUG-A FIX: loadAndConnect() was called *after* setContent() returned,
            // which starts coroutines that push state updates (_state emissions) before
            // Compose has registered its first collector. On fast devices the CONNECTED
            // state arrived before the first recomposition, causing the UI to miss it
            // entirely and display a blank/frozen screen. Moving the call into a
            // LaunchedEffect(Unit) guarantees Compose is set up and collecting before
            // the connection coroutine begins.
            LaunchedEffect(Unit) {
                if (profileId == "__quick__") {
                    val token = intent.getStringExtra("quick_token") ?: ""
                    val creds = com.gotohex.rdp.security.QuickConnectCache.take(token)
                    // HIGH-R3 FIX: Extract ALL fields from creds BEFORE calling clear().
                    // The previous code read host/port/username inside the loadAndConnectQuick()
                    // call after clear() had already been invoked. clear() currently zeros only
                    // the password CharArray, so host/port/username happened to be safe — but
                    // this is fragile: if QuickConnectParams.clear() is ever extended to null/zero
                    // other fields the connection silently uses empty values with no error or log.
                    // Fix: snapshot every field atomically first, then clear the credential object.
                    val host     = creds?.host     ?: ""
                    val port     = creds?.port     ?: 3389
                    val username = creds?.username ?: ""
                    val quickPwd = creds?.let { String(it.password) } ?: ""
                    creds?.clear()   // zero password CharArray — all fields already captured above
                    viewModel.loadAndConnectQuick(
                        host         = host,
                        port         = port,
                        username     = username,
                        password     = quickPwd,
                        deviceWidth  = deviceWidth,
                        deviceHeight = deviceHeight,
                    )
                } else {
                    viewModel.loadAndConnect(profileId, deviceWidth, deviceHeight)
                }
            }

            // ✅ keepScreenOn يُطبَّق بشكل حي من الإعداد
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(state, settings.runInBackground) {
                val profileName = (state as? SessionUiState.Connected)?.profile?.name
                if (state is SessionUiState.Connected && settings.runInBackground) {
                    RdpSessionService.start(this@RdpSessionActivity, profileName ?: "RDP")
                } else if (state is SessionUiState.Disconnected || state is SessionUiState.Error) {
                    RdpSessionService.stop(this@RdpSessionActivity)
                }
            }

            // CRASH BUG-B FIX: settings.themeVariant starts as the DataStore default
            // (AppSettings()) before the first emission loads from disk. If the user
            // picked a non-default variant the first frame renders with the wrong theme
            // and then jumps on the second frame — visible flicker and unexpected
            // recomposition. Keeping darkTheme = true but binding themeVariant to the
            // live settings flow means at worst one quick theme swap, which is acceptable
            // and matches how MainActivity handles it. No additional change needed here
            // beyond the loadAndConnect timing fix above, which removes the window where
            // state changes could race the initial composition.
            HexRDPTheme(darkTheme = true, themeVariant = settings.themeVariant) {
                // CRASH BUG-C FIX: CompositionLocalProvider was missing in RdpSessionActivity
                // (present in MainActivity but not here). Every Composable calling
                // LocalSoundManager.current was receiving null; any future caller without
                // null-safe ?. would crash. The soundManager field is @Inject-ed above.
                CompositionLocalProvider(LocalSoundManager provides soundManager) {
                    RdpSessionScreen(
                        viewModel = viewModel,
                        onClose   = {
                            RdpSessionService.stop(this@RdpSessionActivity)
                            finish()
                        }
                    )
                }
            }
        }
        // CRASH BUG-A FIX: loadAndConnect() calls removed from here; moved into
        // LaunchedEffect(Unit) inside setContent() above.
    }

    /** Feature-05: called when FLAG_ACTIVITY_REORDER_TO_FRONT brings us back. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("close_tab", false)) {
            viewModel.disconnect()
            finish()
        }
    }

    /**
     * BUG 6 FIX: configChanges="orientation|screenSize" prevents Activity recreation
     * on rotation, so onCreate (where dimensions were originally read) is never called
     * again. Any reconnection after rotation would use stale pre-rotation dimensions.
     * Push fresh metrics into the ViewModel whenever the configuration changes.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        viewModel.updateDisplayMetrics(metrics.widthPixels, metrics.heightPixels)
    }

    override fun onDestroy() {
        super.onDestroy()
        // BUG-ONDESTROY FIX: The previous `if (isFinishing)` guard meant the service
        // was only stopped when the user explicitly closed the Activity. If the system
        // destroyed the Activity for any other reason (low memory, process kill, or a
        // config change not listed in android:configChanges such as uiMode/dark-mode),
        // isFinishing == false and the foreground service — along with its persistent
        // notification — would remain alive indefinitely.
        // Fix: always stop the service in onDestroy(). If the Activity is recreated
        // (config change), the LaunchedEffect in setContent() restarts the service
        // automatically once the new Activity reaches the CONNECTED state.
        RdpSessionService.stop(this)
    }

    // ✅ إخفاء شريط الحالة وشريط التنقل في وضع الجلسة الكاملة
    // API 30+ (Android 11+): WindowInsetsController الحديث
    // API 26-29 (Android 8-10): systemUiVisibility المتوافق مع الإصدارات القديمة
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // Android 8.0 - 10 (API 26-29): استخدام الطريقة المتوافقة
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class RdpSessionViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: RdpProfileRepository,
    private val settingsRepository: com.gotohex.rdp.data.repository.AppSettingsRepository,
    private val connectionLogRepository: com.gotohex.rdp.data.repository.ConnectionLogRepository,
    private val sessionTabManager: com.gotohex.rdp.session.SessionTabManager,
) : ViewModel() {

    private val _state       = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency.asStateFlow()

    // BUG-M3 FIX: separate StateFlow for the actual frame-to-frame interval (ms).
    // Previously the FPS counter used 1000/latency where latency = connection setup
    // time (constant), giving a meaningless FPS value for the entire session lifetime.
    private val _frameRateMs = MutableStateFlow(0L)
    val frameRateMs: StateFlow<Long> = _frameRateMs.asStateFlow()
    private var lastFrameTimeMs = 0L

    private val _resolution = MutableStateFlow(1280 to 720)
    val resolution: StateFlow<Pair<Int, Int>> = _resolution.asStateFlow()

    private val _terminalText = MutableStateFlow("")
    val terminalText: StateFlow<String> = _terminalText.asStateFlow()

    // FIX #3 (Performance): StringBuilder replaces the split+join approach that
    // previously allocated three full copies of the ~200 KB terminal buffer on
    // every incoming SSH byte (combined string, split list, joined string).
    // The buffer is protected by the Dispatchers.IO coroutine that calls it.
    private val terminalBuffer = StringBuilder()
    // Track newline count separately to avoid an O(n) scan on every chunk.
    private var terminalLineCount = 0

    val settings: StateFlow<com.gotohex.rdp.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, com.gotohex.rdp.data.repository.AppSettings()
        )

    private var remoteClient: RemoteSessionClient? = null
    // BUG-1 FIX: @Volatile added — screenBitmap is written on Dispatchers.IO inside
    // currentSessionJob and read on Main in onCleared(). Without @Volatile the JVM
    // may cache the old null value in the Main thread's register and the snapshot
    // save in onCleared() silently skips the last rendered frame.
    @Volatile private var screenBitmap: Bitmap? = null
    // FIX #5: Double-buffer display bitmaps — we alternate between A and B so
    // Compose always gets a different object reference (triggering StateFlow
    // recomposition) without allocating a new Bitmap on every 16 ms frame.
    @Volatile private var displayBitmapA: Bitmap? = null
    @Volatile private var displayBitmapB: Bitmap? = null
    // AtomicBoolean ensures the buffer-flip (read + write) is a single
    // atomic operation, preventing two concurrent frame callbacks from
    // writing to the same buffer simultaneously (BUG-05).
    private val useDisplayBitmapA = java.util.concurrent.atomic.AtomicBoolean(true)
    private var screenWidth  = 1280
    private var screenHeight = 720
    private var currentProfileId: String? = null
    @Volatile private var reconnectAttempts = 0  // BUG-N4 FIX: @Volatile prevents IO thread reading stale cache value
    // FIX #7: Use an explicit flag instead of the unsafe Int.MAX_VALUE sentinel
    // which risks integer overflow bugs in any arithmetic that touches the counter.
    @Volatile private var intentionalDisconnect = false  // BUG-N4 FIX: written on Main, read on IO — needs @Volatile
    private var savedDeviceWidth  = 0
    private var savedDeviceHeight = 0
    // FIX #8: Track the Job for the current session so we can cancel all its
    // child coroutines before starting a new session on reconnect.
    private var currentSessionJob: kotlinx.coroutines.Job? = null
    // BUG-reconnect FIX: Track the pending delayed-reconnect Job so disconnect()
    // can cancel it immediately instead of waiting for the delay to expire.
    // Without this, calling disconnect() during "Reconnecting in Ns…" would set
    // intentionalDisconnect=true but the orphan coroutine would still call
    // loadAndConnect() once the countdown finished.
    private var reconnectJob: kotlinx.coroutines.Job? = null

    // ── Feature-05: multi-session tab support ─────────────────────────────────
    /** Tab ID assigned by [SessionTabManager] for this Activity instance. */
    var currentTabId: String? = null
        private set

    /** Expose the live tab list so the UI can render the tab bar. */
    val sessionTabs = sessionTabManager.tabs
    val activeTabId = sessionTabManager.activeTabId

    // ── Feature-06: connection history logging ────────────────────────────────
    // BUG-AA3 FIX: both fields are written from Dispatchers.IO (inside currentSessionJob
    // or the state collector) and read from a different IO coroutine. Without @Volatile,
    // a CPU with its own register cache can see a stale value — same root cause that was
    // already fixed for reconnectAttempts and intentionalDisconnect (BUG-N4).
    /** ID of the [ConnectionLog] row for the current session (null = not started yet). */
    @Volatile private var currentLogId: String? = null
    /** True once the remote session has reached CONNECTED at least once. */
    @Volatile private var sessionWasSuccessful = false

    private companion object {
        // FIX #6: Maximum terminal lines kept in memory to prevent OOM
        // during long SSH sessions (top, journalctl, etc.) on ARMv7 devices.
        const val MAX_TERMINAL_LINES = 5_000
    }

    /** The protocol of the currently loaded profile, used by the UI to decide
     *  whether to render the framebuffer canvas or the terminal screen. */
    private val _protocolType = MutableStateFlow(ProtocolType.RDP)
    val protocolType: StateFlow<ProtocolType> = _protocolType.asStateFlow()

    /**
     * Dedicated scope for everything related to the active session
     * (connection, receive loop collectors, frame decoding). Any exception
     * that escapes here is caught and surfaced as a normal "Error" state
     * instead of crashing the process — this is the final safety net behind
     * the per-frame try/catch in [loadAndConnect] (issue #3 — the app used to
     * close immediately after a connection attempt because an uncaught
     * exception from a `viewModelScope.launch` child propagates and kills
     * the app by design).
     */
    private val sessionScope = viewModelScope + CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("RdpSession", "Unhandled session error", throwable)
        // i18n FIX: was `throwable.message ?: "Unexpected error"` — throwable.message
        // comes from third-party libraries (bVNC / JSch) and is always English.
        // Fall back to a localised string so Arabic users see an Arabic message.
        _state.value = SessionUiState.Error(
            throwable.message ?: appContext.getString(R.string.error_unexpected)
        )
    }

    // ── BUG-N3 FIX: TrimMemoryBus registration ───────────────────────────────
    // The bus was sending trim events (from HexRDPApp.onTrimMemory) but no
    // ViewModel ever registered a listener, so OOM protection was completely dead.
    // We keep a named reference so we can unregister it exactly in onCleared().
    private val trimMemoryListener: (Int) -> Unit = { _ ->
        // Under memory pressure, recycle whichever display buffer is currently
        // idle (the one NOT being composed on screen). This frees ~16 MB of
        // native heap per session without disturbing the active rendered frame.
        val useA = useDisplayBitmapA.get()
        if (!useA) {
            displayBitmapA?.recycle()
            displayBitmapA = null
        } else {
            displayBitmapB?.recycle()
            displayBitmapB = null
        }
    }

    init {
        com.gotohex.rdp.TrimMemoryBus.register(trimMemoryListener)
    }

    /**
     * @param deviceWidth/deviceHeight the device's current display size, used when
     *        the profile and the global default resolution are both "auto" (issue #5).
     */
    fun loadAndConnect(profileId: String, deviceWidth: Int, deviceHeight: Int) {
        // ✅ حفظ المعاملات لإعادة الاتصال التلقائي
        savedDeviceWidth  = deviceWidth
        savedDeviceHeight = deviceHeight

        // BUG-Y3 FIX: intentionalDisconnect is set to true by disconnect() and was only
        // cleared inside the CONNECTED state handler (i.e. after a successful connection).
        // If the user disconnects manually and then immediately reconnects, the flag stays
        // true throughout the CONNECTING phase. Any ERROR or DISCONNECTED that occurs
        // before CONNECTED is reached (e.g. wrong password on first attempt) is treated
        // as "intentional" → auto-reconnect is permanently suppressed for the new session.
        // Fix: reset the flag here at the very start of each new connection attempt so
        // the new session always begins with a clean auto-reconnect state.
        intentionalDisconnect = false

        // FIX #8: Cancel all coroutines from the previous session before
        // starting a new one. Without this, every reconnect accumulates new
        // flow-collectors alongside the still-running ones from prior sessions,
        // leading to duplicated events and unpredictable behaviour.
        currentSessionJob?.cancel()

        // FIX #7: Disconnect the old client before replacing its reference.
        // Previously the old RemoteSessionClient was simply abandoned, leaving
        // its network connections and IO threads open indefinitely (resource leak).
        remoteClient?.disconnect()
        remoteClient = null

        currentSessionJob = sessionScope.launch(Dispatchers.IO) { // BUG 9 FIX: was using Main dispatcher, causing ANR risk for heavy network/IO ops
            _state.emit(SessionUiState.Connecting(""))
            val profile = try {
                repository.getProfileById(profileId)
            } catch (e: SecurityException) {
                // BUG-DECRYPT FIX: CryptoHelper.decrypt() now throws SecurityException
                // when the Keystore key is lost (reinstall / backup restore). Surface a
                // meaningful message so the user knows to re-enter their credentials
                // rather than seeing a generic "Auth failed" with no explanation.
                android.util.Log.e("RdpSession", "Failed to decrypt profile credentials", e)
                _state.emit(SessionUiState.Error(
                    appContext.getString(R.string.error_credentials_lost)
                ))
                return@launch
            }
            if (profile == null) {
                // i18n FIX: was hardcoded "Profile not found" — use string resource.
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_profile_not_found)))
                return@launch
            }
            _state.emit(SessionUiState.Connecting(profile.name))
            currentProfileId = profile.id
            _protocolType.value = profile.protocolType

            // ── Feature-05: open / reuse tab ─────────────────────────────────
            if (currentTabId == null) {
                currentTabId = sessionTabManager.openTab(profile)
                // FIX MAX_TABS: openTab() returns null when the session cap is
                // reached. In that case the Activity was still launched from the UI
                // (HomeScreen now checks isFull first, but guard here too as a
                // safety net for any future callers). Emit an error and abort so we
                // do NOT allocate the 3× ~16 MB Bitmaps below, which would bypass
                // OOM protection entirely.
                if (currentTabId == null) {
                    // i18n FIX: was a hardcoded English multi-line string.
                    // error_max_sessions already exists in strings.xml with %1$d for the limit.
                    _state.emit(
                        SessionUiState.Error(
                            appContext.getString(
                                R.string.error_max_sessions,
                                com.gotohex.rdp.session.SessionTabManager.MAX_TABS
                            )
                        )
                    )
                    return@launch
                }
                currentTabId?.let {
                    sessionTabManager.updateState(
                        it, com.gotohex.rdp.data.model.ConnectionState.CONNECTING,
                        appContext.getString(R.string.session_tab_connecting)
                    )
                }
            }

            // ── Feature-06: start a new history log entry ─────────────────────
            sessionWasSuccessful = false
            val logEntry = com.gotohex.rdp.data.model.ConnectionLog(
                profileId    = if (profileId == "__quick__") null else profileId,
                profileName  = profile.name,
                host         = profile.host,
                port         = profile.port,
                protocolType = profile.protocolType
            )
            currentLogId = connectionLogRepository.start(logEntry)

            val defaultRes = settings.value.defaultResolution
            val (resW, resH) = when {
                profile.width > 0 && profile.height > 0 -> profile.width to profile.height
                defaultRes != "auto" && defaultRes.contains("x") -> {
                    val parts = defaultRes.split("x")
                    (parts.getOrNull(0)?.toIntOrNull() ?: deviceWidth) to (parts.getOrNull(1)?.toIntOrNull() ?: deviceHeight)
                }
                deviceWidth > 0 && deviceHeight > 0 -> deviceWidth to deviceHeight
                else -> 1280 to 720
            }
            screenWidth  = resW
            screenHeight = resH
            _resolution.value = screenWidth to screenHeight

            if (profile.protocolType != ProtocolType.SSH) {
                // FIX #9: Recycle the old bitmaps before allocating new ones.
                // Previously screenBitmap was silently replaced, leaving the old
                // Bitmap alive until the next GC cycle — an extra 3–7 MB leak on
                // every reconnect on ARMv7 devices.
                screenBitmap?.recycle()
                displayBitmapA?.recycle()
                displayBitmapB?.recycle()
                // BUG-M3 FIX: reset frame-rate tracker so a stale interval from the
                // previous session doesn't contaminate the new session's FPS counter.
                lastFrameTimeMs = 0L
                _frameRateMs.value = 0L
                // FIX #5: Allocate the double-buffer display bitmaps once here.
                screenBitmap = android.graphics.Bitmap.createBitmap(
                    screenWidth, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                displayBitmapA = android.graphics.Bitmap.createBitmap(
                    screenWidth, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                displayBitmapB = android.graphics.Bitmap.createBitmap(
                    screenWidth, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                useDisplayBitmapA.set(true)
            }

            val client = RemoteSessionFactory.create(
                profile,
                screenWidth,
                screenHeight,
                settings.value.compressionQuality,  // FIX #4
                appContext = appContext,   // BUG-2 FIX: was missing → compile error (appContext has no default)
            )
            remoteClient = client

            // FIX: previously two independent flows (state + error) were
            // collected by separate `launch {}` coroutines that could run in
            // either order, so the ERROR/AUTH_FAILED branch could fire before
            // the matching error string had been recorded. combine()
            // guarantees the state and the latest error are always read
            // together from a single downstream collector.
            launch {
                kotlinx.coroutines.flow.combine(client.sessionState, client.error.onStart { emit("") }) { remoteState, msg ->
                    remoteState to msg.ifBlank { null }
                }.collect { (remoteState, lastError) ->
                    when (remoteState) {
                        RemoteSessionState.CONNECTED -> {
                            reconnectAttempts = 0   // ✅ إعادة العداد عند الاتصال الناجح
                            intentionalDisconnect = false  // FIX #7: clear flag on successful reconnect
                            sessionWasSuccessful = true
                            // FIX B: تفعيل النقطة الخضراء (isConnected) والنقطة الكهرمانية (lastConnected).
                            // كانت updateConnectionState و updateLastConnected معرّفتين في Repository و DAO
                            // لكن لم يستدعهما أحد — البطاقات ظلّت رمادية للأبد.
                            // "__quick__" ليس صفاً حقيقياً في DB، نتخطاه لتفادي تحديث لا طائل منه.
                            if (profile.id != "__quick__") {
                                repository.updateConnectionState(profile.id, true)
                                repository.updateLastConnected(profile.id)
                            }
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.gotohex.rdp.data.model.ConnectionState.CONNECTED, ""
                                )
                            }
                            _state.emit(SessionUiState.Connected(profile))
                        }
                        RemoteSessionState.AUTH_FAILED -> {
                            currentLogId?.let { logId ->
                                connectionLogRepository.finish(logId, appContext.getString(R.string.disconnect_reason_auth), sessionWasSuccessful)
                            }
                            currentLogId = null  // BUG-Z2 FIX: prevent double-finish overwriting error reason
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.gotohex.rdp.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_auth_failed)
                                )
                            }
                            // FIX B: فشل المصادقة = لا اتصال → أعد النقطة إلى الرمادي
                            if (profile.id != "__quick__") {
                                repository.updateConnectionState(profile.id, false)
                            }
                            _state.emit(SessionUiState.Error(lastError ?: appContext.getString(R.string.error_auth_failed_credentials)))
                        }
                        RemoteSessionState.ERROR -> {
                            // ✅ إعادة الاتصال التلقائي عند خطأ (ليس AUTH_FAILED)
                            val s = settings.value
                            // FIX #7: guard with intentionalDisconnect so a user-initiated
                            // disconnect() is never followed by an auto-reconnect attempt.
                            if (!intentionalDisconnect && s.autoReconnect && reconnectAttempts < s.autoReconnectAttempts) {
                                reconnectAttempts++
                                val waitSec = reconnectAttempts * 3
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry, reconnectAttempts, s.autoReconnectAttempts)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_countdown, reconnectAttempts, s.autoReconnectAttempts, waitSec)
                                ))
                                // BUG-AA2 FIX: close the current log before launching the reconnect
                                // job. Previously currentLogId was overwritten by the next
                                // loadAndConnect() call without calling finish() → orphan entry
                                // with disconnectedAt=0 for every auto-reconnect attempt.
                                val errMsgForLog = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsgForLog, sessionWasSuccessful)
                                }
                                currentLogId = null
                                /* BUG-7 FIX: calling loadAndConnect() directly here would cancel
                                 * currentSessionJob (which IS this coroutine) → self-cancellation
                                 * race condition causing duplicate collectors and duplicate log entries.
                                 * Launch a sibling coroutine that outlives the current collector. */
                                val savedProfileId = profile.id
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // BUG-reconnect FIX: save the Job so disconnect() can cancel
                                // it immediately. Also guard after the delay — in case
                                // intentionalDisconnect was set while we were waiting.
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    delay(waitSec * 1000L)
                                    if (!intentionalDisconnect) {
                                        loadAndConnect(savedProfileId, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                val errMsg = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsg, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent DISCONNECTED branch from overwriting the error reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_error)
                                    )
                                }
                                // FIX B: انتهت محاولات إعادة الاتصال → أعد النقطة إلى الرمادي
                                if (profile.id != "__quick__") {
                                    repository.updateConnectionState(profile.id, false)
                                }
                                _state.emit(SessionUiState.Error(errMsg))
                            }
                        }
                        RemoteSessionState.DISCONNECTED -> {
                            // ✅ إعادة الاتصال عند الانقطاع المفاجئ (ليس عند disconnect() المتعمّد)
                            val s = settings.value
                            // BUG-3 FIX: FreeRDP fires both ERROR then DISCONNECTED on the same
                            // network drop. If the ERROR branch already launched a reconnect job,
                            // skip DISCONNECTED to prevent doubling reconnectAttempts.
                            if (reconnectJob?.isActive == true) return@collect
                            // FIX #7: intentionalDisconnect prevents reconnect after user-initiated
                            // disconnect(). Previously Int.MAX_VALUE was used as sentinel which is
                            // unsafe if the counter is ever used in arithmetic.
                            if (!intentionalDisconnect && s.autoReconnect && reconnectAttempts < s.autoReconnectAttempts &&
                                _state.value is SessionUiState.Connected
                            ) {
                                reconnectAttempts++
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry, reconnectAttempts, s.autoReconnectAttempts)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_retry, reconnectAttempts, s.autoReconnectAttempts)
                                ))
                                /* BUG-7 FIX: same self-cancellation issue as in the ERROR branch above. */
                                val savedProfileId = profile.id
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // BUG-AA2 FIX: same as in the ERROR branch — close the current log
                                // before the reconnect job overwrites currentLogId.
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, null, sessionWasSuccessful)
                                }
                                currentLogId = null
                                // BUG-reconnect FIX: save the Job so disconnect() can cancel
                                // it immediately. Also guard after the delay — in case
                                // intentionalDisconnect was set while we were waiting.
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    delay(3_000)
                                    if (!intentionalDisconnect) {
                                        loadAndConnect(savedProfileId, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, null, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent any further finish() from re-writing over a null reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.DISCONNECTED, ""
                                    )
                                }
                                // FIX B: قطع الاتصال النهائي → أعد النقطة إلى الرمادي
                                if (profile.id != "__quick__") {
                                    repository.updateConnectionState(profile.id, false)
                                }
                                _state.emit(SessionUiState.Disconnected)
                            }
                        }
                        else -> {}
                    }
                    if (remoteState != RemoteSessionState.CONNECTED && profile.protocolType != ProtocolType.SSH) {
                        saveLastFrameThumbnail()
                    }
                }
            }
            // BUG-07: Removed redundant `lastError` collector — the `combine`
            // block above (line ~306) already collects `client.error` on the
            // same SharedFlow. A second concurrent collector caused unsynchronised
            // writes to `lastError` from two coroutines and double-consumption
            // of error events. Error messages are now read directly from the
            // combine block's `lastError` destructured value.

            if (profile.protocolType != ProtocolType.SSH) {
                // Periodically persist a thumbnail of the current screen (issue
                // #11 — "show the last point the system was at" in the profile
                // list, blended into the card). Throttled to once every 15s.
                launch {
                    while (isActive) {
                        delay(15_000)
                        if (_state.value is SessionUiState.Connected) {
                            saveLastFrameThumbnail()
                        }
                    }
                }

                launch {
                    client.frameUpdates.collect { frame ->
                        try {
                            applyFrameUpdate(frame)
                        } catch (e: Exception) {
                            // A single malformed/oversized frame must never crash
                            // the whole app (issue #3). Log and skip this frame only.
                            android.util.Log.w("RdpSession", "Dropping malformed frame: ${e.message}")
                        }
                        _latency.value = client.latencyMs
                    }
                }
            } else {
                // FIX #2 (Logic): Clear terminal state from any previous SSH session.
                // Without this reset, stale output from the last session remains visible
                // until the first new chunk arrives from the new connection.
                terminalBuffer.clear()
                terminalLineCount = 0
                _terminalText.value = ""

                launch {
                    client.terminalOutput.collect { chunk ->
                        // FIX #3 (Performance): Process only the incoming chunk — O(chunk_size)
                        // — instead of splitting the entire accumulated text on every byte.
                        // The old split('\n') + joinToString() created three full copies of
                        // the ~200 KB buffer per chunk, causing GC pressure and ANR risk on
                        // commands like `dmesg` or `tail -f`.
                        val text = chunk.text
                        terminalBuffer.append(text)
                        terminalLineCount += text.count { it == '\n' }

                        // Trim oldest lines from the front when we exceed MAX_TERMINAL_LINES.
                        while (terminalLineCount > MAX_TERMINAL_LINES) {
                            val nl = terminalBuffer.indexOf('\n')
                            if (nl < 0) break
                            terminalBuffer.delete(0, nl + 1)
                            terminalLineCount--
                        }

                        // One string copy (toString) vs the previous three — ~3x fewer
                        // allocations per incoming chunk.
                        _terminalText.value = terminalBuffer.toString()
                        _latency.value = client.latencyMs
                    }
                }
            }

            val success = client.connect()
            if (!success && _state.value !is SessionUiState.Error) {
                // lastError was removed (BUG-07). The combine collector above
                // already surfaces error messages via SessionUiState.Error.
                // This path only fires if connect() returns false *before* any
                // error event is emitted, so a generic message is appropriate.
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_connect_failed_host, profile.host, profile.port)))
            }
        }
    }

    /**
     * FIX C1: Quick Connect — builds a temporary in-memory [RdpProfile] from
     * the supplied credentials and connects without saving anything to the DB.
     */
    fun loadAndConnectQuick(
        host: String,
        port: Int,
        username: String,
        password: String,
        deviceWidth: Int,
        deviceHeight: Int,
    ) {
        if (host.isBlank()) {
            _state.value = SessionUiState.Error(appContext.getString(R.string.error_quick_connect_host_required))
            return
        }
        val tempProfile = com.gotohex.rdp.data.model.RdpProfile(
            id       = "__quick__",
            name     = host,
            host     = host,
            port     = port,
            username = username,
            password = password,
        )
        savedDeviceWidth  = deviceWidth
        savedDeviceHeight = deviceHeight

        // BUG-Z3 FIX: intentionalDisconnect is set to true by disconnect() and was not
        // being reset here, mirroring the same bug fixed in loadAndConnect() (BUG-Y3).
        // Without this reset, auto-reconnect is silently disabled for any Quick Connect
        // session that follows a manual disconnect().
        intentionalDisconnect = false
        currentSessionJob?.cancel()
        remoteClient?.disconnect()
        remoteClient = null

        currentSessionJob = sessionScope.launch(Dispatchers.IO) { // BUG 9 FIX
            _state.emit(SessionUiState.Connecting(host))
            currentProfileId = "__quick__"
            _protocolType.value = com.gotohex.rdp.data.model.ProtocolType.RDP

            if (currentTabId == null) {
                currentTabId = sessionTabManager.openTab(tempProfile)
                // FIX MAX_TABS: same guard as loadAndConnect — abort before
                // allocating large Bitmaps if we're at the session cap.
                if (currentTabId == null) {
                    // i18n FIX: same fix as loadAndConnect above.
                    _state.emit(
                        SessionUiState.Error(
                            appContext.getString(
                                R.string.error_max_sessions,
                                com.gotohex.rdp.session.SessionTabManager.MAX_TABS
                            )
                        )
                    )
                    return@launch
                }
            }

            screenBitmap?.recycle()
            displayBitmapA?.recycle()
            displayBitmapB?.recycle()
            // BUG-M3 FIX: reset frame-rate tracker on new quick-connect session.
            lastFrameTimeMs = 0L
            _frameRateMs.value = 0L
            val (resW, resH) = if (deviceWidth > 0 && deviceHeight > 0) deviceWidth to deviceHeight else 1280 to 720
            screenWidth  = resW
            screenHeight = resH
            _resolution.value = resW to resH
            screenBitmap   = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
            displayBitmapA = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
            displayBitmapB = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
            useDisplayBitmapA.set(true)

            val client = RemoteSessionFactory.create(
                tempProfile, resW, resH,
                compressionQuality = settings.value.compressionQuality,  // FIX-QC1: was hardcoded 75
                appContext = appContext,
            )  // BUG-B/H FIX
            remoteClient = client

            // FIX-QC2: Log Quick Connect sessions in connection history just like regular profiles.
            sessionWasSuccessful = false
            val logEntry = com.gotohex.rdp.data.model.ConnectionLog(
                profileId    = null,  // __quick__ is not a real DB row
                profileName  = host,
                host         = host,
                port         = port,
                protocolType = com.gotohex.rdp.data.model.ProtocolType.RDP
            )
            currentLogId = connectionLogRepository.start(logEntry)

            launch {
                kotlinx.coroutines.flow.combine(client.sessionState, client.error.onStart { emit("") }) { remoteState, msg ->
                    remoteState to msg.ifBlank { null }
                }.collect { (remoteState, lastError) ->
                    when (remoteState) {
                        RemoteSessionState.CONNECTED -> {
                            reconnectAttempts = 0
                            intentionalDisconnect = false
                            sessionWasSuccessful = true
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.gotohex.rdp.data.model.ConnectionState.CONNECTED, ""
                                )
                            }
                            _state.emit(SessionUiState.Connected(tempProfile))
                        }
                        RemoteSessionState.AUTH_FAILED -> {
                            currentLogId?.let { logId ->
                                connectionLogRepository.finish(logId, appContext.getString(R.string.disconnect_reason_auth), sessionWasSuccessful)
                            }
                            currentLogId = null  // BUG-Z2 FIX: prevent double-finish overwriting error reason
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.gotohex.rdp.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_auth_failed)
                                )
                            }
                            _state.emit(SessionUiState.Error(lastError ?: appContext.getString(R.string.error_auth_failed_credentials)))
                        }
                        RemoteSessionState.ERROR -> {
                            // FIX-QC3: Auto-reconnect for Quick Connect, mirroring loadAndConnect logic.
                            val s = settings.value
                            if (!intentionalDisconnect && s.autoReconnect && reconnectAttempts < s.autoReconnectAttempts) {
                                reconnectAttempts++
                                val waitSec = reconnectAttempts * 3
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry, reconnectAttempts, s.autoReconnectAttempts)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_countdown, reconnectAttempts, s.autoReconnectAttempts, waitSec)
                                ))
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // BUG-AA2 FIX: close the current log before the reconnect job
                                // overwrites currentLogId with a new entry.
                                val errMsgForLog = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsgForLog, sessionWasSuccessful)
                                }
                                currentLogId = null
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    delay(waitSec * 1000L)
                                    if (!intentionalDisconnect) {
                                        loadAndConnectQuick(host, port, username, password, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                val errMsg = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsg, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent DISCONNECTED branch from overwriting the error reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_error)
                                    )
                                }
                                _state.emit(SessionUiState.Error(errMsg))
                            }
                        }
                        RemoteSessionState.DISCONNECTED -> {
                            val s = settings.value
                            if (reconnectJob?.isActive == true) return@collect
                            if (!intentionalDisconnect && s.autoReconnect && reconnectAttempts < s.autoReconnectAttempts &&
                                _state.value is SessionUiState.Connected
                            ) {
                                reconnectAttempts++
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry, reconnectAttempts, s.autoReconnectAttempts)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_retry, reconnectAttempts, s.autoReconnectAttempts)
                                ))
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // BUG-AA2 FIX: close the current log before the reconnect job
                                // overwrites currentLogId with a new entry.
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, null, sessionWasSuccessful)
                                }
                                currentLogId = null
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    delay(3_000)
                                    if (!intentionalDisconnect) {
                                        loadAndConnectQuick(host, port, username, password, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, null, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent any further finish() from re-writing over a null reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.gotohex.rdp.data.model.ConnectionState.DISCONNECTED, ""
                                    )
                                }
                                _state.emit(SessionUiState.Disconnected)
                            }
                        }
                        else -> {}
                    }
                }
            }
            launch {
                client.frameUpdates.collect { frame ->
                    try { applyFrameUpdate(frame) } catch (_: Exception) {}
                    _latency.value = client.latencyMs
                }
            }
            // BUG-4 FIX: Periodic thumbnail loop was present in loadAndConnect() but missing
            // here. Quick Connect sessions never persisted a card thumbnail, so the profile
            // list always showed the placeholder image even after a successful connection.
            // Note: currentProfileId == "__quick__", so saveLastFrameThumbnail() already
            // guards against writing an orphan file via its internal "__quick__" check.
            launch {
                while (isActive) {
                    delay(15_000)
                    if (_state.value is SessionUiState.Connected) {
                        saveLastFrameThumbnail()
                    }
                }
            }
            val success = client.connect()
            if (!success && _state.value !is SessionUiState.Error) {
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_connect_failed_host, host, port)))
            }
        }
    }

    /**
     * Saves a small thumbnail of the current [screenBitmap] for the active
     * profile (issue #11), used by [com.gotohex.rdp.ui.components.RdpProfileCard]
     * to show "what the system looked like last time" blended into the card.
     * Runs on a background thread and never throws.
     */
    private fun saveLastFrameThumbnail() {
        val bitmap = screenBitmap ?: return
        val profileId = currentProfileId ?: return
        // BUG-R1 FIX: The bitmap copy used to happen INSIDE the viewModelScope coroutine.
        // That coroutine is NOT cancelled by currentSessionJob?.cancel(), so when
        // loadAndConnect() ran screenBitmap?.recycle() inside the new session job, the
        // copy coroutine might still be alive — bitmap.copy() on a recycled bitmap throws
        // IllegalStateException("can't copy a recycled bitmap").
        // Fix: take the snapshot synchronously HERE (before the coroutine launch) so the
        // result is already an independent Bitmap, unaffected by any later recycle().
        val snapshot = try {
            synchronized(bitmap) {
                if (bitmap.isRecycled) return
                bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            }
        } catch (_: Exception) { return }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // BUG-AA1 FIX: guard BOTH LastFrameStore.save() and updateScreenshot()
                // behind the same "__quick__" check. The previous BUG-X2 fix only protected
                // the DB update; LastFrameStore.save() was still called unconditionally,
                // writing an orphan "__quick__.jpg" into cacheDir/last_frames/ whenever
                // disconnect() was called on a Quick Connect session.
                if (profileId != "__quick__") {
                    com.gotohex.rdp.util.LastFrameStore.save(appContext, profileId, snapshot)
                    // BUG-5 FIX: persist filename to DB so the card thumbnail survives app restarts.
                    // LastFrameStore saves the file as "$profileId.jpg" in cacheDir/last_frames/.
                    repository.updateScreenshot(profileId, "$profileId.jpg")
                }
            } finally {
                snapshot.recycle()
            }
        }
    }

    private fun applyFrameUpdate(frame: RemoteFrameUpdate) {
        val bitmap = screenBitmap ?: return
        if (frame.width <= 0 || frame.height <= 0) return

        // Defensive validation — never trust pixel array size or rectangle
        // bounds blindly. A mismatch here previously threw an uncaught
        // ArrayIndexOutOfBoundsException (or a native Canvas crash from
        // Bitmap.createBitmap/drawBitmap with out-of-range rects) on the very
        // first frame after the session reports CONNECTED, which is exactly
        // the "app closes suddenly when the connection starts" symptom
        // (issue #3).
        val expectedPixelCount = frame.width.toLong() * frame.height.toLong()
        if (frame.pixels.size.toLong() < expectedPixelCount) return

        // Clamp the destination rectangle to the screen bitmap's bounds.
        val dstX = frame.x.coerceIn(0, bitmap.width)
        val dstY = frame.y.coerceIn(0, bitmap.height)
        val drawW = frame.width.coerceAtMost(bitmap.width - dstX)
        val drawH = frame.height.coerceAtMost(bitmap.height - dstY)
        if (drawW <= 0 || drawH <= 0) return

        synchronized(bitmap) {
            if (frame.fullScreen && dstX == 0 && dstY == 0 &&
                drawW == bitmap.width && drawH == bitmap.height &&
                drawW == frame.width && drawH == frame.height
            ) {
                bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            } else {
                // BUG-E FIX: Avoid creating a temporary Bitmap per dirty rectangle.
                // The old pattern allocated a full Bitmap(w×h) then immediately recycled it
                // on every partial update — causing continuous GC pressure.
                // Canvas.drawBitmap(int[], offset, stride, x, y, width, height, hasAlpha, paint)
                // reads directly from the IntArray with zero heap allocation.
                // srcRect is always (0, 0, drawW, drawH), so offset = 0 and stride = frame.width.
                val androidCanvas = android.graphics.Canvas(bitmap)
                @Suppress("DEPRECATION")
                androidCanvas.drawBitmap(
                    frame.pixels,
                    0,              // offset: dirty rect starts at pixel[0]
                    frame.width,    // stride: full row width of the source frame
                    dstX.toFloat(),
                    dstY.toFloat(),
                    drawW,
                    drawH,
                    false,          // hasAlpha: no alpha blending needed for remote desktop
                    null            // paint
                )
            }
        }
        // FIX #5: Double-buffer — copy the freshly-written screenBitmap into
        // the inactive display buffer (no new allocation), then swap so Compose
        // always receives a different object reference (StateFlow equality check)
        // while the other buffer is safely idle. Eliminates the per-frame Bitmap
        // allocation that caused continuous GC pressure on ARMv7 (512 MB–1 GB RAM).
        // FIX #5: CAS-based atomic flip — getAndSet(!get()) was a two-step
        // read-then-write that let two concurrent frame callbacks land on the
        // same display buffer. compareAndSet retries until it wins the race,
        // guaranteeing exactly one winner per flip.
        var wasA: Boolean
        do {
            wasA = useDisplayBitmapA.get()
        } while (!useDisplayBitmapA.compareAndSet(wasA, !wasA))
        val displayTarget = if (wasA) displayBitmapA else displayBitmapB
        if (displayTarget != null) {
            // BUG-RACE FIX: bitmap is read by drawBitmap() below. Previously it was only
            // protected inside the synchronized(bitmap) block above, but between that block
            // and synchronized(displayTarget) a concurrent loadAndConnect() could call
            // screenBitmap?.recycle(), causing drawBitmap() to throw IllegalStateException.
            // Solution: hold synchronized(bitmap) across the copy so recycle() must wait.
            synchronized(bitmap) {
                if (!bitmap.isRecycled) {
                    synchronized(displayTarget) {
                        android.graphics.Canvas(displayTarget).drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            }
            _frameBitmap.tryEmit(displayTarget)
            // BUG-M3 FIX: record frame interval for accurate FPS display.
            // The old FPS counter used 1000/latency (connection setup time → constant),
            // producing a meaningless value. We track the real inter-frame gap here.
            val now = System.currentTimeMillis()
            if (lastFrameTimeMs > 0L) _frameRateMs.value = now - lastFrameTimeMs
            lastFrameTimeMs = now
        }
    }

    fun sendMouseMove(x: Int, y: Int)                                            = remoteClient?.sendMouseMove(x, y)
    fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) = remoteClient?.sendMouseClick(x, y, button, down)
    fun sendMouseScroll(x: Int, y: Int, delta: Int)                              = remoteClient?.sendMouseScroll(x, y, delta)
    fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean = false)    = remoteClient?.sendKeyEvent(scanCode, down, extended)
    fun sendCtrlAltDel()                                                         = remoteClient?.sendCtrlAltDel()

    /** SSH terminal text input — typed characters or pasted text. */
    fun sendTerminalText(text: String) = remoteClient?.sendText(text)

    /** SSH terminal control byte, e.g. Ctrl+C. */
    fun sendTerminalControlByte(byte: Int) {
        (remoteClient as? SshClient)?.sendControlByte(byte)
    }

    fun setSessionToolbarVisible(visible: Boolean) = viewModelScope.launch {
        settingsRepository.updateSessionToolbarVisible(visible)
    }

    fun setSessionExtraKeysVisible(visible: Boolean) = viewModelScope.launch {
        settingsRepository.updateSessionExtraKeysVisible(visible)
    }

    // UX-07: mark gesture hints as shown so overlay never appears again
    fun markGestureHintsShown() = viewModelScope.launch {
        settingsRepository.markGestureHintsShown()
    }

    // UX-09: Save the current remote frame as a PNG to the app-private screenshots directory.
    //
    // NEW-CRIT-1 FIX: Previous implementation wrote to MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    // (DIRECTORY_PICTURES/HexRDP on API 29+, or getExternalStoragePublicDirectory on API 26-28).
    // Both are world-readable public locations:
    //   • Any app holding READ_MEDIA_IMAGES can access every screenshot silently.
    //   • Google Photos, Samsung Cloud, and other gallery apps auto-upload them to the cloud.
    //   • RDP screenshots routinely contain corporate data, credentials, and confidential docs.
    // FLAG_SECURE prevents Android's system screenshot mechanism, but does NOT stop the app
    // itself from reading its own framebuffer and writing it anywhere — as was the case here.
    //
    // Fix: save to filesDir/screenshots/ (app-private, mode 0700, inaccessible to other apps)
    // and exclude the path from backup via backup_rules.xml (already present: NEW-CRIT-2 FIX).
    // To share a screenshot the user must explicitly tap "Share" and choose a destination —
    // implement that with FileProvider + ACTION_SEND at the UI layer when needed.
    //
    // SEC-SCREENSHOT-RETENTION FIX: The previous implementation created a new timestamped file
    // for every capture with no cleanup policy. Screenshots contain confidential remote-desktop
    // content (corporate data, credentials, documents) and accumulate indefinitely, causing:
    //   1. Storage exhaustion — each PNG can be several MB; no upper bound existed.
    //   2. Growing attack surface — sensitive data accumulates in filesDir/screenshots/
    //      and stays there forever (until the user uninstalls).
    // Fix: enforce a dual retention policy enforced on every save:
    //   • MAX_SCREENSHOTS (20): oldest file deleted when the count exceeds the limit.
    //   • MAX_SCREENSHOT_DIR_BYTES (200 MB): oldest files pruned until under the cap.
    // Both limits are applied BEFORE writing the new file so the directory never briefly
    // exceeds the limit. The newest files are always preserved.
    private val MAX_SCREENSHOTS = 20
    private val MAX_SCREENSHOT_DIR_BYTES = 200L * 1024 * 1024  // 200 MB

    /**
     * Prunes old screenshots from [dir] to enforce the retention policy.
     * Files are sorted oldest-first (by lastModified); the newest ones survive.
     * Called on Dispatchers.IO before every new screenshot is written.
     */
    private fun pruneScreenshots(dir: java.io.File) {
        val files = (dir.listFiles() ?: return)
            .filter { it.isFile && it.name.endsWith(".png") }
            .sortedBy { it.lastModified() }   // oldest first

        // Enforce count limit
        var toDelete = files.size - MAX_SCREENSHOTS + 1   // +1 to leave room for the new file
        if (toDelete > 0) {
            files.take(toDelete).forEach { it.delete() }
        }

        // Enforce size limit (re-read after count pruning)
        val remaining = (dir.listFiles() ?: return)
            .filter { it.isFile && it.name.endsWith(".png") }
            .sortedBy { it.lastModified() }
        var totalBytes = remaining.sumOf { it.length() }
        for (f in remaining) {
            if (totalBytes <= MAX_SCREENSHOT_DIR_BYTES) break
            totalBytes -= f.length()
            f.delete()
        }
    }

    fun takeScreenshot(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            // BUG #3 FIX: Race Condition — applyFrameUpdate() writes to the display
            // bitmaps (displayBitmapA/B) concurrently using synchronized(displayTarget).
            // bitmap.compress() is NOT synchronized, so compressing the live display
            // bitmap while a frame update writes to it produces torn/corrupt screenshots
            // and can crash Bitmap.compress() with an IllegalStateException on some devices.
            // Fix: make a thread-safe copy first, then compress the copy.
            val safeCopy: Bitmap = synchronized(bitmap) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            try {
                val screenshotsDir = java.io.File(appContext.filesDir, "screenshots").also { it.mkdirs() }
                // SEC-SCREENSHOT-RETENTION FIX: Prune old files BEFORE writing the new one.
                pruneScreenshots(screenshotsDir)
                val file = java.io.File(screenshotsDir, "HexRDP_${System.currentTimeMillis()}.png")
                file.outputStream().use { out ->
                    safeCopy.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                _screenshotSaved.emit(true)
            } catch (_: Exception) {
                _screenshotSaved.emit(false)
            } finally {
                safeCopy.recycle()
            }
        }
    }

    private val _screenshotSaved = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val screenshotSaved = _screenshotSaved.asSharedFlow()

    /**
     * BUG 6 FIX: Called from Activity.onConfigurationChanged() to keep
     * savedDeviceWidth/Height in sync after the user rotates the device.
     * Without this, reconnections after rotation send pre-rotation dimensions
     * to the RDP server, causing a mismatched resolution.
     */
    fun updateDisplayMetrics(width: Int, height: Int) {
        savedDeviceWidth  = width
        savedDeviceHeight = height
    }

    fun disconnect() {
        intentionalDisconnect = true   // FIX #7: explicit flag replaces unsafe Int.MAX_VALUE sentinel
        // FIX-reconnect-reset: reset the attempt counter so the next manual connection
        // starts fresh. Without this, a session that exhausted some retries before the
        // user disconnected would leave a non-zero counter, giving fewer auto-reconnect
        // attempts in the subsequent session.
        reconnectAttempts = 0
        // BUG-reconnect FIX: cancel any pending delayed-reconnect coroutine so the
        // countdown stops immediately. Without this, a user clicking "Disconnect"
        // during "Reconnecting in Ns…" would see the connection re-open the moment
        // the timer expired, violating their explicit intent.
        reconnectJob?.cancel()
        reconnectJob = null
        if (currentProtocol() != ProtocolType.SSH) saveLastFrameThumbnail()
        remoteClient?.disconnect()
        // Feature-06: finalise the history log as a clean user-initiated disconnect
        viewModelScope.launch {
            currentLogId?.let { logId ->
                connectionLogRepository.finish(logId, null, sessionWasSuccessful)
            }
            currentLogId = null  // BUG-Z2 FIX: prevent any trailing state event from calling finish() again
            _state.emit(SessionUiState.Disconnected)
        }
        // Feature-05: remove this tab from the tab bar
        currentTabId?.let { sessionTabManager.closeTab(it) }
    }

    private fun currentProtocol(): ProtocolType = _protocolType.value

    /** Feature-05: switch the foreground tab (called from tab bar clicks). */
    fun switchToTab(tabId: String) = sessionTabManager.switchTo(tabId)

    /** Feature-05: close a tab by id (e.g. from the × button in the tab bar). */
    fun closeTab(tabId: String) = sessionTabManager.closeTab(tabId)

    override fun onCleared() {
        super.onCleared()
        // BUG-N3 FIX: unregister so the listener lambda is not held alive after the ViewModel is gone
        com.gotohex.rdp.TrimMemoryBus.unregister(trimMemoryListener)

        // BUG-5 FIX: When the app is killed during an active session (process death, OOM,
        // swipe-to-kill), onCleared() is called but the normal disconnect/state-machine path
        // is never reached — leaving the connection_logs row with disconnectedAt=0 forever.
        // viewModelScope is already cancelled by the time onCleared() runs, so we cannot
        // use launch{}. Use runBlocking on a plain background thread instead, mirroring
        // the same pattern used for the thumbnail save below.
        val logId = currentLogId
        val wasOk = sessionWasSuccessful
        if (logId != null) {
            currentLogId = null  // prevent any concurrent access from double-finishing
            Thread {
                try {
                    kotlinx.coroutines.runBlocking {
                        connectionLogRepository.finish(logId, appContext.getString(R.string.disconnect_reason_app_closed), wasOk)
                    }
                } catch (_: Exception) { /* best-effort; never crash onCleared */ }
            }.start()
        }

        // viewModelScope is already cancelled by this point, so save directly
        // on a plain thread rather than relying on saveLastFrameThumbnail's
        // viewModelScope.launch (issue #11).
        val bitmap = screenBitmap
        val profileId = currentProfileId
        remoteClient?.disconnect()
        if (bitmap != null && profileId != null) {
            Thread {
                // BUG-N1 FIX: the previous code had no isRecycled guard and no try-catch
                // inside the Thread. If bitmap was recycled by a concurrent call (race with
                // saveLastFrameThumbnail or loadAndConnect), bitmap.copy() would throw an
                // IllegalStateException that the Thread swallowed silently — leaving the
                // snapshot un-recycled and causing a native heap leak. Fix:
                // 1. Check isRecycled inside the synchronized block before copy().
                // 2. Wrap everything in try-catch so any remaining edge-case exception
                //    still triggers cleanup via finally.
                var snapshot: android.graphics.Bitmap? = null
                try {
                    snapshot = synchronized(bitmap) {
                        if (bitmap.isRecycled) null
                        else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    }
                    // BUG-Z1 FIX: "__quick__" is not a real profile; skip saving to avoid
                    // an orphan "__quick__.jpg" accumulating in cacheDir/last_frames/.
                    if (snapshot != null && profileId != "__quick__") {
                        com.gotohex.rdp.util.LastFrameStore.save(appContext, profileId, snapshot)
                    }
                } catch (_: Exception) {
                    // bitmap was recycled between the isRecycled check and copy() — ignore
                } finally {
                    snapshot?.recycle()
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
        } else {
            screenBitmap?.recycle()
        }
        // FIX #5 / #9: Also recycle the double-buffer display bitmaps.
        displayBitmapA?.recycle(); displayBitmapA = null
        displayBitmapB?.recycle(); displayBitmapB = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

sealed class SessionUiState {
    object Idle                                   : SessionUiState()
    data class Connecting(val name: String)       : SessionUiState()
    data class Connected(val profile: RdpProfile) : SessionUiState()
    data class Error(val message: String)         : SessionUiState()
    object Disconnected                           : SessionUiState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Session Screen Root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RdpSessionScreen(
    viewModel: RdpSessionViewModel,
    onClose: () -> Unit
) {
    val state        by viewModel.state.collectAsState()
    val frameBitmap  by viewModel.frameBitmap.collectAsState()
    val soundManager = com.gotohex.rdp.ui.components.LocalSoundManager.current
    var prevState by remember { mutableStateOf<SessionUiState>(SessionUiState.Idle) }

    // BUG 11 FIX: enableOnBackInvokedCallback="true" is now set in the Manifest for
    // RdpSessionActivity. Without a BackHandler, pressing back during an active session
    // would exit the app immediately with no warning, leaving the session running.
    // Intercept back when connected and show a confirmation dialog instead.
    var showDisconnectDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = state is SessionUiState.Connected) {
        showDisconnectDialog = true
    }
    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title   = { Text(stringResource(R.string.disconnect_dialog_title)) },
            text    = { Text(stringResource(R.string.disconnect_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectDialog = false
                    viewModel.disconnect()
                    onClose()
                }) { Text(stringResource(R.string.disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    LaunchedEffect(state) {
        if (state is SessionUiState.Connected && prevState !is SessionUiState.Connected) {
            soundManager?.play(com.gotohex.rdp.audio.SoundManager.Sound.CONNECT, 0.85f)
        } else if (state is SessionUiState.Error) {
            soundManager?.play(com.gotohex.rdp.audio.SoundManager.Sound.ERROR, 0.7f)
        } else if (state is SessionUiState.Disconnected && prevState is SessionUiState.Connected) {
            soundManager?.play(com.gotohex.rdp.audio.SoundManager.Sound.SUCCESS, 0.5f)
        }
        prevState = state
    }
    val latency      by viewModel.latency.collectAsState()
    val frameRateMs  by viewModel.frameRateMs.collectAsState()   // BUG-M3 FIX
    val settings     by viewModel.settings.collectAsState()
    val protocolType by viewModel.protocolType.collectAsState()
    val terminalText by viewModel.terminalText.collectAsState()
    val (screenWidth, screenHeight) = viewModel.resolution.collectAsState().value

    // UX-09: Show toast when screenshot is saved/failed
    val context = androidx.compose.ui.platform.LocalContext.current

    // NEW-CRIT-1 FIX: screenshotPermLauncher removed — saving to filesDir/screenshots/
    // requires no runtime permission (app-private storage is always accessible to the app).
    // The previous WRITE_EXTERNAL_STORAGE launcher was only needed for the public gallery path.

    LaunchedEffect(Unit) {
        viewModel.screenshotSaved.collect { success ->
            val msg = if (success) context.getString(R.string.screenshot_saved)
                      else context.getString(R.string.screenshot_failed)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Toolbar / extra-keys visibility default from Settings, but can be toggled
    // freely by the user during the session (issue #8 — full show/hide control).
    // The default is persisted back to Settings so it's remembered next time
    // (issue #9).
    var showToolbar      by remember { mutableStateOf(settings.sessionToolbarVisible) }
    var showExtraKeys    by remember { mutableStateOf(settings.sessionExtraKeysVisible) }
    var showFileTransfer by remember { mutableStateOf(false) }

    val backgroundColor = DeepSpace

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        // Smooth crossfade between connecting / connected / error / disconnected
        // states instead of an abrupt switch (issue #6 — professional animation).
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(animationSpec = tween(350)) + scaleIn(initialScale = 0.97f, animationSpec = tween(350))) togetherWith
                    fadeOut(animationSpec = tween(200))
            },
            label = "session_state"
        ) { s ->
            when (s) {
                is SessionUiState.Connecting   -> ConnectingOverlay(s.name)
                is SessionUiState.Error        -> ErrorOverlay(s.message, onClose)
                is SessionUiState.Disconnected -> DisconnectedOverlay(onClose)
                is SessionUiState.Connected -> if (protocolType == ProtocolType.SSH) {
                    // SSH is a text terminal, not a framebuffer — a completely
                    // different (and much simpler) UI than the RDP/VNC canvas.
                    TerminalScreen(
                        profileName  = s.profile.name,
                        terminalText = terminalText,
                        latency      = latency,
                        onSendText   = { viewModel.sendTerminalText(it) },
                        onSendControlByte = { viewModel.sendTerminalControlByte(it) },
                        onDisconnect = { viewModel.disconnect(); onClose() }
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        RdpCanvas(
                            bitmap               = frameBitmap,
                            screenWidth          = screenWidth,
                            screenHeight         = screenHeight,
                            cursorStyle          = settings.cursorStyle,
                            cursorSize           = settings.cursorSize,
                            showCursor           = settings.showCursorOnTouch,
                            touchpadSensitivity  = settings.touchpadSensitivity,   // ✅
                            scrollSensitivity    = settings.scrollSensitivity,     // ✅
                            rightClickLongPress  = settings.rightClickLongPress,   // ✅
                            onMouseMove  = { x, y       -> viewModel.sendMouseMove(x, y) },
                            onMouseClick = { x, y, b, d -> viewModel.sendMouseClick(x, y, b, d) },
                            onScroll     = { x, y, d    -> viewModel.sendMouseScroll(x, y, d) },
                            modifier     = Modifier.fillMaxSize()
                        )

                        // Toolbar pinned to the top, safe-area aware.
                        AnimatedVisibility(
                            visible  = showToolbar,
                            enter    = slideInVertically(animationSpec = tween(250)) { -it } + fadeIn(tween(250)),
                            exit     = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(200)),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .windowInsetsPadding(WindowInsets.statusBars)
                        ) {
                            SessionToolbar(
                                latency      = latency,
                                profileName  = s.profile.name,
                                onCtrlAltDel = { viewModel.sendCtrlAltDel() },
                                onScreenshot = {
                                    // NEW-CRIT-1 FIX: No runtime permission needed — screenshots
                                    // are saved to app-private filesDir/screenshots/ which requires
                                    // no WRITE_EXTERNAL_STORAGE permission on any API level.
                                    frameBitmap?.let { viewModel.takeScreenshot(it) }
                                },
                                onFileTransfer = { showFileTransfer = true },
                                onDisconnect = { viewModel.disconnect(); onClose() },
                                onHide       = {
                                    showToolbar = false
                                    viewModel.setSessionToolbarVisible(false)
                                }
                            )
                        }

                        // Extra-keys bar pinned to the bottom, but always pushed
                        // above the on-screen keyboard via imePadding (issue #8 —
                        // "important buttons must appear directly above the
                        // keyboard").
                        AnimatedVisibility(
                            visible  = showExtraKeys,
                            enter    = slideInVertically(animationSpec = tween(250)) { it } + fadeIn(tween(250)),
                            exit     = slideOutVertically(animationSpec = tween(200)) { it } + fadeOut(tween(200)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .imePadding()
                                .navigationBarsPadding()
                        ) {
                            ExtraKeysBar(
                                onHide = {
                                    showExtraKeys = false
                                    viewModel.setSessionExtraKeysVisible(false)
                                }
                            ) { sc, dn, ext -> viewModel.sendKeyEvent(sc, dn, ext) }
                        }

                        // UX-07: View-only banner — persistent indicator when VNC
                        // is in view-only mode so users know why input is blocked.
                        if (protocolType == ProtocolType.VNC && s.profile.vncViewOnly) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(top = if (showToolbar) 56.dp else 8.dp)
                                    .background(
                                        color = SolarFlare.copy(alpha = 0.88f),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector         = Icons.Outlined.Visibility,
                                        contentDescription  = null,
                                        tint                = DeepSpace,
                                        modifier            = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text  = stringResource(R.string.view_only_banner),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DeepSpace,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Small re-show handles — full control over hidden bars.
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AnimatedVisibility(visible = !showToolbar) {
                                SmallShowButton(
                                    icon = Icons.Default.KeyboardArrowDown,
                                    onClick = {
                                        showToolbar = true
                                        viewModel.setSessionToolbarVisible(true)
                                    }
                                )
                            }
                        }
                        if (!showExtraKeys) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .imePadding()
                                    .navigationBarsPadding()
                                    .padding(8.dp)
                            ) {
                                SmallShowButton(
                                    icon = Icons.Default.KeyboardArrowUp,
                                    onClick = {
                                        showExtraKeys = true
                                        viewModel.setSessionExtraKeysVisible(true)
                                    }
                                )
                            }
                        }

                        // FIX B1: عداد FPS — يُعرض في الزاوية السفلية اليسرى عند تفعيل الإعداد.
                        // كان الإعداد موجوداً في DataStore لكنه لم يُعرض هنا قط.
                        if (settings.showFpsCounter) {
                            // BUG-M3 FIX: use real frame interval instead of connection
                            // latency. Old formula: 1000/latency where latency = setup time
                            // (constant) → always showed the same wrong FPS all session long.
                            val fpsText = remember(frameRateMs, latency) {
                                if (frameRateMs > 0L) "${(1000L / frameRateMs.coerceAtLeast(1L)).coerceAtMost(999)}fps  ${latency}ms"
                                else "-- fps"
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .navigationBarsPadding()
                                    .imePadding()
                                    .padding(start = 8.dp, bottom = if (showExtraKeys) 48.dp else 8.dp)
                                    .background(
                                        color = DeepSpace.copy(alpha = 0.72f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text  = fpsText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        latency < 100  -> PlasmaGreen
                                        latency < 300  -> ConnectingAmber
                                        else           -> ErrorRed
                                    },
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }

                        // UX-07: Gesture hints overlay — shown once on first connection.
                        // FIX B5: تم حذف الـ LaunchedEffect الميت الذي كان يشغّل delay(500)
                        // دون أن يغير أي حالة، مما جعل التعليق "brief pause" مضللاً.
                        // الـ AnimatedVisibility + fadeIn(tween(400)) تعطي تأخيراً بصرياً كافياً.
                        var gestureHintsDismissed by remember { mutableStateOf(false) }
                        val shouldShowHints = !settings.hasShownGestureHints && !gestureHintsDismissed
                        AnimatedVisibility(
                            visible  = shouldShowHints,
                            enter    = fadeIn(tween(400)),
                            exit     = fadeOut(tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            GestureHintsOverlay(
                                onDismiss = {
                                    gestureHintsDismissed = true
                                    viewModel.markGestureHintsShown()
                                }
                            )
                        }

                        // ── File Transfer Dialog ───────────────────────────
                        if (showFileTransfer) {
                            FileTransferDialog(
                                profile  = s.profile,
                                onDismiss = { showFileTransfer = false }
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SmallShowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val bg = DeepSpace
    val accent = PulsarCyan
    Surface(
        color    = bg.copy(alpha = 0.8f),
        shape    = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            icon, null,
            tint     = accent,
            modifier = Modifier.size(28.dp).padding(4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RDP Canvas — uses Compose Canvas + android.graphics for bitmap drawing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RdpCanvas(
    bitmap: Bitmap?,
    screenWidth: Int,
    screenHeight: Int,
    cursorStyle: String = "default",
    cursorSize: Int = 24,
    showCursor: Boolean = true,
    touchpadSensitivity: Float = 1f,    // ✅ حساسية التوباد
    scrollSensitivity: Float = 1f,      // ✅ حساسية التمرير
    rightClickLongPress: Boolean = true, // ✅ كليك أيمن بالضغط المطوّل
    onMouseMove:  (Int, Int) -> Unit,
    onMouseClick: (Int, Int, RemoteMouseButton, Boolean) -> Unit,
    onScroll:     (Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var cursorX      by remember { mutableStateOf(screenWidth / 2f) }
    var cursorY      by remember { mutableStateOf(screenHeight / 2f) }
    var scale        by remember { mutableStateOf(1f) }
    var offsetX      by remember { mutableStateOf(0f) }
    var offsetY      by remember { mutableStateOf(0f) }
    var lastPtrCount by remember { mutableStateOf(0) }
    // FIX #4: Track whether the finger has dragged past a threshold since
    // Press so that Release does not fire a spurious LEFT click after a pan.
    var hasDragged   by remember { mutableStateOf(false) }
    var dragAccumX   by remember { mutableStateOf(0f) }
    var dragAccumY   by remember { mutableStateOf(0f) }
    val DRAG_THRESHOLD_PX = 8f * LocalDensity.current.density  // ~8 dp

    val backgroundColor  = DeepSpace
    val cursorThemeColor = LocalSpaceColors.current.cursorColor

    // BUG-M4 FIX: capture mutable parameters as updatable State so the
    // pointerInput(Unit) coroutine (which never restarts) always reads the
    // latest values without needing its key list to change.
    val scrollSensitivityRef = rememberUpdatedState(scrollSensitivity)
    val onScrollRef          = rememberUpdatedState(onScroll)

    val cursorBitmap = remember(cursorStyle, cursorSize, cursorThemeColor) {
        com.gotohex.rdp.ui.components.buildCursorBitmap(cursorStyle, cursorSize, cursorThemeColor).asImageBitmap()
    }
    val cursorPxSize = cursorBitmap.width.toFloat()

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                // BUG-M4 FIX: previously both this handler (via pan update) AND the
                // manual awaitPointerEventScope below (via onScroll) fired simultaneously
                // on every 2-finger event — the local canvas panned AND a remote scroll
                // was sent at the same time, causing erratic behavior.
                // Fix: distinguish intent by zoom magnitude.
                //  • Significant spread/pinch (|zoom−1| > 1.5%) → pan the local view.
                //  • Pure 2-finger swipe (zoom ≈ 1) → send remote scroll only.
                // The manual handler's "2 ->" scroll case is removed to prevent the double-fire.
                detectTransformGestures { _, pan, zoom, _ ->
                    if (lastPtrCount >= 2) {
                        scale = (scale * zoom).coerceIn(0.5f, 4f)
                        if (kotlin.math.abs(zoom - 1f) > 0.015f) {
                            // Pinch-zoom: pan the local canvas view
                            offsetX += pan.x
                            offsetY += pan.y
                        } else if (pan.y != 0f) {
                            // Pure 2-finger swipe → forward as remote scroll
                            val steps = (pan.y / 8f * scrollSensitivityRef.value).toInt()
                            val delta = when {
                                steps != 0 -> (-steps).coerceIn(-5, 5)
                                pan.y > 0  -> -1
                                else       -> 1
                            }
                            onScrollRef.value(cursorX.toInt(), cursorY.toInt(), delta)
                        }
                    }
                }
            }
            // ✅ كليك أيمن بالضغط المطوّل
            .pointerInput(rightClickLongPress) {
                if (rightClickLongPress) {
                    detectTapGestures(
                        onLongPress = { _ ->
                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.RIGHT, true)
                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.RIGHT, false)
                        }
                    )
                }
            }
            .pointerInput(touchpadSensitivity, scrollSensitivity) {
                awaitPointerEventScope {
                    while (true) {
                        val event    = awaitPointerEvent()
                        val active   = event.changes.filter { it.pressed }
                        lastPtrCount = active.size

                        when (event.type) {
                            PointerEventType.Press -> {
                                // FIX #4: Reset drag tracking on every new touch-down
                                hasDragged = false
                                dragAccumX = 0f
                                dragAccumY = 0f
                            }
                            PointerEventType.Move -> {
                                when (active.size) {
                                    1 -> {
                                        // ✅ حساسية التوباد مطبَّقة
                                        val dx = (active[0].position.x - active[0].previousPosition.x) * 2f * touchpadSensitivity
                                        val dy = (active[0].position.y - active[0].previousPosition.y) * 2f * touchpadSensitivity
                                        cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
                                        cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
                                        onMouseMove(cursorX.toInt(), cursorY.toInt())
                                        // FIX #4: accumulate raw displacement to detect intentional drags
                                        dragAccumX += kotlin.math.abs(active[0].position.x - active[0].previousPosition.x)
                                        dragAccumY += kotlin.math.abs(active[0].position.y - active[0].previousPosition.y)
                                        if (dragAccumX > DRAG_THRESHOLD_PX || dragAccumY > DRAG_THRESHOLD_PX) {
                                            hasDragged = true
                                        }
                                    }
                                    // BUG-M4 FIX: 2-finger scroll removed from here.
                                    // detectTransformGestures above now handles it exclusively,
                                    // distinguishing scroll (zoom≈1) from pinch-zoom (|zoom-1|>1.5%).
                                    // Having both fire on the same event caused local-view pan AND
                                    // remote scroll to trigger simultaneously.
                                }
                                event.changes.forEach { it.consume() }
                            }
                            PointerEventType.Release -> {
                                // FIX #4: Only fire LEFT click if the finger did NOT drag past
                                // the threshold. Before this fix, any pan followed by lifting
                                // the finger would generate an unintended click on the remote.
                                if (active.isEmpty() && !hasDragged) {
                                    onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.LEFT, true)
                                    onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.LEFT, false)
                                }
                                hasDragged = false
                                dragAccumX = 0f
                                dragAccumY = 0f
                            }
                            else -> {}
                        }
                    }
                }
            }
    ) {
        withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, Offset(size.width / 2f, size.height / 2f))
        }) {
            if (bitmap != null) {
                drawImage(
                    image   = bitmap.asImageBitmap(),
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                )
            } else {
                drawRect(color = backgroundColor)
            }

            // Draw cursor (issue #4 — now reflects the chosen style/size/visibility)
            if (showCursor) {
                val cx = cursorX / screenWidth  * size.width  - cursorPxSize / 2f
                val cy = cursorY / screenHeight * size.height - cursorPxSize / 2f
                drawImage(cursorBitmap, topLeft = Offset(cx, cy))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Session Toolbar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SessionToolbar(
    latency:        Long,
    profileName:    String,
    onCtrlAltDel:   () -> Unit,
    onScreenshot:   () -> Unit,
    onFileTransfer: () -> Unit,
    onDisconnect:   () -> Unit,
    onHide:         () -> Unit
) {
    Surface(
        color    = DeepSpace.copy(alpha = 0.93f),
        border   = BorderStroke(1.dp, HorizonGray),
        shape    = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    profileName,
                    style      = MaterialTheme.typography.labelLarge,
                    color      = StarDust,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${latency}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        latency < 100  -> PlasmaGreen
                        latency < 300  -> ConnectingAmber
                        else           -> ErrorRed
                    }
                )
            }
            ToolbarIconButton(Icons.Default.CameraAlt, stringResource(R.string.take_screenshot), onScreenshot, tint = PlasmaGreen)
            ToolbarIconButton(Icons.Outlined.Lock,  stringResource(R.string.cd_ctrl_alt_del),  onCtrlAltDel)
            // ── File Transfer icon ────────────────────────────────────────────
            ToolbarIconButton(Icons.Default.FolderOpen, stringResource(R.string.ft_toolbar_button), onFileTransfer, tint = PulsarCyan)
            ToolbarIconButton(Icons.Default.Close,  stringResource(R.string.disconnect), onDisconnect, tint = NovaPink)
            IconButton(onClick = onHide) {
                Icon(Icons.Default.ExpandLess, null, tint = CometTail, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun ToolbarIconButton(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    label:   String,
    onClick: () -> Unit,
    tint:    Color = PulsarCyan
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = CometTail, fontSize = 9.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Extra Keys Bar
// ─────────────────────────────────────────────────────────────────────────────

private data class SpecialKey(val label: String, val scanCode: Int, val extended: Boolean = false)

private val EXTRA_KEYS = listOf(
    SpecialKey("Esc",   0x01), SpecialKey("Tab",   0x0F),
    SpecialKey("Ctrl",  0x1D), SpecialKey("Alt",   0x38),
    SpecialKey("Win",   0x5B, true),
    SpecialKey("F1",  0x3B), SpecialKey("F2",  0x3C), SpecialKey("F3",  0x3D),
    SpecialKey("F4",  0x3E), SpecialKey("F5",  0x3F), SpecialKey("F6",  0x40),
    SpecialKey("F7",  0x41), SpecialKey("F8",  0x42), SpecialKey("F9",  0x43),
    SpecialKey("F10", 0x44), SpecialKey("F11", 0x57), SpecialKey("F12", 0x58),
    SpecialKey("Del",   0x53, true), SpecialKey("Home",  0x47, true),
    SpecialKey("End",   0x4F, true), SpecialKey("PgUp",  0x49, true),
    SpecialKey("PgDn",  0x51, true), SpecialKey("Ins",   0x52, true),
    SpecialKey("PrtSc", 0x37, true)
)

@Composable
fun ExtraKeysBar(onHide: () -> Unit = {}, onKeyEvent: (Int, Boolean, Boolean) -> Unit) {
    Surface(
        color    = DeepSpace.copy(alpha = 0.95f),
        border   = BorderStroke(1.dp, HorizonGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier              = Modifier.weight(1f),
                contentPadding        = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(EXTRA_KEYS.size) { i ->
                    val key     = EXTRA_KEYS[i]
                    var pressed by remember { mutableStateOf(false) }
                    Surface(
                        color    = if (pressed) PulsarCyan.copy(alpha = 0.2f) else NebulaSurface,
                        shape    = RoundedCornerShape(8.dp),
                        border   = BorderStroke(1.dp, if (pressed) PulsarCyan else HorizonGray),
                        modifier = Modifier.pointerInput(key.label) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    when (ev.type) {
                                        PointerEventType.Press   -> { pressed = true;  onKeyEvent(key.scanCode, true,  key.extended) }
                                        PointerEventType.Release -> { pressed = false; onKeyEvent(key.scanCode, false, key.extended) }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            key.label,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = if (pressed) PulsarCyan else StarDust
                        )
                    }
                }
            }
            IconButton(onClick = onHide) {
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Overlay Composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectingOverlay(name: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "connect_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Box(Modifier.fillMaxSize().background(DeepSpace), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                color       = PulsarCyan.copy(alpha = alpha),
                modifier    = Modifier.size(56.dp),
                strokeWidth = 3.dp
            )
            Text(stringResource(R.string.connecting_to, name), style = MaterialTheme.typography.titleMedium, color = StarDust)
            Text(stringResource(R.string.connecting_please_wait), style = MaterialTheme.typography.bodySmall, color = CometTail)
        }
    }
}

@Composable
fun ErrorOverlay(message: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeepSpace), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = NovaPink, modifier = Modifier.size(64.dp))
            Text(
                stringResource(R.string.error_connection_failed),
                style      = MaterialTheme.typography.headlineSmall,
                color      = StarDust,
                fontWeight = FontWeight.Bold
            )
            Text(
                message,
                style     = MaterialTheme.typography.bodyMedium,
                color     = CometTail,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
            SpaceButton(stringResource(R.string.close), onClose, variant = ButtonVariant.GHOST, modifier = Modifier.width(160.dp))
        }
    }
}

@Composable
fun DisconnectedOverlay(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeepSpace), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Outlined.LinkOff, null, tint = DisconnectedGray, modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.disconnected_title), style = MaterialTheme.typography.titleLarge, color = StarDust)
            SpaceButton(stringResource(R.string.close), onClose, modifier = Modifier.width(160.dp))
        }
    }
}

// ── UX-07: Gesture Hints Overlay ─────────────────────────────────────────────
@Composable
private fun GestureHintsOverlay(onDismiss: () -> Unit) {
    val hints = listOf(
        Triple(Icons.Outlined.ZoomIn,               stringResource(R.string.gesture_pinch_pan_title),  stringResource(R.string.gesture_pinch_pan_desc)),
        Triple(Icons.Outlined.TouchApp,              stringResource(R.string.gesture_long_press_title), stringResource(R.string.gesture_long_press_desc)),
        // FIX A: كانت "3-Finger Swipe" — لا يوجد كود يعالج ثلاثة أصابع في RdpCanvas.
        // الطريقة الفعلية لإظهار/إخفاء شريط الأدوات هي الضغط على الزر ↓ في أعلى اليمين.
        Triple(Icons.Default.KeyboardArrowDown,      stringResource(R.string.gesture_toolbar_title),    stringResource(R.string.gesture_toolbar_desc)),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                stringResource(R.string.gesture_guide_title),
                style    = MaterialTheme.typography.titleLarge,
                color    = StarDust,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))

            hints.forEach { (icon, title, desc) ->
                Row(
                    modifier = Modifier
                        .padding(horizontal = 40.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(PulsarCyan.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, PulsarCyan.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(26.dp))
                    }
                    Column {
                        Text(title, color = StarDust,  style = MaterialTheme.typography.bodyLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(desc,  color = CometTail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Surface(
                color  = PulsarCyan.copy(alpha = 0.15f),
                shape  = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .border(1.dp, PulsarCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Text(stringResource(R.string.gesture_got_it), color = PulsarCyan,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.gesture_tap_to_dismiss), color = CometTail.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
