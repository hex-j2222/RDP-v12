package com.gotohex.rdp.ui

import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gotohex.rdp.audio.SoundManager
import com.gotohex.rdp.data.model.RdpPerformance
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.data.repository.AppSettingsRepository
import com.gotohex.rdp.data.repository.AppSettings
import com.gotohex.rdp.data.repository.RdpProfileRepository
import com.gotohex.rdp.R
import com.gotohex.rdp.util.RdpFileParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class HomeUiState(
    val profiles: List<RdpProfile> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val showFirstLaunchDialog: Boolean = false,
    val networkQuality: NetworkQuality = NetworkQuality.UNKNOWN,
    val isLoading: Boolean = true,
)

enum class NetworkQuality { UNKNOWN, POOR, FAIR, GOOD, EXCELLENT }

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: RdpProfileRepository,
    private val settingsRepository: AppSettingsRepository,
    val soundManager: SoundManager,
    private val connectionLogRepository: com.gotohex.rdp.data.repository.ConnectionLogRepository,
    private val sessionTabManager: com.gotohex.rdp.session.SessionTabManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ── Feature-05: live session tabs ─────────────────────────────────────────
    val sessionTabs = sessionTabManager.tabs
    val activeTabId = sessionTabManager.activeTabId

    // ── Import .rdp file ──────────────────────────────────────────────────────
    // Holds a pre-parsed profile waiting for the user to review and confirm.
    private val _pendingImportProfile = MutableStateFlow<RdpProfile?>(null)
    val pendingImportProfile: StateFlow<RdpProfile?> = _pendingImportProfile.asStateFlow()

    // FIX #8: expose parse errors so the UI can show a meaningful message
    // instead of silently ignoring a file with a missing "full address".
    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // FIX L1 / FIX-i18n: Expose WakeOnLan result as Boolean? (true=success, false=error)
    // so the UI layer can pick the correct localised string via stringResource().
    // Previously this held a hardcoded English string, breaking Arabic localisation.
    private val _wolResult = MutableStateFlow<Boolean?>(null)
    val wolResult: StateFlow<Boolean?> = _wolResult.asStateFlow()

    fun clearWolResult() { _wolResult.value = null }

    /** Parses an .rdp URI (from file picker or external intent) and stores the result. */
    fun parseRdpUri(uri: Uri, contentResolver: ContentResolver) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            try {
                // FIX-FILE-URI: Reject any URI whose scheme is not "content".
                // Although the file:// intent-filter was removed from the Manifest,
                // a malicious app on the device can still send an explicit Intent that
                // bypasses the filter and lands here. Enforcing content:// at this layer
                // ensures the OS content-permission model is always in effect regardless
                // of how the intent was constructed.
                if (uri.scheme != "content") {
                    _importError.value = context.getString(R.string.error_rdp_file_corrupted)
                    return@withContext
                }
                val fileName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.removeSuffix(".rdp")
                    ?.removeSuffix(".RDP")
                    // i18n FIX: was hardcoded "Imported" — use string resource so
                    // Arabic users see "مستورد" instead of English.
                    ?: context.getString(R.string.imported_profile_name)
                // BUG #2 FIX: openInputStream returns null when the URI is no longer
                // accessible (revoked permission, deleted file, etc.). Report it.
                val stream = contentResolver.openInputStream(uri)
                if (stream == null) {
                    _importError.value = context.getString(R.string.error_file_cannot_open)
                } else {
                    stream.use {
                        val profile = RdpFileParser.parse(it, fileName)
                        _pendingImportProfile.value = profile
                        _importError.value = null
                    }
                }
            } catch (e: IllegalArgumentException) {
                // BUG-4 FIX: was e.message (raw English hardcoded string) → now uses
                // localized R.string so Arabic users see an Arabic error message.
                _importError.value = context.getString(R.string.error_rdp_file_missing_host)
            } catch (_: Exception) {
                _importError.value = context.getString(R.string.error_rdp_file_corrupted)
            }
        }
    }

    /** Called when the user dismisses the import review dialog. */
    fun clearPendingImport() {
        _pendingImportProfile.value = null
        _importError.value = null
    }

    // ── Feature-06: connection history ────────────────────────────────────────
    val connectionLogs = connectionLogRepository.getRecentLogs()

    fun clearConnectionHistory() = viewModelScope.launch {
        connectionLogRepository.clearAll()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        observeData()
        registerNetworkCallback()   // ✅ مراقبة حية بدل قراءة لمرة واحدة
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                profileRepository.getAllProfiles(),
                settingsRepository.settingsFlow
            ) { profiles, settings ->
                val shouldShowSubscribe = settings.showSubscribePopup &&
                        shouldShowSubscribeDialog(settings)
                val shouldShowFirstLaunch = !settings.hasShownFirstLaunch
                soundManager.setEnabled(settings.soundEnabled)
                HomeUiState(
                    profiles = profiles,
                    settings = settings,
                    showFirstLaunchDialog = shouldShowFirstLaunch,
                    networkQuality        = _uiState.value.networkQuality,
                    isLoading             = false
                )
            }.collect { _uiState.value = it }
        }
    }

    // ── Network — مراقبة حية بـ NetworkCallback ───────────────────────────────
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // قراءة أولية فورية
        _uiState.update { it.copy(networkQuality = readNetworkQuality(cm)) }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                _uiState.update { it.copy(networkQuality = qualityFromCaps(caps)) }
            }
            override fun onLost(net: Network) {
                _uiState.update { it.copy(networkQuality = NetworkQuality.UNKNOWN) }
            }
        }
        networkCallback = cb
        try { cm.registerNetworkCallback(request, cb) } catch (_: Exception) {}
    }

    private fun readNetworkQuality(cm: ConnectivityManager): NetworkQuality {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkQuality.UNKNOWN
        return qualityFromCaps(caps)
    }

    // UX-10: WiFi quality is now based on actual downstream bandwidth, not
    // just transport type. A weak WiFi signal gets the same treatment as
    // slow cellular so we don't suggest LAN-level settings for a poor link.
    private fun qualityFromCaps(caps: NetworkCapabilities): NetworkQuality {
        val bw = caps.linkDownstreamBandwidthKbps
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                // Wired Ethernet: treat as EXCELLENT regardless of reported BW
                // (Android often under-reports BW for Ethernet adapters).
                NetworkQuality.EXCELLENT

            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> when {
                bw > 25_000 -> NetworkQuality.EXCELLENT   // > 25 Mbps  — strong WiFi
                bw >  5_000 -> NetworkQuality.GOOD        // > 5 Mbps   — decent WiFi
                bw >  1_000 -> NetworkQuality.FAIR        // > 1 Mbps   — weak WiFi
                else        -> NetworkQuality.POOR        //  ≤ 1 Mbps  — very weak signal
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> when {
                bw > 5_000 -> NetworkQuality.GOOD
                bw > 1_000 -> NetworkQuality.FAIR
                else       -> NetworkQuality.POOR
            }

            else -> NetworkQuality.UNKNOWN
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let {
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        // BUG-Y1 FIX: soundManager.release() removed from here.
        // SoundManager is @Singleton — its lifecycle is the Application process, not
        // any individual ViewModel. Calling release() here caused two problems:
        //   1. Double-release on true finish: MainActivity.onDestroy(isFinishing=true)
        //      already calls release(); onCleared() fires moments later → pool.release() twice.
        //   2. Wrong owner: a future ViewModel recreation after process restore would get
        //      the same poisoned singleton (released=true) with no way to re-initialise it.
        // Ownership is now solely in MainActivity.onDestroy() guarded by isFinishing.
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun shouldShowSubscribeDialog(settings: AppSettings): Boolean {
        if (settings.lastSubscribePromptTime == 0L) return true
        val days = TimeUnit.MILLISECONDS.toDays(
            System.currentTimeMillis() - settings.lastSubscribePromptTime
        )
        return days >= settings.subscribePromptIntervalDays
    }

    fun getRecommendedPerformance(): Int = when (_uiState.value.networkQuality) {
        NetworkQuality.POOR      -> RdpPerformance.LOW_BANDWIDTH
        NetworkQuality.FAIR      -> RdpPerformance.MEDIUM
        NetworkQuality.GOOD      -> RdpPerformance.WIFI
        NetworkQuality.EXCELLENT -> RdpPerformance.LAN
        NetworkQuality.UNKNOWN   -> RdpPerformance.AUTO
    }

    // ── Wake-on-LAN ────────────────────────────────────────────────────────────

    /**
     * Sends a UDP Magic Packet for [profile] and returns a [Result].
     * The caller is responsible for any post-wake delay before connecting.
     */
    fun sendWakeOnLan(profile: RdpProfile) = viewModelScope.launch {
        // FIX L1: Surface WoL result (success or error) to the UI.
        // FIX-i18n: emit Boolean (true=success, false=error) so the UI picks
        // the correct localised string; avoids hardcoded English messages.
        _wolResult.value = null
        val result = runCatching {
            com.gotohex.rdp.util.WakeOnLanManager.sendMagicPacket(
                context          = context,
                macAddress       = profile.wolMacAddress,
                broadcastAddress = profile.wolBroadcastAddress
            )
        }
        _wolResult.value = result.isSuccess
    }

    // ── Profile Actions ────────────────────────────────────────────────────────

    // UX-03: Drag-to-reorder
    fun reorderProfiles(profiles: List<RdpProfile>) = viewModelScope.launch {
        profileRepository.reorderProfiles(profiles)
    }

    // CRIT-1 FIX: _profileSaveError exposes Keystore encryption failures to the UI.
    // addProfile() and updateProfile() call withEncryptedSecrets() which calls
    // CryptoHelper.encrypt(). That throws SecurityException when the Android Keystore
    // is unavailable (factory reset, old ARMv7 device, OEM quirks). Without try/catch
    // the exception propagated silently inside viewModelScope.launch, crashing the app.
    private val _profileSaveError = MutableStateFlow<String?>(null)
    val profileSaveError: StateFlow<String?> = _profileSaveError.asStateFlow()
    fun clearProfileSaveError() { _profileSaveError.value = null }

    fun addProfile(profile: RdpProfile) = viewModelScope.launch {
        // BUG-L2 FIX: imported .rdp files may already carry specific performanceFlags.
        // BUG-N2 FIX: previous sentinel was `!= 0`, but RdpPerformance.LAN = 3 ≠ 0,
        // so a new profile with the old default (LAN) was never given the recommended
        // performance — getRecommendedPerformance() was dead code.
        // New sentinel: AUTO (= 4) is the new default in RdpProfile. Any profile that
        // explicitly chose a real performance level (LOW_BANDWIDTH/MEDIUM/WIFI/LAN) will
        // differ from AUTO and keep its value; only fresh auto-default profiles trigger
        // the network-aware recommendation.
        try {
            val flags = if (profile.performanceFlags != RdpPerformance.AUTO) profile.performanceFlags
                        else getRecommendedPerformance()
            profileRepository.saveProfile(profile.copy(performanceFlags = flags))
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "addProfile: Keystore encryption failed", e)
            _profileSaveError.value = context.getString(R.string.error_profile_save_keystore)
        }
    }
    fun updateProfile(profile: RdpProfile) = viewModelScope.launch {
        try {
            profileRepository.updateProfile(profile)
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "updateProfile: Keystore encryption failed", e)
            _profileSaveError.value = context.getString(R.string.error_profile_save_keystore)
        }
    }
    fun deleteProfile(profile: RdpProfile)  = viewModelScope.launch {
        profileRepository.deleteProfile(profile)
        // BUG-Y4 FIX: deleting the DB row left the thumbnail file at
        // cacheDir/last_frames/$profileId.jpg with no owner. Over time these
        // orphan files accumulate and are never evicted (the OS only clears
        // cacheDir under storage pressure, and only the whole directory at once).
        // Delete the file synchronously here — it's a tiny JPEG, never blocks.
        com.gotohex.rdp.util.LastFrameStore.delete(context, profile.id)
    }
    fun dismissFirstLaunchDialog()          = viewModelScope.launch {
        settingsRepository.markFirstLaunchShown()
        _uiState.update { it.copy(showFirstLaunchDialog = false) }
    }

    // ── Settings Update Functions ──────────────────────────────────────────────

    fun updateDarkMode(v: Boolean)              = viewModelScope.launch { settingsRepository.updateDarkMode(v) }
    fun updateLanguage(v: String)               = viewModelScope.launch { settingsRepository.updateLanguage(v) }
    fun updateTheme(v: String)                  = viewModelScope.launch { settingsRepository.updateThemeVariant(v) }
    fun updateCursorStyle(v: String)            = viewModelScope.launch { settingsRepository.updateCursorStyle(v) }
    fun updateCursorSize(v: Int)                = viewModelScope.launch { settingsRepository.updateCursorSize(v) }
    fun updateTouchpadSensitivity(v: Float)     = viewModelScope.launch { settingsRepository.updateTouchpadSensitivity(v) }
    fun updateScrollSensitivity(v: Float)       = viewModelScope.launch { settingsRepository.updateScrollSensitivity(v) }  // ✅
    fun updateHapticFeedback(v: Boolean)        = viewModelScope.launch { settingsRepository.updateHapticFeedback(v) }
    fun updateKeepScreenOn(v: Boolean)          = viewModelScope.launch { settingsRepository.updateKeepScreenOn(v) }       // ✅
    fun updateAutoReconnect(v: Boolean)         = viewModelScope.launch { settingsRepository.updateAutoReconnect(v) }
    fun updateAutoReconnectAttempts(v: Int)     = viewModelScope.launch { settingsRepository.updateAutoReconnectAttempts(v) }
    fun updateCompressionQuality(v: Int)        = viewModelScope.launch { settingsRepository.updateCompressionQuality(v) }
    fun updateDefaultResolution(v: String)      = viewModelScope.launch { settingsRepository.updateDefaultResolution(v) }
    fun updateSessionToolbarVisible(v: Boolean) = viewModelScope.launch { settingsRepository.updateSessionToolbarVisible(v) }
    fun updateSessionExtraKeysVisible(v: Boolean) = viewModelScope.launch { settingsRepository.updateSessionExtraKeysVisible(v) }
    fun updateRunInBackground(v: Boolean)       = viewModelScope.launch { settingsRepository.updateRunInBackground(v) }
    fun updateSoundEnabled(v: Boolean)          = viewModelScope.launch { settingsRepository.updateSoundEnabled(v) }
    fun updateBiometricLock(v: Boolean)         = viewModelScope.launch { settingsRepository.updateBiometricLock(v) }     // ✅
    fun updatePinLock(enabled: Boolean, pin: String = "") = viewModelScope.launch {
        // FIX-PIN-ENCRYPT: CryptoHelper.encrypt() throws SecurityException when
        // Android Keystore is unavailable (e.g. after factory reset on some OEMs,
        // or on very old ARMv7 devices). Without a try/catch here the exception
        // propagates silently — the UI shows the PIN as enabled but it was never
        // actually saved, locking the user out with no working PIN.
        // We surface the error via _pinLockError so the UI can display a message.
        try {
            settingsRepository.updatePinLock(enabled, pin)
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "PIN encryption failed — Keystore unavailable", e)
            _pinLockError.value = context.getString(R.string.error_pin_keystore_unavailable)
        }
    }
    private val _pinLockError = MutableStateFlow<String?>(null)
    val pinLockError: StateFlow<String?> = _pinLockError.asStateFlow()
    fun clearPinLockError() { _pinLockError.value = null }
    fun updateRightClickLongPress(v: Boolean)   = viewModelScope.launch { settingsRepository.updateRightClickLongPress(v) } // ✅
    fun markGestureHintsShown()                 = viewModelScope.launch { settingsRepository.markGestureHintsShown() }
    fun markHomeScreenOpened(currentCount: Int) = viewModelScope.launch { settingsRepository.markHomeScreenOpened(currentCount) }
    // FIX I1: These settings existed in AppSettings and AppSettingsRepository
    // but had no ViewModel wrapper, making them impossible to change from the UI.
    fun updateShowFps(v: Boolean)               = viewModelScope.launch { settingsRepository.updateShowFps(v) }
    fun updateShowCursorOnTouch(v: Boolean)     = viewModelScope.launch { settingsRepository.updateShowCursorOnTouch(v) }
}
// Appended by WoL patch — keep at end
