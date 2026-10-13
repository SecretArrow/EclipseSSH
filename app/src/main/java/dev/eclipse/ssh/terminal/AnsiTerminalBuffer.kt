package dev.eclipse.ssh.terminal

/**
 * ESC, built from its code point rather than written as a literal.
 *
 * A raw 0x1B in a source file is invisible in every diff and every review, and this file is full of
 * places one could hide in. Naming it once is what keeps the sequences below readable, and the same
 * reasoning is why the C0 bytes further down are matched by code rather than by character literal.
 */
private val ESC: Char = Char(0x1B)

private const val MAX_SCROLLBACK = 2_000
private const val DEFAULT_COLUMNS = 120
private const val DEFAULT_ROWS = 40

/**
 * How many bytes an escape sequence may accumulate before it is treated as malformed.
 *
 * Generous next to anything real - a CSI sequence is a handful of bytes and the longest OSC an app
 * like this sees is a window title - and the point is only to make "never terminated" a bounded
 * condition rather than an unbounded one.
 */
private const val MAX_CONTROL_LENGTH = 256

/** Where the default tab stops sit, on this terminal and on every other one since the VT100. */
private const val TAB_WIDTH = 8

/**
 * What XTVERSION (`CSI > Ps q`) answers with: `DCS > | name(version) ST`.
 *
 * The version is the app's, because the emulator ships inside the app and nothing else versions it -
 * `scripts/check-doc-figures.sh` holds this constant against `versionName` in app/build.gradle.kts,
 * so a release bump cannot leave it behind.
 */
const val TERMINAL_IDENTITY = "EclipseSSH(1.23.0)"

// DECRQM's three answers: the mode is on, it is off, or this terminal has never heard of it.
private const val MODE_SET = 1
private const val MODE_RESET = 2
private const val MODE_UNRECOGNIZED = 0

// The C0 control bytes this terminal acts on.
private const val C0_NUL = 0x00
private const val C0_BEL = 0x07
private const val C0_BS = 0x08
private const val C0_HT = 0x09
private const val C0_LF = 0x0A
private const val C0_VT = 0x0B
private const val C0_FF = 0x0C
private const val C0_CR = 0x0D
private const val C0_SO = 0x0E
private const val C0_SI = 0x0F
private const val C0_ESC = 0x1B

/**
 * The window sizes a terminal may be set to, shared with [dev.eclipse.ssh.ssh.TerminalChannel].
 *
 * Two independent clamps used to sit at the two ends of the same resize: the pty accepted up to
 * 1000x500 and this buffer up to 400x200, so any request in between was sent to the server and
 * silently refused locally. The remote shell then wrapped its output at a width the display did not
 * have, and every line past the buffer's last column folded into the next row - the whole screen
 * sheared, with nothing to say why. Reachable today on a large external display at the smallest font
 * size, and reachable by any future caller that trusts one clamp without knowing about the other.
 *
 * The display is the tighter constraint, because a grid of cells is real memory and the pty is only
 * two integers, so the display's limits are the shared ones. That way the server is never told about
 * a window that cannot be drawn.
 *
 * The rows reach 1000 because an app-wide height is a *floor* the view scrolls through rather than a
 * ceiling the screen cuts down - a value the screen overruled on every device would be a setting that
 * never did anything, which is why the choices used to stop at 60. What that costs is bounded here
 * and nowhere else: at this range's corner (1000 rows by 400 columns) the grid is the largest thing
 * this class will ever hold, some tens of megabytes of cells for a terminal the user asked to be that
 * tall. Every other height is proportional to it, and the default is still "fit the screen".
 */
val TERMINAL_COLUMN_RANGE = 20..400
val TERMINAL_ROW_RANGE = 5..1000

data class TerminalColor(val value: Int = COLOR_DEFAULT) {
    val isDefault: Boolean get() = value == COLOR_DEFAULT
    val isTrueColor: Boolean get() = value and TRUE_COLOR_MASK == TRUE_COLOR_MASK
    val rgb: Int get() = value and RGB_MASK

    companion object {
        const val COLOR_DEFAULT = -1
        const val TRUE_COLOR_MASK = 0x01000000
        const val RGB_MASK = 0x00FFFFFF
    }
}

/**
 * What this terminal paints as its default foreground and background, as ARGB.
 *
 * [TerminalColor] answers "has a colour been set for this cell", not "what is the default", so a
 * program asking OSC 10 or 11 can only be answered if the app hands the buffer the theme it is
 * actually drawing in. With none to hand it, those queries go unanswered - which is what a terminal
 * that does not implement them says, and an invented colour is worse than none, since the asking
 * program uses the reply to pick colours that have to contrast with what this display paints.
 */
data class TerminalColors(val foreground: Int, val background: Int)

data class TerminalStyle(
    val foreground: TerminalColor = TerminalColor(),
    val background: TerminalColor = TerminalColor(),
    val bold: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    /** SGR 8. Drawn as blanks: the whole point of conceal is that the text is not readable. */
    val hidden: Boolean = false,
) {
    /**
     * This style as a blank cell can still show it.
     *
     * A blank has no glyph, so bold, dim, italic, underline, strikethrough and conceal draw nothing
     * on one, and every terminal's own erase drops them - keeping them would only let a cleared cell
     * join the run beside it and carry that run's decoration across what the program cleared. What is
     * left is the two colours and the swap between them, and both are visible: the background is
     * painted, and under [inverse] it is the foreground that is painted in its place.
     */
    val blanked: TerminalStyle get() = TerminalStyle(foreground, background, inverse = inverse)
}

/**
 * One cell of the grid: the character it shows, the style it shows it in, and whatever zero-width
 * characters belong on top of it.
 *
 * [value] is the cell's base and is always exactly one `Char`, so a line is an array of characters
 * that can be indexed by a column. That is what the whole emulator is built on. [combining] holds the
 * code points that take no column of their own and belong to the character before them: an `e`
 * followed by U+0301 is one cell in two code points, and the zero-width joiner in a composed emoji is
 * another. They are stored here rather than in cells of their own because a cell *is* a column, and
 * the program on the far side of the pty does not count them as one - which is the bug this field
 * exists to fix.
 *
 * [wide] marks the left half of a character that takes **two** columns - a CJK ideograph, a fullwidth
 * form, an emoji the standard widened. One cell cannot hold it: the far side counted two columns for
 * it when it sent it, so the printer writes it here and leaves the cell after it as its right half,
 * which is what keeps this buffer's cursor in step with the program's. That right half is an ordinary
 * blank cell carrying the same style, so a background or an underline covers the whole character; see
 * [clearUnit] for the rule that keeps the two together, and [repairLine] for what happens when
 * something shifts them apart.
 *
 * Everything that draws or copies a cell reads [value] plus [combining]. Everything that addresses a
 * cell by column reads [value] alone and needs no part of [combining], because a mark always sits on
 * a base: the base is what decides whether a cell is a word character or a painted one, so those two
 * tests are unchanged by a mark, and no cell ever holds a mark without a base under it.
 */
data class TerminalCell(
    val value: Char = ' ',
    val style: TerminalStyle = TerminalStyle(),
    val combining: String = "",
    /** True on the left half of a two-column character; the cell after it is its right half. */
    val wide: Boolean = false,
)

data class TerminalSnapshot(
    val lines: List<List<TerminalCell>>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val columns: Int,
    val rows: Int,
)

/**
 * One drawable window onto the buffer: exactly the lines the view can show, and nothing else.
 *
 * [TerminalSnapshot] copies the entire scrollback - 2 000 lines by 120 columns is a quarter of a
 * million cells - and the renderer asked for one on every frame of a live session. A window of
 * `rows` lines is two orders of magnitude cheaper and is all a screen can display; the rest of the
 * scrollback only matters when the user scrolls back into it, which moves [firstLine] rather than
 * copying anything extra.
 *
 * [revision] is the cheap way to know whether anything changed at all. It advances on every mutation
 * of the buffer, so a recomposition can skip redrawing without comparing several thousand cells.
 *
 * "Every mutation" includes a cursor that only moved, and that is not a detail: the renderer memoizes
 * the layout it draws the cursor box from on this value, so a move that does not advance it is a move
 * the user cannot see. The left arrow is the case that proves it - its byte is `^H`, a bare control
 * character rather than a CSI, and it goes through [setCursorColumn] for exactly this reason. Every
 * path that changes the cursor, the style or a cell reports it here, including the ones a caller also
 * bumps for; a second increment costs nothing because only "did it change" is ever asked.
 */
data class TerminalFrame(
    val lines: List<List<TerminalCell>>,
    /** Index into the whole buffer of `lines[0]`, so a selection can be addressed absolutely. */
    val firstLine: Int,
    val totalLines: Int,
    /**
     * Index just past the last line the *live* output reaches, which is where this window would end if
     * the user were at the bottom.
     *
     * Not [totalLines], and the difference is the whole reason this field exists. The buffer is sized to
     * the pty's height - the app-wide floor, 60 rows by default - and filled with blank lines from the
     * start, so a session with five lines of output on it has `totalLines` 60 and a live end of 5. A
     * reader that measures "how far behind the live output am I" against [totalLines] is therefore
     * measuring against rows the shell has not written yet, and reports a large positive number for a
     * viewport sitting at the bottom: the scrolled-back badge then draws over the grid permanently, and
     * prints the buffer's un-inked tail as a count of lines below.
     *
     * Defaults to [totalLines], which is the same number for a frame cut from a buffer whose whole
     * height is in use - the shape every hand-built frame in the tests has - so the buffer is the only
     * caller that has to say otherwise, and it is the only one that knows.
     */
    val liveEnd: Int = totalLines,
    /** Row of the cursor within [lines], or -1 when the user has scrolled it out of view. */
    val cursorRow: Int,
    val cursorColumn: Int,
    val columns: Int,
    val rows: Int,
    /**
     * The widest column any line in this window actually paints into, and how far the view may pan.
     *
     * Not the same number as [columns], in either direction. It is *smaller* for an ordinary shell
     * screen, where nothing reaches the right margin and panning into that emptiness would only be a
     * way to lose the text; it is *larger* whenever the history was printed at a wider terminal than
     * the one on display now - after a rotation, or on a phone whose screen fits fewer columns than
     * the pty was given - because those lines keep every character they were printed with. Counted
     * the way the renderer counts a painted cell, so a coloured blank in a status bar extends it and
     * the trailing spaces of a short line do not, plus the cursor's own cell: the cell being typed
     * into is blank until the character lands, and the view has to be able to follow it there.
     */
    val contentColumns: Int = 0,
    val cursorVisible: Boolean,
    val revision: Long,
    val title: String? = null,
    val alternateScreen: Boolean = false,
    /**
     * Whether this frame is a screen a program painted by address on the *primary* screen.
     *
     * `top` is why this exists rather than [alternateScreen] being the whole test; the reasoning is on
     * `AnsiTerminalBuffer`'s own flag of the same name. Like [alternateScreen] it tells the display that
     * the geometry carries the meaning and that re-wrapping would destroy it.
     */
    val positionalScreen: Boolean = false,
    /**
     * Whether this frame was built while a synchronized-output batch (`ESC [ ? 2026 h`) was open.
     *
     * A live frame carrying this is a screen the program has not finished painting, so it is not
     * published: the reader keeps the previous complete screen until the batch closes. A frame built
     * for a scrolled-back view is published regardless - the batch is about the live screen, and
     * refusing to move the window under the user's finger would be a different bug.
     */
    val synchronizedOutput: Boolean = false,
    /** DECCKM. Decides whether an arrow key sends `ESC [ A` or `ESC O A`; see [TerminalKeys]. */
    val applicationCursorKeys: Boolean = false,
    /** DECKPAM. Some full-screen apps expect the keypad to send SS3 sequences. */
    val applicationKeypad: Boolean = false,
    /** `ESC [ ? 2004 h`. A paste is bracketed when the remote asked for it, and never otherwise. */
    val bracketedPaste: Boolean = false,
    /**
     * What the kitty and modifyOtherKeys protocols currently ask a key press to send.
     *
     * Carried on the frame for the same reason [applicationCursorKeys] is: a keystroke has to read the
     * answer from the value the display is drawing, not ask the view model mid-press and race the frames
     * already in flight. Not part of the layout key - it moves no rows.
     */
    val keyboardModes: KeyboardModes = KeyboardModes.NONE,
    /**
     * `ESC [ ? 12 h` / `l`: whether the cursor blinks, which is what terminfo's `cvvis` and `cnorm`
     * turn on and off. Not part of the layout key, like the mouse modes: the rows do not move with it.
     */
    val cursorBlink: Boolean = true,
    /**
     * How far this window is scrolled back into the history, and how far it can go, both in buffer
     * lines.
     *
     * The view needs both to know when a fling has run out of scrollback: the ceiling is the buffer's
     * answer, not the view's, because it depends on how many rows the window fits at the current width
     * - see [windowCeiling]. Carried on the frame so a gesture reads it from the same value it draws,
     * rather than asking the view model mid-gesture and racing the frames already in flight.
     */
    val scrollOffset: Int = 0,
    val scrollCeiling: Int = 0,
    /**
     * The mouse tracking the remote program has turned on, and whether it wants SGR reports.
     *
     * The gesture branches on these in the middle of a drag: with tracking on, a vertical drag is the
     * wheel the program is waiting for rather than a scroll of the local history. Deliberately not
     * part of the layout key - they change nothing about where the rows go, and listing them there
     * would rebuild every layout each time a program toggled its mouse mode.
     */
    val mouseMode: MouseMode = MouseMode.OFF,
    val mouseSgr: Boolean = false,
    /**
     * The lines just above and just below the window, and how many rendered rows one scroll step moves
     * the window's top edge by in each direction.
     *
     * The top edge does not move by the same distance both ways, and at the live end it does not move at
     * all: a step back from there hands the prompt's row to the background while the top stays pinned
     * (see `TerminalScrollViewportTest.the first page up gives up the blank line under the prompt`).
     * A view that priced every step with one number - the height of the line at the top of the window -
     * would translate the screen by a step that never happened and then snap it back, which is the jump
     * a sub-row scroll exists to remove.
     *
     * [marginAbove] and [marginBelow] are exactly the lines those steps would add, in order, so a drag
     * that has not yet finished a step has real text to slide into the strip it opens. A step off either
     * end is priced at zero, which is the hard stop.
     */
    val marginAbove: List<List<TerminalCell>> = emptyList(),
    val marginBelow: List<List<TerminalCell>> = emptyList(),
    val stepRowsBack: Int = 0,
    val stepRowsForward: Int = 0,
) {
    companion object {
        val EMPTY = TerminalFrame(
            lines = emptyList(),
            firstLine = 0,
            totalLines = 0,
            cursorRow = -1,
            cursorColumn = 0,
            columns = DEFAULT_COLUMNS,
            rows = DEFAULT_ROWS,
            cursorVisible = false,
            revision = 0L,
        )
    }
}

/**
 * Small, allocation-conscious terminal emulator core. It intentionally keeps
 * terminal state outside Compose so output can continue while the UI is paused.
 *
 * The model is one flat list of lines whose *tail* is the screen: `lines.size - rows` is the top of
 * the visible area and everything above it is scrollback. That is what lets the scrollback exist at
 * all without a second data structure, and it is why every screen-relative coordinate in here is
 * computed from [screenTop] rather than stored.
 *
 * The alternate screen (`ESC [ ? 1049 h`) swaps that whole list for a fresh one of exactly `rows`
 * lines and puts the primary aside. It is what makes `vim`, `less`, `htop` and every other
 * full-screen program leave the shell's scrollback exactly as they found it - without it their
 * redraws were painted into the history, and a user who quit `htop` was left scrolling through a
 * few hundred half-overwritten copies of its screen instead of the commands they had run.
 */
class AnsiTerminalBuffer(
    private var columns: Int = DEFAULT_COLUMNS,
    private var rows: Int = DEFAULT_ROWS,
    private val scrollbackLimit: Int = MAX_SCROLLBACK,
) {
    private var lines = ArrayList<MutableList<TerminalCell>>()
    private var cursorRow = 0
    private var cursorColumn = 0

    /**
     * The cursor has printed in the last column and the *next* printable character wraps first.
     *
     * Deferred wrapping itself is not new - it used to be expressed by letting `cursorColumn` reach
     * `columns`, one past the last real cell. Holding it in a flag instead is what makes the column
     * always a valid index: erase, insert and delete no longer have to coerce a possibly-out-of-range
     * cursor before touching a line, and the renderer can put a caret on a cell that exists. It is
     * also the only way DECAWM becomes expressible - with the old marker there was nowhere to record
     * that autowrap was *off*, so `ESC [ ? 7 l` had to be ignored, and a program that turned wrapping
     * off to draw a full-width status bar had its last column pushed onto the next row.
     */
    private var wrapPending = false

    /**
     * Whether the program on the other end is painting this screen by address instead of streaming it.
     *
     * The alternate screen was supposed to be the whole answer to "may this frame be re-wrapped", and
     * it is not, because `top` does not use it: procps homes the cursor with `ESC [ H` and rewrites the
     * screen in place on the primary one, exactly eleven newlines on a twelve-row screen so that it
     * never scrolls. A frame like that is a grid whose meaning is its geometry - the load averages are
     * on row one because row one is where the program put them - so re-wrapping row three into two rows
     * pushes everything below it down and the reader is looking at a screen the program never drew. On
     * a phone this is not hypothetical: the pty is eighty columns wide by default, whatever the screen
     * shows, and the view fits about half that - so every one of `top`'s rows would have reflowed.
     *
     * Set by absolute addressing - a sequence that says *which row* to put the cursor on, and does not
     * move it down - and that is the tell. Output that flows only ever goes down, and a program that
     * names a row is stating where the whole screen's contents belong rather than appending to them.
     *
     * A relative move is deliberately not part of that test, although it once was. readline repaints a
     * wrapped input line by moving up with `ESC [ A` and writing the line again, so *editing* any
     * command longer than the screen - a backspace, a Ctrl-A, a completion - used to mark the screen
     * painted and switch word wrapping off for the rest of the session. That is the state the user sees
     * as a long line clipped at the right edge instead of flowing onto the row below, with the cursor
     * drawn past the edge and the view panning after it. Measured through a real pty at 46 columns:
     * editing one long line emits ten `ESC [ A`; `top`, `less`, `nano`, `vi` and `watch`, run the same
     * way, emit none between them and home with `ESC [ H` instead.
     *
     * Cleared when the screen scrolls under flowing output, which is the opposite tell and is how the
     * flag lets go - `top`'s own exit does it.
     *
     * And cleared by `ESC [ 3 J`, which is what makes `clear` not a false positive. Measured on this
     * machine at 80x12: `clear` writes `ESC[H ESC[2J ESC[3J`, and `top`'s startup writes `ESC[H ESC[2J`
     * and nothing else of the kind - one ED2, never an ED3, in a whole run. So the erase cannot tell
     * the two apart and the home is common to both, but dropping the scrollback separates them exactly,
     * and it is the honest signal: a program that means to keep repainting a screen does not throw the
     * history away first. Without this the screenful after every `clear` was laid out unwrapped, which
     * is what 1.0.6 did to every line and the thing this feature exists to stop.
     */
    private var positionalScreen = false

    private var savedRow = 0
    private var savedColumn = 0
    private var savedStyle = TerminalStyle()
    private var savedGraphics = false
    private var style = TerminalStyle()
    private var escapeState = EscapeState.NORMAL
    private val controlBuffer = StringBuilder()

    /** The intermediate bytes of the escape sequence in progress; see [executeEscIntermediate]. */
    private val escapeIntermediates = StringBuilder()

    /** The character [repeatLastCharacter] repeats, which is what CSI b means by "last". */
    private var lastPrinted: Char? = null

    /** Scroll region, as rows relative to the top of the screen; the full screen unless DECSTBM says otherwise. */
    private var topMargin = 0
    private var bottomMargin = rows - 1

    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var cursorVisible = true
    private var cursorBlink = true
    private var applicationCursorKeys = false
    private var applicationKeypad = false
    private var bracketedPaste = false

    /**
     * Kitty's keyboard flags in effect, and the pushes behind them.
     *
     * One stack per screen, as the protocol requires: a full-screen program may push flags knowing
     * nothing about what the shell had set, and the shell has to get its own back when the program
     * exits - so [primaryKeyboardFlags] holds the main screen's value for as long as the alternate
     * screen is up, and the two stacks never mix.
     */
    private var keyboardFlags = 0
    private var primaryKeyboardFlags = 0
    private val keyboardStack = ArrayList<Int>()
    private val alternateKeyboardStack = ArrayList<Int>()

    /** xterm's modifyOtherKeys level. See `ModifyOtherKeys`. */
    private var modifyOtherKeys = 0

    /**
     * The DECSET mouse modes currently on, as the mode numbers themselves rather than as a decoded
     * enum: a program turns tracking on and SGR separately, and often in either order, so what has to
     * be remembered is the set it asked for. [MouseModes] is what decodes it.
     */
    private var mouseModes: Set<Int> = emptySet()
    private var title: String? = null

    /**
     * Window titles saved by XTWINOPS 22 and restored by 23, one stack per thing xterm keeps a stack
     * for. Both stay in step because both are pushed and popped together by the `0` parameter.
     */
    private val iconTitles = ArrayList<String?>()
    private val windowTitles = ArrayList<String?>()

    /** DEC special graphics selected into G0/G1, and which of the two SI/SO has shifted in. */
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftedOut = false

    private var tabStops = BooleanArray(0)

    /** Set while the alternate screen is up; holds everything the primary screen needs back. */
    private var primary: PrimaryScreen? = null

    /**
     * The revision a frame carries, and the ordering every consumer of one compares.
     *
     * It advances on every mutation, including inside a synchronized-output batch, and that is
     * deliberate: [TerminalLayoutKey] is built from it on the promise that two windows of one revision
     * hold the same cells, so a batch that froze it would hand the view's layout memo a key that
     * describes a screen the frame no longer holds. The batch is honoured where it is observable - the
     * frame says the screen is unfinished and is not published - rather than by falsifying this number.
     */
    private var revision = 0L

    /**
     * Whether a synchronized-output batch (`ESC [ ? 2026 h`) is open.
     *
     * The mode is a promise about what the reader sees: a program saying "do not show this until I am
     * finished" wants the previous complete screen to stay up, and a frame published mid-batch would
     * replace it with a half-painted one. So it is carried on the frame and honoured by the publisher.
     */
    private var synchronizedOutput = false

    /**
     * Replies the remote asked for (device attributes, cursor position) waiting to be written back.
     *
     * Queued rather than sent from inside the parser: [responder] writes to the SSH channel, which
     * can block, and doing that while holding this object's monitor would stall every other thread
     * that wants to feed or render the buffer - including the one draining the channel that the
     * write is waiting on.
     */
    private val pendingReplies = ArrayList<String>()

    /**
     * Where answers to device-status queries go, normally the pty's input.
     *
     * A terminal that never answers `ESC [ 6 n` is a terminal that hangs its own line editor:
     * `bash` with `checkwinsize`, and readline on several configurations, ask for the cursor position
     * at startup and wait for the report. Answering is not optional politeness.
     */
    @Volatile
    var responder: ((String) -> Unit)? = null

    /**
     * Where the answer to the colour queries (OSC 10 and 11) comes from: the theme's default
     * foreground and background, or null for a terminal with none to give. Asked through a function
     * rather than copied in, because the user can change the theme while a session is live and the
     * answer has to be what the screen is painting at that moment.
     */
    @Volatile
    var themeColors: (() -> TerminalColors?)? = null

    init {
        repeat(rows) { lines += blankLine() }
        resetTabStops()
    }

    @Synchronized
    fun resize(newColumns: Int, newRows: Int) {
        val previousRows = rows
        val wasFullRegion = topMargin == 0 && bottomMargin == previousRows - 1
        columns = newColumns.coerceIn(TERMINAL_COLUMN_RANGE)
        rows = newRows.coerceIn(TERMINAL_ROW_RANGE)
        while (lines.size < rows) lines += blankLine()
        trimBlankRowsForShrink(lines, cursorRow)
        normalizeScreen(lines)
        // A region that covered the whole screen still covers it at the new height. One that did not
        // is clamped instead, so a shrink cannot leave a margin pointing past the last row - every
        // scroll after that would have indexed outside the list.
        if (wasFullRegion) {
            topMargin = 0
            bottomMargin = rows - 1
        } else {
            topMargin = topMargin.coerceIn(0, rows - 1)
            bottomMargin = bottomMargin.coerceIn(topMargin, rows - 1)
        }
        // The primary screen is resized too, not just the one on display. Rotating the device while
        // `vim` is open otherwise dropped the user back onto a shell screen still laid out for the
        // old width, with every wrapped line folded at the wrong column.
        primary?.let { saved ->
            while (saved.lines.size < rows) saved.lines += blankLine()
            trimBlankRowsForShrink(saved.lines, saved.cursorRow)
            normalizeScreen(saved.lines)
        }
        cursorRow = cursorRow.coerceIn(0, lines.lastIndex)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        wrapPending = false
        resetTabStops()
        revision++
    }

    /**
     * Ends a synchronized-output batch from outside the stream.
     *
     * The only caller is the collector, when a session ends. A program killed between its `2026 h` and
     * its `2026 l` - or a connection dropped in between - would otherwise leave the latch set, and the
     * buffer outlives the session: a reconnect reuses it, and the new shell's output would then never
     * be published. A batch is a promise from a program that is still running, so the session ending
     * ends it.
     */
    @Synchronized
    fun releaseSynchronizedOutput() {
        synchronizedOutput = false
    }

    /**
     * Feeds decoded output in, then hands any replies the stream asked for to [responder].
     *
     * Split in two on purpose: the parsing happens under this object's monitor and the replying does
     * not. See [pendingReplies].
     */
    fun feed(value: String) {
        val replies = feedLocked(value)
        if (replies.isEmpty()) return
        val sink = responder ?: return
        replies.forEach { reply -> sink(reply) }
    }

    @Synchronized
    private fun feedLocked(value: String): List<String> {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            // A variation selector from the supplement block (U+E0100-U+E01EF) is astral *and*
            // zero-width, so it has to reach [putCodePoint] as one character or it spends two cells on
            // a character the far side counts as none - the exact defect this walk exists for. Only a
            // pair the width table calls zero-width is joined: an astral character of any other width
            // already occupies the two cells the far side counts two columns for, so pairing it here
            // would change how the printer has always stored it without moving anything.
            //
            // Joined only in the normal state, too. The escape states are an ASCII grammar in which a
            // surrogate is noise, and pairing one there would swallow the byte after it.
            if (escapeState == EscapeState.NORMAL && Character.isHighSurrogate(char) &&
                index + 1 < value.length && Character.isLowSurrogate(value[index + 1])
            ) {
                val pair = Character.toCodePoint(char, value[index + 1])
                if (terminalCharWidth(pair) == 0) {
                    // Straight to the printer rather than through [consume]. In the normal state the
                    // only thing `consume` would do with it is hand it to the printer, and the printer
                    // is the one place that can take a character that is two `Char`s long.
                    putCodePoint(pair)
                    index += 2
                    continue
                }
            }
            consume(char)
            index++
        }
        if (pendingReplies.isEmpty()) return emptyList()
        val replies = pendingReplies.toList()
        pendingReplies.clear()
        return replies
    }

    @Synchronized
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(
        lines = lines.map { it.toList() },
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
        columns = columns,
        rows = rows,
    )

    /**
     * The window the view can actually draw: [viewportRows] lines ending at the cursor, [scrollOffset]
     * lines above it.
     *
     * The cursor is the anchor rather than the bottom of the buffer, and the two are the same thing
     * whenever the cursor is on the last line - which it is in every session whose pty is exactly as
     * tall as the buffer. They part company precisely when the pty is *taller*, which is the setting
     * this exists for: with a 1000-row pty and 200 lines of output the cursor sits on row 200 while
     * the last line of the buffer is row 999, so a window measured from the bottom is 800 rows of
     * blank screen with the prompt somewhere far above it. Anchoring on the cursor keeps what the
     * shell is writing on the screen, and the blank rows below the cursor are never drawn at all.
     *
     * An offset of zero is "following the output", which is the state a terminal is in unless the
     * user has deliberately scrolled back. The offset walks *up* from that anchor, and it is measured
     * the same way by [maxScrollOffset] so a scrolled-back view holds the text it was showing while
     * output arrives below: the caller adds the growth back, which cancels the anchor's own movement
     * exactly as it used to cancel the buffer's. Offsets past the top are clamped rather than
     * rejected, because the buffer shrinks under the view whenever the scrollback limit evicts a line.
     *
     * [windowColumns] is the width the view will *paint* at, which is not this buffer's width whenever
     * the pty is wider than the phone - the app-wide width floor makes that the default, and the view
     * re-wraps the lines it is given. A window of [viewportRows] lines is then not a window of
     * [viewportRows] screen rows: at 80 columns on a phone that fits 44, one line of `ls` output is two
     * rows, and the view used to be handed more rows than it had - so it dropped the overflow from the
     * top, and the screen's first row was the *tail* of a line whose beginning was off-screen. That is
     * a word fragment at column 0, and it changed shape with every scroll because it was a different
     * line each time. The fix belongs here rather than in the layout, because this is the only place
     * that can walk: the window is fitted while it is being built, taking lines from the cursor
     * upwards until the *wrapped* row count reaches [viewportRows], so the frame and the screen agree
     * on the count and nothing has to be dropped at all.
     *
     * The walk always keeps at least one line, even one taller than the screen - a line longer than
     * the whole window has to be shown in part, and the part that matters is the end, where the cursor
     * is. Zero or a negative [windowColumns] means "not wrapped for a window": the window is
     * [viewportRows] lines, exactly as it was before this parameter existed, which is what the
     * snapshot, the export renderer and the tests that drive this class directly all want.
     */
    @Synchronized
    fun frame(scrollOffset: Int = 0, viewportRows: Int = rows, windowColumns: Int = 0): TerminalFrame {
        val budget = viewportRows.coerceIn(1, lines.size)
        // A painted screen is never re-wrapped by the view - see [wrapsForDisplay] - so a window fitted
        // for wrapping would cut `vim`'s screen in half. The rule is asked, not repeated.
        val fitWidth = if (wrapsForDisplay(primary != null, positionalScreen, windowColumns)) windowColumns else 0
        // The window's lower edge, and it is deliberately not the cursor alone.
        //
        // A program that paints a screen can leave its cursor *above* its own last line, and every Ink
        // TUI does: the cursor sits in an input box with a status line drawn under it. Anchoring on the
        // cursor then puts that status line - and everything else below the cursor - past the bottom of
        // the window, where no amount of scrolling reaches it, because the window is what the reader is
        // looking through. Measured on a real [CC] session in tmux at 52x60 with a 30-row viewport: 52
        // of 55 reads produced a window that ended on the cursor's line, leaving the app's status line
        // and tmux's own permanently off-screen.
        //
        // So the bottommost inked line is a floor as well as the cursor. Both are wanted and the taller
        // of the two wins; a cursor more than a windowful above the last line is then off-screen, which
        // is what a real terminal does with a cursor that has been left behind by its own output.
        val anchor = windowAnchorEnd().let { end ->
            if (fitWidth > 0) {
                // The fit decides where the window starts, so there is no lower bound to hold it away
                // from the buffer's own beginning.
                end.coerceIn(1, lines.size)
            } else {
                // Never so near the start that the window would begin before the buffer does - the lower
                // bound is what stops a cursor on row 3 of a tall screen from pulling the window up past
                // the first line the terminal ever wrote.
                end.coerceIn(budget, lines.size)
            }
        }
        val ceiling = scrollCeiling(anchor, budget, fitWidth)
        val offset = scrollOffset.coerceIn(0, ceiling)
        val here = windowAt(anchor, offset, budget, fitWidth)
        val first = windowFirst(here)
        val end = windowEnd(here)
        val take = end - first
        val window = ArrayList<List<TerminalCell>>(take)
        var content = 0
        for (index in first until end) {
            val line = lines[index]
            window += line.toList()
            val painted = terminalPaintedWidth(line)
            if (painted > content) content = painted
        }
        val relativeCursor = cursorRow - first
        if (cursorVisible && relativeCursor in 0 until take) content = maxOf(content, cursorColumn + 1)
        // What the next step each way would do, asked of the same function that built this window so the
        // two cannot disagree. Both neighbours are clamped to the ceiling and to zero the same way the
        // offset itself was, so a step off either end moves the top by nothing - which is the whole of
        // the hard stop, and needs no case of its own.
        val backFirst = if (offset < ceiling) windowFirst(windowAt(anchor, offset + 1, budget, fitWidth)) else first
        val forward = if (offset > 0) windowAt(anchor, offset - 1, budget, fitWidth) else here
        val stepRowsBack = rowsBetween(backFirst, first, fitWidth)
        val stepRowsForward = rowsBetween(first, windowFirst(forward), fitWidth)
        return TerminalFrame(
            lines = window,
            firstLine = first,
            totalLines = lines.size,
            // The window's own lower edge at rest, which is the anchor: the frame is cut from `anchor`
            // back by `offset`, so this is the line the live screen ends on. See [TerminalFrame.liveEnd]
            // for why the buffer's line count cannot stand in for it.
            liveEnd = anchor,
            cursorRow = if (relativeCursor in 0 until take) relativeCursor else -1,
            cursorColumn = cursorColumn.coerceIn(0, columns - 1),
            columns = columns,
            rows = rows,
            contentColumns = content,
            cursorVisible = cursorVisible,
            revision = revision,
            title = title,
            alternateScreen = primary != null,
            positionalScreen = positionalScreen,
            synchronizedOutput = synchronizedOutput,
            applicationCursorKeys = applicationCursorKeys,
            applicationKeypad = applicationKeypad,
            bracketedPaste = bracketedPaste,
            keyboardModes = keyboardModes(),
            cursorBlink = cursorBlink,
            scrollOffset = offset,
            scrollCeiling = ceiling,
            mouseMode = MouseModes.modeOf(mouseModes),
            mouseSgr = MouseModes.sgrOf(mouseModes),
            // Exactly the lines each step would add, so a drag that has not finished a step has real
            // text to slide into the strip it opens. The one above is the whole walk the back step would
            // make - not one line, because a short line can be replaced by several - and the one below is
            // taken until it covers the forward step.
            marginAbove = if (backFirst < first) {
                lines.subList(backFirst, first).map { it.toList() }
            } else {
                emptyList()
            },
            marginBelow = marginBelow(end, stepRowsForward, fitWidth),
            stepRowsBack = stepRowsBack,
            stepRowsForward = stepRowsForward,
        )
    }

    /** The window's `first` line and its `end`, as `[first, end)`, for an offset already clamped. */
    private fun windowAt(anchor: Int, offset: Int, budget: Int, fitWidth: Int): Long {
        var end = anchor - offset
        var first = if (fitWidth > 0) fittedStart(end, budget, fitWidth) else end - budget
        if (first <= 0) {
            // The window ran into the first line the terminal ever wrote. Fill it downward instead, so
            // the screen holds as much as it can rather than the cursor's line and nothing after it -
            // the state a freshly connected session is in, where the prompt is on row 2 and every row
            // below it is blank until the view has something to scroll.
            if (fitWidth > 0) end = fittedEnd(0, budget, fitWidth)
            first = 0
        }
        return (first.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
    }

    private fun windowFirst(window: Long): Int = (window ushr 32).toInt()

    private fun windowEnd(window: Long): Int = window.toInt()

    /** How many wrapped rows the lines `[from, to)` occupy at [width]. */
    private fun rowsBetween(from: Int, to: Int, width: Int): Int {
        var rows = 0
        var index = from.coerceAtLeast(0)
        val last = to.coerceAtMost(lines.size)
        while (index < last) {
            rows += displayRowCount(lines[index], windowExtent(index), width)
            index++
        }
        return rows
    }

    /**
     * The lines below the window, down to [enoughRows] wrapped rows of them, or to the last line.
     *
     * The overscan for a drag towards the live end. There is usually less of it than the step costs -
     * the newest line is where the cursor is, and nothing follows it - and that is not a shortfall to
     * paper over: past the end of the output there is genuinely nothing to reveal, and the screen shows
     * its own background there, exactly as it does at rest one row below a window that did not fill.
     */
    private fun marginBelow(end: Int, enoughRows: Int, width: Int): List<List<TerminalCell>> {
        if (enoughRows <= 0) return emptyList()
        val margin = ArrayList<List<TerminalCell>>(enoughRows)
        var rows = 0
        var index = end
        while (index < lines.size && rows < enoughRows) {
            val line = lines[index]
            margin += line.toList()
            rows += displayRowCount(line, windowExtent(index), width)
            index++
        }
        return margin
    }

    /**
     * The first line of the window ending at [end]: walked up from the cursor's line while the wrapped
     * rows still fit [budget].
     *
     * The line at `end - 1` is always kept whatever its height, because a line taller than the whole
     * window has to be shown in part and the part worth showing is the end, where the cursor is. Every
     * line above it is kept only while the screen has room for it whole - which is what makes the top
     * row of the screen the first row of a line rather than the middle of one.
     */
    private fun fittedStart(end: Int, budget: Int, width: Int): Int {
        var rows = 0
        var index = end
        while (index > 0) {
            val height = displayRowCount(lines[index - 1], windowExtent(index - 1), width)
            if (index < end && rows + height > budget) break
            rows += height
            index--
        }
        return index
    }

    /**
     * The line after the last one that fits [budget] wrapped rows, starting at [start] - the window's
     * other end, for the case where the cursor is near the top of a tall screen and the screen has to
     * be filled downward.
     */
    private fun fittedEnd(start: Int, budget: Int, width: Int): Int {
        var rows = 0
        var index = start
        while (index < lines.size) {
            val height = displayRowCount(lines[index], windowExtent(index), width)
            if (index > start && rows + height > budget) break
            rows += height
            index++
        }
        return index
    }

    /**
     * The largest offset [frame] will honour for a window anchored at [anchor].
     *
     * [maxScrollOffset]'s own arithmetic, shared so the two cannot disagree: an offset the view is
     * allowed to set that the frame then clamps away is a scroll that stops responding at the point the
     * user is trying hardest to reach - the top of the scrollback.
     */
    private fun windowCeiling(anchor: Int, budget: Int, width: Int): Int =
        if (width > 0) (anchor - fittedEnd(0, budget, width)).coerceAtLeast(0) else anchor - budget

    /**
     * Whether these lines belong to a program rather than to the shell's scrollback.
     *
     * True on the alternate screen and on the primary screen under cursor addressing - `vim`, `less`,
     * `htop` and `top`. A program that has taken the screen has already decided what is on every row,
     * so Page Up belongs to it rather than to the history, and a drag belongs to whatever mouse
     * reporting it turned on.
     */
    val programOwnsScreen: Boolean
        @Synchronized get() = primary != null || positionalScreen

    /**
     * How far this window can be scrolled back: [windowCeiling], or nothing at all on the alternate
     * screen.
     *
     * The alternate screen has no history at all. It is a fresh list the program paints over for as
     * long as it is up, so scrolling it shows stale frames of the program's own drawing, misaligned
     * with the cursor it keeps positioning, and there is no way back to the live bottom except by
     * scrolling the same distance down again. It is not a view the reader asked for and not one they
     * can navigate, so the ceiling is zero and a drag there does nothing - which is also what leaves
     * the gesture free for the wheel reports and arrow keys such a program does understand.
     *
     * A program addressing the *primary* screen - `top`, and the full-screen CLI tools that only
     * repaint their own bottom rows - keeps its ceiling, because the lines it leaves behind are the
     * session's real output and the reader reaches them the same way they reach anything else. What
     * makes that bearable is [programOwnsScreen] being asked separately: the keys and the gesture are
     * routed by *that*, so a scrollback that still exists does not hand Page Up back to the terminal.
     *
     * Both the frame and [maxScrollOffset] come through here, which is the point - the two used to
     * share [windowCeiling] alone, and a frame that had stopped scrolling while the view model still
     * believed there were thirty rows of scrollback would be a gesture that moved an offset nothing
     * drew.
     */
    private fun scrollCeiling(anchor: Int, budget: Int, width: Int): Int =
        if (primary != null) 0 else windowCeiling(anchor, budget, width)

    /**
     * How many columns of the line at [index] the view has to place: its painted width, plus the cell
     * being typed into when the cursor is on it, bounded by the line's own length.
     *
     * [dev.eclipse.ssh.terminal.paintedExtent]'s arithmetic, from the buffer rather than from a frame -
     * the frame does not exist yet when the window is being fitted, which is the whole reason these
     * two numbers have to be kept in step. The cursor's column is bounded the way [frame] bounds it in
     * the frame it returns, so the two agree to the column.
     */
    private fun windowExtent(index: Int): Int {
        val line = lines[index]
        val painted = terminalPaintedWidth(line)
        val withCursor = if (index == cursorRow && cursorVisible) {
            maxOf(painted, cursorColumn.coerceIn(0, columns - 1) + 1)
        } else {
            painted
        }
        return withCursor.coerceAtMost(line.size)
    }

    /**
     * Gives a shrinking viewport its rows back from the bottom, where they are blank, instead of
     * taking them off the top, where the output is.
     *
     * The screen is the *tail* of [lines] - `lines.size - rows` is its first row - so lowering [rows]
     * on its own moves the top of the screen *down* and turns everything above it into scrollback.
     * That is right for a full screen and completely wrong for an empty one, which is the state every
     * session starts in: the shell prints a banner and a prompt on the first two rows, and the very
     * next thing that happens is the view measuring itself and resizing from the default forty rows to
     * however many the device actually fits. Re-anchoring to the last thirty-odd lines put the only two
     * lines that had anything on them into the history, so a freshly connected terminal showed a blank
     * grid with its cursor off-screen, and stayed that way until enough output arrived to fill the
     * window. It looked exactly like a session that had connected and hung.
     *
     * A terminal shrinks by discarding blank rows from the bottom and only scrolls the top away once
     * the screen is genuinely full. Two things are therefore never removed: a row with anything on it,
     * because output below the cursor is still output, and the cursor's own row, because a shell that
     * has just been given a newline is about to print its prompt there.
     */
    private fun trimBlankRowsForShrink(target: ArrayList<MutableList<TerminalCell>>, cursorRow: Int) {
        while (target.size > rows && target.lastIndex > cursorRow && isBlank(target[target.lastIndex])) {
            target.removeAt(target.lastIndex)
        }
    }

    private fun isBlank(line: List<TerminalCell>): Boolean = line.all { it == BLANK_CELL }

    /**
     * One past the last line that paints anything, or 0 when nothing does.
     *
     * The floor [frame] puts under its window, so that a program whose cursor sits above its own last
     * line still gets that line onto the reader's screen. A line carrying only a background colour counts
     * as painting, which is what makes a `ESC [ 2 K` onto a coloured background keep the window over it.
     *
     * Cached on [revision] because the answer changes only when the buffer does, while [frame] asks for it
     * on every build - a publish and a repaint of one mutation are two. The walk is from the bottom and
     * stops at the first inked line, so its cost is the blank tail, not the buffer.
     */
    private fun bottomInkedEnd(): Int {
        if (inkCacheRevision == revision) return inkCacheBottom
        var index = lines.lastIndex
        while (index >= 0 && isBlank(lines[index])) index--
        inkCacheBottom = index + 1
        inkCacheRevision = revision
        return inkCacheBottom
    }

    private var inkCacheRevision = -1L
    private var inkCacheBottom = 0

    /**
     * The window's lower edge, in lines: the end of the screen once it has scrolled, and otherwise the
     * cursor's own line or the bottommost inked line, whichever is lower.
     *
     * The one expression of the anchor, because three things read it and they have to agree: [frame]
     * places the window from it, [maxScrollOffset] measures the ceiling from it, and [cursorLine] hands
     * it to `MainViewModel.pinScrollback`, which gives back the distance it moved so a scrolled-back view
     * stays still. Two of them disagreeing is a window and a ceiling that do not match - a stored offset
     * the frame then clamps away - or a view that slides while the user reads it.
     *
     * The two regimes are one question: *where does the live output end?* Before the screen has scrolled,
     * the buffer's blank tail is rows the shell has not written yet - the pty is 60 rows tall and a fresh
     * session has five lines on it - and the window has to end at the last of them, or the badge counts
     * the unwritten rows as lines below and the view scrolls back into nothing. Once `lines.size` exceeds
     * [rows] the tail is the *screen*, blank or not, and the live output reaches the end of the buffer.
     *
     * That second case is not a refinement, it is the whole of the divergence this rule was measured on.
     * A screen that has scrolled and whose last lines are blank - `ESC [ 2 J` on a session with history,
     * or any program that homes its cursor and repaints from the top - ends its output *above* the end of
     * the buffer, so an ink floor leaves the window short and the whole screen is drawn one row lower
     * than the reference draws it: the line that scrolled off is still on the reader's screen and the
     * newest row of the program's own drawing is off it. Measured against tmux at 52x60 on
     * `reproductions/cursor-moves.py`: reads 3 to 5 handed the view `[0, 60)` where tmux's screen is
     * `[1, 61)`, and read 15 handed it `[90, 150)` where the screen is `[91, 151)` - the cursor reported
     * one row low in every one of them.
     *
     * The cursor is not a bound of its own in the scrolled case, and does not need to be: the screen is
     * the last [rows] lines and the cursor is always inside it, so the window that is the screen has the
     * cursor in it - which is what the ink floor was added for, and it still does that job for the screen
     * that has not scrolled yet.
     */
    private fun windowAnchorEnd(): Int =
        if (lines.size > rows) lines.size else maxOf(cursorRow + 1, bottomInkedEnd())

    /**
     * The viewport height the buffer is sized to, without building a frame to find out.
     *
     * Exists because reading it off [frame] was costing a whole viewport copy per call, and the two
     * callers that need it — pinning a scrolled-back view as output arrives, and clamping a stale
     * offset after a resize — run on the terminal's hot path at frame rate. `rows` itself stays
     * private: it is mutable state that only [resize] may change.
     */
    val viewportRows: Int
        @Synchronized get() = rows

    /**
     * How far back the view may scroll while showing [viewportRows] lines in a [windowColumns]-wide
     * window.
     *
     * Measured to the window's anchor rather than to the end of the buffer, because [frame] anchors
     * there: the distance that matters is from the anchor up to the first line of scrollback, and
     * measuring to the buffer's last line instead would hand the view a range of offsets whose upper
     * half draws exactly the same screen. A tall pty with a little output is where that shows - 960
     * offsets that all clamp to the top of the buffer is a scroll gesture that stops responding.
     *
     * [windowColumns] has to be the same width the frame was built with, and it is: the two share
     * [windowCeiling] rather than keeping the arithmetic twice. A view that could scroll to an offset
     * the frame then clamps away would simply stop moving at the top of the scrollback, which is the
     * one place a reader is certainly trying to reach.
     */
    @Synchronized
    fun maxScrollOffset(viewportRows: Int, windowColumns: Int = 0): Int {
        val budget = viewportRows.coerceIn(1, lines.size)
        val fitWidth = if (wrapsForDisplay(primary != null, positionalScreen, windowColumns)) windowColumns else 0
        val anchor = windowAnchorEnd().let { end ->
            if (fitWidth > 0) {
                end.coerceIn(1, lines.size)
            } else {
                end.coerceIn(budget, lines.size)
            }
        }
        return scrollCeiling(anchor, budget, fitWidth)
    }

    @Synchronized
    fun lineCount(): Int = lines.size

    /**
     * The line [frame] anchors its window at: the cursor's own line, or the bottommost inked line,
     * whichever is lower - see [windowAnchorEnd] for why the second is in it.
     *
     * The frame's window *ends* here, at `cursorLine - offset`, whatever its start is: the fitted start
     * for a wrapped window, `cursorLine - offset - viewportRows` for an unwrapped one. So this is the
     * single number that decides where the window sits, and moving it by one moves the window by one.
     * That is why the caller that keeps a scrolled-back view still ([MainViewModel.pinScrollback]) reads
     * it before and after feeding output and gives the difference back: the line count is the same
     * number in a session whose pty is exactly as tall as the buffer, and a different one in a session
     * whose pty is taller - where output moves this down the screen without adding a line at all.
     *
     * Synchronized and allocation-free for the same reason [viewportRows] is: it is read twice per
     * collector iteration, on the terminal's hot path.
     */
    val cursorLine: Int
        @Synchronized get() = windowAnchorEnd().coerceIn(1, lines.size)

    /** Whether a paste has to be wrapped in `ESC [ 200 ~` and `ESC [ 201 ~`. */
    @Synchronized
    fun bracketedPasteEnabled(): Boolean = bracketedPaste

    /**
     * DECCKM, for deciding what an arrow key sends. See [TerminalFrame.applicationCursorKeys].
     *
     * A direct read for the same reason as [viewportRows]: the caller is a keystroke, and taking this
     * off [frame] copied the whole viewport to look at one boolean.
     */
    @Synchronized
    fun applicationCursorKeysEnabled(): Boolean = applicationCursorKeys

    /**
     * The mouse tracking the remote program has asked for, and whether it wants SGR reports.
     *
     * Read off [frame] by the gesture, because the gesture branches on it in the middle of a drag and
     * the frame it is drawing is the one it has to agree with; these direct reads are for the callers
     * that need the answer without a frame, as [applicationCursorKeysEnabled] is for a keystroke.
     */
    @Synchronized
    fun mouseMode(): MouseMode = MouseModes.modeOf(mouseModes)

    @Synchronized
    fun mouseSgrEnabled(): Boolean = MouseModes.sgrOf(mouseModes)

    /**
     * The keyboard protocols in effect, for deciding what a key press sends. See
     * [TerminalFrame.keyboardModes], which carries the same value.
     *
     * Read directly rather than off [frame] because the caller is a keystroke and the frame may be a
     * publication behind: a program that pushes its flags at startup types into a terminal that has not
     * repainted yet, and the press has to be encoded under the flags it just set.
     */
    @Synchronized
    fun keyboardModes(): KeyboardModes = KeyboardModes(keyboardFlags, modifyOtherKeys)

    /**
     * The text of the cell range ([fromLine], [fromColumn]) up to but excluding ([toLine],
     * [toColumn]) - what "copy" puts on the clipboard.
     *
     * Addressed in absolute buffer lines so a selection survives new output arriving underneath it:
     * the numbers come from [TerminalFrame.firstLine], not from a screen row.
     *
     * Trimmed the same way [plainText] trims: each line loses its right padding, and blank lines at
     * the *end* of the selection are dropped while blank lines *inside* it are kept. Both halves of
     * that matter. A terminal row is a fixed-width array of cells, so every line the shell wrote is
     * followed by spaces out to the margin - copying those turns a two-word command into a
     * twenty-character one, and pasting it somewhere that cares (a diff, a YAML file, another shell)
     * carries the padding along. Blank rows at the end are the same problem one dimension up: a drag
     * on a phone is an imprecise gesture, the rows below the last line of output look identical to
     * the rows above it, and a selection that overshoots by a few rows should not silently append
     * newlines to what the user copied. Interior blanks are real content - a program that prints a
     * paragraph break meant it - so those stay.
     *
     * Agreeing with [plainText] is itself the point: the same region copied to the clipboard and
     * saved to a file has to come out the same, or the user has to learn which of the two to trust.
     */
    @Synchronized
    fun textIn(fromLine: Int, fromColumn: Int, toLine: Int, toColumn: Int): String {
        if (lines.isEmpty()) return ""
        val startLine = fromLine.coerceIn(0, lines.lastIndex)
        val endLine = toLine.coerceIn(0, lines.lastIndex)
        if (startLine > endLine) return ""
        val out = StringBuilder()
        for (index in startLine..endLine) {
            val line = lines[index]
            val from = if (index == startLine) fromColumn.coerceIn(0, line.size) else 0
            val to = if (index == endLine) toColumn.coerceIn(0, line.size) else line.size
            if (index > startLine) out.append('\n')
            val lineStart = out.length
            for (column in from until to) {
                // A wide character's right half contributes nothing: it is a column, not a character,
                // and copying it as the space it is stored as would put a space in the middle of
                // every CJK word the user selects.
                if (column > 0 && line[column - 1].wide) continue
                val cell = line[column]
                out.append(cell.value)
                // A mark is part of the character the user selected, not a cell of its own: an accent
                // dropped here is an accent dropped from what they paste.
                out.append(cell.combining)
            }
            var lineEnd = out.length
            while (lineEnd > lineStart && out[lineEnd - 1] == ' ') lineEnd--
            out.setLength(lineEnd)
        }
        // Each line is already right-trimmed, so a trailing blank row is now exactly one '\n'.
        var end = out.length
        while (end > 0 && out[end - 1] == '\n') end--
        out.setLength(end)
        return out.toString()
    }

    /**
     * The whole buffer as text, each line right-trimmed and trailing blank lines dropped.
     *
     * Written against a StringBuilder rather than as the two nested `joinToString`s it used to be.
     * That version allocated a `String` per *cell* - `Char.toString()` - plus one per line, so a full
     * 2 000-line scrollback cost around a quarter of a million short-lived objects every time it was
     * called, and it was called once per rendered frame. `plainText matches the straightforward
     * rendering` pins the result against the original expression so the two cannot drift.
     */
    @Synchronized
    fun plainText(): String {
        val out = StringBuilder(lines.size * (columns + 1))
        lines.forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            // Right-trim this line only, never back past the separator: the same scope
            // joinToString gave each line's own trimEnd().
            val lineStart = out.length
            line.forEachIndexed { column, cell ->
                // The right half of a wide character is a cell rather than a character, and the space
                // it is stored as is not part of the text - see [textIn].
                if (column > 0 && line[column - 1].wide) return@forEachIndexed
                out.append(cell.value)
                // The same cluster [textIn] writes, for the same reason: this is the text a person
                // reads, and the reason the two have to agree is spelled out above.
                out.append(cell.combining)
            }
            var lineEnd = out.length
            while (lineEnd > lineStart && out[lineEnd - 1].isWhitespace()) lineEnd--
            out.setLength(lineEnd)
        }
        // The outer trimEnd(), which is what removes the run of newlines left by blank lines at the
        // bottom of an otherwise empty screen.
        var end = out.length
        while (end > 0 && out[end - 1].isWhitespace()) end--
        out.setLength(end)
        return out.toString()
    }

    private fun consume(char: Char) {
        when (escapeState) {
            EscapeState.NORMAL -> when (char.code) {
                C0_ESC -> escapeState = EscapeState.ESC
                // VT and FF move down a line exactly as LF does on every terminal in practice.
                C0_LF, C0_VT, C0_FF -> lineFeed()
                // CR and BS are the two cursor moves that arrive as bare control bytes rather than as
                // a CSI, and they go through [setCursorColumn] so they advance the revision like every
                // other move. BS in particular is what a left arrow costs: readline's `cub1` is `^H`
                // while its `cuf1` is `ESC [ C`, so this branch is the only thing standing between a
                // left arrow and a cursor that visibly moves. Setting the column directly here left the
                // revision untouched, the frame was drawn from a layout memoized on that revision, and
                // the cursor sat still until the next character was typed.
                C0_CR -> setCursorColumn(0)
                C0_BS -> setCursorColumn(cursorColumn - 1)
                C0_HT -> tabForward(1)
                // A bell has no display; padding NULs must not print as blanks over real output.
                C0_NUL, C0_BEL -> Unit
                // SO / SI, which shift the alternate character set in and out. `ncurses` draws every
                // box and every menu border through these, and ignoring them printed the raw letters:
                // `lqqqk` across the top of a dialog instead of a line.
                C0_SO -> { shiftedOut = true; revision++ }
                C0_SI -> { shiftedOut = false; revision++ }
                else -> if (!char.isISOControl()) put(char)
            }
            EscapeState.ESC -> when (char) {
                '[' -> { controlBuffer.clear(); escapeState = EscapeState.CSI }
                ']' -> { controlBuffer.clear(); escapeState = EscapeState.OSC }
                // DCS / SOS / PM / APC: payloads this terminal has no use for, skipped as a unit so
                // that what follows them is still printed instead of being taken for text.
                'P', 'X', '^', '_' -> { controlBuffer.clear(); escapeState = EscapeState.STRING }
                // Any intermediate byte (0x20..0x2F) opens a longer sequence: `ESC ( B`, `ESC # 8`,
                // `ESC % G`. Recognising only `(` and `)` printed the byte that followed the others.
                in ' '..'/' -> {
                    escapeIntermediates.setLength(0)
                    escapeIntermediates.append(char)
                    escapeState = EscapeState.ESC_INTERMEDIATE
                }
                // SS3, the three-byte single-shift sequences. `ESC O` had no case at all, so its final
                // byte fell through to the printer: a program that sends `ESC O D` for cursor-left put
                // a literal `D` on screen instead of moving anything. See [consumeSs3].
                'O' -> escapeState = EscapeState.SS3
                '7' -> { saveCursor(); escapeState = EscapeState.NORMAL }
                '8' -> { restoreCursor(); escapeState = EscapeState.NORMAL }
                'c' -> reset()
                'D' -> { index(); escapeState = EscapeState.NORMAL }
                'E' -> { lineFeed(); escapeState = EscapeState.NORMAL }
                'M' -> { reverseIndex(); escapeState = EscapeState.NORMAL }
                'H' -> { setTabStop(cursorColumn); escapeState = EscapeState.NORMAL }
                '=' -> { applicationKeypad = true; escapeState = EscapeState.NORMAL }
                '>' -> { applicationKeypad = false; escapeState = EscapeState.NORMAL }
                else -> escapeState = EscapeState.NORMAL
            }
            EscapeState.ESC_INTERMEDIATE -> when {
                // A second intermediate extends the run rather than ending it: `ESC SP F`, `ESC ( 0`.
                char in ' '..'/' -> {
                    escapeIntermediates.append(char)
                    // The same bound as CSI and OSC: a run this long is corruption, not a sequence.
                    if (escapeIntermediates.length > MAX_CONTROL_LENGTH) abandonControlSequence()
                }
                char in '0'..'~' -> {
                    executeEscIntermediate(escapeIntermediates.toString(), char)
                    escapeIntermediates.setLength(0)
                    escapeState = EscapeState.NORMAL
                }
                // Any other control byte ends the sequence and is then handled as the control it is,
                // so the ESC that starts a new sequence over a truncated one still works.
                else -> {
                    escapeIntermediates.setLength(0)
                    escapeState = EscapeState.NORMAL
                    consume(char)
                }
            }
            EscapeState.SS3 -> {
                consumeSs3(char)
                escapeState = EscapeState.NORMAL
            }
            EscapeState.CSI -> {
                controlBuffer.append(char)
                if (char in '@'..'~') {
                    executeCsi(controlBuffer.toString())
                    controlBuffer.clear()
                    escapeState = EscapeState.NORMAL
                } else if (controlBuffer.length > MAX_CONTROL_LENGTH) {
                    // A CSI sequence only ends at a byte in '@'..'~', so digits and ';' accumulate
                    // until one arrives. Nothing bounded that: a server stuck mid-sequence, or a
                    // corrupted stream, could grow this StringBuilder until the process died of
                    // OutOfMemoryError. A real sequence is a handful of bytes, so abandoning one this
                    // long discards only input that was never going to parse.
                    abandonControlSequence()
                }
            }
            EscapeState.OSC -> when {
                char.code == C0_BEL -> { finishOsc(); escapeState = EscapeState.NORMAL }
                // ST, i.e. ESC backslash, arrives as the ESC state failing to recognise the backslash.
                char.code == C0_ESC -> { finishOsc(); escapeState = EscapeState.ESC }
                // An OSC that never terminates used to swallow every byte after it for the life of
                // the session. One stray "ESC ]" in a corrupted stream blanked the terminal
                // permanently; now the run gives up and output resumes.
                controlBuffer.length > MAX_CONTROL_LENGTH -> abandonControlSequence()
                else -> controlBuffer.append(char)
            }
            EscapeState.STRING -> when {
                // The one byte that can end a control string, and only as half of a pair: ST is `ESC \`,
                // so an ESC here is a *maybe* and the decision moves to [EscapeState.STRING_ESC]. tmux
                // frames a passthrough as `ESC P tmux; <payload>` with every ESC in the payload doubled
                // for exactly this reason. A parser that ends the string at the ESC instead prints the
                // rest of the payload - `[?25l`, `[>4;2m`, a colour reply - into the grid and advances
                // its cursor past where the far end believes it is, and a far end that disagrees about
                // the cursor never sends those cells again: the damage is permanent for the session.
                char.code == C0_ESC -> escapeState = EscapeState.STRING_ESC
                char.code == C0_BEL -> { controlBuffer.clear(); escapeState = EscapeState.NORMAL }
                controlBuffer.length > MAX_CONTROL_LENGTH -> abandonControlSequence()
                else -> controlBuffer.append(char)
            }
            // The byte after an ESC inside a control string, which is a decision this state holds for
            // exactly one character - across reads, like every other partial sequence. A backslash
            // completes ST and ends the string; anything else, a second ESC included, is taken with the
            // ESC as an unknown two-byte sequence and the string goes on. The payload is discarded
            // either way, so what the pair *meant* never has to be decided.
            EscapeState.STRING_ESC -> when (char) {
                '\\' -> { controlBuffer.clear(); escapeState = EscapeState.NORMAL }
                else -> escapeState = EscapeState.STRING
            }
        }
    }

    /**
     * SS3: `ESC O` and exactly one final byte, the cursor, keypad and function keys of a VT220.
     *
     * Deliberately a copy of the handful of [executeCsi] meanings rather than a translation into a CSI
     * string. SS3 carries no parameters at all, so every one of these moves by exactly one, and
     * feeding them through the parameter parser would mean inventing a `1` for a sequence that has no
     * syntax to hold one. Anything not listed here is consumed and dropped, which is the whole point:
     * before this existed `ESC O` fell out of the ESC state and its final byte was printed as text.
     */
    private fun consumeSs3(char: Char) {
        when (char) {
            'A' -> setCursorRow(cursorRow - 1)
            'B' -> setCursorRow(cursorRow + 1)
            'C' -> setCursorColumn(cursorColumn + 1)
            'D' -> setCursorColumn(cursorColumn - 1)
            'H' -> setCursorColumn(0)
            'F' -> setCursorColumn(columns - 1)
            else -> Unit
        }
    }

    /**
     * The escape sequences that carry an intermediate byte: `ESC # 8` (DECALN), `ESC % G` (UTF-8) and
     * the graphics designations `ESC ( 0`, `ESC ) 0`, `ESC * 0`, `ESC + 0`, plus the selector and
     * 96-character-set forms. Only the designations have something to obey; the rest are consumed
     * rather than printed, which is what a terminal that does not implement a sequence owes the stream.
     */
    private fun executeEscIntermediate(intermediate: String, final: Char) {
        when (intermediate) {
            // `ESC ( 0` is what ncurses draws every box and menu border with, and `ESC ( B` puts the
            // letters back.
            "(" -> { g0Graphics = final == '0'; revision++ }
            ")" -> { g1Graphics = final == '0'; revision++ }
            // The one sequence in this family with something to draw. #3, #4, #5 and #6 are the
            // double-width and double-height line attributes, which this display has no room for and
            // does not claim to have.
            "#" -> if (final == '8') decaln()
            else -> Unit
        }
    }

    /**
     * DECALN: fill every cell of the screen with an `E` in the current rendition, and home the cursor.
     * It writes the screen and not the history, and leaves the scroll region alone - the fill is the
     * whole of what the sequence certainly means, and moving the margins would be a guess.
     */
    private fun decaln() {
        val start = screenTop()
        for (index in start until (start + rows).coerceAtMost(lines.size)) {
            val line = lines[index]
            for (column in 0 until columns.coerceAtMost(line.size)) {
                line[column] = TerminalCell('E', style)
            }
        }
        setScreenRow(0)
        setCursorColumn(0)
        wrapPending = false
        revision++
    }

    private fun executeCsi(command: String) {
        if (command.isEmpty()) return
        val final = command.last()
        val raw = command.dropLast(1)
        // '?' marks a DEC private mode, '>' a secondary request, '<' the private-parameter form the
        // kitty keyboard protocol pops with, '=' the one it sets flags with, '!' and '$' the soft-reset
        // and request-mode families. Kept as the leading byte rather than stripped blindly, because the
        // answer to CSI c depends on which of them introduced it - and so does whether `u` is a cursor
        // restore or a keyboard query.
        val prefix = raw.firstOrNull()?.takeIf {
            it == '?' || it == '>' || it == '<' || it == '=' || it == '!' || it == '$'
        }
        val body = if (prefix == null) raw else raw.substring(1)
        // Clamped at zero, and that is a crash fix rather than tidiness. ECMA-48 parameter bytes are
        // 0x30..0x3F, so "-" is not part of a number at all - but it still reaches here inside the
        // body, because a CSI sequence ends only at a byte in '@'..'~' and "-" is not one. A negative
        // parameter then reversed every relative cursor move: `ESC [ -5 A` is "up by minus five", and
        // a one-sided lower bound happily left cursorRow five rows past the last line. The next
        // printable character indexed `lines` out of bounds, and that throw surfaces inside the
        // terminal output collector - a plain `launch`, so it reached the thread's default handler and
        // killed the process. One malformed sequence from a server was enough to crash the app.
        val params = body.takeIf { it.isNotEmpty() }
            ?.split(';')
            ?.map { it.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
            ?: emptyList()
        val first = params.firstOrNull()?.takeIf { it != 0 } ?: 1
        val private = prefix == '?'
        revision++
        when (final) {
            'A' -> setCursorRow(cursorRow - first)
            'B', 'e' -> setCursorRow(cursorRow + first)
            'C', 'a' -> setCursorColumn(cursorColumn + first)
            'D' -> setCursorColumn(cursorColumn - first)
            'E' -> { setCursorRow(cursorRow + first); setCursorColumn(0) }
            'F' -> { setCursorRow(cursorRow - first); setCursorColumn(0) }
            'G', '`' -> setCursorColumn((params.firstOrNull() ?: 1) - 1)
            'd' -> addressScreenRow((params.firstOrNull() ?: 1) - 1)
            'H', 'f' -> {
                addressScreenRow((params.getOrNull(0) ?: 1) - 1)
                setCursorColumn((params.getOrNull(1) ?: 1) - 1)
            }
            'I' -> tabForward(first)
            'Z' -> tabBackward(first)
            'J' -> eraseDisplay(params.firstOrNull() ?: 0)
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'X' -> eraseChars(first)
            // Repeat counts are clamped to what the buffer can hold. Past that every extra iteration
            // is a no-op on the result but not on the clock: `ESC [ 999999999 P` spun a billion list
            // splices while holding this object's monitor, which stalled the session's output for
            // minutes and looked exactly like a dead connection.
            'P' -> repeat(first.coerceAtMost(columns)) { deleteAtCursor() }
            '@' -> repeat(first.coerceAtMost(columns)) { insertAtCursor() }
            'L' -> insertLines(first.coerceAtMost(rows))
            'M' -> deleteLines(first.coerceAtMost(rows))
            'S' -> repeat(first.coerceAtMost(rows)) { scrollRegionUp() }
            'T' -> repeat(first.coerceAtMost(rows)) { scrollRegionDown() }
            'b' -> repeatLastCharacter(first.coerceAtMost(columns))
            // A prefix asks a different question with the same final byte: `ESC [ > 4 ; 2 m` is
            // modifyOtherKeys, not SGR dim+underline; `ESC [ ? u` / `ESC [ < u` / `ESC [ > 5 u` /
            // `ESC [ = 1 u` are the kitty keyboard protocol's query, pop, push and set. Only the bare
            // and the two keyboard forms are implemented; an unimplemented prefixed form is ignored -
            // which is what makes the program fall back to plain output.
            'm' -> when (prefix) {
                null -> applySgr(params.ifEmpty { listOf(0) })
                '>' -> if (params.firstOrNull() == ModifyOtherKeys.RESOURCE) {
                    setModifyOtherKeys(params.getOrNull(1) ?: ModifyOtherKeys.OFF)
                }
                '?' -> if (params.firstOrNull() == ModifyOtherKeys.RESOURCE) {
                    queueReply(csi(">4;${modifyOtherKeys}m"))
                }
                else -> Unit
            }
            'r' -> if (prefix == null) setScrollRegion(params)
            'g' -> clearTabStop(params.firstOrNull() ?: 0)
            'c' -> when (prefix) {
                // Secondary DA: "a VT220, firmware 95". Primary DA: "a VT100 with AVO", which is what
                // xterm answers and what every terminfo entry for xterm-256color expects.
                '>' -> queueReply(csi(">0;95;0c"))
                null -> queueReply(csi("?1;2c"))
                else -> Unit
            }
            // `CSI > 4 n` is xterm's other spelling of "modifyOtherKeys off", and the one a program
            // uses when it wants the resource unset rather than set to zero. Ignoring it would leave a
            // program's own request in force after it asked for it to be dropped.
            'n' -> when {
                prefix == '>' -> if (params.firstOrNull() == ModifyOtherKeys.RESOURCE) {
                    setModifyOtherKeys(ModifyOtherKeys.OFF)
                }
                !private -> deviceStatus(params.firstOrNull() ?: 0)
                else -> Unit
            }
            's' -> if (prefix == null) saveCursor()
            'u' -> when (prefix) {
                null -> restoreCursor()
                // The flags default to zero here rather than to `first`'s one: `ESC [ > u` is a legal
                // push of the empty set, and reading it as 1 would turn on a flag nobody asked for.
                '>' -> pushKeyboardFlags(params.firstOrNull() ?: 0)
                '<' -> popKeyboardFlags(params.firstOrNull() ?: 1)
                '=' -> setKeyboardFlags(params.getOrNull(0) ?: 0, params.getOrNull(1) ?: 1)
                '?' -> queueReply(csi("?${keyboardFlags}u"))
                else -> Unit
            }
            // XTVERSION. Answered with this emulator's own name rather than an xterm's: the name
            // travels on the wire and a program may branch on it, and claiming to be an xterm is a
            // claim this terminal cannot back with features. DECLL (`CSI Ps q`) and every other `q`
            // form stay unanswered, which is what a terminal that does not implement them says.
            'q' -> if (prefix == '>' && (params.firstOrNull() ?: 0) == 0) {
                queueReply(dcs(">|$TERMINAL_IDENTITY"))
            }
            'h' -> setMode(params, private, true)
            'l' -> setMode(params, private, false)
            // `CSI ! p` is DECSTR, the soft reset - stronger than the standard asks for, and that is
            // the safer direction, since it is sent by programs recovering from a confused terminal.
            // A `$` in front of the `p` makes it DECRQM instead, and that `$` is an intermediate byte
            // rather than one of the prefixes this parser reads, so it is looked for in the body.
            'p' -> when {
                prefix == '!' -> reset()
                body.endsWith('$') && (prefix == null || private) ->
                    body.dropLast(1).split(';').forEach { reportMode(it.toIntOrNull() ?: 0, private) }
                else -> Unit
            }
            // XTWINOPS. The size and title questions are answered, the requests to move, resize or
            // raise the user's window are refused, and a prefix makes it a different sequence.
            't' -> if (prefix == null) windowOperation(params)
        }
    }

    /**
     * DEC private modes, and the one ANSI mode this terminal implements.
     *
     * These used to be ignored wholesale, with a comment calling them "stateful UI concerns". They
     * are not: 1049 is what stops `vim` painting itself into the scrollback, 7 is what makes a long
     * line wrap, 25 is whether there is a cursor to draw, 2004 is whether a paste may contain a
     * newline without executing it, and 1 decides which bytes an arrow key has to send. Ignoring them
     * is most of what made the terminal a command form rather than a terminal.
     *
     * Every mouse mode is listed rather than matched by range, and [MouseModes.handled] is what makes
     * that total: a mode this terminal does not implement leaves [mouseModes] untouched, so an app
     * asking for something newer than this does not silently turn the ones it also asked for off.
     */
    private fun setMode(params: List<Int>, private: Boolean, enable: Boolean) {
        params.ifEmpty { listOf(0) }.forEach { mode ->
            if (!private) {
                // IRM. The only ANSI mode with a visible effect here; LNM is a line-discipline
                // concern that belongs to the pty, not to the display.
                if (mode == 4) insertMode = enable
                return@forEach
            }
            if (mode in MouseModes.handled) {
                mouseModes = MouseModes.withMode(mouseModes, mode, enable)
                return@forEach
            }
            when (mode) {
                1 -> applicationCursorKeys = enable
                6 -> {
                    originMode = enable
                    // DECOM homes the cursor: to the margin when it is on, to the screen when off.
                    setScreenRow(0)
                    setCursorColumn(0)
                }
                7 -> autoWrap = enable
                // The blink bit of DECSCUSR, which is what `cvvis` and `cnorm` are made of. Kept for
                // the session rather than cleared on leaving the alternate screen, unlike the mouse
                // modes: it belongs to the cursor, and the shell handed the screen back keeps it.
                12 -> cursorBlink = enable
                25 -> cursorVisible = enable
                2004 -> bracketedPaste = enable
                // DECSET 2026, synchronized output. The batch is held open until the reset; see
                // [synchronizedOutput] for what "held" means for a frame.
                2026 -> setSynchronizedOutput(enable)
                // 1047 and 1048 are the two halves that 1049 does together; all three are accepted so
                // an app using the older pair is not left drawing onto the primary screen.
                1047, 1049 ->
                    if (enable) enterAlternateScreen(withCursor = mode == 1049)
                    else leaveAlternateScreen(withCursor = mode == 1049)
                1048 -> if (enable) saveCursor() else restoreCursor()
                47 -> if (enable) enterAlternateScreen(withCursor = false) else leaveAlternateScreen(withCursor = false)
            }
        }
    }

    /**
     * Opens or closes a synchronized-output batch, `ESC [ ? 2026 h` and `l`.
     *
     * Nothing is done to the grid here, and nothing needs to be: the batch is a statement about when
     * the screen may be repainted, so the flag rides out on [TerminalFrame.synchronizedOutput] and the
     * publisher is what holds the frame back. The revision goes on advancing, for the reason on it.
     */
    private fun setSynchronizedOutput(enable: Boolean) {
        synchronizedOutput = enable
    }

    /**
     * The push stack of the screen currently up.
     *
     * The protocol requires one stack per screen, so a full-screen program that pushes its flags and
     * crashes before popping cannot leave the shell's keyboard in the wrong mode - the shell's own
     * flags live in the other stack and are put back when it gets the screen back.
     */
    private fun screenKeyboardStack(): ArrayList<Int> =
        if (primary == null) keyboardStack else alternateKeyboardStack

    /**
     * `ESC [ > flags u`: save the flags in effect and adopt [flags].
     *
     * Only the bits in [KittyKeyboard.SUPPORTED] are stored. The rest ask for something this terminal
     * cannot send - see that constant - and a flag kept but not honoured is worse than one refused,
     * because the program stops using its fallback.
     */
    private fun pushKeyboardFlags(flags: Int) {
        val stack = screenKeyboardStack()
        if (stack.size >= KittyKeyboard.MAX_DEPTH) stack.removeAt(0)
        stack.add(keyboardFlags)
        keyboardFlags = flags and KittyKeyboard.SUPPORTED
    }

    /**
     * `ESC [ count u`: put back the flags saved by that many pushes.
     *
     * An empty stack means the default - no flags - and that is the whole of the reset rule: the value
     * the bottom-most push saved is some program's, not the default, so it is not what an emptied stack
     * falls back to. The loop cannot spin on a huge count, because it is bounded by the stack, which is
     * bounded by [KittyKeyboard.MAX_DEPTH].
     */
    private fun popKeyboardFlags(count: Int) {
        val stack = screenKeyboardStack()
        var remaining = count
        while (remaining > 0 && stack.isNotEmpty()) {
            keyboardFlags = stack.removeAt(stack.size - 1)
            remaining--
        }
        if (stack.isEmpty()) keyboardFlags = 0
    }

    /**
     * `ESC [ = flags ; mode u`, the form that sets flags without touching the stack.
     *
     * Mode 1 assigns, 2 sets the named bits and leaves the others alone, 3 clears them; an unknown mode
     * is treated as 1 rather than ignored, since 1 is the default for an omitted parameter and a
     * program asking for a mode this terminal has never heard of still wants the flags it named.
     */
    private fun setKeyboardFlags(flags: Int, mode: Int) {
        val named = flags and KittyKeyboard.SUPPORTED
        keyboardFlags = when (mode) {
            2 -> keyboardFlags or named
            3 -> keyboardFlags and named.inv()
            else -> named
        }
    }

    /**
     * `ESC [ > 4 ; level m`, xterm's modifyOtherKeys.
     *
     * Clamped rather than stored as sent: the levels above [ModifyOtherKeys.ALL] mean nothing, and a
     * value kept outside the defined range would reach the encoder. No per-screen copy is kept, unlike
     * the kitty flags - xterm's is a terminal-wide resource, and no program expects it to change when
     * the alternate screen comes and goes.
     */
    private fun setModifyOtherKeys(level: Int) {
        modifyOtherKeys = level.coerceIn(ModifyOtherKeys.OFF, ModifyOtherKeys.ALL)
    }

    private fun enterAlternateScreen(withCursor: Boolean) {
        if (primary != null) return
        if (withCursor) saveCursor()
        primary = PrimaryScreen(
            lines = lines,
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
        )
        lines = ArrayList(rows)
        repeat(rows) { lines += blankLine() }
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        positionalScreen = false
        topMargin = 0
        bottomMargin = rows - 1
        // The alternate screen has a keyboard mode of its own, which starts empty and is put aside
        // with the rest of the primary screen. A full-screen program therefore never inherits the flags
        // of the shell it was started from, and the shell gets its own back on the way out. Its stack is
        // emptied for the same reason: whatever the last program pushed and never popped is state no
        // later pop can reach, and leaving it would restore a mode of that program's making.
        primaryKeyboardFlags = keyboardFlags
        keyboardFlags = 0
        alternateKeyboardStack.clear()
    }

    private fun leaveAlternateScreen(withCursor: Boolean) {
        val saved = primary ?: return
        primary = null
        lines = saved.lines
        // The screen may have been resized while the alternate one was up; resize() keeps the stored
        // lines in step, but a fresh height still has to be topped up here.
        while (lines.size < rows) lines += blankLine()
        trimBlankRowsForShrink(lines, saved.cursorRow)
        normalizeScreen(lines)
        topMargin = saved.topMargin.coerceIn(0, rows - 1)
        bottomMargin = saved.bottomMargin.coerceIn(topMargin, rows - 1)
        cursorRow = saved.cursorRow.coerceIn(0, lines.lastIndex)
        cursorColumn = saved.cursorColumn.coerceIn(0, columns - 1)
        wrapPending = false
        positionalScreen = false
        // The program that had the screen is gone, and whatever it turned on went with it. A program
        // that exits without disabling its own mouse reporting would otherwise leave the terminal
        // reporting every drag to the shell. The same goes for the keyboard flags it pushed, and the
        // primary screen's own are what the shell gets back.
        mouseModes = emptySet()
        keyboardFlags = primaryKeyboardFlags
        if (withCursor) restoreCursor()
    }

    private fun setScrollRegion(params: List<Int>) {
        val top = ((params.getOrNull(0) ?: 1) - 1).coerceIn(0, rows - 1)
        val bottom = ((params.getOrNull(1)?.takeIf { it != 0 } ?: rows) - 1).coerceIn(0, rows - 1)
        // A region needs at least two rows to scroll; anything else resets to the full screen, which
        // is what xterm does rather than accepting an inverted or degenerate pair.
        if (top >= bottom) {
            topMargin = 0
            bottomMargin = rows - 1
        } else {
            topMargin = top
            bottomMargin = bottom
        }
        setScreenRow(0)
        setCursorColumn(0)
    }

    private fun deviceStatus(request: Int) {
        when (request) {
            5 -> queueReply(csi("0n"))
            // Reported relative to the scroll region when origin mode is on, exactly as it is set.
            6 -> {
                val row = cursorRow - screenTop() - if (originMode) topMargin else 0
                queueReply(csi("${row + 1};${cursorColumn + 1}R"))
            }
        }
    }

    /**
     * DECRQM: `CSI ? Ps $ p` asks about a DEC private mode, `CSI Ps $ p` about an ANSI one, and the
     * answer is `CSI [?] Ps ; Pm $ y`.
     *
     * It is asked in order to decide what to send next, so the answer has to be true rather than
     * flattering: a mode reported as set is one a program will go on to use. Only modes this terminal
     * keeps a state for are reported as set or reset; everything else answers [MODE_UNRECOGNIZED],
     * including 1048, which saves a cursor rather than switching anything.
     */
    private fun reportMode(mode: Int, private: Boolean) {
        val state = when {
            !private -> if (mode == 4) onOff(insertMode) else MODE_UNRECOGNIZED
            mode in MouseModes.handled -> onOff(mode in mouseModes)
            else -> when (mode) {
                1 -> onOff(applicationCursorKeys)
                6 -> onOff(originMode)
                7 -> onOff(autoWrap)
                12 -> onOff(cursorBlink)
                25 -> onOff(cursorVisible)
                2004 -> onOff(bracketedPaste)
                // Answered from the latch rather than claimed as supported: a program that asks and
                // is told 1 goes on to wrap its repaint in a batch, which is the behaviour the latch
                // exists for.
                2026 -> onOff(synchronizedOutput)
                // 47, 1047 and 1049 are one state here: enterAlternateScreen is what all three call,
                // and reporting them apart would claim a distinction this terminal does not draw.
                47, 1047, 1049 -> onOff(primary != null)
                else -> MODE_UNRECOGNIZED
            }
        }
        val prefix = if (private) "?" else ""
        queueReply(csi("$prefix$mode;$state\$y"))
    }

    private fun onOff(on: Boolean) = if (on) MODE_SET else MODE_RESET

    /**
     * XTWINOPS, `CSI Ps ; Ps ; Ps t`.
     *
     * The rule is the protocol's rather than a taste: a question whose answer this terminal knows is
     * answered, and a request to do something to a window it does not own is refused by being ignored.
     * So `18 t` and `19 t` report sizes, `21 t` reports the title, and 22 and 23 save and restore it.
     * The rest ask for what does not exist here or ask the remote to act on the user's display: 11, 13,
     * 14, 16 and 20 want window state, screen pixels or an icon label (this terminal has one name, not
     * two), and 1, 2, 3, 4, 5, 6, 7, 8, 9 and 24 want it de-iconified, iconified, moved, resized,
     * raised, lowered, refreshed, maximized or given a different number of lines by whoever is on the
     * other end of the connection.
     */
    private fun windowOperation(params: List<Int>) {
        when (params.firstOrNull() ?: 0) {
            // Rows before columns, in both: `CSI 8 ; rows ; cols t` for the text area and
            // `CSI 9 ; rows ; cols t` for the screen, which is the order xterm answers in.
            18 -> queueReply(csi("8;$rows;$columns" + "t"))
            19 -> queueReply(csi("9;$rows;$columns" + "t"))
            // `OSC l <title> ST`. A terminal whose title has never been set reports an empty one
            // rather than staying silent: it was asked a question it can answer.
            21 -> queueReply(osc("l${title ?: ""}"))
            22 -> saveTitle(params.getOrNull(1) ?: 0)
            23 -> restoreTitle(params.getOrNull(1) ?: 0)
            else -> Unit
        }
    }

    /**
     * `CSI 22 t` saves the title, `CSI 23 t` puts it back. The parameter picks xterm's two stacks: 0
     * saves or restores both, 1 the icon one, 2 the window one. Both are kept, all six combinations
     * are handled, and any other parameter is nothing.
     *
     * The title saved can be "no title at all", and restoring that puts the tab back to its own label
     * - that is the state the save recorded, which is the point of saving it. A restore from an empty
     * stack changes nothing, so a program that restores more often than it saves cannot reach a title
     * this terminal never held, and the stacks are bounded so it cannot grow one either.
     */
    private fun saveTitle(kind: Int) {
        if (kind == 0 || kind == 1) pushTitle(iconTitles)
        if (kind == 0 || kind == 2) pushTitle(windowTitles)
    }

    private fun restoreTitle(kind: Int) {
        if (kind == 0 || kind == 1) popTitle(iconTitles)
        if (kind == 0 || kind == 2) popTitle(windowTitles)
    }

    private fun pushTitle(stack: ArrayList<String?>) {
        if (stack.size < MAX_TITLE_STACK) stack += title
    }

    private fun popTitle(stack: ArrayList<String?>) {
        if (stack.isNotEmpty()) title = stack.removeAt(stack.lastIndex)
    }

    private fun queueReply(reply: String) {
        // Bounded for the same reason the control buffer is: a remote that asks for status faster
        // than the channel drains must not be able to grow this without limit.
        if (pendingReplies.size < MAX_PENDING_REPLIES) pendingReplies += reply
    }

    private fun finishOsc() {
        // OSC 0 and 2 set the window title, which the session tab shows; 10 and 11 ask what this
        // terminal's default colours are. Everything else - hyperlinks, the palette and cursor-colour
        // queries, and OSC 52 in particular - is discarded rather than acted on: 52 lets a remote host
        // write the *local* clipboard, which is not a capability this app is willing to hand to a
        // server it has just connected to.
        val payload = controlBuffer.toString()
        controlBuffer.clear()
        val separator = payload.indexOf(';')
        if (separator <= 0) return
        val code = payload.substring(0, separator).toIntOrNull() ?: return
        val rest = payload.substring(separator + 1)
        when (code) {
            0, 2 -> {
                title = rest.take(MAX_TITLE_LENGTH).takeIf { it.isNotBlank() }
                revision++
            }
            // Only `?` is a query. A payload that is a colour is a *set*, and this terminal does not
            // take one: the theme is the user's, chosen in settings, and a remote repainting the screen
            // it was given for the length of a session is a remote changing the user's display.
            10, 11 -> if (rest == "?") answerColorQuery(code)
            else -> Unit
        }
    }

    /**
     * OSC 10 and 11 ask for the default foreground and background, and the reply is `OSC Ps ;
     * rgb:RRRR/GGGG/BBBB ST` - the shape OSC 4 uses for the palette, each 8-bit component widened to
     * sixteen bits by repeating it, so 0xAB is 0xABAB.
     */
    private fun answerColorQuery(code: Int) {
        val colors = themeColors?.invoke() ?: return
        val argb = if (code == COLOR_QUERY_FOREGROUND) colors.foreground else colors.background
        queueReply(osc("$code;rgb:" + rgb16(argb, 16) + "/" + rgb16(argb, 8) + "/" + rgb16(argb, 0)))
    }

    /**
     * One 8-bit component of an ARGB colour as four hex digits.
     *
     * The mask matters: a theme colour arrives as a signed Int, and shifting a negative one right
     * would smear its sign bits over the low byte without it.
     */
    private fun rgb16(argb: Int, shift: Int): String {
        val pair = ((argb shr shift) and 0xFF).toString(16).uppercase().padStart(2, '0')
        return pair + pair
    }

    private fun applySgr(params: List<Int>) {
        var index = 0
        var next = style
        while (index < params.size) {
            when (val code = params[index]) {
                0 -> next = TerminalStyle()
                1 -> next = next.copy(bold = true)
                2 -> next = next.copy(dim = true)
                3 -> next = next.copy(italic = true)
                4 -> next = next.copy(underline = true)
                // 5 and 6 are blink, recorded as nothing on purpose: a blinking cell would have to
                // invalidate the view twice a second for as long as it is on screen, which on a
                // low-end device is a measurable battery cost for an effect most terminals now
                // render as bold anyway.
                7 -> next = next.copy(inverse = true)
                8 -> next = next.copy(hidden = true)
                9 -> next = next.copy(strikethrough = true)
                21, 22 -> next = next.copy(bold = false, dim = false)
                23 -> next = next.copy(italic = false)
                24 -> next = next.copy(underline = false)
                27 -> next = next.copy(inverse = false)
                28 -> next = next.copy(hidden = false)
                29 -> next = next.copy(strikethrough = false)
                39 -> next = next.copy(foreground = TerminalColor())
                49 -> next = next.copy(background = TerminalColor())
                in 30..37, in 90..97 -> next = next.copy(foreground = TerminalColor(ansiColor(code)))
                in 40..47, in 100..107 -> next = next.copy(background = TerminalColor(ansiColor(code - 10)))
                38, 48 -> {
                    val isForeground = code == 38
                    when (params.getOrNull(index + 1)) {
                        5 -> params.getOrNull(index + 2)?.let { color ->
                            val terminalColor = TerminalColor(ansi256(color))
                            next = if (isForeground) next.copy(foreground = terminalColor) else next.copy(background = terminalColor)
                            index += 2
                        }
                        2 -> if (index + 4 < params.size) {
                            val rgb = (params[index + 2].coerceIn(0, 255) shl 16) or
                                (params[index + 3].coerceIn(0, 255) shl 8) or params[index + 4].coerceIn(0, 255)
                            val terminalColor = TerminalColor(TerminalColor.TRUE_COLOR_MASK or rgb)
                            next = if (isForeground) next.copy(foreground = terminalColor) else next.copy(background = terminalColor)
                            index += 4
                        }
                    }
                }
            }
            index++
        }
        style = next
    }

    /** Builds a CSI reply. Exists so no string in this file has to embed a control byte. */
    private fun csi(body: String): String = "$ESC[$body"

    /** Builds a DCS reply, which is what XTVERSION answers with, terminated by ST. */
    private fun dcs(body: String): String = "${ESC}P$body${ESC}\\"

    /** Builds an OSC reply, ended by ST so a reply can never be run into the text after it. */
    private fun osc(body: String): String = "$ESC]$body${ESC}\\"

    /**
     * A *relative* vertical move - CUU, CUD, CNL, CPL, and the two-byte `ESC A`/`ESC B` - clamped by the
     * screen and by the scrolling region.
     *
     * Every call site used to clamp for itself, in the one direction its own sequence moved in, and a
     * parameter that reversed that direction slipped past the missing end. `cursorRow` is an array index
     * into [lines] and the cost of getting it wrong is an exception, not a mis-drawn cell, so the bound
     * belongs here rather than in eleven separate expressions.
     *
     * The screen is the outer bound, and it is the one that was wrong: clamping against the whole buffer
     * let an up-move leave the screen for the scrollback, where the next character printed was written.
     * One row of history was corrupted, the character was invisible, and the row it belonged to was left
     * as it was - and a full-screen program that homes to the top row and then steps up reaches this on
     * its first frame. [setScreenRow] answers the other question - which row a program *names* - and is
     * bounded by the margin only under DECOM; a relative move is bounded by the margin always, which is
     * what tmux does and therefore what this emulator has to do with the sequences tmux composed.
     *
     * Both bounds are read from where the cursor *is*, not from where the move ends. A cursor below the
     * region moving up stops at the region's top rather than at the screen's, and one above the region
     * is bounded by the screen; the comparison is inclusive at the margin, so a cursor already *on* the
     * region's top row moving up stays there and one on its bottom row moving down stays there too.
     * Measured against tmux rather than read off the spec, because the cursor exactly at the margin is
     * the case a reading is most likely to get wrong - and the markers that show it are each overwritten
     * by the case that follows, so it took a fixture of its own to see them at all.
     */
    private fun setCursorRow(row: Int) {
        val top = screenTop()
        cursorRow = if (row < cursorRow) {
            val floor = top + topMargin
            if (cursorRow >= floor) row.coerceAtLeast(floor) else row.coerceAtLeast(top)
        } else {
            val ceiling = top + bottomMargin
            if (cursorRow <= ceiling) row.coerceAtMost(ceiling) else row.coerceAtMost(lines.lastIndex)
        }
        // A move of any kind cancels the pending wrap, the same way [setCursorColumn] does for a
        // horizontal one: the cell the cursor was left in is no longer where the next character goes.
        wrapPending = false
        revision++
    }

    /**
     * [setScreenRow], recording that a program placed its cursor there *by naming the row*.
     *
     * This, and nothing else, is what marks a screen painted - and what it keys on is the shape of the
     * sequence rather than the direction of the move. `ESC [ H` and `ESC [ d` say *which row* the cursor
     * belongs on, which is a statement about the whole screen that only a program meaning to own it
     * makes. A relative move says merely how far, and the one thing that emits those while a shell is on
     * screen is the line editor putting a wrapped command line back - which is not a repaint, and
     * treating it as one is what [positionalScreen] records the cost of.
     *
     * The one direction that is still excluded is down: a name for a row *below* the cursor is a jump
     * forward into unwritten space, which is output resuming somewhere else rather than a screen being
     * rewritten. Naming the row the cursor is already on is not excluded, because a program that homes
     * to the top-left on a screen it has nothing above is beginning a repaint, and `top`'s first frame
     * can be exactly that.
     *
     * DECOM, DECSTBM and DECRC call [setScreenRow] directly, because those move the cursor as a side
     * effect of something else and a shell drawing a two-line prompt with `ESC 7`/`ESC 8` is not a
     * program painting a screen.
     */
    private fun addressScreenRow(row: Int) {
        val from = cursorRow
        setScreenRow(row)
        if (cursorRow <= from) positionalScreen = true
    }

    private fun setCursorColumn(column: Int) {
        cursorColumn = column.coerceIn(0, columns - 1)
        wrapPending = false
        revision++
    }

    /** Absolute addressing: [row] counts from the top of the screen, or of the margin under DECOM. */
    private fun setScreenRow(row: Int) {
        val top = screenTop()
        val base = top + if (originMode) topMargin else 0
        val limit = top + if (originMode) bottomMargin else rows - 1
        cursorRow = (base + row).coerceIn(top.coerceAtMost(limit), limit)
        wrapPending = false
        revision++
    }

    /** The first line of the visible screen; everything before it is scrollback. */
    private fun screenTop(): Int = (lines.size - rows).coerceAtLeast(0)

    private fun saveCursor() {
        savedRow = cursorRow - screenTop()
        savedColumn = cursorColumn
        savedStyle = style
        savedGraphics = if (shiftedOut) g1Graphics else g0Graphics
    }

    private fun restoreCursor() {
        setScreenRow(savedRow.coerceIn(0, rows - 1))
        setCursorColumn(savedColumn)
        style = savedStyle
        if (shiftedOut) g1Graphics = savedGraphics else g0Graphics = savedGraphics
        // Its own bump, not just the ones the two moves above now carry: a program that saves, prints
        // nothing and restores has changed only the style, and a restore onto the cell the cursor was
        // already on would otherwise draw in the old style.
        revision++
    }

    /** Drops an escape sequence that has run too long to be real, and resumes printing. */
    private fun abandonControlSequence() {
        controlBuffer.clear()
        escapeIntermediates.setLength(0)
        escapeState = EscapeState.NORMAL
    }

    private fun put(char: Char) = putCodePoint(char.code)

    /**
     * Prints one character - one code point, which may be a surrogate pair - at the cursor.
     *
     * The zero-width arm comes first, and it is first for two separate reasons.
     *
     * It is the fix. A character the far side counts as no column must spend no cell here either, and
     * advancing for it is what puts this buffer's cursor one cell to the right of the program's. The
     * cursor column is what every later erase, move and cursor-position reply is measured from, so a
     * program whose `ESC [ K` starts one cell too far right leaves the cells to the left of it
     * standing - characters the user can still see after the program believes it erased them. The
     * character belongs on the cell before it instead.
     *
     * And it has to come *before* the pending wrap. A mark that arrives while a wrap is armed belongs
     * to the character that armed it, which is the cell the cursor is still standing on - the wrap is
     * exactly the state in which the cursor did not advance past it. Letting the mark consume the wrap
     * would put the *next* real character on the row below and break the line where the program did
     * not break it.
     */
    private fun putCodePoint(codePoint: Int) {
        if (terminalCharWidth(codePoint) == 0) {
            attachCombining(codePoint)
            return
        }
        // A character the far side counts two columns for takes two cells, and it took two long before
        // this printer existed: writing it into one cell and letting its ink spill over the neighbour
        // is what put this buffer's cursor one cell behind the program's, which is the defect the
        // width table's `2` arm is for. It is written by [putWide] instead, which reserves the second
        // cell and moves the cursor over both.
        if (terminalCharWidth(codePoint) == 2 && codePoint <= Char.MAX_VALUE.code) {
            putWide(codePoint.toChar())
            return
        }
        // An astral character is written as the two code units it is made of, one per cell. That is
        // what this printer has always done with them, and it is what keeps a composed emoji drawing
        // as a single glyph: the renderer joins the cells of a style run before it measures them, so
        // the two halves are shaped together even though they are stored apart. Two cells is also the
        // right count for the emoji the far side counts two columns for. A *narrow* astral character -
        // a mathematical alphanumeric, say - still gets two where the far side counts one, which is a
        // defect this change neither fixes nor worsens: a cell's base is one `Char`, and half a
        // surrogate pair is not a character.
        if (codePoint > Char.MAX_VALUE.code) {
            val pair = Character.toChars(codePoint)
            putUnit(pair[0])
            putUnit(pair[1])
            return
        }
        putUnit(codePoint.toChar())
    }

    /**
     * Writes a two-column character: its cell and the blank cell that is its right half.
     *
     * The pair is what the far side counted when it sent the character, so the cursor moves over both,
     * and the two are written together so that no state exists in which one is there without the other
     * - see [clearUnit] for the rule that keeps that true as cells are overwritten and erased.
     *
     * A character with no room left on the line goes to the next row whole rather than being split
     * across the margin, which is what every terminal does and what the alternative would cost: half a
     * glyph at the last column, and a cursor on a row the program did not put it on. With autowrap off
     * there is no next row to defer to, so it is placed as far right as it fits and overprints.
     */
    private fun putWide(char: Char) {
        if (wrapPending) {
            if (autoWrap) {
                cursorColumn = 0
                lineFeed()
            } else {
                cursorColumn = columns - 1
            }
            wrapPending = false
        }
        // Nothing to defer to when the line cannot hold the pair, so make room by moving to the row
        // below - the same thing a character at the last column does with autowrap on.
        if (autoWrap && cursorColumn >= columns - 1) {
            cursorColumn = 0
            lineFeed()
        }
        if (cursorColumn > columns - 2) cursorColumn = (columns - 2).coerceAtLeast(0)
        val glyph = translate(char)
        val line = lines[cursorRow]
        clearUnit(line, cursorColumn)
        clearUnit(line, cursorColumn + 1)
        line[cursorColumn] = TerminalCell(glyph, style, wide = true)
        if (cursorColumn + 1 < line.size) line[cursorColumn + 1] = TerminalCell(' ', style)
        lastPrinted = glyph
        if (cursorColumn + 2 >= columns) wrapPending = true else cursorColumn += 2
        revision++
    }

    /**
     * The blank an erase leaves behind: a space wearing what the current style can still show on one.
     *
     * The app tells the far side it is `xterm-256color`, whose `bce` promises that an erase fills with
     * the current background, so a program that paints one and then clears has every right to see it.
     * Dropping it is invisible in a shell, which rewrites the line, and permanent in a program that
     * repaints nothing - and in tmux, which sends only the cells it believes have changed.
     */
    private fun eraseBlank() = TerminalCell(' ', style.blanked)

    /**
     * Destroys the whole character at [column]: the cell, and the other half of a wide character.
     *
     * The one rule that keeps the two cells of a wide character from coming apart, and every erase and
     * every overwrite goes through it. A half-character is not a thing a screen can show: clearing the
     * left half of a wide glyph would leave the program's own idea of "two blank columns" with ink
     * still drawn across it, which is exactly the overlap a user sees and cannot explain.
     *
     * Deliberately narrow. It clears the cell asked for and, only when that cell is half of a wide
     * character, the other half; it does not clear neighbours of ordinary cells.
     */
    private fun clearUnit(line: MutableList<TerminalCell>, column: Int) {
        if (column !in line.indices) return
        if (line[column].wide) {
            if (column + 1 < line.size) line[column + 1] = eraseBlank()
            line[column] = eraseBlank()
            return
        }
        if (column > 0 && line[column - 1].wide) {
            line[column - 1] = eraseBlank()
            line[column] = eraseBlank()
            return
        }
        line[column] = eraseBlank()
    }

    /**
     * Reunites or destroys the wide characters on a line whose cells have moved under them.
     *
     * An insert, a delete or a truncation slides cells sideways, and a wide character's two halves can
     * end up apart - its right half pushed off the end of the line, or the pair pulled out from under
     * it so that the left half is now followed by a character rather than by its own blank. There is no
     * way to put that back together: the glyph is two columns wide and the cells no longer are. So the
     * character is dropped, which is what a terminal does when a program cuts one in half, and the
     * screen is left with the blanks the program asked for rather than with ink across two unrelated
     * cells.
     *
     * What counts as still having a right half is the blank cell the pair was written with and nothing
     * else. A left half followed by a *character* is the case this has to catch rather than allow: the
     * glyph is drawn across both columns, so a letter that slid into the second one would be drawn
     * underneath the ink of a character the program believes it deleted - the overlap a user sees and
     * cannot account for.
     */
    private fun repairLine(line: MutableList<TerminalCell>) {
        for (index in line.indices) {
            if (!line[index].wide) continue
            if (!hasRightHalf(line, index)) line[index] = TerminalCell()
        }
    }

    /** Whether the cell at [index] is still followed by the blank half its pair was written with. */
    private fun hasRightHalf(line: List<TerminalCell>, index: Int): Boolean {
        val next = line.getOrNull(index + 1) ?: return false
        return !next.wide && next.value == ' ' && next.combining.isEmpty()
    }

    /**
     * Writes one cell - one `Char`, one column - and moves the cursor past it.
     *
     * The single-character path, and the only one that consults the DEC graphics set: a character that
     * took a cell before [terminalCharWidth] existed still takes it in exactly the same way.
     */
    private fun putUnit(char: Char) {
        if (wrapPending) {
            if (autoWrap) {
                cursorColumn = 0
                lineFeed()
            } else {
                cursorColumn = columns - 1
            }
            wrapPending = false
        }
        val glyph = translate(char)
        val line = lines[cursorRow]
        if (insertMode) {
            // An insert inside a wide character pushes its right half along with everything else, and
            // what is left is a two-column glyph with a character under its right half. The character
            // it cuts in half goes, exactly as [repairLine] would have decided for it.
            if (cursorColumn in 1 until line.size && line[cursorColumn - 1].wide) {
                clearUnit(line, cursorColumn - 1)
            }
            line.add(cursorColumn, TerminalCell(glyph, style))
            while (line.size > columns) line.removeAt(line.lastIndex)
            repairLine(line)
        } else {
            // Overwriting either half of a wide character destroys the whole one: a glyph with a new
            // character under one of its two columns is the overlap this is all for.
            clearUnit(line, cursorColumn)
            line[cursorColumn] = TerminalCell(glyph, style)
        }
        lastPrinted = glyph
        if (cursorColumn >= columns - 1) wrapPending = true else cursorColumn++
        revision++
    }

    /**
     * Puts a zero-width character on the cell it belongs to, without moving the cursor.
     *
     * "The cell it belongs to" is the one the last printed character landed on. That is the cell under
     * the cursor while a wrap is armed, and the one before it otherwise: [putUnit] leaves the cursor
     * where it is when a character fills the last column, so a mark arriving after that has to look at
     * the cursor's own column rather than behind it.
     *
     * A mark with nothing under it is dropped rather than carried somewhere it does not belong. At
     * column zero there is no cell to its left at all, and a cell holding a blank is one the far side
     * counted as a space rather than as a base for it. This buffer also keeps no "last cell written"
     * that could reach back to the previous row, and inventing one would put the accent on whatever
     * happened to be there. Dropping it costs nothing in the column arithmetic, which is the point of
     * the change: a zero-width character moves the cursor on neither side of the pty.
     *
     * Deliberately does not touch [lastPrinted]. `CSI b` repeats "the last character printed", and
     * repeating an accent instead of the character under it would be a new way to be wrong.
     */
    private fun attachCombining(codePoint: Int) {
        val written = if (wrapPending) cursorColumn else cursorColumn - 1
        // A wide character's base is the cell it starts at, and the cursor stands one cell past that
        // once it has been written - so a mark arriving after one lands on the right half and has to
        // be handed back to the left. A mark on the right half alone would be drawn twice over two
        // columns and copied as a character of its own.
        val column = if (written > 0 && written in lines[cursorRow].indices && lines[cursorRow][written - 1].wide) {
            written - 1
        } else {
            written
        }
        val line = lines[cursorRow]
        if (column !in line.indices) return
        val cell = line[column]
        if (cell.value == ' ') return
        line[column] = cell.copy(combining = cell.combining + String(Character.toChars(codePoint)))
        revision++
    }

    /** Maps a printable byte through the DEC special graphics set when one is shifted in. */
    private fun translate(char: Char): Char {
        val graphics = if (shiftedOut) g1Graphics else g0Graphics
        if (!graphics || char < '_' || char > '~') return char
        return Char(DEC_SPECIAL_GRAPHICS[char - '_'])
    }

    private fun repeatLastCharacter(count: Int) {
        val char = lastPrinted ?: return
        repeat(count) { put(char) }
    }

    private fun tabForward(count: Int) {
        repeat(count.coerceIn(1, columns)) {
            var column = cursorColumn + 1
            while (column < columns - 1 && !tabStops.getOrElse(column) { false }) column++
            // Stops at the right margin rather than one past it, which is where a tab stops on a
            // real terminal. A tab must also never be what sets the pending-wrap flag: the next
            // printable character would then open a new row, breaking a line where the shell did not.
            cursorColumn = column.coerceAtMost(columns - 1)
        }
        wrapPending = false
        revision++
    }

    private fun tabBackward(count: Int) {
        repeat(count.coerceIn(1, columns)) {
            var column = cursorColumn - 1
            while (column > 0 && !tabStops.getOrElse(column) { false }) column--
            cursorColumn = column.coerceAtLeast(0)
        }
        wrapPending = false
        revision++
    }

    private fun resetTabStops() {
        tabStops = BooleanArray(columns) { it % TAB_WIDTH == 0 && it != 0 }
    }

    private fun setTabStop(column: Int) {
        if (column in tabStops.indices) tabStops[column] = true
    }

    private fun clearTabStop(mode: Int) {
        when (mode) {
            3 -> tabStops.fill(false)
            else -> if (cursorColumn in tabStops.indices) tabStops[cursorColumn] = false
        }
    }

    /** IND: down one row inside the scroll region, scrolling at its bottom, column unchanged. */
    private fun index() {
        val bottom = screenTop() + bottomMargin
        if (cursorRow >= bottom) {
            // Output has reached the bottom and pushed the screen up, which is a stream behaving like
            // one: whatever was painted by address is now history and the next line may be wrapped.
            positionalScreen = false
            scrollRegionUp()
        } else if (cursorRow < lines.lastIndex) {
            cursorRow++
        }
        wrapPending = false
        revision++
    }

    /**
     * A newline as it arrives from a pty, which is to say NEL: down one row *and* back to column zero.
     *
     * Strictly, LF alone should leave the column where it was and the CR in "\r\n" should be what
     * resets it. This is deliberately the forgiving reading, because a pty runs with ONLCR on and
     * therefore always sends the pair - so the CR is redundant here rather than load-bearing - while
     * a server that sends a bare LF and expects a full newline is common enough that the strict
     * reading produces the classic staircase, each line starting where the last one ended. [index]
     * is the strict form, and ESC D is the sequence that means it.
     */
    private fun lineFeed() {
        index()
        cursorColumn = 0
    }

    private fun reverseIndex() {
        val top = screenTop() + topMargin
        if (cursorRow <= top) scrollRegionDown() else cursorRow--
        wrapPending = false
        revision++
    }

    /**
     * Scrolls the active region up by one line.
     *
     * When the region is the whole screen and the primary buffer is up, the line leaving the top
     * becomes scrollback - that is the only path that ever grows the history. Inside a margin, or on
     * the alternate screen, the displaced line is discarded instead, which is what a scroll region
     * means and what keeps a full-screen app's redraws out of the user's history.
     */
    private fun scrollRegionUp() {
        val fullScreen = topMargin == 0 && bottomMargin == rows - 1
        if (fullScreen && primary == null) {
            lines += blankLine()
            trimScrollback()
            cursorRow = lines.lastIndex
        } else {
            val top = screenTop() + topMargin
            val bottom = screenTop() + bottomMargin
            lines.removeAt(top)
            lines.add(bottom, blankLine())
        }
        revision++
    }

    private fun scrollRegionDown() {
        val top = screenTop() + topMargin
        val bottom = screenTop() + bottomMargin
        lines.removeAt(bottom)
        lines.add(top, blankLine())
        revision++
    }

    /**
     * Evicts the oldest scrollback, keeping every index that pointed into it pointing at the same
     * text - [cursorRow] above all, which is an index into [lines] and not a screen row.
     */
    private fun trimScrollback() {
        // No scrollback at all while the alternate screen is up: it is a fixed window that a program
        // redraws in place, and anything pushed off its top is a fragment of a frame, not history.
        val limit = rows + if (primary == null) scrollbackLimit else 0
        while (lines.size > limit) {
            lines.removeAt(0)
            cursorRow = (cursorRow - 1).coerceAtLeast(0)
        }
    }

    private fun eraseDisplay(mode: Int) {
        val top = screenTop()
        val blank = eraseBlank()
        when (mode) {
            // Only the screen, never the scrollback: `clear` at a shell prompt must not throw away
            // the history the user scrolled back to read.
            2 -> for (row in top until lines.size) lines[row].fill(blank)
            0 -> { eraseLine(0); for (row in cursorRow + 1 until lines.size) lines[row].fill(blank) }
            1 -> { eraseLine(1); for (row in top until cursorRow) lines[row].fill(blank) }
            // `ESC [ 3 J` is the explicit "and drop the scrollback too" that `clear` sends on a modern
            // system, and the only sequence allowed to discard history. It also releases
            // [positionalScreen]: throwing the history away is a statement that nothing on the way out
            // needs preserving, which is the opposite of a program repainting a screen it means to keep.
            3 -> {
                while (lines.size > rows) { lines.removeAt(0); cursorRow = (cursorRow - 1).coerceAtLeast(0) }
                positionalScreen = false
            }
        }
        wrapPending = false
        revision++
    }

    private fun eraseLine(mode: Int) {
        val line = lines[cursorRow]
        when (mode) {
            // A whole line goes in one move, so there is no half-character to leave behind: nothing on
            // it survives to be overprinted by the other half of a wide glyph.
            2 -> line.fill(eraseBlank())
            1 -> for (i in 0..cursorColumn.coerceAtMost(columns - 1)) clearUnit(line, i)
            else -> for (i in cursorColumn.coerceIn(0, columns - 1) until columns) clearUnit(line, i)
        }
        wrapPending = false
        revision++
    }

    /** ECH: blank [count] cells from the cursor without moving it or shifting the rest of the line. */
    private fun eraseChars(count: Int) {
        val line = lines[cursorRow]
        val end = (cursorColumn + count).coerceAtMost(columns)
        for (i in cursorColumn until end) clearUnit(line, i)
        wrapPending = false
        revision++
    }

    private fun insertAtCursor() {
        lines[cursorRow].add(cursorColumn.coerceIn(0, columns - 1), TerminalCell())
        normalizeLine(lines[cursorRow])
        repairLine(lines[cursorRow])
    }

    private fun deleteAtCursor() {
        if (cursorColumn >= columns) return
        lines[cursorRow].removeAt(cursorColumn)
        lines[cursorRow] += TerminalCell()
        repairLine(lines[cursorRow])
    }

    /**
     * IL / DL, which move lines *within the scroll region* and leave the buffer exactly as long as it
     * was.
     *
     * The old pair grew and shrank `lines` instead, so `ESC [ L` inside a full-screen program pushed a
     * line into the scrollback that the program then overwrote somewhere else - the history filled
     * with fragments of a redraw, and the screen below the insertion point drifted by a row each time.
     */
    private fun insertLines(count: Int) {
        val top = screenTop() + topMargin
        val bottom = screenTop() + bottomMargin
        if (cursorRow < top || cursorRow > bottom) return
        repeat(count) {
            lines.removeAt(bottom)
            lines.add(cursorRow, blankLine())
        }
    }

    private fun deleteLines(count: Int) {
        val top = screenTop() + topMargin
        val bottom = screenTop() + bottomMargin
        if (cursorRow < top || cursorRow > bottom) return
        repeat(count) {
            lines.removeAt(cursorRow)
            lines.add(bottom, blankLine())
        }
    }

    private fun reset() {
        primary = null
        lines = ArrayList(rows)
        repeat(rows) { lines += blankLine() }
        cursorRow = 0
        cursorColumn = 0
        wrapPending = false
        positionalScreen = false
        // A soft reset ends any batch: the program that opened it is gone, and a latch left set would
        // hold the next program's output back forever.
        synchronizedOutput = false
        style = TerminalStyle()
        savedStyle = TerminalStyle()
        savedRow = 0
        savedColumn = 0
        savedGraphics = false
        escapeState = EscapeState.NORMAL
        controlBuffer.clear()
        topMargin = 0
        bottomMargin = rows - 1
        autoWrap = true
        originMode = false
        insertMode = false
        cursorVisible = true
        cursorBlink = true
        applicationCursorKeys = false
        applicationKeypad = false
        bracketedPaste = false
        mouseModes = emptySet()
        // A soft reset is a program saying "forget everything", so the keyboard flags go with the rest.
        // Only the screen that is up needs clearing: the reset ends any alternate screen rather than
        // returning to it, and entering one empties its stack.
        keyboardFlags = 0
        keyboardStack.clear()
        modifyOtherKeys = ModifyOtherKeys.OFF
        g0Graphics = false
        g1Graphics = false
        shiftedOut = false
        title = null
        lastPrinted = null
        resetTabStops()
        revision++
    }

    private fun blankLine() = MutableList(columns) { TerminalCell() }

    /**
     * Fits the *screen* to the current width, and deliberately does not touch the scrollback.
     *
     * [normalizeLine] pads a short line and truncates a long one, and it used to be applied to every
     * line in the buffer on every resize. Truncating the screen is right - the region a full-screen
     * program addresses has to be exactly [columns] wide, or a redraw at the new width would leave
     * the tail of the old one standing to the right of it. Truncating the history is data loss, and
     * on a phone it is constant: turning the device to portrait cut every line of the session so far
     * at the narrower width, and turning it back showed the wreckage rather than the output. What was
     * lost was whatever sat past that column - the end of a path, of a URL, of a hash, of a table's
     * last column - and it was lost from the copy buffer and the saved log too, because both read the
     * same cells. Scrollback is finished text: no program will ever address it again, so its width is
     * simply the width it was printed at, and every character of it survives a rotation.
     *
     * The screen is the *tail* of the list, so this has to run after the row count is settled.
     */
    private fun normalizeScreen(target: ArrayList<MutableList<TerminalCell>>) {
        for (index in (target.size - rows).coerceAtLeast(0) until target.size) normalizeLine(target[index])
    }

    private fun normalizeLine(line: MutableList<TerminalCell>) {
        while (line.size < columns) line += TerminalCell()
        while (line.size > columns) line.removeAt(line.lastIndex)
        // A truncation can take a wide character's right half off the end of the line, which leaves the
        // glyph drawn across the two cells of whatever comes after it in the renderer's run.
        repairLine(line)
    }

    /** The primary screen, set aside while the alternate one is on display. */
    private class PrimaryScreen(
        val lines: ArrayList<MutableList<TerminalCell>>,
        val cursorRow: Int,
        val cursorColumn: Int,
        val topMargin: Int,
        val bottomMargin: Int,
    )

    private enum class EscapeState { NORMAL, ESC, ESC_INTERMEDIATE, CSI, SS3, OSC, STRING, STRING_ESC }

    private companion object {
        /** What an untouched cell holds, for telling a row with nothing on it from one with a space. */
        val BLANK_CELL = TerminalCell()

        const val MAX_PENDING_REPLIES = 32
        const val MAX_TITLE_LENGTH = 200

        /** How many titles XTWINOPS may hold per stack. xterm's own limit, and it is enough. */
        const val MAX_TITLE_STACK = 10

        /** The two colour queries that are answered: OSC 10 for the foreground, OSC 11 the background. */
        const val COLOR_QUERY_FOREGROUND = 10
        const val COLOR_QUERY_BACKGROUND = 11

        /**
         * DEC special graphics, as code points indexed from '_' (0x5F) to '~' (0x7E).
         *
         * Code points rather than characters so the table cannot be silently mangled by anything that
         * re-encodes this file, and these are exactly the glyphs most likely to be mishandled: box
         * corners, the horizontal rules `ncurses` draws menus with, and the control pictures.
         */
        val DEC_SPECIAL_GRAPHICS = intArrayOf(
            0x0020, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )

        /**
         * The 16 ANSI colours.
         *
         * The bright half used to be aliased onto the normal half - 30 and 90 returned the same value -
         * so "bright black", which is what every diff, prompt and man page reaches for to dim text,
         * came out as pure black on a black background and was simply invisible. They are separate
         * entries now, and the normal half is darkened slightly so the pair is distinguishable.
         */
        fun ansiColor(code: Int): Int = when (code) {
            30 -> 0x00232838
            31 -> 0x00E05561
            32 -> 0x0038C172
            33 -> 0x00D9A343
            34 -> 0x005B8CFF
            35 -> 0x00C678DD
            36 -> 0x0056B6C2
            37 -> 0x00C7CEDB
            90 -> 0x00666F85
            91 -> 0x00FF5555
            92 -> 0x0050FA7B
            93 -> 0x00F1FA8C
            94 -> 0x006FA8FF
            95 -> 0x00FF79C6
            96 -> 0x008BE9FD
            else -> 0x00F8F8F2
        }

        /** The xterm 256-colour cube: 16 named, then 6x6x6, then 24 greys. */
        fun ansi256(index: Int): Int {
            val value = index.coerceIn(0, 255)
            if (value < 8) return ansiColor(30 + value)
            if (value < 16) return ansiColor(90 + (value - 8))
            if (value >= 232) {
                val gray = 8 + (value - 232) * 10
                return (gray shl 16) or (gray shl 8) or gray
            }
            val color = value - 16
            val red = (color / 36) * 51
            val green = ((color / 6) % 6) * 51
            val blue = (color % 6) * 51
            return (red shl 16) or (green shl 8) or blue
        }
    }
}

/**
 * How many columns of [line] carry anything: a glyph, a background colour, or an inverse.
 *
 * One function for the three places that need the answer - [TerminalFrame.contentColumns], the
 * renderer's search for the last cell worth drawing, and [terminalLayout]'s decision about whether a
 * line needs wrapping at all - because they have to agree. [TerminalFrame.contentColumns] is how far
 * the view lets the user pan, so a cell that one of them counts as painted and another does not is
 * either unreachable or a column of pannable emptiness.
 *
 * A blank counts when it has a background or an inverse: that is how a status bar's coloured padding
 * and a highlighted row in `less` are drawn, and stopping short of them would clip the bar.
 *
 * Scanned backwards because the answer is near the end: most of a terminal row is the run of blanks
 * after the text.
 *
 * A cell's base is the whole test, and nothing here has to look at [TerminalCell.combining]: a
 * zero-width character is only ever put on a cell that already holds a base, so a cell carrying one is
 * painted or blank exactly as its base is, and the pan extent is unchanged by it.
 *
 * A wide character's right half is painted: it is blank, but it is the second of the two columns the
 * glyph's ink covers. Stopping at the base would make that column unpannable, so a wide character on
 * the last column of a wrapped row would be drawn with half of it past the edge the user can reach.
 */
internal fun terminalPaintedWidth(line: List<TerminalCell>): Int {
    var index = line.size
    while (index > 0) {
        val cell = line[index - 1]
        if (cell.isPainted()) {
            return if (cell.wide && index < line.size) index + 1 else index
        }
        index--
    }
    return 0
}

/**
 * Whether [this] is a cell the renderer puts ink on: a character, or a blank carrying a background or
 * an inverse.
 *
 * The rule lives here rather than at each caller because the two callers have to agree. [terminalPaintedWidth]
 * stops on it to decide where a row's ink ends, and the render audit counts it to decide whether a screen
 * is empty; a foreground colour on a space is drawn by neither, so a screen that was painted only in
 * foreground on blanks has to read as blank to both.
 */
internal fun TerminalCell.isPainted(): Boolean =
    value != ' ' || !style.background.isDefault || style.inverse
