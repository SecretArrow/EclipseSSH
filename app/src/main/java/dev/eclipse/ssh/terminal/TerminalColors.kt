/*
 * The foreground and background colours one terminal is drawn with, as the app's own plain
 * holder. Built from [TerminalTheme]'s packed ARGB ints — the theme is a setting name, the
 * colours are the two ints a renderer actually paints with, and this is the shape that
 * carries them across the boundary from settings to terminal without dragging the whole
 * theme enum along.
 *
 * Under the ConnectBot engine these reach the renderer through [TerminalSessionController]'s
 * defaultForeground / defaultBackground, which the emulator reads once at construction and
 * again whenever the theme changes; the library's own colour table is what answers for the
 * 16 ANSI colours, derived from these two.
 */
package dev.eclipse.ssh.terminal

/**
 * @property foreground the default text colour, as a packed ARGB int.
 * @property background the default cell colour behind the text, as a packed ARGB int.
 */
data class TerminalColors(
    val foreground: Int,
    val background: Int,
)
