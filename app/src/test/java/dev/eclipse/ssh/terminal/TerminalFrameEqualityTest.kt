package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Damage is the *transition into* a frame, not the frame, so it must not decide whether two frames are
 * the same screen.
 *
 * Both directions matter and both are checked here. A frame republished with damage still describes the
 * screen the display already has - the buffer hands damage out once and resets it, so consecutive frames
 * of an untouched screen differ only in these three fields, and treating them as different values lays
 * the view out again on every tick. And the renderer still has to *read* the damage, so the fields stay
 * public and keep their values; only equality ignores them.
 */
class TerminalFrameEqualityTest {
    private fun frame(): TerminalFrame =
        AnsiTerminalBuffer(columns = 12, rows = 4).apply { feed("hello\r\nworld") }.frame()

    @Test
    fun `damage alone does not make a different frame`() {
        val base = frame()
        val damaged = base.copy(damageFrom = 0, damageTo = 3, damageFull = false)
        val undamaged = base.copy(damageFrom = -1, damageTo = -1, damageFull = false)

        assertThat(damaged).isEqualTo(undamaged)
        assertThat(damaged.hashCode()).isEqualTo(undamaged.hashCode())
        // Ignored by equality is not the same as absent: the renderer still reads these.
        assertThat(damaged.damageFrom).isEqualTo(0)
        assertThat(damaged.damageTo).isEqualTo(3)
        assertThat(damaged.damageFull).isFalse()
    }

    @Test
    fun `the damage a buffer hands out is not what makes its frame new`() {
        val buffer = AnsiTerminalBuffer(columns = 12, rows = 4)
        buffer.feed("hello")

        // The first frame carries damage and the second does not, and both describe the same screen.
        val first = buffer.frame()
        val second = buffer.frame()
        assertThat(second).isEqualTo(first)
        assertThat(second.damageFull).isFalse()
        assertThat(first.damageFull).isTrue()
    }

    /**
     * One case per constructor property other than the damage three, so a field dropped from the
     * hand-written `equals`/`hashCode` pair is a failing case here rather than a silent one.
     */
    @Test
    fun `every other field still decides identity`() {
        val base = frame()
        val others = listOf(
            "lines" to base.copy(lines = base.lines.dropLast(1)),
            "firstLine" to base.copy(firstLine = base.firstLine + 1),
            "totalLines" to base.copy(totalLines = base.totalLines + 1),
            "liveEnd" to base.copy(liveEnd = base.liveEnd + 1),
            "cursorRow" to base.copy(cursorRow = base.cursorRow + 1),
            "cursorColumn" to base.copy(cursorColumn = base.cursorColumn + 1),
            "columns" to base.copy(columns = base.columns + 1),
            "rows" to base.copy(rows = base.rows + 1),
            "contentColumns" to base.copy(contentColumns = base.contentColumns + 1),
            "cursorVisible" to base.copy(cursorVisible = !base.cursorVisible),
            "revision" to base.copy(revision = base.revision + 1),
            "title" to base.copy(title = "a title"),
            "alternateScreen" to base.copy(alternateScreen = !base.alternateScreen),
            "positionalScreen" to base.copy(positionalScreen = !base.positionalScreen),
            "synchronizedOutput" to base.copy(synchronizedOutput = !base.synchronizedOutput),
            "applicationCursorKeys" to base.copy(applicationCursorKeys = !base.applicationCursorKeys),
            "applicationKeypad" to base.copy(applicationKeypad = !base.applicationKeypad),
            "bracketedPaste" to base.copy(bracketedPaste = !base.bracketedPaste),
            "keyboardModes" to base.copy(keyboardModes = KeyboardModes(kittyFlags = 1)),
            "cursorBlink" to base.copy(cursorBlink = !base.cursorBlink),
            "scrollOffset" to base.copy(scrollOffset = base.scrollOffset + 1),
            "scrollCeiling" to base.copy(scrollCeiling = base.scrollCeiling + 1),
            "mouseMode" to base.copy(mouseMode = MouseMode.X10),
            "mouseSgr" to base.copy(mouseSgr = !base.mouseSgr),
            "marginAbove" to base.copy(marginAbove = listOf(listOf(TerminalCell('x')))),
            "marginBelow" to base.copy(marginBelow = listOf(listOf(TerminalCell('x')))),
            "stepRowsBack" to base.copy(stepRowsBack = base.stepRowsBack + 1),
            "stepRowsForward" to base.copy(stepRowsForward = base.stepRowsForward + 1),
        )

        others.forEach { (name, other) ->
            assertWithMessage(name).that(other).isNotEqualTo(base)
        }
    }
}
