package dev.eclipse.ssh.terminal

/**
 * Something that happens to a key cap's press.
 *
 * [ROW_MOVED] is the one that is not the gesture's own: the shortcut bar scrolls horizontally, and a
 * drag that takes the pointer stream away from a cap leaves that cap's press waiting for a release it
 * will never be given. There is no other event in this vocabulary because there is no other way a
 * press ends - which is exactly the gap this closes.
 */
enum class CapPress { DOWN, UP, ROW_MOVED }

/**
 * Whether a cap should still be drawn as pressed, after [event].
 *
 * A press ends when the finger leaves, and that used to be the *only* way it ended. The bar scrolls,
 * though, and the scroll takes the gesture: the cap is told the pointer left its cell and nothing
 * else, so a press that the row's drag carried away stayed drawn on the key. Reported as a small
 * round mark left on the `→` cap and then on `PGDN` - a ripple caught mid-expansion, which is what a
 * frozen Material ripple is - and it stays until something else is tapped.
 *
 * So the row's own movement is the second way a press ends. It is the row that knows the gesture was
 * taken, and a cap whose press is ended by the movement that ended it cannot be left drawn by it.
 * The pressed look is a state rather than an animation for the same reason: there is nothing here
 * that can be caught half-way through.
 *
 * [DOWN] wins over everything, including a press that was never released: a keyboard whose gesture
 * stream was broken for one tap is not a reason to stop drawing the next one.
 */
fun capPressed(current: Boolean, event: CapPress): Boolean = when (event) {
    CapPress.DOWN -> true
    CapPress.UP, CapPress.ROW_MOVED -> false
}
