package com.gotohex.rdp.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.res.stringResource
import com.gotohex.rdp.R
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import com.gotohex.rdp.ssh.protocol.SshKeyMap
import com.gotohex.rdp.ui.theme.*

// ── UX-12: ANSI escape code parser ───────────────────────────────────────────

/** Default terminal green for uncoloured output. */
private val AnsiDefaultGreen = Color(0xFF33FF66)

/**
 * Tracks the active SGR (Select Graphic Rendition) state between chunks so
 * that incremental parsing can resume at the correct colour and weight without
 * re-scanning the entire accumulated buffer.
 */
internal data class AnsiParseState(
    val color: Color = AnsiDefaultGreen,
    val bold: Boolean = false,
)

/**
 * Maps SGR colour codes to Compose [Color].
 * Covers the standard 8 foreground colours (30–37) and their bright
 * variants (90–97). Background codes and 256-colour/true-colour
 * extensions are stripped silently.
 */
private fun ansiCodeToColor(code: Int): Color? = when (code) {
    30    -> Color(0xFF555555)
    31    -> Color(0xFFFF5555)
    32    -> Color(0xFF55FF55)
    33    -> Color(0xFFFFFF55)
    34    -> Color(0xFF5555FF)
    35    -> Color(0xFFFF55FF)
    36    -> Color(0xFF55FFFF)
    37    -> Color(0xFFCCCCCC)
    90    -> Color(0xFF777777)
    91    -> Color(0xFFFF7777)
    92    -> Color(0xFF77FF77)
    93    -> Color(0xFFFFFF77)
    94    -> Color(0xFF7777FF)
    95    -> Color(0xFFFF77FF)
    96    -> Color(0xFF77FFFF)
    97    -> Color(0xFFFFFFFF)
    else  -> null
}

/**
 * FIX #5 (Performance): Parses only the [chunk] string (the NEW bytes that
 * arrived since the last call), starting from [state]. Returns the rendered
 * [AnnotatedString] for the chunk plus the updated [AnsiParseState] to pass
 * into the next call.
 *
 * Callers compose the incremental result onto the previously cached
 * [AnnotatedString] via [buildAnnotatedString] { append(existing); append(newChunk) },
 * so the O(n) regex scan is limited to the incoming bytes (~10–200 chars)
 * rather than the full ~200 KB accumulated buffer.
 */
internal fun parseAnsiChunk(chunk: String, state: AnsiParseState): Pair<AnnotatedString, AnsiParseState> {
    val escapeRegex = Regex("\u001B\\[([0-9;]*)([A-Za-z])")
    var currentColor = state.color
    var bold = state.bold
    var pos = 0

    val annotated = buildAnnotatedString {
        for (match in escapeRegex.findAll(chunk)) {
            if (match.range.first > pos) {
                withStyle(SpanStyle(
                    color = currentColor,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                )) {
                    append(chunk.substring(pos, match.range.first))
                }
            }
            pos = match.range.last + 1

            if (match.groupValues[2] == "m") {
                val params = match.groupValues[1]
                if (params.isEmpty() || params == "0") {
                    currentColor = AnsiDefaultGreen
                    bold = false
                } else {
                    params.split(";").forEach { part ->
                        val code = part.toIntOrNull() ?: return@forEach
                        when (code) {
                            0           -> { currentColor = AnsiDefaultGreen; bold = false }
                            1           -> bold = true
                            22          -> bold = false
                            in 30..37   -> currentColor = ansiCodeToColor(code) ?: currentColor
                            in 90..97   -> currentColor = ansiCodeToColor(code) ?: currentColor
                            39          -> currentColor = AnsiDefaultGreen
                        }
                    }
                }
            }
        }
        if (pos < chunk.length) {
            withStyle(SpanStyle(
                color = currentColor,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            )) {
                append(chunk.substring(pos))
            }
        }
    }
    return annotated to AnsiParseState(currentColor, bold)
}

/**
 * Legacy full-parse overload — kept for callers that need a one-shot parse
 * (e.g. tests). The [TerminalScreen] composable uses [rememberIncrementalAnsiText]
 * which calls [parseAnsiChunk] incrementally instead.
 */
internal fun parseAnsiColors(raw: String): AnnotatedString =
    parseAnsiChunk(raw, AnsiParseState()).first

/**
 * FIX #5 (Performance): Composable helper that maintains incremental ANSI
 * parse state across recompositions. On each new [terminalText] value it
 * parses ONLY the newly appended suffix (a few bytes) rather than re-running
 * the full regex over the entire 5 000-line buffer.
 *
 * Falls back to a full re-parse only when lines are trimmed (buffer wrap) or
 * when the text is reset between sessions.
 */
@Composable
private fun rememberIncrementalAnsiText(terminalText: String): AnnotatedString {
    // Mutable refs survive recomposition but are invisible to the snapshot system,
    // which is exactly what we want — we manage invalidation ourselves via
    // `remember(terminalText)` below.
    val prevText      = remember { mutableStateOf("") }
    val prevAnnotated = remember { mutableStateOf(AnnotatedString("")) }
    val prevState     = remember { mutableStateOf(AnsiParseState()) }

    return remember(terminalText) {
        when {
            terminalText.isEmpty() -> {
                // Session reset — clear everything.
                prevText.value      = ""
                prevAnnotated.value = AnnotatedString("")
                prevState.value     = AnsiParseState()
                AnnotatedString("")
            }
            terminalText.length > prevText.value.length &&
            terminalText.startsWith(prevText.value) -> {
                // Fast path: only the suffix is new — parse just that.
                val suffix = terminalText.substring(prevText.value.length)
                val (newAnnotated, newState) = parseAnsiChunk(suffix, prevState.value)
                val combined = buildAnnotatedString {
                    append(prevAnnotated.value)
                    append(newAnnotated)
                }
                prevText.value      = terminalText
                prevAnnotated.value = combined
                prevState.value     = newState
                combined
            }
            else -> {
                // Slow path: buffer was trimmed or text changed in a non-append
                // way (reconnect). Full re-parse is correct and necessary here.
                val (annotated, state) = parseAnsiChunk(terminalText, AnsiParseState())
                prevText.value      = terminalText
                prevAnnotated.value = annotated
                prevState.value     = state
                annotated
            }
        }
    }
}

/**
 * Interactive SSH terminal — the SSH equivalent of [com.gotohex.rdp.ui.screens.RdpCanvas],
 * but text-based rather than framebuffer-based. Renders the running output
 * stream and drives input via a hidden text field (so typed keystrokes are
 * sent as raw bytes immediately) plus a row of common terminal control keys
 * (Ctrl+C, Tab, arrows, Esc) that have no plain-text representation.
 */
@Composable
fun TerminalScreen(
    profileName: String,
    terminalText: String,
    latency: Long,
    onSendText: (String) -> Unit,
    onSendControlByte: (Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    // FIX #TERM-BS: Use a sentinel space so the field is never truly empty.
    // An empty field means the IME never fires onValueChange for backspace.
    // With a sentinel, typing 'a' → " a" (length 2 > 1) → send "a".
    // Pressing backspace → "" (length 0 < 1) → send \u007F (DEL).
    // The sentinel is invisible in the terminal because we only forward
    // characters AFTER position 0, and the decoration box shows "$ " anyway.
    var inputBuffer by remember { mutableStateOf(" ") }  // single space sentinel

    // FIX #4 (UX): Replace animateScrollTo with instant scrollTo so rapid output
    // doesn't cause the screen to "float" up and down as each animation is
    // cancelled by the next chunk. Only auto-scroll when the user is already
    // near the bottom — if they've scrolled up to review earlier output we
    // stay put so we don't interrupt their reading.
    LaunchedEffect(terminalText) {
        val distanceFromBottom = scrollState.maxValue - scrollState.value
        // 300 px tolerance: accounts for partial visible line at the bottom edge.
        if (distanceFromBottom < 300 || scrollState.maxValue == 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // FIX #TERM-SCROLL: Track whether user has scrolled away from the bottom
    // so we can show a scroll-to-bottom FAB.
    val isAtBottom = remember(scrollState.value, scrollState.maxValue) {
        scrollState.maxValue == 0 || (scrollState.maxValue - scrollState.value) < 300
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(DeepSpace.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(profileName, color = StarDust, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp)
                    Text(stringResource(R.string.terminal_ssh_latency, latency), color = PulsarCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            val clip = clipboard.getClipEntry()
                            val text = clip?.clipData?.getItemAt(0)?.text?.toString()
                            text?.let { onSendText(it) }
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.cd_paste), tint = CometTail)
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_disconnect), tint = NovaPink)
                    }
                }
            }

            // Terminal output — monospace, green-on-black classic terminal look.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .verticalScroll(scrollState)
                    .padding(10.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        keyboardController?.show()
                    }
            ) {
                // FIX #5 (Performance): rememberIncrementalAnsiText only re-parses the
                // newly arrived suffix on each update rather than running Regex.findAll()
                // over the entire ~200 KB buffer. Full re-parse only on session reset.
                val connectingText = stringResource(R.string.terminal_connecting)
                val ansiText: AnnotatedString = if (terminalText.isEmpty()) {
                    remember(connectingText) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = AnsiDefaultGreen)) { append(connectingText) }
                        }
                    }
                } else {
                    rememberIncrementalAnsiText(terminalText)
                }
                // FIX-SELECT: Wrap in SelectionContainer so the user can long-press
                // to select text and copy it — previously the plain Text composable
                // had no selection support at all.
                SelectionContainer {
                    Text(
                        text = ansiText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // Control-key row — keys with no plain-text representation.
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebulaSurface)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                // FIX 5: terminal key labels use stringResource so they are translatable.
                // Arrow / symbol keys (↑ ↓ ← → | / ~) are universal symbols — kept as-is.
                item { TermKeyChip(stringResource(R.string.term_key_esc))  { onSendText("\u001B") } }
                item { TermKeyChip(stringResource(R.string.term_key_tab))  { onSendText("\t") } }
                item { TermKeyChip("Ctrl+C") { onSendControlByte(SshKeyMap.CTRL_C) } }
                item { TermKeyChip("Ctrl+D") { onSendControlByte(SshKeyMap.CTRL_D) } }
                item { TermKeyChip("Ctrl+Z") { onSendControlByte(SshKeyMap.CTRL_Z) } }
                item { TermKeyChip("Ctrl+L") { onSendControlByte(SshKeyMap.CTRL_L) } }
                item { TermKeyChip("↑") { onSendText("\u001B[A") } }
                item { TermKeyChip("↓") { onSendText("\u001B[B") } }
                item { TermKeyChip("←") { onSendText("\u001B[D") } }
                item { TermKeyChip("→") { onSendText("\u001B[C") } }
                item { TermKeyChip(stringResource(R.string.term_key_home)) { onSendText("\u001B[H") } }
                item { TermKeyChip(stringResource(R.string.term_key_end))  { onSendText("\u001B[F") } }
                item { TermKeyChip("|") { onSendText("|") } }
                item { TermKeyChip("/") { onSendText("/") } }
                item { TermKeyChip("~") { onSendText("~") } }
            }

            // Hidden input field: captures the system keyboard, sends each
            // character/line straight to the SSH channel, and is cleared
            // immediately after every keystroke so it never accumulates.
            BasicTextField(
                value = inputBuffer,
                onValueChange = { newValue ->
                    // Sentinel is always at index 0 (" ").
                    // Typing a char: newValue = " x" → length 2 > 1 → send "x"
                    // Backspace:     newValue = ""   → length 0 < 1 → send DEL
                    when {
                        newValue.length > inputBuffer.length ->
                            onSendText(newValue.substring(inputBuffer.length))
                        newValue.length < inputBuffer.length ->
                            onSendText("\u007F")  // DEL / backspace
                    }
                    inputBuffer = " "  // always restore sentinel
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .background(NebulaSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                textStyle = TextStyle(color = StarDust, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PulsarCyan),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { onSendText("\n") }
                ),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ", color = PulsarCyan, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Box(Modifier.weight(1f)) { inner() }
                    }
                }
            )
        } // end Column

        // FIX #TERM-SCROLL: Scroll-to-bottom FAB — visible only when the user
        // has scrolled up from the bottom of the terminal output.
        AnimatedVisibility(
            visible = !isAtBottom,
            enter   = fadeIn() + scaleIn(),
            exit    = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                containerColor = NebulaSurface,
                contentColor   = PulsarCyan,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    } // end outer Box

    // Bring up the keyboard automatically when the terminal first connects.
    LaunchedEffect(Unit) { keyboardController?.show() }
}

@Composable
private fun TermKeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = StarfieldSurface,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = StarDust,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
