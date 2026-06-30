package com.gotohex.rdp.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.gotohex.rdp.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val isDarkMode: Boolean = true,
    val language: String = "system",
    val themeVariant: String = "space",
    val cursorStyle: String = "default",
    val cursorSize: Int = 24,
    val showCursorOnTouch: Boolean = true,
    val touchpadSensitivity: Float = 1.0f,
    val scrollSensitivity: Float = 1.0f,
    val showSubscribePopup: Boolean = true,
    val lastSubscribePromptTime: Long = 0L,
    val subscribePromptIntervalDays: Int = 3,
    val hasShownFirstLaunch: Boolean = false,
    val hapticFeedback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoReconnect: Boolean = true,
    val autoReconnectAttempts: Int = 3,
    val compressionQuality: Int = 75,
    val showFpsCounter: Boolean = false,
    val defaultResolution: String = "auto",
    val sessionToolbarVisible: Boolean = true,
    val sessionExtraKeysVisible: Boolean = true,
    val runInBackground: Boolean = true,
    val soundEnabled: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val pinLockEnabled: Boolean = false,
    val pinCode: String = "",                    // مُشفَّر
    val rightClickLongPress: Boolean = true,
    val hasShownGestureHints: Boolean = false,
    // UI-POLISH: number of times the home screen has been opened — used to show the
    // full "swipe to edit/delete" text hint only for the first few visits, then fall
    // back to the small persistent icon-only hint. Avoids permanent text-noise on
    // every card once the user already knows the gesture.
    val homeScreenOpenCount: Int = 0,
)

@Singleton
class AppSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // CRIT-5 FIX: Replace plain Preferences DataStore with EncryptedSharedPreferences.
    //
    // Plain DataStore writes a protobuf file with no authentication or encryption.
    // On a rooted device any process can:
    //   • Read PIN_LOCK_ENABLED and set it to false → bypass app lock entirely.
    //   • Read the encrypted PIN ciphertext and replace it with a ciphertext of a
    //     known PIN (under the same Keystore key) → unlock the app with an attacker-
    //     controlled PIN.
    // Neither attack is detectable because plain DataStore has no tamper-detection tag.
    //
    // EncryptedSharedPreferences uses AES-256-GCM authenticated encryption for all
    // values (and AES-256-SIV for keys), backed by an Android Keystore key.  Any
    // modification of the on-disk file is detected via the GCM authentication tag and
    // causes decryption to fail — matching the tamper-detection already in place for
    // TOFU data and the database key (both already using EncryptedSharedPreferences).
    //
    // Migration note: the DataStore file (hex_rdp_settings.preferences_pb) is a
    // different on-disk format from SharedPreferences XML.  On first launch after this
    // change all settings reset to defaults — an accepted one-time UX trade-off.
    private val prefs: SharedPreferences by lazy {
        // HIGH-R2 FIX: Delete the stale plain-text DataStore file left behind by the
        // pre-CRIT-5 build.  The file is no longer read (EncryptedSharedPreferences is
        // now the sole source of truth), but it remains on disk and can expose settings
        // — including the encrypted PIN ciphertext — to any process on a rooted device
        // and to Android Backup (prior to the backup_rules.xml exclusion being added).
        // Deletion is best-effort: if it fails the app continues normally, since the
        // old file is harmless once nothing reads from it.
        try {
            val dsFile = java.io.File(context.filesDir, "datastore/hex_rdp_settings.preferences_pb")
            if (dsFile.exists()) dsFile.delete()
        } catch (_: Exception) { }
        context.openEncryptedPrefs("hex_rdp_settings")
    }

    // Key constants (match the old DataStore key strings exactly for forward-compat if
    // the DataStore file is still present — the two stores are different files so there
    // is no conflict, but consistent naming aids debugging).
    private object Keys {
        const val IS_DARK_MODE            = "is_dark_mode"
        const val LANGUAGE                = "language"
        const val THEME_VARIANT           = "theme_variant"
        const val CURSOR_STYLE            = "cursor_style"
        const val CURSOR_SIZE             = "cursor_size"
        const val SHOW_CURSOR_ON_TOUCH    = "show_cursor_on_touch"
        const val TOUCHPAD_SENSITIVITY    = "touchpad_sensitivity"
        const val SCROLL_SENSITIVITY      = "scroll_sensitivity"
        const val SHOW_SUBSCRIBE_POPUP    = "show_subscribe_popup"
        const val LAST_SUBSCRIBE_PROMPT   = "last_subscribe_prompt"
        const val SUBSCRIBE_INTERVAL_DAYS = "subscribe_interval_days"
        const val HAS_SHOWN_FIRST_LAUNCH  = "has_shown_first_launch"
        const val HAPTIC_FEEDBACK         = "haptic_feedback"
        const val KEEP_SCREEN_ON          = "keep_screen_on"
        const val AUTO_RECONNECT          = "auto_reconnect"
        const val AUTO_RECONNECT_ATTEMPTS = "auto_reconnect_attempts"
        const val COMPRESSION_QUALITY     = "compression_quality"
        const val SHOW_FPS_COUNTER        = "show_fps_counter"
        const val DEFAULT_RESOLUTION      = "default_resolution"
        const val SESSION_TOOLBAR_VISIBLE     = "session_toolbar_visible"
        const val SESSION_EXTRA_KEYS_VISIBLE  = "session_extra_keys_visible"
        const val RUN_IN_BACKGROUND       = "run_in_background"
        const val SOUND_ENABLED           = "sound_enabled"
        const val BIOMETRIC_LOCK_ENABLED  = "biometric_lock_enabled"
        const val PIN_LOCK_ENABLED        = "pin_lock_enabled"
        const val PIN_CODE                = "pin_code"
        const val RIGHT_CLICK_LONG_PRESS  = "right_click_long_press"
        const val HAS_SHOWN_GESTURE_HINTS = "has_shown_gesture_hints"
        const val HOME_SCREEN_OPEN_COUNT  = "home_screen_open_count"
    }

    private fun readSettings(): AppSettings = prefs.run {
        AppSettings(
            isDarkMode              = getBoolean(Keys.IS_DARK_MODE, true),
            language                = getString(Keys.LANGUAGE, "system") ?: "system",
            themeVariant            = getString(Keys.THEME_VARIANT, "space") ?: "space",
            cursorStyle             = getString(Keys.CURSOR_STYLE, "default") ?: "default",
            cursorSize              = getInt(Keys.CURSOR_SIZE, 24),
            showCursorOnTouch       = getBoolean(Keys.SHOW_CURSOR_ON_TOUCH, true),
            touchpadSensitivity     = getFloat(Keys.TOUCHPAD_SENSITIVITY, 1.0f),
            scrollSensitivity       = getFloat(Keys.SCROLL_SENSITIVITY, 1.0f),
            showSubscribePopup      = getBoolean(Keys.SHOW_SUBSCRIBE_POPUP, true),
            lastSubscribePromptTime = getLong(Keys.LAST_SUBSCRIBE_PROMPT, 0L),
            subscribePromptIntervalDays = getInt(Keys.SUBSCRIBE_INTERVAL_DAYS, 3),
            hasShownFirstLaunch     = getBoolean(Keys.HAS_SHOWN_FIRST_LAUNCH, false),
            hapticFeedback          = getBoolean(Keys.HAPTIC_FEEDBACK, true),
            keepScreenOn            = getBoolean(Keys.KEEP_SCREEN_ON, true),
            autoReconnect           = getBoolean(Keys.AUTO_RECONNECT, true),
            autoReconnectAttempts   = getInt(Keys.AUTO_RECONNECT_ATTEMPTS, 3),
            compressionQuality      = getInt(Keys.COMPRESSION_QUALITY, 75),
            showFpsCounter          = getBoolean(Keys.SHOW_FPS_COUNTER, false),
            defaultResolution       = getString(Keys.DEFAULT_RESOLUTION, "auto") ?: "auto",
            sessionToolbarVisible   = getBoolean(Keys.SESSION_TOOLBAR_VISIBLE, true),
            sessionExtraKeysVisible = getBoolean(Keys.SESSION_EXTRA_KEYS_VISIBLE, true),
            runInBackground         = getBoolean(Keys.RUN_IN_BACKGROUND, true),
            soundEnabled            = getBoolean(Keys.SOUND_ENABLED, true),
            biometricLockEnabled    = getBoolean(Keys.BIOMETRIC_LOCK_ENABLED, false),
            pinLockEnabled          = getBoolean(Keys.PIN_LOCK_ENABLED, false),
            pinCode                 = getString(Keys.PIN_CODE, "") ?: "",
            rightClickLongPress     = getBoolean(Keys.RIGHT_CLICK_LONG_PRESS, true),
            hasShownGestureHints    = getBoolean(Keys.HAS_SHOWN_GESTURE_HINTS, false),
            homeScreenOpenCount     = getInt(Keys.HOME_SCREEN_OPEN_COUNT, 0),
        )
    }

    // Reactive Flow via SharedPreferences listener — mirrors the DataStore Flow API.
    // distinctUntilChanged avoids redundant re-compositions for unchanged settings.
    val settingsFlow: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readSettings())   // emit current state immediately on collection start
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    // ── Update helpers ────────────────────────────────────────────────────────
    // SharedPreferences.edit().apply() commits to disk asynchronously on a background
    // thread — equivalent to DataStore's suspend-based edit().  Keeping suspend
    // signatures lets all existing ViewModel call-sites remain unchanged.

    private fun put(block: SharedPreferences.Editor.() -> Unit) =
        prefs.edit().apply(block).apply()

    suspend fun updateDarkMode(v: Boolean)              = put { putBoolean(Keys.IS_DARK_MODE, v) }
    suspend fun updateLanguage(v: String)               = put { putString(Keys.LANGUAGE, v) }
    suspend fun updateThemeVariant(v: String)           = put { putString(Keys.THEME_VARIANT, v) }
    suspend fun updateCursorStyle(v: String)            = put { putString(Keys.CURSOR_STYLE, v) }
    suspend fun updateCursorSize(v: Int)                = put { putInt(Keys.CURSOR_SIZE, v) }
    suspend fun updateTouchpadSensitivity(v: Float)     = put { putFloat(Keys.TOUCHPAD_SENSITIVITY, v) }
    suspend fun updateScrollSensitivity(v: Float)       = put { putFloat(Keys.SCROLL_SENSITIVITY, v) }
    suspend fun updateHapticFeedback(v: Boolean)        = put { putBoolean(Keys.HAPTIC_FEEDBACK, v) }
    suspend fun updateKeepScreenOn(v: Boolean)          = put { putBoolean(Keys.KEEP_SCREEN_ON, v) }
    suspend fun updateAutoReconnect(v: Boolean)         = put { putBoolean(Keys.AUTO_RECONNECT, v) }
    suspend fun updateAutoReconnectAttempts(v: Int)     = put { putInt(Keys.AUTO_RECONNECT_ATTEMPTS, v) }
    suspend fun updateCompressionQuality(v: Int)        = put {
        // BUG-CLAMP FIX: Bitmap.compress() requires quality in [0, 100].
        putInt(Keys.COMPRESSION_QUALITY, v.coerceIn(0, 100))
    }
    suspend fun updateShowFps(v: Boolean)               = put { putBoolean(Keys.SHOW_FPS_COUNTER, v) }
    suspend fun updateShowCursorOnTouch(v: Boolean)     = put { putBoolean(Keys.SHOW_CURSOR_ON_TOUCH, v) }
    suspend fun updateDefaultResolution(v: String)      = put { putString(Keys.DEFAULT_RESOLUTION, v) }
    suspend fun updateSessionToolbarVisible(v: Boolean) = put { putBoolean(Keys.SESSION_TOOLBAR_VISIBLE, v) }
    suspend fun updateSessionExtraKeysVisible(v: Boolean) = put { putBoolean(Keys.SESSION_EXTRA_KEYS_VISIBLE, v) }
    suspend fun updateRunInBackground(v: Boolean)       = put { putBoolean(Keys.RUN_IN_BACKGROUND, v) }
    suspend fun updateSoundEnabled(v: Boolean)          = put { putBoolean(Keys.SOUND_ENABLED, v) }
    suspend fun updateBiometricLock(v: Boolean)         = put { putBoolean(Keys.BIOMETRIC_LOCK_ENABLED, v) }

    suspend fun updatePinLock(enabled: Boolean, pin: String = "") {
        // NEW-BUG-2 FIX: Use isNotEmpty() instead of isNotBlank().
        // isNotBlank() returns false for whitespace-only PINs (e.g. " "), causing
        // encryptedPin to be null while PIN_LOCK_ENABLED is still written as true.
        // Result: the lock is "enabled" with no stored PIN code → permanent lockout
        // with no valid PIN that can ever open it.
        // isNotEmpty() only skips truly empty strings (the default when no PIN is
        // provided), which is the correct semantic here. Consistent with the
        // isBlank()→isEmpty() fix already applied to CryptoHelper.encrypt().
        val encryptedPin: String? = if (enabled && pin.isNotEmpty()) {
            val payload = com.gotohex.rdp.security.PinHasher.hash(pin)
            com.gotohex.rdp.security.CryptoHelper.encrypt(payload)
        } else null

        put {
            putBoolean(Keys.PIN_LOCK_ENABLED, enabled)
            when {
                encryptedPin != null -> putString(Keys.PIN_CODE, encryptedPin)
                !enabled -> {
                    // BUG 2 FIX (SECURITY): Wipe stored PIN when lock is disabled.
                    putString(Keys.PIN_CODE, "")
                }
            }
        }
    }

    suspend fun updateRightClickLongPress(v: Boolean)   = put { putBoolean(Keys.RIGHT_CLICK_LONG_PRESS, v) }
    suspend fun markGestureHintsShown()                 = put { putBoolean(Keys.HAS_SHOWN_GESTURE_HINTS, true) }
    // UI-POLISH: increments on every HomeScreen composition entry, capped at a small
    // number — used only to decide whether to show the full text swipe-hint banner.
    suspend fun markHomeScreenOpened(currentCount: Int) = put {
        putInt(Keys.HOME_SCREEN_OPEN_COUNT, (currentCount + 1).coerceAtMost(99))
    }
    suspend fun markSubscribePromptShown()              = put { putLong(Keys.LAST_SUBSCRIBE_PROMPT, System.currentTimeMillis()) }
    suspend fun markFirstLaunchShown()                  = put { putBoolean(Keys.HAS_SHOWN_FIRST_LAUNCH, true) }
}
