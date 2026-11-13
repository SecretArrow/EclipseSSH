package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The widest of the app-wide floor and the host's own width: a viewport report resizes the
 * pty, and the first one arrives before any output does, so the floor is what a phone
 * portrait screen gets until the settings say wider.
 */
internal fun minTerminalColumns(
    settingColumns: Int,
    hostColumns: Int,
): Int = maxOf(settingColumns.coerceAtLeast(0), hostColumns.coerceAtLeast(0))

/**
 * The gap between the grid and the screen edge, derived from the screen rather than from the
 * terminal's own box: the box loses height to the software keyboard, and a margin derived
 * from it would shrink every time the keyboard opened.
 */
fun terminalTextInset(
    screenWidthDp: Int,
    screenHeightDp: Int,
): Dp =
    when {
        screenWidthDp >= 1200 -> 48.dp
        screenWidthDp >= 840 -> 32.dp
        screenWidthDp >= 600 -> 24.dp
        else -> 12.dp
    }
