package dev.eclipse.ssh.presentation

import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalModifiers

/** Where a key press goes. */
sealed interface KeyTarget {
    /** The local scrollback. The remote program is not told about the key. */
    data object LocalScroll : KeyTarget

    /** The remote program, the way every other key goes. */
    data object Remote : KeyTarget
}

/**
 * Whether [key] pages the local scrollback or goes to the remote program.
 *
 * Page Up and Page Down belong to the remote program: `less`, `vim`, `man` and `htop` all page with
 * them, and on a screen a program is painting itself that is the only thing they can mean. They are
 * the local scrollback's only on the primary screen, only unmodified, and only when the page would
 * actually move the window - at a ceiling of zero, already at the far end, or already at the live end,
 * a local page would be a silent no-op, and a silent no-op eats a keypress the shell could have used.
 *
 * Every other key is listed rather than defaulted, so a key added to [TerminalKey] has to be given a
 * destination here instead of quietly inheriting one.
 *
 * [programScreen] is `AnsiTerminalBuffer.programOwnsScreen` - the same question the scroll ceiling
 * asks, so a key can never disagree with the gesture about who owns the screen. [scrollOffset] and
 * [scrollCeiling] are in buffer lines, as `MainViewModel.scrollTerminal` takes them.
 */
fun terminalKeyTarget(
    key: TerminalKey,
    modifiers: TerminalModifiers,
    programScreen: Boolean,
    scrollOffset: Int,
    scrollCeiling: Int,
): KeyTarget {
    val paging = modifiers == TerminalModifiers.NONE && !programScreen
    return when (key) {
        TerminalKey.PAGE_UP ->
            if (paging && scrollOffset < scrollCeiling) KeyTarget.LocalScroll else KeyTarget.Remote

        TerminalKey.PAGE_DOWN ->
            if (paging && scrollOffset > 0) KeyTarget.LocalScroll else KeyTarget.Remote

        TerminalKey.ENTER,
        TerminalKey.BACKSPACE,
        TerminalKey.TAB,
        TerminalKey.ESCAPE,
        TerminalKey.ARROW_UP,
        TerminalKey.ARROW_DOWN,
        TerminalKey.ARROW_RIGHT,
        TerminalKey.ARROW_LEFT,
        TerminalKey.HOME,
        TerminalKey.END,
        TerminalKey.INSERT,
        TerminalKey.DELETE,
        TerminalKey.F1,
        TerminalKey.F2,
        TerminalKey.F3,
        TerminalKey.F4,
        TerminalKey.F5,
        TerminalKey.F6,
        TerminalKey.F7,
        TerminalKey.F8,
        TerminalKey.F9,
        TerminalKey.F10,
        TerminalKey.F11,
        TerminalKey.F12,
        -> KeyTarget.Remote
    }
}
