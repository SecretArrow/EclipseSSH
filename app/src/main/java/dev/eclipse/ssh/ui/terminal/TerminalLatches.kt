/*
 * The Ctrl / Alt / Shift latches on the on-screen key row.
 *
 * They latch rather than requiring a chord because a touchscreen cannot hold one key while pressing
 * another: tapping CTRL arms it, the next key press consumes it, and [consume] clears all three. Two
 * taps on the same latch turn it off again, so an armed modifier is never a trap. Sticky-until-used
 * is what every terminal keyboard on the platform does, and it is the only scheme that makes Ctrl-C
 * reachable with one thumb.
 *
 * Under the ConnectBot engine the armed state is read through [LatchModifierManager] (see
 * ConnectBotTerminal.kt): the library's Terminal() asks it on every key it dispatches, so a latched
 * Ctrl turns the next typed 'c' into Ctrl+C, and the library clears the latches through
 * clearTransients after the keystroke lands — the same sticky-until-used contract as before.
 */
package dev.eclipse.ssh.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Stable
class TerminalLatches(
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
) {
    var ctrl by mutableStateOf(ctrl)
        private set
    var alt by mutableStateOf(alt)
        private set
    var shift by mutableStateOf(shift)
        private set

    fun toggleCtrl() {
        ctrl = !ctrl
    }

    fun toggleAlt() {
        alt = !alt
    }

    fun toggleShift() {
        shift = !shift
    }

    /** Toggle by latch id, for callers holding a [KeyBarCap] rather than a direct reference. */
    fun toggle(id: String) {
        when (id) {
            "CTRL" -> toggleCtrl()
            "ALT" -> toggleAlt()
            "SHIFT" -> toggleShift()
        }
    }

    /** Whether [id] is armed, by the same ids [toggle] accepts. */
    fun armed(id: String): Boolean =
        when (id) {
            "CTRL" -> ctrl
            "ALT" -> alt
            "SHIFT" -> shift
            else -> false
        }

    /** Reads the armed modifiers and disarms them, which is what pressing any other key does. */
    fun consume(): Triple<Boolean, Boolean, Boolean> {
        val armed = Triple(ctrl, alt, shift)
        ctrl = false
        alt = false
        shift = false
        return armed
    }

    companion object {
        /** Survives rotation. An armed Ctrl is invisible state the user is relying on. */
        val Saver: Saver<TerminalLatches, List<Boolean>> =
            Saver(
                save = { listOf(it.ctrl, it.alt, it.shift) },
                restore = { TerminalLatches(it[0], it[1], it[2]) },
            )
    }
}

@Composable
fun rememberTerminalLatches(): TerminalLatches = rememberSaveable(saver = TerminalLatches.Saver) { TerminalLatches() }
