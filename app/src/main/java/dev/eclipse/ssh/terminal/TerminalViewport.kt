package dev.eclipse.ssh.terminal

/**
 * Everything the terminal view measured, in the four numbers the rest of the app needs from it.
 *
 * This used to be two numbers, and the reason it is four is the whole of the tall-terminal setting. A
 * viewport is not one size: the *pty* is told a height, the *buffer* is given a height, and the *window*
 * the user reads is a height, and once the app-wide setting became a floor those three stopped being the
 * same number. Collapsing them meant the app could only ever express "the terminal is exactly the size of
 * the screen", which is the one configuration the setting exists to leave.
 *
 * [columns] is the *grid's* width - what the pty is told and what the buffer wraps at - and [visibleColumns]
 * is how much of it fits on screen. They differ whenever the width floor is in play, which is the
 * default: the pty is told 80 columns and a phone in portrait fits about 44. The view re-wraps the lines
 * it is handed at [visibleColumns], so one buffer line can be several screen rows - which is why the
 * buffer is told this number as well as the height, and fits its window in rows rather than lines.
 *
 * @param columns the width to give both the pty and the buffer.
 * @param ptyRows the height to tell the pty. Normally [bufferRows]; the on-screen rows while a
 *   full-screen program is drawing, because `vim` and `htop` address every cell positionally and would
 *   put their status line a thousand rows below the last pixel.
 * @param bufferRows the height to give the local grid - the app-wide floor, or a host's own shorter
 *   choice. This is the number that makes `ls` print a thousand entries without paging, and it is
 *   deliberately *not* the pty's height on an alternate screen: entering and leaving `vim` must not
 *   reflow the tall screen underneath it.
 * @param visibleRows how many rows fit on screen, and therefore the window [AnsiTerminalBuffer.frame]
 *   should cut - the height the user actually reads, wherever the cursor has scrolled to.
 * @param visibleColumns how many columns fit on screen, and therefore the width
 *   [AnsiTerminalBuffer.frame] fits that window to. Zero on the callers with no screen of their own.
 */
data class TerminalViewport(
    val columns: Int,
    val ptyRows: Int,
    val bufferRows: Int,
    val visibleRows: Int,
    val visibleColumns: Int = 0,
)
