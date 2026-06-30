package com.gotohex.rdp.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.gotohex.rdp.R
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.RdpPerformance
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.data.model.SshAuthType
import com.gotohex.rdp.ui.theme.*
import kotlin.math.*
import kotlin.random.Random
import androidx.compose.foundation.BorderStroke
import com.gotohex.rdp.security.openEncryptedPrefs

// ── Sound Manager CompositionLocal ────────────────────────────────────────────
val LocalSoundManager = staticCompositionLocalOf<com.gotohex.rdp.audio.SoundManager?> { null }

// ── Press Scale Modifier ──────────────────────────────────────────────────────
@Composable
fun Modifier.pressScale(
    enabled:    Boolean = true,
    scaleDown:  Float   = 0.96f,
    playSound:  Boolean = true,
    onClick:    () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) scaleDown else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label         = "press_scale"
    )
    val soundManager = LocalSoundManager.current
    return this
        .scale(scale)
        .clickable(
            enabled           = enabled,
            interactionSource = interactionSource,
            indication        = null,
            onClick           = {
                if (playSound) soundManager?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.35f)
                onClick()
            }
        )
}

// ── Cursor Bitmap Builder ─────────────────────────────────────────────────────
fun buildCursorBitmap(
    cursorStyle: String,
    cursorSize:  Int,
    accentColor: Color
): android.graphics.Bitmap {
    val px     = (cursorSize * 2).coerceIn(20, 64)
    val cx     = px / 2f
    val cy     = px / 2f
    val bmp    = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val cvs    = android.graphics.Canvas(bmp)
    val accent = accentColor.toArgb()

    val fillPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color       = accent
        style       = android.graphics.Paint.Style.FILL
    }
    val strokePaint = android.graphics.Paint().apply {
        isAntiAlias  = true
        color        = android.graphics.Color.argb(200, 0, 0, 0)
        style        = android.graphics.Paint.Style.STROKE
        strokeWidth  = 2.5f
        strokeCap    = android.graphics.Paint.Cap.ROUND
        strokeJoin   = android.graphics.Paint.Join.ROUND
    }
    val glowPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color       = android.graphics.Color.argb(80,
            android.graphics.Color.red(accent),
            android.graphics.Color.green(accent),
            android.graphics.Color.blue(accent)
        )
        style       = android.graphics.Paint.Style.FILL
    }

    when (cursorStyle) {
        "crosshair" -> {
            // Sci-fi crosshair with gap in center and corner brackets
            val r    = px * 0.38f
            val gap  = px * 0.12f
            val brkL = px * 0.18f  // bracket length

            // Glow
            val glowP = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
                color = android.graphics.Color.argb(50,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent))
            }
            cvs.drawLine(cx, cy - r, cx, cy - gap, glowP)
            cvs.drawLine(cx, cy + gap, cx, cy + r, glowP)
            cvs.drawLine(cx - r, cy, cx - gap, cy, glowP)
            cvs.drawLine(cx + gap, cy, cx + r, cy, glowP)

            val lineP = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f; color = accent; strokeCap = android.graphics.Paint.Cap.ROUND
            }
            cvs.drawLine(cx, cy - r, cx, cy - gap, lineP)
            cvs.drawLine(cx, cy + gap, cx, cy + r, lineP)
            cvs.drawLine(cx - r, cy, cx - gap, cy, lineP)
            cvs.drawLine(cx + gap, cy, cx + r, cy, lineP)

            // Corner brackets
            val brk = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.5f; color = accent; strokeCap = android.graphics.Paint.Cap.SQUARE
            }
            val m = px * 0.08f
            cvs.drawLine(m, m, m + brkL, m, brk)
            cvs.drawLine(m, m, m, m + brkL, brk)
            cvs.drawLine(px - m, m, px - m - brkL, m, brk)
            cvs.drawLine(px - m, m, px - m, m + brkL, brk)
            cvs.drawLine(m, px - m, m + brkL, px - m, brk)
            cvs.drawLine(m, px - m, m, px - m - brkL, brk)
            cvs.drawLine(px - m, px - m, px - m - brkL, px - m, brk)
            cvs.drawLine(px - m, px - m, px - m, px - m - brkL, brk)

            // Center dot
            cvs.drawCircle(cx, cy, 2.5f, glowPaint)
            cvs.drawCircle(cx, cy, 2.5f, fillPaint)
        }
        "dot" -> {
            val r = cx * 0.7f
            cvs.drawCircle(cx, cy, r + 3f, glowPaint)
            cvs.drawCircle(cx, cy, r, fillPaint)
            val innerPaint = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL
            }
            cvs.drawCircle(cx * 0.75f + cx * 0.1f, cy * 0.75f + cy * 0.1f, r * 0.25f, innerPaint)
            cvs.drawCircle(cx, cy, r, strokePaint)
        }
        "circle" -> {
            val r       = cx - 4f
            val ringP   = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f; color = accent
            }
            val ringGlow = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 7f
                color = android.graphics.Color.argb(60,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent))
            }
            cvs.drawCircle(cx, cy, r, ringGlow)
            cvs.drawCircle(cx, cy, r, ringP)
            // Tick marks at cardinal points
            val tLen = r * 0.22f
            val tP = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f; color = accent
            }
            cvs.drawLine(cx, 2f, cx, 2f + tLen, tP)
            cvs.drawLine(cx, px - 2f, cx, px - 2f - tLen, tP)
            cvs.drawLine(2f, cy, 2f + tLen, cy, tP)
            cvs.drawLine(px - 2f, cy, px - 2f - tLen, cy, tP)
            cvs.drawCircle(cx, cy, 2.5f, glowPaint)
            cvs.drawCircle(cx, cy, 2.5f, fillPaint)
        }
        else -> { // "default" — refined space-arrow pointer
            val tip    = Offset(px * 0.12f, px * 0.10f)
            val path   = android.graphics.Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x, tip.y + px * 0.72f)
                lineTo(tip.x + px * 0.28f, tip.y + px * 0.56f)
                lineTo(tip.x + px * 0.40f, tip.y + px * 0.82f)
                lineTo(tip.x + px * 0.52f, tip.y + px * 0.76f)
                lineTo(tip.x + px * 0.40f, tip.y + px * 0.50f)
                lineTo(tip.x + px * 0.72f, tip.y + px * 0.38f)
                close()
            }
            val shadowP = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.argb(100, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val whiteFill = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            val accentStroke = android.graphics.Paint().apply {
                isAntiAlias = true; color = accent; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            // Shadow offset
            cvs.save(); cvs.translate(1.5f, 2f); cvs.drawPath(path, shadowP); cvs.restore()
            cvs.drawPath(path, whiteFill)
            cvs.drawPath(path, accentStroke)
            // Accent tip triangle
            val tipPath = android.graphics.Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x + px * 0.18f, tip.y + px * 0.28f)
                lineTo(tip.x + px * 0.28f, tip.y + px * 0.10f)
                close()
            }
            cvs.drawPath(tipPath, fillPaint)
        }
    }
    return bmp
}

// ── Starfield Background ──────────────────────────────────────────────────────
@Composable
fun StarfieldBackground(
    modifier:   Modifier = Modifier,
    starCount:  Int      = 100,
    isDark:     Boolean? = null,
    content:    @Composable BoxScope.() -> Unit
) {
    val spaceColors    = LocalSpaceColors.current
    val dark           = isDark ?: spaceColors.isDark
    val gradientColors = spaceColors.backgroundGradient
    val accentColor    = spaceColors.accent
    val accentSecondary = spaceColors.accentSecondary

    // BUG-13 FIX: pause all infinite animations when app is backgrounded to save battery
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val infiniteT = rememberInfiniteTransition(label = "stars")
    val twinkle by infiniteT.animateFloat(
        initialValue  = 0f, targetValue = if (isResumed) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation  = if (isResumed) tween(4000, easing = LinearEasing) else snap(),
            repeatMode = RepeatMode.Reverse
        ),
        label = "twinkle"
    )
    val nebulaShift by infiniteT.animateFloat(
        initialValue = 0f, targetValue = if (isResumed) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation  = if (isResumed) tween(12000, easing = LinearEasing) else snap(),
            repeatMode = RepeatMode.Restart
        ),
        label = "nebula"
    )

    val stars = remember(starCount) {
        List(starCount) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }
    }
    // Larger, brighter "feature" stars
    val bigStars = remember { List(8) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Background gradient
            drawRect(
                brush = if (gradientColors.size > 1)
                    Brush.verticalGradient(gradientColors)
                else
                    Brush.verticalGradient(listOf(gradientColors[0], gradientColors[0]))
            )

            if (dark) {
                // ── Dark mode: stars + nebula glow orbs ──────────────────────
                // Nebula glow orbs — animated drift
                val driftX = sin(nebulaShift * 2 * PI.toFloat()) * size.width * 0.05f
                val driftY = cos(nebulaShift * 2 * PI.toFloat()) * size.height * 0.03f
                drawCircle(
                    brush  = Brush.radialGradient(
                        listOf(accentColor.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.75f + driftX, size.height * 0.15f + driftY),
                        radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f,
                    center = Offset(size.width * 0.75f + driftX, size.height * 0.15f + driftY)
                )
                drawCircle(
                    brush  = Brush.radialGradient(
                        listOf(accentSecondary.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.15f - driftX, size.height * 0.72f - driftY),
                        radius = size.width * 0.38f
                    ),
                    radius = size.width * 0.38f,
                    center = Offset(size.width * 0.15f - driftX, size.height * 0.72f - driftY)
                )
                // Small stars
                stars.forEach { (xFrac, yFrac, factor) ->
                    val brightness = 0.35f + factor * 0.65f *
                            (0.7f + 0.3f * sin(twinkle * PI.toFloat() * 2 + factor * 13))
                    drawCircle(
                        color  = Color.White.copy(alpha = brightness),
                        radius = 0.8f + factor * 1.8f,
                        center = Offset(xFrac * size.width, yFrac * size.height)
                    )
                }
                // Big "feature" stars with diffraction spikes
                bigStars.forEach { (xFrac, yFrac, factor) ->
                    val pulse = 0.6f + 0.4f * sin(twinkle * PI.toFloat() * 1.5f + factor * 7)
                    val cx = xFrac * size.width
                    val cy = yFrac * size.height
                    val r  = 2.5f + factor * 2f
                    drawCircle(color = Color.White.copy(alpha = 0.9f * pulse), radius = r, center = Offset(cx, cy))
                    drawCircle(
                        color = accentColor.copy(alpha = 0.3f * pulse),
                        radius = r * 3, center = Offset(cx, cy)
                    )
                    val spikeLen = r * 4 * pulse
                    val spikePaint = Stroke(width = 0.8f)
                    drawLine(
                        Color.White.copy(alpha = 0.5f * pulse),
                        Offset(cx - spikeLen, cy), Offset(cx + spikeLen, cy), strokeWidth = 1f
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.5f * pulse),
                        Offset(cx, cy - spikeLen), Offset(cx, cy + spikeLen), strokeWidth = 1f
                    )
                }
            } else {
                // ── Light mode: subtle geometric "star-map" dots + accent gradient ──
                val driftX = kotlin.math.sin(nebulaShift * 2 * kotlin.math.PI.toFloat()) * size.width * 0.04f
                val driftY = kotlin.math.cos(nebulaShift * 2 * kotlin.math.PI.toFloat()) * size.height * 0.025f
                // Soft accent glow pools
                drawCircle(
                    brush  = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accentColor.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.80f + driftX, size.height * 0.12f + driftY),
                        radius = size.width * 0.50f
                    ),
                    radius = size.width * 0.50f,
                    center = Offset(size.width * 0.80f + driftX, size.height * 0.12f + driftY)
                )
                drawCircle(
                    brush  = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accentSecondary.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.10f - driftX, size.height * 0.80f - driftY),
                        radius = size.width * 0.40f
                    ),
                    radius = size.width * 0.40f,
                    center = Offset(size.width * 0.10f - driftX, size.height * 0.80f - driftY)
                )
                // Subtle dot grid — a "star map" at very low opacity
                stars.forEach { (xFrac, yFrac, factor) ->
                    if (factor > 0.60f) {  // only ~40% of dots show
                        drawCircle(
                            color  = accentColor.copy(alpha = 0.05f + factor * 0.06f),
                            radius = 1.2f + factor * 1.5f,
                            center = Offset(xFrac * size.width, yFrac * size.height)
                        )
                    }
                }
            }
        }
        content()
    }
}

// ── RDP Profile Card ──────────────────────────────────────────────────────────
@Composable
fun RdpProfileCard(
    profile:      RdpProfile,
    onConnect:    () -> Unit,
    onEdit:       () -> Unit,
    onDelete:     () -> Unit,
    onWakeOnLan:  (() -> Unit)? = null,
    modifier:     Modifier = Modifier,
    hapticEnabled: Boolean = true,   // FIX #1: تمرير إعداد hapticFeedback للـ SwipeableCard
) {
    val statusColor = when {
        profile.isConnected       -> ConnectedGreen
        profile.lastConnected > 0 -> ConnectingAmber
        else                      -> DisconnectedGray
    }

    // Last-frame thumbnail
    val context  = androidx.compose.ui.platform.LocalContext.current
    val lastFrame by produceState<ImageBitmap?>(initialValue = null, profile.id) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.gotohex.rdp.util.LastFrameStore.load(context, profile.id)?.asImageBitmap()
        }
    }

    val accent    = PulsarCyan
    val secondary = QuantumBlue

    SwipeableProfileCard(
        onDelete       = onDelete,
        onEdit         = onEdit,
        modifier       = modifier,
        hapticEnabled  = hapticEnabled,   // FIX #1
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(GradientCardStart, GradientCardEnd)))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(listOf(accent.copy(0.4f), secondary.copy(0.15f))),
                    shape = RoundedCornerShape(18.dp)
                )
                .pressScale(onClick = onConnect)
        ) {
            // Corner glow accent
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset((-20).dp, (-20).dp)
                    .background(
                        brush = Brush.radialGradient(listOf(accent.copy(0.15f), Color.Transparent)),
                        shape = RoundedCornerShape(50)
                    )
            )

            // Last-frame backdrop
            lastFrame?.let { img ->
                androidx.compose.foundation.Image(
                    bitmap             = img,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(18.dp))
                        .alpha(0.18f)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(listOf(
                                GradientCardStart.copy(0.88f),
                                GradientCardStart.copy(0.35f),
                                GradientCardEnd.copy(0.9f)
                            ))
                        )
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                // Header row
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.weight(1f)
                    ) {
                        ProtocolIconBadge(profile.protocolType)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                profile.name,
                                style      = MaterialTheme.typography.titleMedium,
                                color      = StarDust,
                                fontWeight = FontWeight.Bold,
                                maxLines   = 1,
                                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                "${profile.host}:${profile.port}",
                                style    = MaterialTheme.typography.bodySmall,
                                color    = CometTail,
                                maxLines = 1
                            )
                        }
                    }

                    // Status dot + accessible overflow menu (alternative to swipe
                    // gestures, which conflict with TalkBack's horizontal swipe
                    // navigation and are otherwise undiscoverable for new users)
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        PulsingDot(color = statusColor)
                        Spacer(Modifier.width(4.dp))
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick  = { menuExpanded = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.card_more_options),
                                    tint     = CometTail.copy(alpha = 0.75f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded         = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                containerColor   = NebulaSurface
                            ) {
                                DropdownMenuItem(
                                    text    = { Text(stringResource(R.string.edit), color = StarDust) },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null, tint = PulsarCyan) },
                                    onClick = { menuExpanded = false; onEdit() }
                                )
                                if (profile.wolEnabled && onWakeOnLan != null) {
                                    DropdownMenuItem(
                                        text    = { Text(stringResource(R.string.wol_wake), color = StarDust) },
                                        leadingIcon = { Icon(Icons.Outlined.PowerSettingsNew, null, tint = ConnectingAmber) },
                                        onClick = { menuExpanded = false; onWakeOnLan() }
                                    )
                                }
                                DropdownMenuItem(
                                    text    = { Text(stringResource(R.string.delete), color = Color(0xFFFF2D78)) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFFF2D78)) },
                                    onClick = { menuExpanded = false; onDelete() }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Info chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (profile.protocolType) {
                        ProtocolType.RDP -> {
                            InfoChip(Icons.Outlined.Person,   profile.username.ifEmpty { "—" })
                            InfoChip(Icons.Outlined.Security, if (profile.useNla) "NLA" else "RDP")
                            if (profile.gatewayEnabled)
                                InfoChip(Icons.Outlined.Hub, stringResource(R.string.chip_gateway))
                        }
                        ProtocolType.VNC -> {
                            InfoChip(Icons.Outlined.Monitor, "VNC")
                            if (profile.vncViewOnly)
                                InfoChip(Icons.Outlined.Visibility, stringResource(R.string.chip_view_only))
                        }
                        ProtocolType.SSH -> {
                            InfoChip(Icons.Outlined.Person, profile.username.ifEmpty { "—" })
                            InfoChip(
                                Icons.Outlined.Key,
                                if (profile.sshAuthType == SshAuthType.PRIVATE_KEY) stringResource(R.string.chip_key) else stringResource(R.string.ssh_auth_password)
                            )
                        }
                    }
                    // WoL chip — shown for any protocol when Wake-on-LAN is configured
                    if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) {
                        // i18n FIX: was hardcoded "WoL" — use string resource.
                        InfoChip(Icons.Outlined.PowerSettingsNew, stringResource(R.string.chip_wol))
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Connect row — optionally paired with Wake button
                if (profile.wolEnabled && onWakeOnLan != null) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Wake button (amber accent, narrower)
                        SpaceButton(
                            text     = stringResource(R.string.wol_wake),
                            onClick  = onWakeOnLan,
                            variant  = ButtonVariant.GHOST,
                            modifier = Modifier.weight(0.42f)
                        )
                        // Connect button
                        SpaceButton(
                            text     = stringResource(R.string.connect),
                            onClick  = onConnect,
                            modifier = Modifier.weight(0.58f)
                        )
                    }
                } else {
                    // Connect button
                    SpaceButton(
                        text    = stringResource(R.string.connect),
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}


// ── Protocol Icon Badge ────────────────────────────────────────────────────────
@Composable
fun ProtocolIconBadge(type: ProtocolType) {
    val context      = androidx.compose.ui.platform.LocalContext.current
    val themeVariant = LocalThemeVariant.current
    val color = when (type) {
        ProtocolType.RDP -> QuantumBlue
        ProtocolType.VNC -> VoidPurple
        ProtocolType.SSH -> PlasmaGreen
    }
    // Resolve the theme-aware SVG drawable once; re-resolves only when theme or type changes.
    val iconRes = remember(type, themeVariant) {
        when (type) {
            ProtocolType.RDP -> SpaceIcons.rdp(context, themeVariant)
            ProtocolType.VNC -> SpaceIcons.vnc(context, themeVariant)
            ProtocolType.SSH -> SpaceIcons.ssh(context, themeVariant)
        }
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color.copy(alpha = if (LocalSpaceColors.current.isDark) 0.18f else 0.12f), RoundedCornerShape(10.dp))
            .border(1.5.dp, color.copy(alpha = if (LocalSpaceColors.current.isDark) 0.4f else 0.5f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (iconRes != 0) {
            Icon(
                androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                tint     = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ProtocolBadge(type: ProtocolType) {
    val color = when (type) {
        ProtocolType.RDP -> QuantumBlue
        ProtocolType.VNC -> VoidPurple
        ProtocolType.SSH -> PlasmaGreen
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(protocolIcon(type), null, tint = color, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(type.label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

// ── Pulsing Status Dot ────────────────────────────────────────────────────────
// PERF: previously every card ran its own infiniteRepeatable animation regardless
// of status, so a list of 20 saved connections meant 20 concurrent infinite
// animations recomposing forever — a real cost on mid-range devices. A
// "connected"/"disconnected" dot conveys no extra meaning by pulsing, so we only
// animate for the transient "connecting" (amber) state, where it actually
// communicates something. Idle dots are drawn once and never invalidate.
@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp) {
    if (color == ConnectingAmber) {
        val infiniteT = rememberInfiniteTransition(label = "dot_pulse")
        val pulse by infiniteT.animateFloat(
            initialValue  = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dot_scale"
        )
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    drawCircle(color = color.copy(alpha = 0.25f * pulse), radius = this.size.minDimension / 2 * 2.2f)
                    drawCircle(color = color.copy(alpha = 0.5f * pulse),  radius = this.size.minDimension / 2 * 1.4f)
                    drawCircle(color = color, radius = this.size.minDimension / 2)
                }
        )
    } else {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    drawCircle(color = color.copy(alpha = 0.25f), radius = this.size.minDimension / 2 * 1.6f)
                    drawCircle(color = color, radius = this.size.minDimension / 2)
                }
        )
    }
}

// ── Info Chip ─────────────────────────────────────────────────────────────────
@Composable
fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(ChipBg, RoundedCornerShape(8.dp))
            .border(1.dp, HorizonGray.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text     = text,
            style    = MaterialTheme.typography.labelSmall,
            color    = CometTail,
            maxLines = 1,                                          // BUG-10 FIX: prevent long usernames overflowing Chip
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

// ── Space Button ──────────────────────────────────────────────────────────────
enum class ButtonVariant { PRIMARY, DANGER, GHOST }

@Composable
fun SpaceButton(
    text:     String,
    onClick:  () -> Unit,
    variant:  ButtonVariant = ButtonVariant.PRIMARY,
    modifier: Modifier = Modifier,
    enabled:  Boolean  = true
) {
    val accent     = PulsarCyan
    val secondary  = QuantumBlue
    val danger1    = NovaPink
    val danger2    = SolarFlare

    val gradient = when (variant) {
        ButtonVariant.PRIMARY -> Brush.horizontalGradient(listOf(secondary, accent))
        ButtonVariant.DANGER  -> Brush.horizontalGradient(listOf(danger1, danger2))
        ButtonVariant.GHOST   -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val spaceColors = LocalSpaceColors.current
    val textColor = when (variant) {
        ButtonVariant.PRIMARY -> if (spaceColors.isDark) Color(0xFF020816) else Color.White
        ButtonVariant.DANGER  -> Color.White
        ButtonVariant.GHOST   -> accent
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.95f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "btn_scale"
    )
    val soundManager = LocalSoundManager.current

    Box(
        modifier = modifier
            .scale(scale)
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (enabled) 1f else 0.38f) // BUG-5 FIX: dim entire button (gradient + text) when disabled
            .background(brush = gradient)
            .then(
                if (variant == ButtonVariant.GHOST)
                    Modifier.border(1.dp, accent.copy(0.6f), RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(
                enabled           = enabled,
                interactionSource = interactionSource,
                indication        = null,
                onClick = {
                    soundManager?.play(com.gotohex.rdp.audio.SoundManager.Sound.TAP, 0.4f)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = text,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color      = textColor // BUG-5 FIX: alpha now applied to whole Box above
        )
    }
}

// ── Network Quality Badge ─────────────────────────────────────────────────────
@Composable
fun NetworkQualityBadge(quality: com.gotohex.rdp.ui.NetworkQuality) {
    val (color, bars) = when (quality) {
        com.gotohex.rdp.ui.NetworkQuality.POOR      -> Pair(ErrorRed,        1)
        com.gotohex.rdp.ui.NetworkQuality.FAIR      -> Pair(ConnectingAmber, 2)
        com.gotohex.rdp.ui.NetworkQuality.GOOD      -> Pair(PlasmaGreen,     3)
        com.gotohex.rdp.ui.NetworkQuality.EXCELLENT -> Pair(PulsarCyan,      4)
        else                                         -> Pair(DisconnectedGray, 0)
    }
    Row(
        verticalAlignment     = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier              = Modifier.height(18.dp)
    ) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .background(
                        color = if (i <= bars) color else color.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

// ── Subscribe Dialog ─────────────────────────────────────────────────────────
// BUG-11 FIX: SubscribeDialog removed (dead code since UX-05).
// ViewModel state (showSubscribeDialog) retained for back-compat; UI call site was removed.

// ── Profile Form Dialog ───────────────────────────────────────────────────────
@Composable
fun ProfileFormDialog(
    profile:   RdpProfile? = null,
    onDismiss: () -> Unit,
    onSave:    (RdpProfile) -> Unit
) {
    var protocolType    by remember { mutableStateOf(profile?.protocolType ?: ProtocolType.RDP) }
    var name            by remember { mutableStateOf(profile?.name     ?: "") }
    var host            by remember { mutableStateOf(profile?.host     ?: "") }
    var port            by remember { mutableStateOf(profile?.port?.toString() ?: ProtocolType.RDP.defaultPort.toString()) }
    var username        by remember { mutableStateOf(profile?.username ?: "") }
    var password        by remember { mutableStateOf(profile?.password ?: "") }
    var domain          by remember { mutableStateOf(profile?.domain   ?: "") }
    var useNla          by remember { mutableStateOf(profile?.useNla   ?: true) }
    // BUG-3 FIX: expose per-profile self-signed cert acceptance so users with
    // home/office RDP servers can connect without a full PKI certificate chain.
    var acceptSelfSignedCertificate by remember { mutableStateOf(profile?.acceptSelfSignedCertificate ?: false) }
    var passwordVisible by remember { mutableStateOf(false) }

    var gatewayEnabled  by remember { mutableStateOf(profile?.gatewayEnabled ?: false) }
    var gatewayHost     by remember { mutableStateOf(profile?.gatewayHost ?: "") }
    var gatewayPort     by remember { mutableStateOf(profile?.gatewayPort?.toString() ?: "443") }
    var gatewayUsername by remember { mutableStateOf(profile?.gatewayUsername ?: "") }
    var gatewayPassword by remember { mutableStateOf(profile?.gatewayPassword ?: "") }
    var gatewayDomain   by remember { mutableStateOf(profile?.gatewayDomain ?: "") }
    var gatewayPasswordVisible by remember { mutableStateOf(false) }

    var vncViewOnly     by remember { mutableStateOf(profile?.vncViewOnly ?: false) }
    var sshAuthType     by remember { mutableStateOf(profile?.sshAuthType ?: SshAuthType.PASSWORD) }

    // UX-06: RDP advanced settings
    var colorDepth      by remember { mutableStateOf(profile?.colorDepth ?: 32) }
    var customWidth     by remember { mutableStateOf(profile?.width?.takeIf  { it > 0 }?.toString() ?: "") }
    var customHeight    by remember { mutableStateOf(profile?.height?.takeIf { it > 0 }?.toString() ?: "") }
    var performanceFlags by remember { mutableStateOf(profile?.performanceFlags ?: RdpPerformance.LAN) }
    var enableSound     by remember { mutableStateOf(profile?.enableSound ?: false) }
    var sshPrivateKey   by remember { mutableStateOf(profile?.sshPrivateKey ?: "") }
    var sshKeyPassphrase by remember { mutableStateOf(profile?.sshPrivateKeyPassphrase ?: "") }

    // SSH Tunnel state
    var sshTunnelEnabled     by remember { mutableStateOf(profile?.sshTunnelEnabled ?: false) }
    var sshTunnelHost        by remember { mutableStateOf(profile?.sshTunnelHost ?: "") }
    var sshTunnelPort        by remember { mutableStateOf(profile?.sshTunnelPort?.toString() ?: "22") }
    var sshTunnelUsername    by remember { mutableStateOf(profile?.sshTunnelUsername ?: "") }
    var sshTunnelAuthType    by remember { mutableStateOf(profile?.sshTunnelAuthType ?: SshAuthType.PASSWORD) }
    var sshTunnelPassword    by remember { mutableStateOf(profile?.sshTunnelPassword ?: "") }
    var sshTunnelPasswordVisible by remember { mutableStateOf(false) }
    var sshTunnelPrivateKey  by remember { mutableStateOf(profile?.sshTunnelPrivateKey ?: "") }
    var sshTunnelKeyPassphrase by remember { mutableStateOf(profile?.sshTunnelPrivateKeyPassphrase ?: "") }

    var portTouchedByUser by remember { mutableStateOf(profile != null) }

    // Wake-on-LAN state
    var wolEnabled         by remember { mutableStateOf(profile?.wolEnabled ?: false) }
    var wolMacAddress      by remember { mutableStateOf(profile?.wolMacAddress ?: "") }
    var wolBroadcastAddress by remember { mutableStateOf(profile?.wolBroadcastAddress ?: "255.255.255.255") }
    val wolMacValid = wolMacAddress.isBlank() || com.gotohex.rdp.util.WakeOnLanManager.isValidMac(wolMacAddress)

    // ── UX REDESIGN: every optional section is now its own self-contained,
    // collapsible card (icon + title + live status + chevron) instead of a
    // long flat list of mixed switches and fields. Required/essential fields
    // stay visible up top ("Quick Connect"); everything situational lives
    // under "Optional Settings", collapsed by default but auto-opens the
    // moment its switch is turned on so the user always sees what they just
    // enabled. This keeps the screen calm for the common case (4–5 fields)
    // while still surfacing every capability for power users.
    var expandSecurity by remember { mutableStateOf(profile != null && (domain.isNotBlank() || acceptSelfSignedCertificate)) }
    var expandDisplay  by remember { mutableStateOf(
        profile != null && (colorDepth != 32 || performanceFlags != RdpPerformance.LAN ||
            customWidth.isNotBlank() || customHeight.isNotBlank() || enableSound)
    ) }
    var expandGateway by remember { mutableStateOf(gatewayEnabled) }
    var expandTunnel  by remember { mutableStateOf(sshTunnelEnabled) }
    var expandWol     by remember { mutableStateOf(wolEnabled) }
    var expandTrust   by remember { mutableStateOf(false) }

    fun selectProtocol(newType: ProtocolType) {
        if (newType == protocolType) return
        protocolType = newType
        if (!portTouchedByUser) port = newType.defaultPort.toString()
    }

    val isValid = name.isNotBlank() && host.isNotBlank() &&
        when (protocolType) {
            ProtocolType.RDP -> username.isNotBlank()
            ProtocolType.SSH -> username.isNotBlank() &&
                (sshAuthType == SshAuthType.PASSWORD || sshPrivateKey.isNotBlank())
            ProtocolType.VNC -> true
        }

    // FIX-MED-R3-4: TOFU fingerprint clear — only available when editing an existing profile
    val context = androidx.compose.ui.platform.LocalContext.current
    var showClearTofuSshDialog    by remember { mutableStateOf(false) }
    var showClearTofuTunnelDialog by remember { mutableStateOf(false) }
    var tofuClearedMessage        by remember { mutableStateOf("") }

    // Confirmation dialog — SSH fingerprint
    if (showClearTofuSshDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuSshDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        val hostKey = "${host.trim()}:${port.toIntOrNull() ?: 22}"
                        // LIVE-HIGH-1 FIX: commit() for atomic TOFU removal.
                        context.openEncryptedPrefs("hexrdp_tofu_ssh")
                            .edit().remove(hostKey).commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuSshDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuSshDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // Confirmation dialog — SSH Tunnel fingerprint
    if (showClearTofuTunnelDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuTunnelDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        val tunnelKey = "${sshTunnelHost.trim()}:${sshTunnelPort.toIntOrNull() ?: 22}"
                        // LIVE-HIGH-1 FIX: commit() for atomic TOFU removal.
                        context.openEncryptedPrefs("hexrdp_tofu_tunnel")
                            .edit().remove(tunnelKey).commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuTunnelDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuTunnelDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(24.dp),
        modifier         = Modifier.fillMaxWidth(0.96f),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolIconBadge(protocolType)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (profile != null) stringResource(R.string.edit_profile)
                        else stringResource(R.string.new_connection),
                        style = MaterialTheme.typography.titleLarge, color = StarDust
                    )
                    Text(
                        protocolType.label,
                        style = MaterialTheme.typography.labelSmall, color = PulsarCyan
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxHeight(0.85f), // BUG-2 FIX: was heightIn(max=500.dp) — unreachable Save button on small screens
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ProtocolSelector(selected = protocolType, onSelect = ::selectProtocol, isEditing = profile != null)

                // VNC-STUB FIX: warn the user proactively, before they fill in the whole
                // form and attempt to connect, that VNC support is not implemented yet in
                // this build (com.undatech.opaque.RfbConnectable is a stub that always
                // fails — see VncClient.kt / VncNotImplementedException). Without this,
                // the only feedback the user gets is a connection-failure error *after*
                // saving the profile and trying to connect, which reads like a normal
                // network/credentials problem rather than a missing feature.
                if (protocolType == ProtocolType.VNC) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Warning, null, tint = SolarFlare, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text  = stringResource(R.string.vnc_unavailable_notice),
                            color = SolarFlare,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                FormGroupHeader(
                    icon     = Icons.Outlined.RocketLaunch,
                    title    = stringResource(R.string.quick_connect),
                    subtitle = stringResource(R.string.quick_connect_desc)
                )
                SpaceTextField(name, { name = it }, stringResource(R.string.connection_name), Icons.AutoMirrored.Outlined.Label)
                SpaceTextField(host, { host = it }, stringResource(R.string.host_ip), Icons.Outlined.Language)
                SpaceTextField(
                    port,
                    { port = it.filter(Char::isDigit); portTouchedByUser = true },
                    stringResource(R.string.port),
                    Icons.Outlined.SettingsEthernet
                )

                if (protocolType != ProtocolType.VNC) {
                    SpaceTextField(username, { username = it }, stringResource(R.string.username), Icons.Outlined.Person)
                }

                SpaceTextField(
                    value          = password,
                    onValueChange  = { password = it },
                    label          = if (protocolType == ProtocolType.VNC)
                        stringResource(R.string.vnc_password) else stringResource(R.string.password),
                    icon           = Icons.Outlined.Lock,
                    isPassword     = true,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible }
                )

                when (protocolType) {
                    ProtocolType.RDP -> {
                        SpaceSwitch(
                            label   = stringResource(R.string.use_nla),
                            checked = useNla,
                            onCheckedChange = { useNla = it }
                        )
                    }
                    ProtocolType.VNC -> {
                        SpaceSwitch(
                            label   = stringResource(R.string.vnc_view_only),
                            checked = vncViewOnly,
                            onCheckedChange = { vncViewOnly = it }
                        )
                    }
                    ProtocolType.SSH -> {
                        SectionDivider(stringResource(R.string.ssh_authentication))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuthTypeChip(stringResource(R.string.ssh_auth_password), sshAuthType == SshAuthType.PASSWORD, { sshAuthType = SshAuthType.PASSWORD }, Modifier.weight(1f))
                            AuthTypeChip(stringResource(R.string.ssh_auth_key), sshAuthType == SshAuthType.PRIVATE_KEY, { sshAuthType = SshAuthType.PRIVATE_KEY }, Modifier.weight(1f))
                        }
                        AnimatedVisibility(visible = sshAuthType == SshAuthType.PRIVATE_KEY, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                OutlinedTextField(
                                    value = sshPrivateKey, onValueChange = { sshPrivateKey = it },
                                    label = { Text(stringResource(R.string.ssh_private_key), color = CometTail) },
                                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", color = HorizonGray) },
                                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PulsarCyan, unfocusedBorderColor = InputBorder,
                                        focusedLabelColor = PulsarCyan, cursorColor = PulsarCyan,
                                        focusedTextColor = StarDust, unfocusedTextColor = StarDust,
                                        focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                SpaceTextField(sshKeyPassphrase, { sshKeyPassphrase = it }, stringResource(R.string.ssh_key_passphrase), Icons.Outlined.Key)
                            }
                        }
                    }
                }

                // ── Optional Settings ──────────────────────────────────────────
                // Everything situational lives here as its own self-contained card:
                // icon + name + one-line description + live status, collapsed by
                // default. Nothing is hidden — every capability still has a visible
                // entry point — but nothing demands attention unless the person
                // opens it or switches it on. This replaces the previous mix of
                // bare switches, inline field stacks and section dividers with one
                // predictable interaction pattern across the whole form.
                FormGroupHeader(
                    icon     = Icons.Outlined.Tune,
                    title    = stringResource(R.string.advanced_optional),
                    subtitle = stringResource(R.string.advanced_optional_desc)
                )

                if (protocolType == ProtocolType.RDP) {
                    SettingsCard(
                        icon         = Icons.Outlined.Security,
                        accentColor  = QuantumBlue,
                        title        = stringResource(R.string.section_security_auth),
                        subtitle     = stringResource(R.string.section_security_auth_desc),
                        expanded     = expandSecurity,
                        onExpandedChange = { expandSecurity = it }
                    ) {
                        SpaceTextField(domain, { domain = it }, stringResource(R.string.domain), Icons.Outlined.Domain)
                        // BUG-3 FIX: per-profile self-signed certificate acceptance,
                        // surfaced explicitly so users understand the security trade-off.
                        SpaceSwitch(
                            label   = stringResource(R.string.accept_self_signed_cert),
                            checked = acceptSelfSignedCertificate,
                            onCheckedChange = { acceptSelfSignedCertificate = it }
                        )
                    }

                    SettingsCard(
                        icon         = Icons.Outlined.Tune,
                        accentColor  = VoidPurple,
                        title        = stringResource(R.string.section_display_performance),
                        subtitle     = stringResource(R.string.section_display_performance_desc),
                        expanded     = expandDisplay,
                        onExpandedChange = { expandDisplay = it }
                    ) {
                        Text(stringResource(R.string.color_depth), style = MaterialTheme.typography.labelMedium, color = CometTail)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(16, 24, 32).forEach { depth ->
                                val label = when (depth) {
                                    16 -> stringResource(R.string.color_depth_16)
                                    24 -> stringResource(R.string.color_depth_24)
                                    else -> stringResource(R.string.color_depth_32)
                                }
                                AuthTypeChip(label = label, selected = colorDepth == depth, onClick = { colorDepth = depth }, modifier = Modifier.weight(1f))
                            }
                        }

                        Text(stringResource(R.string.performance_quality), style = MaterialTheme.typography.labelMedium, color = CometTail)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                RdpPerformance.LOW_BANDWIDTH to stringResource(R.string.performance_low),
                                RdpPerformance.MEDIUM        to stringResource(R.string.performance_medium),
                                RdpPerformance.WIFI          to stringResource(R.string.performance_wifi),
                                RdpPerformance.LAN           to stringResource(R.string.performance_lan),
                                RdpPerformance.AUTO          to stringResource(R.string.performance_auto),
                            ).forEach { (flag, label) ->
                                AuthTypeChip(label = label, selected = performanceFlags == flag, onClick = { performanceFlags = flag }, modifier = Modifier.weight(1f))
                            }
                        }

                        Text(stringResource(R.string.resolution), style = MaterialTheme.typography.labelMedium, color = CometTail)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SpaceTextField(customWidth, { customWidth = it.filter(Char::isDigit) }, stringResource(R.string.width), Icons.Outlined.SettingsEthernet, modifier = Modifier.weight(1f))
                            SpaceTextField(customHeight, { customHeight = it.filter(Char::isDigit) }, stringResource(R.string.height), Icons.Outlined.SettingsEthernet, modifier = Modifier.weight(1f))
                        }
                        if (customWidth.isBlank() && customHeight.isBlank()) {
                            Text(stringResource(R.string.resolution_auto), style = MaterialTheme.typography.labelSmall, color = HorizonGray)
                        }

                        SpaceSwitch(
                            label   = stringResource(R.string.enable_sound),
                            checked = enableSound,
                            onCheckedChange = { enableSound = it }
                        )
                    }

                    SettingsCard(
                        icon         = Icons.Outlined.Hub,
                        accentColor  = PulsarCyan,
                        title        = stringResource(R.string.rd_gateway),
                        subtitle     = stringResource(R.string.card_gateway_desc) + " · " +
                            stringResource(if (gatewayEnabled) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = gatewayEnabled,
                        onToggleChange = { gatewayEnabled = it },
                        expanded     = expandGateway,
                        onExpandedChange = { expandGateway = it }
                    ) {
                        SpaceTextField(gatewayHost, { gatewayHost = it }, stringResource(R.string.gateway_host), Icons.Outlined.Hub)
                        SpaceTextField(gatewayPort, { gatewayPort = it.filter(Char::isDigit) }, stringResource(R.string.gateway_port), Icons.Outlined.SettingsEthernet)
                        SpaceTextField(gatewayUsername, { gatewayUsername = it }, stringResource(R.string.gateway_username), Icons.Outlined.Person)
                        SpaceTextField(gatewayPassword, { gatewayPassword = it }, stringResource(R.string.gateway_password), Icons.Outlined.Lock, isPassword = true, passwordVisible = gatewayPasswordVisible, onTogglePassword = { gatewayPasswordVisible = !gatewayPasswordVisible })
                        SpaceTextField(gatewayDomain, { gatewayDomain = it }, stringResource(R.string.gateway_domain), Icons.Outlined.Domain)
                    }
                }

                if (protocolType != ProtocolType.SSH) {
                    SettingsCard(
                        icon         = Icons.Outlined.Terminal,
                        accentColor  = PlasmaGreen,
                        title        = stringResource(R.string.ssh_tunnel),
                        subtitle     = stringResource(R.string.card_tunnel_desc) + " · " +
                            stringResource(if (sshTunnelEnabled) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = sshTunnelEnabled,
                        onToggleChange = { sshTunnelEnabled = it },
                        expanded     = expandTunnel,
                        onExpandedChange = { expandTunnel = it }
                    ) {
                        SshTunnelSection(
                            host                 = sshTunnelHost,
                            onHostChange         = { sshTunnelHost = it },
                            port                 = sshTunnelPort,
                            onPortChange         = { sshTunnelPort = it.filter(Char::isDigit) },
                            username             = sshTunnelUsername,
                            onUsernameChange     = { sshTunnelUsername = it },
                            authType             = sshTunnelAuthType,
                            onAuthTypeChange     = { sshTunnelAuthType = it },
                            password             = sshTunnelPassword,
                            onPasswordChange     = { sshTunnelPassword = it },
                            passwordVisible      = sshTunnelPasswordVisible,
                            onTogglePassword     = { sshTunnelPasswordVisible = !sshTunnelPasswordVisible },
                            privateKey           = sshTunnelPrivateKey,
                            onPrivateKeyChange   = { sshTunnelPrivateKey = it },
                            keyPassphrase        = sshTunnelKeyPassphrase,
                            onKeyPassphraseChange = { sshTunnelKeyPassphrase = it },
                        )
                    }
                }

                SettingsCard(
                    icon         = Icons.Outlined.Wifi,
                    accentColor  = SolarFlare,
                    title        = stringResource(R.string.wol_enable),
                    subtitle     = stringResource(R.string.card_wol_desc) + " · " +
                        stringResource(if (wolEnabled) R.string.status_on else R.string.status_off),
                    hasToggle    = true,
                    toggleChecked = wolEnabled,
                    onToggleChange = { wolEnabled = it },
                    expanded     = expandWol,
                    onExpandedChange = { expandWol = it }
                ) {
                    SpaceTextField(
                        value         = wolMacAddress,
                        onValueChange = { wolMacAddress = it },
                        label         = stringResource(R.string.wol_mac_address),
                        icon          = Icons.Outlined.Wifi,
                        isError       = wolMacAddress.isNotBlank() && !wolMacValid
                    )
                    if (wolMacAddress.isNotBlank() && !wolMacValid) {
                        Text(
                            stringResource(R.string.wol_mac_invalid),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    SpaceTextField(wolBroadcastAddress, { wolBroadcastAddress = it }, stringResource(R.string.wol_broadcast), Icons.Outlined.Router)
                    Text(stringResource(R.string.wol_hint), color = CometTail, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }

                // ── Server Trust — TOFU fingerprint management ────────────────
                // FIX-MED-R3-4: only shown when editing an existing profile so there
                // is an actual stored fingerprint that might need clearing (e.g. after
                // the server certificate is renewed or the host is rebuilt).
                if (profile != null) {
                    val showSshTrust    = protocolType == ProtocolType.SSH
                    val showTunnelTrust = protocolType != ProtocolType.SSH && sshTunnelEnabled &&
                        sshTunnelHost.isNotBlank()
                    if (showSshTrust || showTunnelTrust) {
                        SettingsCard(
                            icon         = Icons.Outlined.Fingerprint,
                            accentColor  = NovaPink,
                            title        = stringResource(R.string.tofu_clear_section),
                            subtitle     = stringResource(R.string.card_trust_desc),
                            expanded     = expandTrust,
                            onExpandedChange = { expandTrust = it }
                        ) {
                            if (tofuClearedMessage.isNotEmpty()) {
                                Text(tofuClearedMessage, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                            }
                            if (showSshTrust) {
                                SpaceButton(
                                    text    = stringResource(R.string.tofu_clear_button_ssh),
                                    onClick = { showClearTofuSshDialog = true },
                                    variant = ButtonVariant.GHOST,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (showTunnelTrust) {
                                SpaceButton(
                                    text    = stringResource(R.string.tofu_clear_button_tunnel),
                                    onClick = { showClearTofuTunnelDialog = true },
                                    variant = ButtonVariant.GHOST,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            SpaceButton(
                text    = stringResource(R.string.save),
                onClick = {
                    val base = profile ?: RdpProfile(name = "", host = "", username = "", password = "")
                    onSave(base.copy(
                        name = name.trim(), protocolType = protocolType,
                        host = host.trim(), port = port.toIntOrNull() ?: protocolType.defaultPort,
                        username = username.trim(), password = password,
                        domain = domain.trim(), useNla = useNla,
                        acceptSelfSignedCertificate = acceptSelfSignedCertificate && protocolType == ProtocolType.RDP,  // BUG-3 FIX
                        gatewayEnabled = gatewayEnabled && protocolType == ProtocolType.RDP,
                        gatewayHost = gatewayHost.trim(), gatewayPort = gatewayPort.toIntOrNull() ?: 443,
                        gatewayUsername = gatewayUsername.trim(), gatewayPassword = gatewayPassword,
                        gatewayDomain = gatewayDomain.trim(), vncViewOnly = vncViewOnly,
                        sshAuthType = sshAuthType, sshPrivateKey = sshPrivateKey,
                        sshPrivateKeyPassphrase = sshKeyPassphrase,
                        // SSH Tunnel fields (applies to RDP and VNC only)
                        sshTunnelEnabled  = sshTunnelEnabled && protocolType != ProtocolType.SSH,
                        sshTunnelHost     = sshTunnelHost.trim(),
                        sshTunnelPort     = sshTunnelPort.toIntOrNull() ?: 22,
                        sshTunnelUsername = sshTunnelUsername.trim(),
                        sshTunnelAuthType = sshTunnelAuthType,
                        sshTunnelPassword = sshTunnelPassword,
                        sshTunnelPrivateKey = sshTunnelPrivateKey,
                        sshTunnelPrivateKeyPassphrase = sshTunnelKeyPassphrase,
                        // UX-06: advanced RDP fields
                        colorDepth       = if (protocolType == ProtocolType.RDP) colorDepth else 32,
                        width            = if (protocolType == ProtocolType.RDP) customWidth.toIntOrNull() ?: 0 else 0,
                        height           = if (protocolType == ProtocolType.RDP) customHeight.toIntOrNull() ?: 0 else 0,
                        performanceFlags = if (protocolType == ProtocolType.RDP) performanceFlags else RdpPerformance.LAN,
                        enableSound      = if (protocolType == ProtocolType.RDP) enableSound else false,
                        // Wake-on-LAN fields
                        wolEnabled          = wolEnabled,
                        wolMacAddress       = wolMacAddress.trim(),
                        wolBroadcastAddress = wolBroadcastAddress.trim().ifBlank { "255.255.255.255" },
                    ))
                },
                enabled  = isValid && (!wolEnabled || (wolMacValid && wolMacAddress.isNotBlank())),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = CometTail)
            }
        }
    )
}

@Composable
private fun SpaceSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = CometTail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepSpace, checkedTrackColor = PulsarCyan,
                uncheckedThumbColor = CometTail, uncheckedTrackColor = HorizonGray
            )
        )
    }
}

@Composable
private fun ProtocolSelector(selected: ProtocolType, onSelect: (ProtocolType) -> Unit, isEditing: Boolean = false) {
    // UX-11: Protocol can now be changed in edit mode; we show a warning instead of locking.
    var showProtocolChangeWarning by remember { mutableStateOf(false) }
    var pendingProtocol by remember { mutableStateOf<ProtocolType?>(null) }

    if (showProtocolChangeWarning && pendingProtocol != null) {
        AlertDialog(
            onDismissRequest = { showProtocolChangeWarning = false; pendingProtocol = null },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.protocol_change_title), color = StarDust) },
            text  = { Text(stringResource(R.string.protocol_change_warning), color = CometTail, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    pendingProtocol?.let { onSelect(it) }
                    showProtocolChangeWarning = false
                    pendingProtocol = null
                }) { Text(stringResource(R.string.protocol_change_confirm), color = NovaPink) }
            },
            dismissButton = {
                TextButton(onClick = { showProtocolChangeWarning = false; pendingProtocol = null }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.protocol), color = CometTail, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProtocolType.entries.forEach { type ->
                val sel = type == selected
                val color = when (type) {
                    ProtocolType.RDP -> QuantumBlue
                    ProtocolType.VNC -> VoidPurple
                    ProtocolType.SSH -> PlasmaGreen
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        if (!sel) {
                            if (isEditing) {
                                pendingProtocol = type
                                showProtocolChangeWarning = true
                            } else {
                                onSelect(type)
                            }
                        }
                    },
                    shape  = RoundedCornerShape(12.dp),
                    color  = if (sel) color.copy(alpha = 0.18f) else NebulaSurface,
                    border = BorderStroke(1.dp, if (sel) color else HorizonGray)
                ) {
                    Column(
                        modifier              = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment   = Alignment.CenterHorizontally
                    ) {
                        Icon(protocolIcon(type), null, tint = if (sel) color else CometTail, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(type.label, color = if (sel) color else CometTail, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

fun protocolIcon(type: ProtocolType): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    ProtocolType.RDP -> Icons.Outlined.DesktopWindows
    ProtocolType.VNC -> Icons.Outlined.Monitor
    ProtocolType.SSH -> Icons.Outlined.Terminal
}

@Composable
private fun AuthTypeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = PulsarCyan
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape  = RoundedCornerShape(10.dp),
        color  = if (selected) accent.copy(alpha = 0.15f) else NebulaSurface,
        border = BorderStroke(1.dp, if (selected) accent else HorizonGray)
    ) {
        Text(
            label,
            color = if (selected) accent else CometTail,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun SectionDivider(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = HorizonGray.copy(alpha = 0.35f))
        Text(label, color = PulsarCyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = HorizonGray.copy(alpha = 0.35f))
    }
}

// ── Form Group Header ─────────────────────────────────────────────────────────
// Friendlier, more spacious stand-in for a bare SectionDivider when a group of
// fields benefits from a short one-line explanation of *why* it exists (used to
// frame "Quick Connect" vs "Optional Settings" in the redesigned connection form).
@Composable
fun FormGroupHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = StarDust, fontWeight = FontWeight.Bold)
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = HorizonGray,
            modifier = Modifier.padding(start = 21.dp, top = 1.dp)
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = HorizonGray.copy(alpha = 0.25f))
    }
}

// ── Settings Card ──────────────────────────────────────────────────────────────
// The single, predictable building block for every optional section of the
// connection form. One visual shape, one interaction pattern: an icon, a name,
// a one-line status that's readable without opening the card, an optional
// on/off switch for true toggles, and a chevron that expands the details.
// Turning the switch on auto-expands the card so the person immediately sees
// what they just enabled — never a hidden state.
@Composable
fun SettingsCard(
    icon:             androidx.compose.ui.graphics.vector.ImageVector,
    accentColor:      Color,
    title:            String,
    subtitle:         String,
    hasToggle:        Boolean = false,
    toggleChecked:    Boolean = false,
    onToggleChange:   ((Boolean) -> Unit)? = null,
    expanded:         Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content:          @Composable ColumnScope.() -> Unit
) {
    val isActive = hasToggle && toggleChecked
    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = if (isActive) accentColor.copy(alpha = 0.07f) else NebulaSurface.copy(alpha = 0.55f),
        border   = BorderStroke(1.dp, if (isActive) accentColor.copy(alpha = 0.55f) else HorizonGray.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accentColor, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = if (isActive) accentColor else HorizonGray,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (hasToggle && onToggleChange != null) {
                    Switch(
                        checked = toggleChecked,
                        onCheckedChange = { checked ->
                            onToggleChange(checked)
                            if (checked && !expanded) onExpandedChange(true)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepSpace, checkedTrackColor = accentColor,
                            uncheckedThumbColor = CometTail, uncheckedTrackColor = HorizonGray
                        ),
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint     = CometTail,
                    modifier = Modifier.padding(start = 4.dp).size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun SpaceTextField(
    value:           String,
    onValueChange:   (String) -> Unit,
    label:           String,
    icon:            androidx.compose.ui.graphics.vector.ImageVector,
    isPassword:      Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    isError:         Boolean = false,
    imeAction:       ImeAction = ImeAction.Next,
    onImeAction:     (() -> Unit)? = null,
    modifier:        Modifier = Modifier
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, color = CometTail) },
        leadingIcon   = { Icon(icon, null, tint = if (isError) MaterialTheme.colorScheme.error else PulsarCyan, modifier = Modifier.size(20.dp)) },
        trailingIcon  = if (isPassword && onTogglePassword != null) ({
            IconButton(onClick = onTogglePassword) {
                Icon(
                    if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    null, tint = CometTail
                )
            }
        }) else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        isError       = isError,
        singleLine    = true,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
            onGo   = { onImeAction?.invoke() }
        ),
        modifier      = modifier.fillMaxWidth(),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = PulsarCyan,
            unfocusedBorderColor    = InputBorder,
            focusedLabelColor       = PulsarCyan,
            cursorColor             = PulsarCyan,
            focusedTextColor        = StarDust,
            unfocusedTextColor      = StarDust,
            focusedContainerColor   = InputBg,
            unfocusedContainerColor = InputBg,
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

// ── Delete Confirm Dialog ─────────────────────────────────────────────────────
@Composable
fun DeleteConfirmDialog(
    profileName: String,
    onConfirm:   () -> Unit,
    onDismiss:   () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.Warning, null, tint = SolarFlare, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.delete_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = { Text(stringResource(R.string.delete_confirm_message, profileName), color = CometTail) },
        confirmButton = {
            SpaceButton(stringResource(R.string.delete), onConfirm, ButtonVariant.DANGER, Modifier.fillMaxWidth())
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

// ── SSH Tunnel Section ────────────────────────────────────────────────────────
/**
 * Reusable SSH Tunnel jump-host fields, shown inside the "SSH Tunnel"
 * SettingsCard in ProfileFormDialog for both RDP and VNC profiles. The
 * on/off switch and expand/collapse state now live on the card header
 * itself, so this composable only renders the fields.
 */
@Composable
fun SshTunnelSection(
    host:                  String,
    onHostChange:          (String) -> Unit,
    port:                  String,
    onPortChange:          (String) -> Unit,
    username:              String,
    onUsernameChange:      (String) -> Unit,
    authType:              SshAuthType,
    onAuthTypeChange:      (SshAuthType) -> Unit,
    password:              String,
    onPasswordChange:      (String) -> Unit,
    passwordVisible:       Boolean,
    onTogglePassword:      () -> Unit,
    privateKey:            String,
    onPrivateKeyChange:    (String) -> Unit,
    keyPassphrase:         String,
    onKeyPassphraseChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // Jump-host connection fields
            SpaceTextField(
                value         = host,
                onValueChange = onHostChange,
                label         = stringResource(R.string.ssh_tunnel_host),
                icon          = Icons.Outlined.Hub,
            )
            SpaceTextField(
                value         = port,
                onValueChange = onPortChange,
                label         = stringResource(R.string.ssh_tunnel_port),
                icon          = Icons.Outlined.SettingsEthernet,
            )
            SpaceTextField(
                value         = username,
                onValueChange = onUsernameChange,
                label         = stringResource(R.string.ssh_tunnel_username),
                icon          = Icons.Outlined.Person,
            )

            // Auth type selector
            Text(
                text  = stringResource(R.string.ssh_tunnel_auth),
                style = MaterialTheme.typography.labelMedium,
                color = CometTail,
            )
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuthTypeChip(
                    label    = stringResource(R.string.ssh_auth_password),
                    selected = authType == SshAuthType.PASSWORD,
                    onClick  = { onAuthTypeChange(SshAuthType.PASSWORD) },
                    modifier = Modifier.weight(1f),
                )
                AuthTypeChip(
                    label    = stringResource(R.string.ssh_auth_key),
                    selected = authType == SshAuthType.PRIVATE_KEY,
                    onClick  = { onAuthTypeChange(SshAuthType.PRIVATE_KEY) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Password auth
            AnimatedVisibility(
                visible = authType == SshAuthType.PASSWORD,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                SpaceTextField(
                    value            = password,
                    onValueChange    = onPasswordChange,
                    label            = stringResource(R.string.ssh_tunnel_password),
                    icon             = Icons.Outlined.Lock,
                    isPassword       = true,
                    passwordVisible  = passwordVisible,
                    onTogglePassword = onTogglePassword,
                )
            }

            // Private-key auth
            AnimatedVisibility(
                visible = authType == SshAuthType.PRIVATE_KEY,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value         = privateKey,
                        onValueChange = onPrivateKeyChange,
                        label         = { Text(stringResource(R.string.ssh_tunnel_private_key), color = CometTail) },
                        placeholder   = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", color = HorizonGray) },
                        minLines      = 4,
                        maxLines      = 8,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = PulsarCyan,
                            unfocusedBorderColor    = InputBorder,
                            focusedLabelColor       = PulsarCyan,
                            cursorColor             = PulsarCyan,
                            focusedTextColor        = StarDust,
                            unfocusedTextColor      = StarDust,
                            focusedContainerColor   = InputBg,
                            unfocusedContainerColor = InputBg,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                    SpaceTextField(
                        value         = keyPassphrase,
                        onValueChange = onKeyPassphraseChange,
                        label         = stringResource(R.string.ssh_tunnel_key_passphrase),
                        icon          = Icons.Outlined.Key,
                    )
                }
            }
        }
}
