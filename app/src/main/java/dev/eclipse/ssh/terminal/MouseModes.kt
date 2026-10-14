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
 * What the finger did, in the vocabulary a mouse report is written in.
 *
 * Three and no more, because that is what a touchscreen has: one pointer, no buttons of its own, and
 * therefore exactly three things it can do - go down, come up, and move in between. A middle or a right
 * button would be a fourth and fifth gesture, and the app has no gesture that could mean one; if it
 * grows one (a two-finger tap is the usual place), this is the enum that grows with it.
 */
enum class MouseGesture {
    /** The finger went down. */
    PRESS,

    /** The finger came up. */
    RELEASE,

    /** The finger moved while it was down. */
    MOTION,
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
 *
 * The set answers two questions - which mode is in force, and what that mode is owed ([reports]) - and
 * both are derived from it rather than remembered, so neither can go stale against the other.
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

    /**
     * Whether a program that asked for [mode] in [sgr] is owed a report of [gesture].
     *
     * Each mode is exactly the set of gestures it names, and a gesture outside that set is the
     * terminal's own again: the finger scrolls the history, selects text and follows a link, which is
     * what it would do for a program that had asked for nothing. That is what makes the four tracking
     * modes worth telling apart. A program that asks for presses and releases is asking *not* to hear
     * about the drag, and a drag it hears about is a drag it acts on - `less` scrolls a line per report,
     * so a drag reported to it as movement would tear through the file under a finger that only meant to
     * scroll the screen.
     *
     * [buttonHeld] is what separates the two modes that do report movement: 1002 reports it while a
     * button is down and 1003 reports it always. On a touchscreen the two amount to the same thing -
     * movement with nothing down needs a pointer that hovers, and there is none - but the rule is
     * written as the modes are, so a device that grows a hovering pointer does not need it rewritten.
     *
     * False for every gesture when [sgr] is false. This terminal sends SGR coordinates or none, and a
     * program asking for an encoding it will not get must not be reported to in any form - the reports
     * would be bytes it reads as text.
     */
    fun reports(mode: MouseMode, sgr: Boolean, gesture: MouseGesture, buttonHeld: Boolean): Boolean {
        if (!sgr) return false
        return when (mode) {
            MouseMode.OFF -> false
            // DECSET 9 is the press and nothing else - not even the release. It is the oldest mode and
            // the crudest: a program asking for it wants to know *where* the finger went down.
            MouseMode.X10 -> gesture == MouseGesture.PRESS
            MouseMode.NORMAL -> gesture != MouseGesture.MOTION
            MouseMode.BUTTON -> gesture != MouseGesture.MOTION || buttonHeld
            MouseMode.ANY -> true
        }
    }
}
