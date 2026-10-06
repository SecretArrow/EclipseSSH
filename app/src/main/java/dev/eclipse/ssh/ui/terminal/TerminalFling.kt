package dev.eclipse.ssh.ui.terminal

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import kotlin.math.abs

/**
 * The fling after a drag, decaying in pixels and reported through the drag's own accumulator.
 *
 * Not `ScrollableDefaults.flingBehavior()`. That one decays over the scrollable's own units, which
 * here are scrollback lines rather than pixels: the distance a fling would cover could not be priced
 * against the height of the line it crosses, so it would move at one rate over a wall of wrapped
 * output and another over short lines. It also has nowhere to stop - the end of the scrollback is the
 * buffer's answer, in lines, and the scrollable cannot see it.
 *
 * So the decay runs in pixels, each frame's travel goes to [ScrollScope.scrollBy] and therefore back
 * through the same accumulator a drag uses - which is what keeps the pricing, the carried remainder
 * and the direction in one place - and the fling stops as soon as it has used up the room
 * [roomPx] reported when it started.
 *
 * @param roomPx how far the view can still move, in pixels, for a fling travelling in the direction
 *   of the velocity it is given. Zero or less means there is nothing to fling and none is started.
 */
class TerminalFlingBehavior(
    private val decay: DecayAnimationSpec<Float>,
    private val roomPx: (Float) -> Float,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (!initialVelocity.isFinite() || initialVelocity == 0f) return 0f
        val room = roomPx(initialVelocity)
        if (!room.isFinite() || room <= 0f) return 0f
        var last = 0f
        var consumed = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(decay) {
            val travel = value - last
            last = value
            if (travel != 0f) consumed += scrollBy(travel)
            // Stopped by hand rather than left to run out: the decay lasts the same time whatever it
            // is travelling over, and a value still changing after the scrollback has ended is a
            // screen that looks stuck while the frame clock keeps running.
            if (abs(value) >= room) cancelAnimation()
        }
        return consumed
    }
}
