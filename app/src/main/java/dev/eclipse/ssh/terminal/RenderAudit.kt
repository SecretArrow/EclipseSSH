package dev.eclipse.ssh.terminal

/**
 * Which screen a frame belongs to, as far as the audit cares.
 *
 * The distinction is what keeps the audit quiet: an empty alternate screen is how full-screen programs
 * begin, and counting that as the primary screen going blank would bury the transitions worth reading.
 */
internal enum class ScreenKind { PRIMARY, ALTERNATE, POSITIONAL }

/**
 * The part of one published frame the audit records.
 *
 * Deliberately not the frame itself: [AnsiTerminalBuffer.frame] is up to thirty rows of cells, and an
 * export that kept a few hundred of those would be megabytes of the same mostly-unchanged text. These
 * five numbers are enough to say what the screen did without being able to say what was on it - which
 * is the session log's job, and it is already exported alongside.
 */
internal data class FrameSample(
    val revision: Long,
    val inkCells: Int,
    val cursorRow: Int,
    val cursorColumn: Int,
    val screen: ScreenKind,
)

/**
 * How many cells of [lines] the renderer would put ink on - the number that says whether a screen is
 * empty, and the one the audit's blank/repaint transitions turn on.
 *
 * A count of painted cells rather than [terminalPaintedWidth]'s trailing extent, because a row of
 * `a   b` is four columns wide and two cells of ink, and it is the second number that tells a screen
 * that lost its text from one that kept a skeleton of it.
 */
internal fun paintedCells(lines: List<List<TerminalCell>>): Int =
    lines.sumOf { line -> line.count { it.isPainted() } }

/**
 * The screen a frame belongs to, from the two flags the frame carries.
 *
 * Alternate wins over positional: a full-screen program on the alternate screen is drawn by address
 * too, and of the two names, "alternate" is the one that says why the primary screen's text is not on
 * display. See [ScreenKind] for why the distinction is kept at all.
 */
internal fun screenKind(alternateScreen: Boolean, positionalScreen: Boolean): ScreenKind = when {
    alternateScreen -> ScreenKind.ALTERNATE
    positionalScreen -> ScreenKind.POSITIONAL
    else -> ScreenKind.PRIMARY
}

/** The things worth writing down. Not errors: a record of what the screen did. */
internal enum class RenderNoteKind {
    /** The live screen went from painted to completely empty at a new revision. */
    BLANKED,

    /** The reverse of [BLANKED]. Kept because a blank screen that never comes back is a different bug. */
    REPAINTED,

    /** A published frame carried an older revision than the one before it. */
    REVISION_REWIND,

    /** Output kept arriving and the screen never changed once across the whole stretch. */
    OUTPUT_WITHOUT_FRAME,
}

internal data class RenderNote(
    val kind: RenderNoteKind,
    val atMillis: Long,
    /** How far into the session's output this happened, in the session log's own count. */
    val outputPosition: Long,
    val detail: String,
)

/**
 * A record of what the app's own frames did, for the bugs that only happen on a device.
 *
 * The rendering failures reported against this app - a screen that goes empty and comes back, a row
 * that keeps a fragment of what it used to hold, a cursor that disagrees with the text - are produced
 * by a real session's byte stream on a real device. This host has neither, so the export is the way
 * one of those reaches a machine that can replay it: [SessionLog] carries the bytes, and this carries
 * the frames the app built from them, at the same clock, so the two can be read together.
 *
 * It is a recorder, not a judge. Every note is a thing that happened, written once, in the frame it
 * happened in; nothing here concludes that a frame was *wrong*, because from inside the app a blank
 * screen and a screen that was told to be blank are the same frame. The wording in [report] says so.
 *
 * Bounded in two directions: the notes are capped at [capacity] and the stretch detector fires once
 * per stretch rather than once per frame, because the failure this exists for lasts seconds and would
 * otherwise be thousands of identical lines.
 *
 * Thread-safe, like [SessionLog] and for the same reason: frames are published from the collector's
 * coroutine and from the main thread, and the export reads it from whichever thread the user's tap
 * runs on.
 */
internal class RenderAudit(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val notes = ArrayDeque<RenderNote>()

    private var last: FrameSample? = null
    private var frames = 0L
    private var noteCount = 0L
    private var droppedNotes = 0L

    /** The session log's count as of the last chunk handed over, and as of the last published frame. */
    private var lastOutput = 0L
    private var outputAtFrame = 0L

    /** How many frames in a row have arrived with output behind them and no revision change. */
    private var unchangedWithOutput = 0

    /** Whether the current stretch has already been written down; one line per stretch, not per frame. */
    private var stretchReported = false

    /**
     * Records that the session's output has reached [position] - the count the session log keeps.
     *
     * The position is handed over rather than counted here, because it is the session log's number and
     * both are fed from the same tee. A note naming its own private count would say "somewhere in this
     * session" and leave the person replaying it to search; naming the log's says which character.
     */
    fun onOutput(position: Long) {
        synchronized(lock) {
            if (position < lastOutput) {
                // Not the log the last chunk came from: it was replaced under the audit. Nothing is
                // measured across the seam - the stretch so far was the old stream's, and the frames
                // that follow are counted from the new one's position.
                outputAtFrame = position
                unchangedWithOutput = 0
                stretchReported = false
            }
            lastOutput = position
        }
    }

    /**
     * Records one published frame of the live view.
     *
     * Scrolled-back views are deliberately not fed here - their ink is a function of where the user
     * scrolled to, and a viewport panned past the end of the text is empty for reasons that have
     * nothing to do with a bug.
     */
    fun onFrame(sample: FrameSample) {
        synchronized(lock) {
            val previous = last
            frames++
            val sinceFrame = lastOutput - outputAtFrame
            when {
                previous == null -> Unit
                sample.revision < previous.revision -> note(
                    RenderNoteKind.REVISION_REWIND,
                    "${previous.revision} -> ${sample.revision}",
                )
                sample.revision == previous.revision -> {
                    if (sinceFrame > 0) unchangedWithOutput++
                    if (unchangedWithOutput >= STALL_FRAMES && !stretchReported) {
                        stretchReported = true
                        note(
                            RenderNoteKind.OUTPUT_WITHOUT_FRAME,
                            "no repaint across $unchangedWithOutput frames " +
                                "($sinceFrame chars at revision ${sample.revision})",
                        )
                    }
                }
                else -> {
                    unchangedWithOutput = 0
                    stretchReported = false
                    if (sample.screen == previous.screen && sample.inkCells == 0 && previous.inkCells > 0) {
                        note(RenderNoteKind.BLANKED, "revision ${sample.revision} (ink ${previous.inkCells} -> 0, ${word(sample.screen)})")
                    } else if (sample.screen == previous.screen && sample.inkCells > 0 && previous.inkCells == 0) {
                        note(RenderNoteKind.REPAINTED, "revision ${sample.revision} (ink 0 -> ${sample.inkCells}, ${word(sample.screen)})")
                    }
                }
            }
            outputAtFrame = lastOutput
            last = sample
        }
    }

    /**
     * The export: what the screen did, in as many lines as it takes to say it.
     *
     * Shaped so that a note can be found in it by eye and by grep - `blanked`, `rewind`, `no repaint` -
     * because the person reading it is deciding what to do next, not reading a log for its own sake.
     *
     * [droppedChars] is what the session log evicted - its own count, handed over by whoever reads the
     * two exports together - and it is what turns a note's position into a character of the text the
     * user saved. Omit it and the positions stay in the session's own numbering, which is still exact
     * but names a character the export no longer has.
     */
    fun report(droppedChars: Long? = null): String = synchronized(lock) {
        val newest = last
        buildString {
            append("render audit · frames ").append(frames).append(" · notes ").append(noteCount)
            if (droppedNotes > 0) append(" (last ").append(capacity).append(" kept)")
            append('\n')
            if (newest == null) {
                append("nothing observed - no frame was published while this session was live\n")
                return@buildString
            }
            append("last frame · revision ").append(newest.revision)
            append(" · ink ").append(newest.inkCells)
            append(" · cursor ").append(newest.cursorRow).append(',').append(newest.cursorColumn)
            append(" · ").append(where(lastOutput, droppedChars))
            append(" · ").append(word(newest.screen))
            append('\n')
            notes.forEach {
                append("  t=").append(it.atMillis).append("  ").append(it.kind.label()).append(' ')
                    .append(it.detail).append(" · ").append(where(it.outputPosition, droppedChars)).append('\n')
            }
        }
    }

    private fun note(kind: RenderNoteKind, detail: String) {
        noteCount++
        if (notes.size == capacity) {
            notes.removeFirst()
            droppedNotes++
        }
        notes.addLast(RenderNote(kind, clock(), lastOutput, detail))
    }

    /**
     * Where a position in the session's output lands in the file the user actually saved.
     *
     * Three cases, and the third is why this is not one subtraction: the log evicts from the front, so a
     * point early in a long session is simply not in the export, and saying "log char -412" would send
     * whoever replays it to a real character that is the wrong one.
     */
    private fun where(position: Long, droppedChars: Long?): String = when {
        droppedChars == null -> "output #$position"
        position < droppedChars -> "output #$position -> evicted"
        else -> "output #$position -> log char ${position - droppedChars}"
    }

    private fun word(screen: ScreenKind): String = when (screen) {
        ScreenKind.PRIMARY -> "primary"
        ScreenKind.ALTERNATE -> "alternate"
        ScreenKind.POSITIONAL -> "positional"
    }

    private fun RenderNoteKind.label(): String = when (this) {
        RenderNoteKind.BLANKED -> "blanked"
        RenderNoteKind.REPAINTED -> "repainted"
        RenderNoteKind.REVISION_REWIND -> "rewind"
        RenderNoteKind.OUTPUT_WITHOUT_FRAME -> "stalled ·"
    }

    companion object {
        /**
         * How many frames in a row may carry output with no revision change before that is written
         * down. At the collector's frame rate this is about a third of a second: long enough that a
         * cursor-position reply or a mode set is not reported, short enough that a real stall is.
         */
        const val STALL_FRAMES = 12

        /** Notes kept per session. Enough for several incidents, small enough to print in a file. */
        const val DEFAULT_CAPACITY = 16
    }
}
