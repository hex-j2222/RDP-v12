package com.gotohex.rdp.ssh.protocol

/**
 * Maps the same PC scan codes used by [com.gotohex.rdp.ui.screens.ExtraKeysBar]
 * to ANSI/VT100 escape sequences, for keys that have no plain-text
 * representation (arrows, function keys, Home/End, etc.) in an SSH terminal
 * session.
 */
object SshKeyMap {
    fun scanCodeToAnsiSequence(scanCode: Int, extended: Boolean): String? = when (scanCode) {
        0x01 -> "\u001B"               // Esc
        0x0F -> "\t"                   // Tab
        0x48 -> "\u001B[A"             // Up
        0x50 -> "\u001B[B"             // Down
        0x4D -> "\u001B[C"             // Right
        0x4B -> "\u001B[D"             // Left
        0x47 -> if (extended) "\u001B[H" else null   // Home
        0x4F -> if (extended) "\u001B[F" else null   // End
        0x49 -> if (extended) "\u001B[5~" else null  // PageUp
        0x51 -> if (extended) "\u001B[6~" else null  // PageDown
        0x52 -> if (extended) "\u001B[2~" else null  // Insert
        0x53 -> if (extended) "\u001B[3~" else "\u007F" // Delete / Backspace-ish
        0x3B -> "\u001BOP"   // F1
        0x3C -> "\u001BOQ"   // F2
        0x3D -> "\u001BOR"   // F3
        0x3E -> "\u001BOS"   // F4
        0x3F -> "\u001B[15~" // F5
        0x40 -> "\u001B[17~" // F6
        0x41 -> "\u001B[18~" // F7
        0x42 -> "\u001B[19~" // F8
        0x43 -> "\u001B[20~" // F9
        0x44 -> "\u001B[21~" // F10
        0x57 -> "\u001B[23~" // F11
        0x58 -> "\u001B[24~" // F12
        else -> null
    }

    const val CTRL_C = 0x03
    const val CTRL_D = 0x04
    const val CTRL_Z = 0x1A
    const val CTRL_L = 0x0C
}
