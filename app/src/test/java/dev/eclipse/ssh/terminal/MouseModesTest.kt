package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which mouse reports the terminal believes the remote program asked for.
 *
 * The modes are kept as a set rather than as "the current one" because a program can enable more than
 * one at a time, and because a program that turns one off while another is still on must keep its
 * mouse - losing it there is a bug the user sees as the mouse working and then stopping.
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
}
