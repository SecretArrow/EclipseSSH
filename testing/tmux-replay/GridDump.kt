/*
 * The app's own terminal buffer, driven from a recording: frames in on stdin, the grid out on stdout.
 *
 * Frames are length-prefixed (4-byte big-endian) rather than one blob, and that is the point of the
 * tool. The app decodes a socket read at a time and feeds the buffer a read at a time, so a control
 * string split across two reads is a case of its own; a harness that glued the recording together
 * could not see one. [--frames N] stops after N frames, which is how the bisection in griddiff.py
 * finds the first prefix that already diverges.
 *
 * [--attrs] adds a second, per-column view of the same rows: which columns share a style, and which
 * carry the default one. Text alone cannot see a cell that carries a colour and no glyph, which is
 * exactly the cell an erase leaves behind, so the style is compared as well - both colours and every
 * attribute this emulator models. See [styleKeys] for what the letters mean and griddiff.py for how
 * tmux's side of the same question is derived.
 *
 * Usage: GridDump <columns> <rows> [--frames N] [--attrs] < recording.frames
 */
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TerminalCell
import dev.eclipse.ssh.terminal.TerminalStyle
import dev.eclipse.ssh.terminal.Utf8StreamDecoder
import java.io.DataInputStream

fun main(args: Array<String>) {
    val columns = args.getOrNull(0)?.toIntOrNull() ?: 80
    val rows = args.getOrNull(1)?.toIntOrNull() ?: 24
    val limit = args.indexOf("--frames").takeIf { it >= 0 }
        ?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: Int.MAX_VALUE
    val attrs = args.contains("--attrs")

    val buffer = AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = 200)
    val decoder = Utf8StreamDecoder()
    // Answered, not acted on: a recording is a one-way stream, so a reply here is printed for a reader
    // and changes nothing. Kept visible because an unanswered query is a real difference between a
    // replay and the session it came from, and seeing one is the only way to notice it.
    buffer.responder = { reply -> println("# reply ${reply.replace("\u001B", "\\e")}") }

    val input = DataInputStream(System.`in`)
    var frame = 0
    while (frame < limit) {
        // A recording that is not length-prefixed decodes as an absurd length, and a bare ByteArray of
        // it dies on the heap instead of saying what is wrong.
        val length = runCatching { input.readInt() }.getOrNull() ?: break
        if (length < 0 || length > MAX_FRAME_BYTES) {
            System.err.println("frame ${frame + 1}: length $length is not a frame prefix; is this a recording?")
            return
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        frame++
        buffer.feed(decoder.decode(bytes))
    }
    buffer.feed(decoder.flush())

    val view = buffer.frame()
    println("# frames $frame columns $columns rows $rows cursor ${view.cursorRow},${view.cursorColumn} visible ${view.cursorVisible}")
    view.lines.forEach { println(rowText(it)) }
    if (attrs) view.lines.forEachIndexed { index, line -> println("# attrs $index ${styleKeys(line)}") }
}

private fun rowText(line: List<TerminalCell>): String {
    val text = StringBuilder()
    line.forEachIndexed { index, cell ->
        // A wide character's right half is a column rather than a character, and printing it would put a
        // space between the two halves that tmux's own capture-pane does not have.
        if (index > 0 && line[index - 1].wide) return@forEachIndexed
        text.append(cell.value).append(cell.combining)
    }
    return text.toString().trimEnd()
}

/**
 * One character per column: `.` for a cell in the default style, and a letter for each distinct style
 * the row uses, assigned in the order the row first uses it.
 *
 * The letters are labels, not colours - `a` is "the first style this row painted", whichever style
 * that is. That is deliberate, and it is what makes the two sides comparable: this emulator renders a
 * palette colour as the theme's own shade of it and tmux as the palette entry, so comparing the
 * colours themselves would compare two themes rather than two grids. What the labels do compare is the
 * *pattern*: which columns share a style, and which are default. A cell that should carry a colour or
 * an attribute and does not - an erase that filled with a default cell, which is the ghost this
 * harness exists to catch, or a style that leaked across a line - reads as `.` here and as a letter
 * there.
 *
 * A row with more than [LABELS] distinct styles is not a screen anyone drew; it fails loudly rather
 * than wrapping the alphabet and comparing two cells as equal that are not.
 */
private fun styleKeys(line: List<TerminalCell>): String {
    val labels = HashMap<String, Char>()
    val keys = StringBuilder(line.size)
    var previous: String? = null
    line.forEachIndexed { index, cell ->
        // The right half of a wide character is an ordinary blank carrying its left half's style, and it
        // takes that half's key for the same reason it takes its style: the two columns are one
        // character, and a key of its own would read as a blank where tmux reads the character.
        val key = if (index > 0 && line[index - 1].wide) previous!! else styleKey(cell)
        previous = key
        if (key == DEFAULT_STYLE_KEY) {
            keys.append('.')
        } else {
            check(labels.size < LABELS.length) { "a row with more than ${LABELS.length} styles" }
            keys.append(labels.getOrPut(key) { LABELS[labels.size] })
        }
    }
    return keys.toString()
}

private val DEFAULT_STYLE_KEY = styleKey(TerminalCell())

/**
 * A cell's style identity as a string: both colours and every attribute this emulator models.
 *
 * The spelling is this side's own and never compared against tmux's - only the equality relations
 * between a row's cells are, which is what the labels are for. What it does have to be is *faithful*:
 * two cells this emulator would paint differently must not share a key, and two it would paint the
 * same must not differ.
 *
 * That is why a true colour and a palette colour that resolve to the same bytes keep different keys.
 * This emulator resolves both to an ARGB value, so the resolved bytes alone would call them the same
 * style - and tmux, which keeps the palette index it was given, would report two. A false divergence
 * on a row that draws identically is the one thing a pattern comparison cannot afford, so the prefix
 * says which of the two was asked for.
 *
 * The foreground is dropped on a cell with no glyph, because the two sides legitimately disagree about
 * what a blank carries: an erase leaves this emulator's cell with the pen's foreground (invisible, and
 * kept so the blank joins the run beside it) and tmux's with the default. Comparing it would report that
 * disagreement as a defect on every erase in the corpus. The background is not dropped for the same
 * reason in reverse: it is the whole point of the comparison.
 *
 * The attributes are every field of [TerminalStyle] that paints a glyph, in a fixed order. SGR 5 and 6
 * are not among them: blink is recorded as nothing on purpose (see `applySgr`), so a blinking cell and
 * a still one are the same style here, and griddiff.py's reader ignores the same codes on tmux's side.
 */
private fun styleKey(cell: TerminalCell): String {
    val style = cell.style
    val fg = when {
        cell.value == ' ' -> "-"
        style.foreground.isDefault -> "-"
        style.foreground.isTrueColor -> "t%06X".format(style.foreground.rgb)
        else -> "c%06X".format(style.foreground.rgb)
    }
    val bg = when {
        style.background.isDefault -> "-"
        style.background.isTrueColor -> "t%06X".format(style.background.rgb)
        else -> "c%06X".format(style.background.rgb)
    }
    val flags = StringBuilder(7)
    for (set in listOf(
        style.bold, style.dim, style.italic, style.underline,
        style.inverse, style.hidden, style.strikethrough,
    )) {
        flags.append(if (set) '1' else '0')
    }
    return "$fg|$bg|$flags"
}

private const val LABELS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/** A socket read is at most a few hundred KiB; anything larger is a mis-framed recording. */
private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
