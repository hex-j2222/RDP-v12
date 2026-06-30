package com.gotohex.rdp.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gotohex.rdp.audio.SoundManager
import com.gotohex.rdp.ui.components.LocalSoundManager
import com.gotohex.rdp.ui.screens.AppLockScreen
import com.gotohex.rdp.ui.screens.ConnectionHistoryScreen
import com.gotohex.rdp.ui.screens.HomeScreen
import com.gotohex.rdp.ui.screens.SettingsScreen
import com.gotohex.rdp.ui.theme.HexRDPTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var soundManager: SoundManager

    // FIX #6: Track the current intent as a Compose state so that
    // onNewIntent() (fired when the app is already open and the user taps an
    // .rdp file) triggers a recomposition and re-runs the LaunchedEffect.
    private val _intentState = mutableStateOf<android.content.Intent?>(null)

    // FIX B6: حالة القفل مرفوعة إلى مستوى Activity حتى يمكن لـ onStop()
    // إعادة القفل عند ذهاب التطبيق للخلفية.
    private val _isUnlocked = mutableStateOf(false)

    // BUG #2 FIX: نحتاج لمعرفة إعدادات القفل في onStop() (خارج Compose)
    // نحفظ آخر قيمة معروفة هنا حتى يمكن فحصها بدون DataStore.
    @Volatile private var _lockEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // BUG-COMPAT-2 FIX: installSplashScreen() MUST be called before super.onCreate().
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // FIX-FLAG_SECURE: Prevent the app (including PIN entry screen) from
        // appearing in Android's Recent Apps thumbnail and from being captured
        // by screen-recording apps. Without this flag, a passive observer could
        // read the PIN digits from the Recent Apps screenshot.
        // This covers MainActivity which hosts AppLockScreen, HomeScreen, Settings, etc.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        _intentState.value = intent
        enableEdgeToEdge()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()

            // FIX #6: keyed on _intentState so it re-runs when onNewIntent fires.
            val currentIntent by _intentState
            LaunchedEffect(currentIntent?.data) {
                if (currentIntent?.action == android.content.Intent.ACTION_VIEW) {
                    currentIntent?.data?.let { uri -> viewModel.parseRdpUri(uri, contentResolver) }
                }
            }

            val uiState  by viewModel.uiState.collectAsState()
            val settings = uiState.settings

            // ── قفل التطبيق ─────────────────────────────────────────────────
            // BUG #5 FIX: نبدأ مقفلاً (false) ريثما تُحمَّل الإعدادات من DataStore.
            // FIX B6: الحالة مُعرَّفة على مستوى Activity حتى يمكن إعادة القفل من onStop().
            var isUnlocked by _isUnlocked
            // BUG #2 FIX: نحدّث _lockEnabled في كل مرة تتغير إعدادات القفل
            // حتى تستطيع onStop() معرفة هل يجب إعادة القفل أم لا.
            LaunchedEffect(settings.biometricLockEnabled, settings.pinLockEnabled) {
                _lockEnabled = settings.biometricLockEnabled || settings.pinLockEnabled
            }
            LaunchedEffect(uiState.isLoading, settings.biometricLockEnabled, settings.pinLockEnabled) {
                if (!uiState.isLoading && !settings.biometricLockEnabled && !settings.pinLockEnabled) {
                    // الإعدادات حُمِّلت ولا يوجد قفل مفعَّل — افتح مباشرة
                    isUnlocked = true
                }
            }

            HexRDPTheme(
                darkTheme    = settings.isDarkMode,
                themeVariant = settings.themeVariant
            ) {
                CompositionLocalProvider(LocalSoundManager provides soundManager) {
                    LaunchedEffect(settings.soundEnabled) {
                        soundManager.setEnabled(settings.soundEnabled)
                    }

                    // FIX #2: إعداد اللغة كان محفوظًا في DataStore لكن لم يكن يُطبَّق أبدًا.
                    // نستخدم AppCompatDelegate.setApplicationLocales() (يعمل على API 26+)
                    // لتغيير لغة التطبيق فعليًا عند كل تغيير في الإعداد.
                    // على Android 13+ يُعيد النظام تشغيل الـ Activity تلقائيًا.
                    LaunchedEffect(settings.language) {
                        val locales = when (settings.language) {
                            "system" -> LocaleListCompat.getEmptyLocaleList()
                            else     -> LocaleListCompat.forLanguageTags(settings.language)
                        }
                        AppCompatDelegate.setApplicationLocales(locales)
                    }

                    // ── شاشة القفل ─────────────────────────────────────────
                    AnimatedVisibility(
                        visible = !isUnlocked,
                        enter   = fadeIn(),
                        exit    = fadeOut(tween(300))
                    ) {
                        AppLockScreen(
                            biometricEnabled = settings.biometricLockEnabled,
                            pinEnabled       = settings.pinLockEnabled,
                            encryptedPin     = settings.pinCode,
                            isUnlocked       = isUnlocked,
                            onUnlocked       = { isUnlocked = true }
                        )
                    }

                    // ── التطبيق الرئيسي ────────────────────────────────────
                    AnimatedVisibility(
                        visible = isUnlocked,
                        enter   = fadeIn(tween(300))
                    ) {
                        val navController = rememberNavController()
                        NavHost(
                            navController    = navController,
                            startDestination = "home",
                            modifier         = Modifier.fillMaxSize(),
                            enterTransition  = {
                                slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(320))
                            },
                            exitTransition   = {
                                slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(250))
                            },
                            popEnterTransition  = {
                                slideInHorizontally(tween(350, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(300))
                            },
                            popExitTransition   = {
                                slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(250))
                            },
                        ) {
                            composable("home")               { HomeScreen(navController = navController) }
                            composable("settings")           { SettingsScreen(navController = navController) }
                            composable("connection_history") { ConnectionHistoryScreen(navController = navController) }
                        }
                    }
                }
            }
        }
    }

    // BUG-Y1 FIX: SoundManager is @Singleton — it lives for the entire process lifetime.
    // The previous code called release() unconditionally in onDestroy(), which fires on
    // BOTH screen rotation AND true finish. On rotation isFinishing=false, so the singleton
    // was permanently poisoned (released=true) and all sounds died silently for the rest
    // of the session, even though the same instance was still injected into the new Activity.
    // Fix: only release when the Activity is truly finishing (user navigates away / back-press).
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            soundManager.release()
        }
    }

    // FIX B6 + BUG #2 FIX: إعادة القفل التلقائي عند ذهاب التطبيق للخلفية،
    // لكن فقط إذا كان قفل البصمة أو PIN مفعَّلاً فعلاً.
    // الخلل الأصلي: _isUnlocked.value = false تُستدعى دائماً بغضّ النظر
    // عن إعدادات القفل، مما يجعل المستخدمين بدون قفل محاصرين في شاشة
    // لا تحتوي على طريقة للفتح (لا بصمة ولا PIN).
    override fun onStop() {
        super.onStop()
        // نُعيد القفل فقط إذا كان أحد آليات القفل مفعَّلاً.
        // _lockEnabled يُحدَّث من LaunchedEffect أعلاه مع كل تغيير للإعدادات.
        if (_lockEnabled) {
            _isUnlocked.value = false
        }
    }

    // FIX #6: Handle .rdp file intents when the app is already in the foreground.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _intentState.value = intent
    }
}
