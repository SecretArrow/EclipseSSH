package dev.eclipse.ssh.terminal

/** What the remote program asked the terminal to report with the mouse. */
enum class MouseMode {
    /** No mouse reporting; the gesture is free for scrollback and selection. */
    OFF,

    /** DECSET 9: press only, and only the button number. */
    X10,

    /** DECSET 1000: press and release. */
    NORMAL,

    /** DECSET 1002: presses and drags while a button is held. */
    BUTTON,

    /** DECSET 1003: every movement, button held or not. */
    ANY,
}

/**
 * The DECSET private modes that concern the mouse.
 *
 * A program may have more than one tracking mode on at once, so the numbers are kept as a set and the
 * effective one is derived rather than remembered: the highest-numbered enabled mode wins, which is
 * what xterm sends and what every emulator built on it copies. Deriving it also means a program that
 * disables 1000 while 1002 is still on keeps mouse reporting instead of losing it.
 *
 * [SGR] is the encoding. 1005 (UTF-8 coordinates) and 1015 (urxvt) are not implemented: a program
 * asking for those gets no mouse reports rather than bytes in an encoding this terminal would get
 * wrong, and the gesture falls back to arrow keys, which is a path that works.
 */
object MouseModes {
    const val X10 = 9
    const val NORMAL = 1000
    const val BUTTON = 1002
    const val ANY = 1003
    const val SGR = 1006

    private val byMode: Map<Int, MouseMode> = mapOf(
        X10 to MouseMode.X10,
        NORMAL to MouseMode.NORMAL,
        BUTTON to MouseMode.BUTTON,
        ANY to MouseMode.ANY,
    )

    /** The modes this terminal acts on. Anything outside this set leaves [enabled] untouched. */
    val handled: Set<Int> = byMode.keys + SGR

    /** The reporting mode [enabled] amounts to, [MouseMode.OFF] when no tracking mode is on. */
    fun modeOf(enabled: Set<Int>): MouseMode {
        val highest = enabled.asSequence().filter { it in byMode }.maxOrNull() ?: return MouseMode.OFF
        return byMode.getValue(highest)
    }

    /** Whether [enabled] asks for SGR coordinates, the only encoding this terminal sends. */
    fun sgrOf(enabled: Set<Int>): Boolean = SGR in enabled

    /**
     * [enabled] after DECSET/DECRST [mode]. A mode this terminal does not implement is not recorded,
     * so nothing here can later be acted on by mistake.
     */
    fun withMode(enabled: Set<Int>, mode: Int, enable: Boolean): Set<Int> = when {
        mode !in handled -> enabled
        enable -> enabled + mode
        else -> enabled - mode
    }
}
