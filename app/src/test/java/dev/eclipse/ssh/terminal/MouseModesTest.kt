package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which mouse reports the terminal believes the remote program asked for.
 *
 * The modes are kept as a set rather than as "the current one" because a program can enable more than
 * one at a time, and because a program that turns one off while another is still on must keep its
 * mouse - losing it there is a bug the user sees as the mouse working and then stopping.
 *
 * The mode is also the answer to a second question - which *gestures* the program wants - and the two
 * are read off the same set, so the coverage table at the end of this file is what keeps the four
 * tracking modes from collapsing into "the mouse is on".
 */
class MouseModesTest {

    @Test
    fun `each tracking mode is reported as itself`() {
        assertThat(MouseModes.modeOf(setOf(MouseModes.X10))).isEqualTo(MouseMode.X10)
        assertThat(MouseModes.modeOf(setOf(MouseModes.NORMAL))).isEqualTo(MouseMode.NORMAL)
        assertThat(MouseModes.modeOf(setOf(MouseModes.BUTTON))).isEqualTo(MouseMode.BUTTON)
        assertThat(MouseModes.modeOf(setOf(MouseModes.ANY))).isEqualTo(MouseMode.ANY)
    }

    @Test
    fun `nothing enabled is no mouse reporting`() {
        assertThat(MouseModes.modeOf(emptySet())).isEqualTo(MouseMode.OFF)
        assertThat(MouseModes.modeOf(setOf(MouseModes.SGR))).isEqualTo(MouseMode.OFF)
    }

    @Test
    fun `the highest enabled mode wins`() {
        assertThat(MouseModes.modeOf(setOf(MouseModes.NORMAL, MouseModes.BUTTON)))
            .isEqualTo(MouseMode.BUTTON)
        assertThat(MouseModes.modeOf(setOf(MouseModes.X10, MouseModes.NORMAL)))
            .isEqualTo(MouseMode.NORMAL)
        assertThat(MouseModes.modeOf(setOf(MouseModes.X10, MouseModes.ANY)))
            .isEqualTo(MouseMode.ANY)
    }

    @Test
    fun `turning off a lower mode keeps the higher one that is still on`() {
        val enabled = MouseModes.withMode(setOf(MouseModes.NORMAL, MouseModes.BUTTON), MouseModes.NORMAL, false)

        assertThat(MouseModes.modeOf(enabled)).isEqualTo(MouseMode.BUTTON)
    }

    @Test
    fun `turning off the only tracking mode ends mouse reporting`() {
        val enabled = MouseModes.withMode(setOf(MouseModes.NORMAL), MouseModes.NORMAL, false)

        assertThat(MouseModes.modeOf(enabled)).isEqualTo(MouseMode.OFF)
    }

    @Test
    fun `sgr is reported only when it was asked for`() {
        assertThat(MouseModes.sgrOf(setOf(MouseModes.NORMAL, MouseModes.SGR))).isTrue()
        assertThat(MouseModes.sgrOf(setOf(MouseModes.NORMAL))).isFalse()
        assertThat(MouseModes.sgrOf(emptySet())).isFalse()
    }

    @Test
    fun `turning sgr off leaves tracking alone`() {
        val enabled = MouseModes.withMode(setOf(MouseModes.NORMAL, MouseModes.SGR), MouseModes.SGR, false)

        assertThat(MouseModes.sgrOf(enabled)).isFalse()
        assertThat(MouseModes.modeOf(enabled)).isEqualTo(MouseMode.NORMAL)
    }

    @Test
    fun `an encoding this terminal cannot send is not recorded`() {
        // 1005 and 1015 are coordinate encodings, 1016 is SGR-in-pixels. Recording any of them would
        // let the gesture send bytes the remote side would misread, so the fallback stays arrow keys.
        for (mode in listOf(1005, 1015, 1016)) {
            val enabled = MouseModes.withMode(emptySet(), mode, true)

            assertThat(enabled).isEmpty()
            assertThat(MouseModes.sgrOf(enabled)).isFalse()
            assertThat(MouseModes.modeOf(enabled)).isEqualTo(MouseMode.OFF)
        }
    }

    @Test
    fun `a mode outside the mouse ranges leaves the state alone`() {
        val enabled = setOf(MouseModes.NORMAL)

        assertThat(MouseModes.withMode(enabled, 2004, true)).isEqualTo(enabled)
        assertThat(MouseModes.withMode(enabled, 2004, false)).isEqualTo(enabled)
    }

    @Test
    fun `disabling something never enabled changes nothing`() {
        assertThat(MouseModes.withMode(emptySet(), MouseModes.ANY, false)).isEmpty()
    }

    @Test
    fun `every implemented mode round-trips through enable and disable`() {
        for (mode in MouseModes.handled) {
            val on = MouseModes.withMode(emptySet(), mode, true)
            assertThat(on).containsExactly(mode)

            assertThat(MouseModes.withMode(on, mode, false)).isEmpty()
        }
    }

    /**
     * The whole table, written as the matrix rather than as a handful of examples.
     *
     * The failure this guards is a mode answering for a gesture it did not ask for, and that failure is
     * invisible in the cases anyone would think to write down: `NORMAL` sending a drag, or `X10` sending
     * a release, reads as correct until a program acts on a report it never asked for.
     *
     * The four rows are the gestures that exist. `PRESS` and `RELEASE` are each asked once, because the
     * gesture is the button going down or up and a "held" flag alongside either is not a state the
     * terminal can be in; [only movement cares whether a button is down] pins that they ignore it.
     */
    @Test
    fun `each tracking mode covers exactly the gestures it names`() {
        // mode -> press, release, movement with a button down, movement with none
        val expected = mapOf(
            MouseMode.OFF to listOf(false, false, false, false),
            MouseMode.X10 to listOf(true, false, false, false),
            MouseMode.NORMAL to listOf(true, true, false, false),
            MouseMode.BUTTON to listOf(true, true, true, false),
            MouseMode.ANY to listOf(true, true, true, true),
        )
        // Every mode is in the table, so a mode added later cannot pass this by being left out.
        assertThat(expected.keys).containsExactlyElementsIn(MouseMode.entries)
        // And no two answer alike: a mode that added nothing to the one below it would be a distinction
        // the parser carries and the gesture never uses.
        assertThat(expected.values.toSet()).hasSize(MouseMode.entries.size)

        for ((mode, answers) in expected) {
            val cases = listOf(
                Triple(MouseGesture.PRESS, true, answers[0]),
                Triple(MouseGesture.RELEASE, true, answers[1]),
                Triple(MouseGesture.MOTION, true, answers[2]),
                Triple(MouseGesture.MOTION, false, answers[3]),
            )
            for ((gesture, held, wanted) in cases) {
                assertThat(MouseModes.reports(mode, sgr = true, gesture = gesture, buttonHeld = held))
                    .isEqualTo(wanted)
            }
        }
    }

    @Test
    fun `only movement cares whether a button is down`() {
        for (mode in MouseMode.entries) {
            for (gesture in listOf(MouseGesture.PRESS, MouseGesture.RELEASE)) {
                assertThat(MouseModes.reports(mode, sgr = true, gesture = gesture, buttonHeld = true))
                    .isEqualTo(MouseModes.reports(mode, sgr = true, gesture = gesture, buttonHeld = false))
            }
        }
    }

    @Test
    fun `an encoding this terminal does not send leaves every gesture to the terminal`() {
        // The same answer the whole gesture gets with no mouse mode at all: the finger still scrolls the
        // history, selects text and follows a link. A program asking for coordinates in an encoding this
        // terminal will not send is one it must not be reported to in *any* form.
        for (mode in MouseMode.entries) {
            for (gesture in MouseGesture.entries) {
                for (held in listOf(true, false)) {
                    assertThat(MouseModes.reports(mode, sgr = false, gesture = gesture, buttonHeld = held))
                        .isFalse()
                }
            }
        }
    }
}
