package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.terminal.TerminalKey

@Composable
fun TerminalKeyRow(
    latches: TerminalLatches,
    prefs: KeyBarPrefs,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onText: (String) -> Unit,
    /** A single custom character held with Ctrl or Alt, the same path a typed character takes. */
    onChar: (Char, Boolean, Boolean) -> Unit,
    /** The colour behind the row, which its edge fades blend into: the terminal's own. */
    background: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prefs.rowsForLayout().forEachIndexed { index, row ->
            // The rows and the caps are both keyed, and both for the same reason: a cap is a
            // clickable with a press state, and a slot reused for a different cap hands the first
            // cap's press to the second. The bar is data - a settings edit, a preset, or a layout
            // mode changed to DOUBLE moves caps between rows - so "the same slot" is not "the same
            // key", and the artifact lands on whichever cap took the slot.
            key(index) {
                KeyRowScroller(background) { scrolling ->
                    row.forEach { cap ->
                        key(cap.id) {
                            when (cap.kind) {
                                KeyBarCapKind.LATCH ->
                                    LatchKey(
                                        cap.id,
                                        latches.armed(cap.id),
                                        { latches.toggle(cap.id) },
                                        prefs.size,
                                        scrolling,
                                    )
                                KeyBarCapKind.KEY -> {
                                    // An id no key answers to draws nothing: the bar's cap list is
                                    // decoded from an untrusted blob, and a cap that cannot be sent
                                    // is better absent than present and dead.
                                    val key = TerminalKey.entries.firstOrNull { it.name == cap.id }
                                    if (key != null) {
                                        TerminalKeyCap(
                                            label = cap.label ?: KeyBarCatalog.defaultLabel(key),
                                            size = prefs.size,
                                            rowScrolling = scrolling,
                                        ) {
                                            val (ctrl, alt, shift) = latches.consume()
                                            onKey(key, ctrl, alt, shift)
                                        }
                                    }
                                }
                                KeyBarCapKind.TEXT -> {
                                    val text = cap.text.orEmpty()
                                    TerminalKeyCap(
                                        label = cap.label ?: text,
                                        size = prefs.size,
                                        rowScrolling = scrolling,
                                    ) {
                                        // The latches apply to a custom cap the way they apply to a
                                        // typed character: Ctrl-/ is a real binding (it sends US), and
                                        // treating a custom cap as "just text" would silently ignore an
                                        // armed latch.
                                        val (ctrl, alt, _) = latches.consume()
                                        if (ctrl || alt) {
                                            text.forEach { char -> onChar(char, ctrl, alt) }
                                        } else {
                                            onText(text)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
