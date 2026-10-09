package dev.eclipse.ssh.ui.terminal

/**
 * Whether the cursor should blink right now: visible, asked to blink, and in the focused window.
 *
 * A pure function rather than the condition inlined into the view's `LaunchedEffect`, for the reason
 * `TerminalScroll.kt` exists - the decision is a rule and the loop around it is Compose. All eight
 * combinations have a case in `CursorBlinkTest`.
 */
internal fun cursorBlinks(cursorVisible: Boolean, blinkMode: Boolean, focused: Boolean): Boolean =
    cursorVisible && blinkMode && focused
