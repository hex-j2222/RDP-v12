package com.gotohex.rdp.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.gotohex.rdp.data.model.ConnectionState
import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.session.SessionTab
import com.gotohex.rdp.ui.theme.*

/**
 * Feature-05 · تعدد الجلسات
 *
 * A horizontally-scrollable tab strip that shows all open remote sessions.
 * Tapping a tab brings that session to the foreground by launching
 * [RdpSessionActivity] with the matching tab id.
 * The × button closes the tab (the Activity handles its own disconnect via
 * [SessionTabManager.closeTab]).
 */
@Composable
fun SessionTabsBar(
    tabs: List<SessionTab>,
    activeTabId: String?,
    onTabClick: (SessionTab) -> Unit,
    onTabClose: (SessionTab) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    LazyRow(
        modifier            = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        NebulaSurface.copy(alpha = 0.7f)
                    )
                )
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tabs, key = { it.tabId }) { tab ->
            SessionTabChip(
                tab         = tab,
                isActive    = tab.tabId == activeTabId,
                onTabClick  = { onTabClick(tab) },
                onTabClose  = { onTabClose(tab) }
            )
        }
    }
}

@Composable
private fun SessionTabChip(
    tab:        SessionTab,
    isActive:   Boolean,
    onTabClick: () -> Unit,
    onTabClose: () -> Unit,
) {
    val accent   = PulsarCyan
    val surface  = NebulaSurface

    val bgColor by animateColorAsState(
        targetValue   = if (isActive) accent.copy(alpha = 0.18f) else surface,
        animationSpec = tween(200),
        label         = "tab_bg"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (isActive) accent else HorizonGray.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label         = "tab_border"
    )
    val scale by animateFloatAsState(
        targetValue   = if (isActive) 1.03f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "tab_scale"
    )

    val stateDot = stateColor(tab.state)
    val protoIcon = when (tab.profile.protocolType) {
        ProtocolType.RDP -> Icons.Outlined.DesktopWindows
        ProtocolType.VNC -> Icons.Outlined.Monitor
        ProtocolType.SSH -> Icons.Outlined.Terminal
    }

    Row(
        modifier = Modifier
            .scale(scale)
            .height(36.dp)
            .background(bgColor, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
                onClick           = onTabClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // State indicator dot
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(stateDot, CircleShape)
        )
        // Protocol icon
        Icon(
            protoIcon, null,
            tint     = if (isActive) accent else CometTail,
            modifier = Modifier.size(13.dp)
        )
        // Profile name
        Text(
            text      = tab.profile.name,
            color     = if (isActive) accent else CometTail,
            style     = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            maxLines  = 1,
            overflow  = TextOverflow.Ellipsis,
            modifier  = Modifier.widthIn(max = 100.dp)
        )
        // Close button — BUG-C FIX: was Icon(size=14.dp).clickable — 14×14dp touch target is practically untappable.
        // Wrapped in a Box so the click area is 36×36dp while the icon stays 14dp.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onTabClose
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Close, null,
                tint     = CometTail.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun stateColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED    -> PlasmaGreen
    ConnectionState.CONNECTING,
    ConnectionState.RECONNECTING -> ConnectingAmber
    ConnectionState.ERROR        -> ErrorRed
    ConnectionState.DISCONNECTED -> CometTail.copy(alpha = 0.4f)
}
