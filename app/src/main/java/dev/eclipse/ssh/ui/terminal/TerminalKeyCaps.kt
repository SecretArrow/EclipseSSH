/*
 * The three composables the on-screen key row renders with: the row's horizontal scroller that
 * tracks the user's drag, the latch key (CTRL/ALT/SHIFT) that toggles on tap, and the named-key
 * cap (ESC/TAB/arrows/...) that fires on tap.
 *
 * Split out of TerminalKeyRow.kt so the row composable stays a layout-only file: it picks caps
 * from configuration and routes taps; these three are the visual primitives it composes from.
 *
 * Engine-independent: a cap press is dispatched through whatever terminal the session uses (under
 * the ConnectBot engine, emulator.dispatchKey with the library's own modifier handling via
 * LatchModifierManager), so this file survived the migration from the legacy engine intact.
 */
package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.ui.terminal.KeyBarSize as SettingsKeyBarSize

/**
 * A horizontally scrolling wrapper that reports its drag state, so caps can dim while the row is
 * being dragged under the user's finger — the same affordance every platform's key row has.
 */
@Composable
internal fun KeyRowScroller(
    background: Color,
    content: @Composable (scrolling: Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    var scrolling by remember { mutableStateOf(false) }
    Box(
        Modifier.horizontalScroll(scrollState)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        scrolling = event.changes.any { it.type == PointerType.Touch && it.positionChanged() }
                    }
                }
            },
    ) {
        content(scrolling)
    }
}

/**
 * A latch key: taps toggle it on, the next cap press consumes it, two taps on the same latch
 * turn it off again. Armed state is shown by the cap's tint.
 */
@Composable
internal fun LatchKey(
    id: String,
    armed: Boolean,
    onToggle: () -> Unit,
    size: SettingsKeyBarSize,
    scrolling: Boolean,
) {
    CapBox(
        label = id,
        tint = if (armed) MaterialTheme.colorScheme.primary else Color.Transparent,
        pressed = armed,
        size = size,
        scrolling = scrolling,
        onClick = onToggle,
    )
}

/**
 * A named-key cap: a single tap fires [onClick]. The label is whatever the caller resolved —
 * the cap's own label, or [KeyBarCatalog]'s default for the key.
 */
@Composable
internal fun TerminalKeyCap(
    label: String,
    size: SettingsKeyBarSize,
    rowScrolling: Boolean,
    onClick: () -> Unit,
) {
    CapBox(
        label = label,
        tint = Color.Transparent,
        pressed = false,
        size = size,
        scrolling = rowScrolling,
        onClick = onClick,
    )
}

/** The shared visual: a rounded box with a label, dimmed while the row is being dragged. */
@Composable
private fun CapBox(
    label: String,
    tint: Color,
    pressed: Boolean,
    size: SettingsKeyBarSize,
    scrolling: Boolean,
    onClick: () -> Unit,
) {
    val dim = if (scrolling) 0.6f else 1f
    Box(
        Modifier
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .width(size.width())
            .height(size.height())
            .alpha(dim)
            .background(
                color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = if (pressed) 2.dp else 1.dp,
                color = tint.takeIf { it != Color.Transparent } ?: MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = size.fontSize(),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Width per cap, by the user's chosen key-bar size. */
private fun SettingsKeyBarSize.width() = when (this) {
    SettingsKeyBarSize.COMPACT -> 44.dp
    SettingsKeyBarSize.NORMAL -> 56.dp
    SettingsKeyBarSize.LARGE -> 72.dp
}

/** Height per cap, by the user's chosen key-bar size. */
private fun SettingsKeyBarSize.height() = when (this) {
    SettingsKeyBarSize.COMPACT -> 32.dp
    SettingsKeyBarSize.NORMAL -> 40.dp
    SettingsKeyBarSize.LARGE -> 48.dp
}

/** Label font size, by the user's chosen key-bar size. */
private fun SettingsKeyBarSize.fontSize() = when (this) {
    SettingsKeyBarSize.COMPACT -> 11.sp
    SettingsKeyBarSize.NORMAL -> 13.sp
    SettingsKeyBarSize.LARGE -> 15.sp
}
