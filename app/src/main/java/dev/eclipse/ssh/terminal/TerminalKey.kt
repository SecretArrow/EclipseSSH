package dev.eclipse.ssh.terminal

/** The pty widths the app will ask a remote side for, inclusive. */
val TERMINAL_COLUMN_RANGE = 80..512

/** The pty heights the app will ask a remote side for, inclusive. */
val TERMINAL_ROW_RANGE = 24..128

/**
 * The keys the on-screen key row and hardware shortcuts name, as the app's own
 * vocabulary. Mapped to the ConnectBot engine's VTermKey codes at the single
 * boundary that crosses into the library (see toVTermKey).
 */
enum class TerminalKey {
    ENTER, BACKSPACE, TAB, ESCAPE,
    ARROW_UP, ARROW_DOWN, ARROW_RIGHT, ARROW_LEFT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}
