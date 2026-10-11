/*
 * The app's own terminal buffer, driven from a recording: frames in on stdin, the grid out on stdout.
 *
 * Frames are length-prefixed (4-byte big-endian) rather than one blob, and that is the point of the
 * tool. The app decodes a socket read at a time and feeds the buffer a read at a time, so a control
 * string split across two reads is a case of its own; a harness that glued the recording together
 * could not see one. [--frames N] stops after N frames, which is how the bisection in griddiff.py
 * finds the first prefix that already diverges.
 *
 * Usage: GridDump <columns> <rows> [--frames N] < recording.frames
 */
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TerminalCell
import dev.eclipse.ssh.terminal.Utf8StreamDecoder
import java.io.DataInputStream

fun main(args: Array<String>) {
    val columns = args.getOrNull(0)?.toIntOrNull() ?: 80
    val rows = args.getOrNull(1)?.toIntOrNull() ?: 24
    val limit = args.indexOf("--frames").takeIf { it >= 0 }
        ?.let { args.getOrNull(it + 1)?.toIntOrNull() } ?: Int.MAX_VALUE

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

/** A socket read is at most a few hundred KiB; anything larger is a mis-framed recording. */
private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
