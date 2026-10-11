package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the exported render audit says, and - more importantly - what it refuses to say.
 *
 * The audit exists because the rendering failures this app has chased are invisible from this host:
 * they are produced by a real session's byte stream on a real device. The export is the way one of
 * those reaches a machine that can replay it, so the audit has to be a *record*, not a verdict. Every
 * test here is about a fact being written down exactly once, in the frame it happened in.
 */
class RenderAuditTest {

    private var now = 1_000L
    private val audit = RenderAudit(clock = { now })

    private fun frame(
        revision: Long,
        ink: Int,
        screen: ScreenKind = ScreenKind.PRIMARY,
        cursorRow: Int = 0,
        cursorColumn: Int = 0,
    ) = FrameSample(
        revision = revision,
        inkCells = ink,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
        screen = screen,
    )

    @Test
    fun `a first frame is remembered without being a change`() {
        audit.onFrame(frame(revision = 1, ink = 40))
        assertThat(audit.report()).contains("frames 1")
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `an ordinary repaint is remembered without a note`() {
        audit.onFrame(frame(revision = 1, ink = 40))
        audit.onFrame(frame(revision = 2, ink = 41))
        assertThat(audit.report()).contains("frames 2")
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `a screen that goes blank at a new revision is recorded with what it held`() {
        audit.onFrame(frame(revision = 1, ink = 812))
        audit.onFrame(frame(revision = 2, ink = 0))
        val report = audit.report()
        assertThat(report).contains("blanked")
        assertThat(report).contains("revision 2")
        assertThat(report).contains("812 -> 0")
    }

    @Test
    fun `a screen that stays blank is recorded once, not once per frame`() {
        audit.onFrame(frame(revision = 1, ink = 812))
        audit.onFrame(frame(revision = 2, ink = 0))
        audit.onFrame(frame(revision = 2, ink = 0))
        audit.onFrame(frame(revision = 3, ink = 0))
        assertThat(audit.report()).contains("notes 1")
    }

    @Test
    fun `a blank screen that is painted again is recorded as a repaint`() {
        audit.onFrame(frame(revision = 1, ink = 812))
        audit.onFrame(frame(revision = 2, ink = 0))
        audit.onFrame(frame(revision = 3, ink = 300))
        val report = audit.report()
        assertThat(report).contains("repainted")
        assertThat(report).contains("0 -> 300")
        assertThat(report).contains("notes 2")
    }

    @Test
    fun `a blank screen that blanks again is a second transition`() {
        audit.onFrame(frame(revision = 1, ink = 812))
        audit.onFrame(frame(revision = 2, ink = 0))
        audit.onFrame(frame(revision = 3, ink = 300))
        audit.onFrame(frame(revision = 4, ink = 0))
        assertThat(audit.report()).contains("notes 3")
    }

    @Test
    fun `a switch to a blank alternate screen is not reported as blanking the primary one`() {
        // What `clear`-then-full-screen-program looks like: the alternate screen starts empty by
        // design, and calling that a blank screen would bury the real ones.
        audit.onFrame(frame(revision = 1, ink = 812, screen = ScreenKind.PRIMARY))
        audit.onFrame(frame(revision = 2, ink = 0, screen = ScreenKind.ALTERNATE))
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `a blank frame in the same screen that was already blank is not a transition`() {
        audit.onFrame(frame(revision = 1, ink = 0, screen = ScreenKind.ALTERNATE))
        audit.onFrame(frame(revision = 2, ink = 0, screen = ScreenKind.ALTERNATE))
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `a revision that goes backwards is recorded`() {
        audit.onFrame(frame(revision = 40, ink = 100))
        audit.onFrame(frame(revision = 39, ink = 100))
        assertThat(audit.report()).contains("rewind")
        assertThat(audit.report()).contains("40 -> 39")
    }

    @Test
    fun `an unchanged revision is not a rewind`() {
        audit.onFrame(frame(revision = 40, ink = 100))
        audit.onFrame(frame(revision = 40, ink = 100))
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `output that never moves the screen is recorded once per stretch`() {
        audit.onFrame(frame(revision = 7, ink = 100))
        repeat(RenderAudit.STALL_FRAMES + 4) {
            audit.onOutput(decodedChars = 120)
            audit.onFrame(frame(revision = 7, ink = 100))
        }
        val report = audit.report()
        assertThat(report).contains("stalled")
        assertThat(report).contains("no repaint")
        assertThat(report).contains("notes 1")
    }

    @Test
    fun `a stretch that is cut short by a repaint is not recorded`() {
        audit.onFrame(frame(revision = 7, ink = 100))
        repeat(RenderAudit.STALL_FRAMES - 1) {
            audit.onOutput(decodedChars = 120)
            audit.onFrame(frame(revision = 7, ink = 100))
        }
        audit.onOutput(decodedChars = 120)
        audit.onFrame(frame(revision = 8, ink = 101))
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `a stretch is recorded again after a repaint ends the first one`() {
        audit.onFrame(frame(revision = 7, ink = 100))
        repeat(RenderAudit.STALL_FRAMES) {
            audit.onOutput(decodedChars = 120)
            audit.onFrame(frame(revision = 7, ink = 100))
        }
        audit.onOutput(decodedChars = 120)
        audit.onFrame(frame(revision = 8, ink = 101))
        repeat(RenderAudit.STALL_FRAMES) {
            audit.onOutput(decodedChars = 120)
            audit.onFrame(frame(revision = 8, ink = 101))
        }
        val report = audit.report()
        assertThat(report).contains("no repaint")
        assertThat(report).contains("notes 2")
    }

    @Test
    fun `frames with no output between them are not a stall however many there are`() {
        audit.onFrame(frame(revision = 7, ink = 100))
        repeat(RenderAudit.STALL_FRAMES * 3) { audit.onFrame(frame(revision = 7, ink = 100)) }
        assertThat(audit.report()).contains("notes 0")
    }

    @Test
    fun `an empty report says nothing was seen rather than failing`() {
        audit.onOutput(decodedChars = 0)
        assertThat(audit.report()).contains("frames 0")
        assertThat(audit.report()).contains("nothing observed")
    }

    @Test
    fun `the notes kept are bounded and the ones dropped are still counted`() {
        val bounded = RenderAudit(capacity = 3, clock = { now })
        bounded.onFrame(frame(revision = 1, ink = 100))
        var revision = 2L
        repeat(10) {
            bounded.onFrame(frame(revision = revision++, ink = 0))
            bounded.onFrame(frame(revision = revision++, ink = 100))
        }
        val report = bounded.report()
        assertThat(report).contains("notes 20")
        assertThat(report).contains("last 3")
    }

    @Test
    fun `the report names the newest frame the audit saw`() {
        audit.onFrame(frame(revision = 1, ink = 40))
        now = 2_000L
        audit.onFrame(frame(revision = 9, ink = 7, cursorRow = 3, cursorColumn = 11))
        val report = audit.report()
        assertThat(report).contains("revision 9")
        assertThat(report).contains("cursor 3,11")
        assertThat(report).contains("ink 7")
    }

    @Test
    fun `every note carries the millisecond it happened at`() {
        audit.onFrame(frame(revision = 1, ink = 40))
        now = 1_234_567L
        audit.onFrame(frame(revision = 2, ink = 0))
        assertThat(audit.report()).contains("t=1234567")
    }

    @Test
    fun `the report names which screen the newest frame belongs to`() {
        // The three names are read by whoever the export is sent to; a frame labelled with the wrong
        // screen sends them looking at the wrong half of the app.
        val expected = mapOf(
            ScreenKind.PRIMARY to "primary",
            ScreenKind.ALTERNATE to "alternate",
            ScreenKind.POSITIONAL to "positional",
        )
        expected.forEach { (kind, word) ->
            val one = RenderAudit(clock = { now })
            one.onFrame(frame(revision = 1, ink = 5, screen = kind))
            assertThat(one.report()).contains(" · $word\n")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a capacity that could keep nothing is refused rather than silently keeping nothing`() {
        RenderAudit(capacity = 0)
    }

    @Test
    fun `ink counts the cells the renderer would draw, not the columns a row reaches`() {
        val line = listOf(
            TerminalCell('a'),
            TerminalCell(),
            TerminalCell(),
            TerminalCell('b'),
        )
        assertThat(paintedCells(listOf(line))).isEqualTo(2)
    }

    @Test
    fun `a blank carrying a background is ink, and one carrying only a foreground is not`() {
        // The renderer's own rule, and the one the blank/repaint transitions turn on: a status bar's
        // coloured padding is painted, a space that merely has a colour chosen for the text after it
        // is not.
        val painted = TerminalCell(style = TerminalStyle(background = TerminalColor(4)))
        val invisible = TerminalCell(style = TerminalStyle(foreground = TerminalColor(4)))
        assertThat(paintedCells(listOf(listOf(painted)))).isEqualTo(1)
        assertThat(paintedCells(listOf(listOf(invisible)))).isEqualTo(0)
    }

    @Test
    fun `an inverse blank is ink, and a line with nothing on it counts nothing`() {
        assertThat(paintedCells(listOf(listOf(TerminalCell(style = TerminalStyle(inverse = true)))))).isEqualTo(1)
        assertThat(paintedCells(listOf(listOf(TerminalCell(), TerminalCell())))).isEqualTo(0)
        assertThat(paintedCells(emptyList())).isEqualTo(0)
    }

    @Test
    fun `a frame is named after the screen it is drawn on, alternate first`() {
        assertThat(screenKind(alternateScreen = false, positionalScreen = false)).isEqualTo(ScreenKind.PRIMARY)
        assertThat(screenKind(alternateScreen = false, positionalScreen = true)).isEqualTo(ScreenKind.POSITIONAL)
        assertThat(screenKind(alternateScreen = true, positionalScreen = false)).isEqualTo(ScreenKind.ALTERNATE)
        assertThat(screenKind(alternateScreen = true, positionalScreen = true)).isEqualTo(ScreenKind.ALTERNATE)
    }
}
