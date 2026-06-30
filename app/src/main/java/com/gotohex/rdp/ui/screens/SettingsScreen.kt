package com.gotohex.rdp.ui.screens

import android.content.Intent
import com.gotohex.rdp.BuildConfig // BUG-4 FIX: needed for dynamic VERSION_NAME
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.gotohex.rdp.R
import com.gotohex.rdp.ui.components.*
import com.gotohex.rdp.ui.components.buildCursorBitmap
import com.gotohex.rdp.ui.MainViewModel
import com.gotohex.rdp.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState  by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val context  = LocalContext.current

    // FIX 3: snackbar scope for non-composable lambdas (e.g. biometric check)
    val scope            = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pinLockError by viewModel.pinLockError.collectAsState()
    LaunchedEffect(pinLockError) {
        pinLockError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearPinLockError()
        }
    }

    StarfieldBackground(isDark = settings.isDarkMode, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost   = { SnackbarHost(snackbarHostState) }, // FIX 3
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings),
                            style = MaterialTheme.typography.titleLarge,
                            color = StarDust, fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = PulsarCyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ══ 1. APPEARANCE ════════════════════════════════════════════
                SettingsSection(
                    icon  = Icons.Outlined.Palette,
                    title = stringResource(R.string.appearance)
                )

                SettingsToggle(
                    icon      = if (settings.isDarkMode) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                    title     = stringResource(R.string.dark_mode),
                    subtitle  = if (settings.isDarkMode) stringResource(R.string.dark) else stringResource(R.string.light),
                    checked   = settings.isDarkMode,
                    onCheckedChange = viewModel::updateDarkMode
                )

                SettingsChoice(
                    icon     = Icons.Outlined.ColorLens,
                    title    = stringResource(R.string.theme),
                    options  = listOf(
                        "space"  to stringResource(R.string.theme_space),
                        "nebula" to stringResource(R.string.theme_nebula),
                        "aurora" to stringResource(R.string.theme_aurora)
                    ),
                    selected = settings.themeVariant,
                    onSelect = viewModel::updateTheme
                )

                SettingsChoice(
                    icon     = Icons.Outlined.Language,
                    title    = stringResource(R.string.language),
                    options  = listOf(
                        "system" to stringResource(R.string.lang_system),
                        "en"     to "English",
                        "ar"     to "العربية"
                    ),
                    selected = settings.language,
                    onSelect = viewModel::updateLanguage
                )

                Spacer(Modifier.height(8.dp))

                // ══ 2. CURSOR & INPUT ════════════════════════════════════════
                SettingsSection(
                    icon  = Icons.Outlined.Mouse,
                    title = stringResource(R.string.cursor_input)
                )

                SettingsCursorChoice(
                    title    = stringResource(R.string.cursor_style),
                    options  = listOf(
                        "default"   to stringResource(R.string.cursor_default),
                        "crosshair" to stringResource(R.string.cursor_crosshair),
                        "dot"       to stringResource(R.string.cursor_dot),
                        "circle"    to stringResource(R.string.cursor_circle)
                    ),
                    selected   = settings.cursorStyle,
                    onSelect   = viewModel::updateCursorStyle,
                    cursorSize = settings.cursorSize,
                    accent     = LocalSpaceColors.current.cursorColor
                )

                SettingsSlider(
                    icon         = Icons.Outlined.ZoomIn,
                    title        = stringResource(R.string.cursor_size),
                    value        = settings.cursorSize.toFloat(),
                    valueRange   = 12f..48f,
                    onValueChange = { viewModel.updateCursorSize(it.toInt()) },
                    valueLabel   = { "${it.toInt()}px" }
                )

                SettingsSlider(
                    icon         = Icons.Outlined.TouchApp,
                    title        = stringResource(R.string.touchpad_sensitivity),
                    value        = settings.touchpadSensitivity,
                    valueRange   = 0.3f..3.0f,
                    onValueChange = viewModel::updateTouchpadSensitivity,
                    valueLabel   = { "%.1f×".format(it) }
                )

                SettingsSlider(
                    icon          = Icons.Outlined.SwapVert,
                    title         = stringResource(R.string.scroll_sensitivity),
                    value         = settings.scrollSensitivity,
                    valueRange    = 0.3f..3.0f,
                    onValueChange = viewModel::updateScrollSensitivity,
                    valueLabel    = { "%.1f×".format(it) }
                )

                // FIX B2: تبديل "إظهار المؤشر عند اللمس" — كان الإعداد موجوداً في DataStore
                // لكن لم يكن للمستخدم أي طريقة لتغييره من الواجهة.
                SettingsToggle(
                    icon            = Icons.Outlined.Visibility,
                    title           = stringResource(R.string.show_cursor_on_touch),
                    subtitle        = stringResource(R.string.show_cursor_on_touch_desc),
                    checked         = settings.showCursorOnTouch,
                    onCheckedChange = viewModel::updateShowCursorOnTouch
                )

                SettingsToggle(
                    icon            = Icons.Outlined.TouchApp,
                    title           = stringResource(R.string.right_click_long_press),
                    subtitle        = stringResource(R.string.right_click_long_press_desc),
                    checked         = settings.rightClickLongPress,
                    onCheckedChange = viewModel::updateRightClickLongPress
                )

                SettingsToggle(
                    icon            = Icons.Outlined.Vibration,
                    title           = stringResource(R.string.haptic_feedback),
                    checked         = settings.hapticFeedback,
                    onCheckedChange = viewModel::updateHapticFeedback
                )

                SettingsToggle(
                    icon            = Icons.AutoMirrored.Outlined.VolumeUp,
                    title           = stringResource(R.string.sound_effects),
                    subtitle        = stringResource(R.string.sound_effects_desc),
                    checked         = settings.soundEnabled,
                    onCheckedChange = viewModel::updateSoundEnabled
                )

                SettingsToggle(
                    icon            = Icons.Outlined.ScreenLockLandscape,
                    title           = stringResource(R.string.keep_screen_on),
                    subtitle        = stringResource(R.string.keep_screen_on_desc),
                    checked         = settings.keepScreenOn,
                    onCheckedChange = viewModel::updateKeepScreenOn
                )

                Spacer(Modifier.height(8.dp))

                // ══ 3. CONNECTION ════════════════════════════════════════════
                SettingsSection(
                    icon  = Icons.Outlined.Wifi,
                    title = stringResource(R.string.connection)
                )

                SettingsChoice(
                    icon     = Icons.Outlined.AspectRatio,
                    title    = stringResource(R.string.default_resolution),
                    options  = listOf(
                        "auto"       to stringResource(R.string.resolution_auto),
                        "1280x720"   to "1280 × 720  HD",
                        "1920x1080"  to "1920 × 1080  FHD",
                        "2560x1440"  to "2560 × 1440  QHD",
                        "3840x2160"  to "3840 × 2160  4K"
                    ),
                    selected = settings.defaultResolution,
                    onSelect = viewModel::updateDefaultResolution
                )

                SettingsSlider(
                    icon         = Icons.Outlined.Compress,
                    title        = stringResource(R.string.compression_quality),
                    value        = settings.compressionQuality.toFloat(),
                    valueRange   = 0f..100f,
                    onValueChange = { viewModel.updateCompressionQuality(it.toInt()) },
                    valueLabel   = { "${it.toInt()}%" }
                )

                SettingsToggle(
                    icon            = Icons.Outlined.Autorenew,
                    title           = stringResource(R.string.auto_reconnect),
                    checked         = settings.autoReconnect,
                    onCheckedChange = viewModel::updateAutoReconnect
                )

                SettingsToggle(
                    icon            = Icons.Outlined.PhoneAndroid,
                    title           = stringResource(R.string.run_in_background),
                    subtitle        = stringResource(R.string.run_in_background_desc),
                    checked         = settings.runInBackground,
                    onCheckedChange = viewModel::updateRunInBackground
                )

                Spacer(Modifier.height(8.dp))

                // ══ 4. IN-SESSION CONTROLS ═══════════════════════════════════
                SettingsSection(
                    icon  = Icons.Outlined.Gamepad,
                    title = stringResource(R.string.session_controls)
                )

                SettingsToggle(
                    icon            = Icons.Outlined.ViewStream,
                    title           = stringResource(R.string.show_toolbar_by_default),
                    checked         = settings.sessionToolbarVisible,
                    onCheckedChange = viewModel::updateSessionToolbarVisible
                )

                SettingsToggle(
                    icon            = Icons.Outlined.Keyboard,
                    title           = stringResource(R.string.show_extra_keys_by_default),
                    checked         = settings.sessionExtraKeysVisible,
                    onCheckedChange = viewModel::updateSessionExtraKeysVisible
                )

                // FIX B1: عداد FPS — الإعداد كان موجوداً في DataStore لكن لم يكن
                // هناك تبديل لتفعيله، ولم يكن يُعرض في شاشة الجلسة. تم إصلاح كلا الجانبين.
                SettingsToggle(
                    icon            = Icons.Outlined.Speed,
                    title           = stringResource(R.string.show_fps_counter),
                    subtitle        = stringResource(R.string.show_fps_counter_desc),
                    checked         = settings.showFpsCounter,
                    onCheckedChange = viewModel::updateShowFps
                )

                // عدد محاولات إعادة الاتصال
                AnimatedVisibility(visible = settings.autoReconnect) {
                    SettingsSlider(
                        icon          = Icons.Outlined.Repeat,
                        title         = stringResource(R.string.reconnect_attempts),
                        value         = settings.autoReconnectAttempts.toFloat(),
                        valueRange    = 1f..10f,
                        onValueChange = { viewModel.updateAutoReconnectAttempts(it.toInt()) },
                        valueLabel    = { "${it.toInt()}" }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ══ 5. DATA ═══════════════════════════════════════════════════
                // UI-FIX: Connection History used to live as its own icon in the
                // home-screen bottom bar — a navigation/data screen sitting next
                // to action buttons (Add, Import). It belongs with other app data
                // & settings, not in the primary action bar.
                SettingsSection(
                    icon  = Icons.Outlined.Storage,
                    title = stringResource(R.string.data_section)
                )

                SettingsItem(
                    icon     = Icons.Outlined.History,
                    title    = stringResource(R.string.connection_history),
                    subtitle = stringResource(R.string.connection_history_in_settings),
                    onClick  = { navController.navigate("connection_history") }
                )

                Spacer(Modifier.height(8.dp))

                // ══ 6. SECURITY ══════════════════════════════════════════════
                SettingsSection(
                    icon  = Icons.Outlined.Security,
                    title = stringResource(R.string.security)
                )

                SettingsToggle(
                    icon            = Icons.Outlined.Fingerprint,
                    title           = stringResource(R.string.biometric_lock),
                    subtitle        = stringResource(R.string.biometric_lock_desc),
                    checked         = settings.biometricLockEnabled,
                    onCheckedChange = { enabled ->
                        // BUG 3 FIX: Check biometric availability BEFORE saving
                        // the setting. Previously, the toggle saved 'true' regardless
                        // of hardware/enrollment state. When the user later reopened
                        // the app they hit AppLockScreen: biometricEnabled=true but
                        // launchBiometric() returned silently (canAuthenticate ≠ SUCCESS),
                        // and if PIN was also off there was no unlock path at all —
                        // the user was locked out of their own app.
                        if (enabled) {
                            val bm = BiometricManager.from(context)
                            val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            } else {
                                BiometricManager.Authenticators.BIOMETRIC_STRONG
                            }
                            if (bm.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                                // Hardware absent, no fingerprints enrolled, or temporarily locked.
                                // Show feedback and abort — do NOT persist the setting.
                                val msg = context.getString(R.string.biometric_not_available)
                                scope.launch { snackbarHostState.showSnackbar(msg) } // FIX 3
                                return@SettingsToggle
                            }
                        }
                        viewModel.updateBiometricLock(enabled)
                    }
                )

                // قفل PIN
                var showPinDialog by remember { mutableStateOf(false) }
                SettingsToggle(
                    icon            = Icons.Outlined.Pin,
                    title           = stringResource(R.string.pin_lock),
                    subtitle        = if (settings.pinLockEnabled)
                        stringResource(R.string.pin_lock_set)
                    else
                        stringResource(R.string.pin_lock_desc),
                    checked         = settings.pinLockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) showPinDialog = true
                        else viewModel.updatePinLock(false)
                    }
                )

                if (showPinDialog) {
                    PinSetupDialog(
                        onConfirm = { pin ->
                            viewModel.updatePinLock(true, pin)
                            showPinDialog = false
                        },
                        onDismiss = { showPinDialog = false }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ══ 7. DEVELOPER ══════════════════════════════════════════════
                SettingsSection(
                    icon  = Icons.Outlined.Code,
                    title = stringResource(R.string.developer)
                )

                SettingsItem(
                    icon     = Icons.AutoMirrored.Outlined.Send,
                    title    = "Telegram",
                    subtitle = stringResource(R.string.developer_telegram),
                    onClick  = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/GoToHEX")))
                    },
                    tint = Color(0xFF2CA5E0L)
                )

                SettingsItem(
                    icon     = Icons.Outlined.VideoLibrary,
                    title    = "YouTube",
                    subtitle = stringResource(R.string.developer_youtube),
                    onClick  = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com/@dev-hex404")))
                    },
                    tint = Color(0xFFFF0000L)
                )

                SettingsAboutCard()

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ── Settings Section Header ───────────────────────────────────────────────────
@Composable
fun SettingsSection(icon: ImageVector, title: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            style  = MaterialTheme.typography.labelMedium,
            color  = PulsarCyan,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = PulsarCyan.copy(alpha = 0.2f)
        )
    }
}

// ── Settings Toggle ───────────────────────────────────────────────────────────
@Composable
fun SettingsToggle(
    icon:            ImageVector,
    title:           String,
    subtitle:        String?  = null,
    checked:         Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val sound = LocalSoundManager.current
    // FIX #SETTINGS-ROW: Make the entire row tappable, not just the Switch widget.
    // Previously, tapping the icon/label area did nothing. Now the whole Surface
    // toggles the switch, matching the standard Android settings UX pattern.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick = {
                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TOGGLE, 0.4f)
                        onCheckedChange(!checked)
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(PulsarCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CometTail)
                }
            }
            Switch(
                checked = checked,
                // onCheckedChange passes through to the outer row handler.
                // The Switch still intercepts direct drags/long-presses correctly.
                onCheckedChange = {
                    sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TOGGLE, 0.4f)
                    onCheckedChange(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = DeepSpace,
                    checkedTrackColor   = PulsarCyan,
                    uncheckedThumbColor = CometTail,
                    uncheckedTrackColor = HorizonGray
                )
            )
        }
    }
}

// ── Settings Choice (dropdown) ────────────────────────────────────────────────
@Composable
fun SettingsChoice(
    icon:     ImageVector,
    title:    String,
    options:  List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val sound = LocalSoundManager.current
    val chevronRotation by animateFloatAsState(
        targetValue   = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = {
                        sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.3f)
                        expanded = !expanded
                    })
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PulsarCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(selectedLabel, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                }
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp).rotate(chevronRotation))
            }
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(tween(250)) + fadeIn(tween(250)),
                exit    = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                Column(modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 10.dp)) {
                    // FIX #SETTINGS-DBL: Remove RadioButton's own onClick to prevent
                    // double-trigger. The outer Row's pressScale handles all selection.
                    options.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(onClick = { onSelect(key); expanded = false })
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = key == selected,
                                onClick  = null,   // FIX: delegated to parent Row pressScale
                                colors   = RadioButtonDefaults.colors(selectedColor = PulsarCyan)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                        }
                    }
                }
            }
        }
    }
}

// ── Cursor Choice with live preview ──────────────────────────────────────────
@Composable
fun SettingsCursorChoice(
    title:      String,
    options:    List<Pair<String, String>>,
    selected:   String,
    onSelect:   (String) -> Unit,
    cursorSize: Int,
    accent:     Color
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val sound = LocalSoundManager.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220), label = "chevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = { sound?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.3f); expanded = !expanded })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CursorPreviewBox(cursorStyle = selected, cursorSize = cursorSize, accent = accent)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(selectedLabel, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                }
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp).rotate(chevronRotation))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(tween(250)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    // FIX #SETTINGS-DBL: RadioButton.onClick = null to avoid double-trigger.
                    options.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(onClick = { onSelect(key); expanded = false })
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CursorPreviewBox(cursorStyle = key, cursorSize = cursorSize, accent = accent)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = StarDust, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = key == selected,
                                onClick  = null,   // FIX: delegated to parent Row pressScale
                                colors   = RadioButtonDefaults.colors(selectedColor = PulsarCyan)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CursorPreviewBox(cursorStyle: String, cursorSize: Int, accent: Color) {
    val previewBitmap = remember(cursorStyle, cursorSize, accent) {
        buildCursorBitmap(cursorStyle, cursorSize.coerceIn(16, 32), accent).asImageBitmap()
    }
    val previewBg = LocalSpaceColors.current.backgroundGradient.first() // BUG-6 FIX: was hardcoded DeepSpace — broken in light themes
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(previewBg, RoundedCornerShape(8.dp))
            .border(1.dp, HorizonGray.copy(0.4f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(bitmap = previewBitmap, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

// ── Settings Slider ───────────────────────────────────────────────────────────
@Composable
fun SettingsSlider(
    icon:         ImageVector,
    title:        String,
    value:        Float,
    valueRange:   ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueLabel:   (Float) -> String = { "%.1f".format(it) }
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PulsarCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust, modifier = Modifier.weight(1f))
                Text(valueLabel(value), style = MaterialTheme.typography.labelMedium, color = PulsarCyan)
            }
            Slider(
                value         = value,
                onValueChange = onValueChange,
                valueRange    = valueRange,
                modifier      = Modifier.padding(top = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor        = PulsarCyan,
                    activeTrackColor  = PulsarCyan,
                    inactiveTrackColor = HorizonGray.copy(alpha = 0.4f)
                )
            )
        }
    }
}

// ── Settings Item (tappable) ──────────────────────────────────────────────────
@Composable
fun SettingsItem(
    icon:     ImageVector,
    title:    String,
    subtitle: String? = null,
    onClick:  () -> Unit,
    tint:     Color = PulsarCyan
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CometTail)
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = CometTail, modifier = Modifier.size(20.dp))
        }
    }
}

// ── About Card ─────────────────────────────────────────────────────────────────
@Composable
private fun SettingsAboutCard() {
    val accent    = PulsarCyan
    val secondary = QuantumBlue
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(accent.copy(0.08f), secondary.copy(0.06f))),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(CardBorderColor, secondary.copy(0.15f))),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.RocketLaunch, null, tint = accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = StarDust, fontWeight = FontWeight.Bold)
                Text("${BuildConfig.VERSION_NAME}  •  ${stringResource(R.string.by_developer)}", style = MaterialTheme.typography.bodySmall, color = CometTail) // BUG-4 FIX: was "v1.0.0" hardcoded — now reads from BuildConfig
                Text(stringResource(R.string.app_subtitle), style = MaterialTheme.typography.labelSmall, color = accent.copy(0.7f))
            }
        }
    }
}

// ── PIN Setup Dialog ──────────────────────────────────────────────────────────

@Composable
fun PinSetupDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin1    by remember { mutableStateOf("") }
    var pin2    by remember { mutableStateOf("") }
    var error   by remember { mutableStateOf("") }

    val strTooShort  = stringResource(R.string.pin_error_too_short)
    val strMismatch  = stringResource(R.string.pin_error_mismatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = NebulaSurface,
        title   = { Text(stringResource(R.string.pin_setup_title), color = StarDust, fontWeight = FontWeight.Bold) },
        text    = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pin_setup_desc), color = CometTail,
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value         = pin1,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin1 = it },
                    label         = { Text(stringResource(R.string.pin_label), color = CometTail) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = PulsarCyan,
                        unfocusedBorderColor = HorizonGray,
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust
                    ),
                    singleLine = true
                )
                OutlinedTextField(
                    value         = pin2,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin2 = it },
                    label         = { Text(stringResource(R.string.pin_confirm_label), color = CometTail) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = PulsarCyan,
                        unfocusedBorderColor = HorizonGray,
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust
                    ),
                    singleLine = true
                )
                if (error.isNotBlank()) {
                    Text(error, color = ErrorRed, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin1.length < 4     -> error = strTooShort
                    pin1 != pin2        -> error = strMismatch
                    else                -> onConfirm(pin1)
                }
            }) { Text(stringResource(R.string.save), color = PulsarCyan, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}
