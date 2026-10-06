package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalModifiers
import org.junit.Test

/**
 * Which keys the local scrollback is allowed to take, and which belong to the remote program.
 *
 * The default is the remote program's, because a key the terminal eats is a key the shell, `less` or
 * `vim` never sees, and the symptom of eating the wrong one is a program that seems to ignore a key
 * that works everywhere else.
 */
class TerminalKeyRoutingTest {

    private fun target(
        key: TerminalKey,
        modifiers: TerminalModifiers = TerminalModifiers.NONE,
        programScreen: Boolean = false,
        scrollOffset: Int = 0,
        scrollCeiling: Int = 20,
    ) = terminalKeyTarget(
        key = key,
        modifiers = modifiers,
        programScreen = programScreen,
        scrollOffset = scrollOffset,
        scrollCeiling = scrollCeiling,
    )

    @Test
    fun `page up on the primary screen with history pages the scrollback`() {
        assertThat(target(TerminalKey.PAGE_UP)).isEqualTo(KeyTarget.LocalScroll)
    }

    @Test
    fun `page up with nothing to page goes to the remote program`() {
        assertThat(target(TerminalKey.PAGE_UP, scrollCeiling = 0)).isEqualTo(KeyTarget.Remote)
    }

    @Test
    fun `page up already at the far end goes to the remote program`() {
        assertThat(target(TerminalKey.PAGE_UP, scrollOffset = 20, scrollCeiling = 20))
            .isEqualTo(KeyTarget.Remote)
        // One line short is still a page, even though it is not a whole one: the walk stops at the
        // ceiling and moves what it can, which is the difference between a short page and no page.
        assertThat(target(TerminalKey.PAGE_UP, scrollOffset = 19, scrollCeiling = 20))
            .isEqualTo(KeyTarget.LocalScroll)
    }

    @Test
    fun `page up on a screen a program owns goes to the remote program`() {
        assertThat(target(TerminalKey.PAGE_UP, programScreen = true)).isEqualTo(KeyTarget.Remote)
    }

    @Test
    fun `page up held with a modifier goes to the remote program`() {
        assertThat(target(TerminalKey.PAGE_UP, TerminalModifiers(ctrl = true)))
            .isEqualTo(KeyTarget.Remote)
        assertThat(target(TerminalKey.PAGE_UP, TerminalModifiers(alt = true)))
            .isEqualTo(KeyTarget.Remote)
        assertThat(target(TerminalKey.PAGE_UP, TerminalModifiers(shift = true)))
            .isEqualTo(KeyTarget.Remote)
    }

    @Test
    fun `page down pages the scrollback only when there is somewhere to come back to`() {
        assertThat(target(TerminalKey.PAGE_DOWN, scrollOffset = 3)).isEqualTo(KeyTarget.LocalScroll)
        assertThat(target(TerminalKey.PAGE_DOWN, scrollOffset = 0)).isEqualTo(KeyTarget.Remote)
    }

    @Test
    fun `page down on a screen a program owns goes to the remote program`() {
        assertThat(
            target(TerminalKey.PAGE_DOWN, programScreen = true, scrollOffset = 3),
        ).isEqualTo(KeyTarget.Remote)
    }

    @Test
    fun `every other key goes to the remote program`() {
        val pagingKeys = setOf(TerminalKey.PAGE_UP, TerminalKey.PAGE_DOWN)

        for (key in TerminalKey.entries) {
            if (key in pagingKeys) continue

            assertThat(target(key)).isEqualTo(KeyTarget.Remote)
        }
    }

    @Test
    fun `paging keys are the only ones the scrollback can take`() {
        val local = TerminalKey.entries.filter { target(it) == KeyTarget.LocalScroll }

        assertThat(local).containsExactly(TerminalKey.PAGE_UP)
    }
}
