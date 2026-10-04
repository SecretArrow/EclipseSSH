package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.AnsiTerminalBuffer
import dev.eclipse.ssh.terminal.TerminalFrame
import org.junit.Test

/**
 * The rule that keeps a terminal from being blanked by a frame that was built before the one already
 * on screen.
 *
 * Two things publish frames for the same session and they run on different threads - the output
 * collector on a background dispatcher, and `attachTerminal`, session adoption, scrolling and
 * resizing on the main thread. Building a frame and writing it are two steps, so a writer that is
 * preempted between them writes a snapshot that has gone stale in the meantime. Reproducing that
 * interleaving on demand means winning a race deliberately, which no test can promise; the ordering
 * rule it depends on is a pure function, so the rule is tested here instead and the integration is
 * covered by the lifecycle suite.
 *
 * Real revisions from a real buffer rather than hand-built [TerminalFrame]s, because the property
 * being relied on belongs to the buffer: that `revision` advances on output and never goes backwards.
 * A test that invented those numbers would still pass if that stopped being true.
 */
class TerminalFramePublishOrderTest {

    private val buffer = AnsiTerminalBuffer(columns = 40, rows = 8)

    @Test
    fun `the first frame of a session is published`() {
        // Nothing to compare against: a session that has just opened its tab has no frame yet, and the
        // empty screen it starts with is what the user should see until output arrives.
        val first = buffer.frame()
        assertThat(newerTerminalFrame(null, first)).isSameInstanceAs(first)
    }

    @Test
    fun `a frame built before the published one does not replace it`() {
        // The bug this exists for: `attachTerminal` builds the empty frame of a session that has just
        // come up, the collector feeds the login banner and publishes it while that build is in
        // flight, and the empty build lands last. A shell at its prompt sends nothing more, so no
        // later frame corrects it - the terminal simply stays blank.
        val atAttach = buffer.frame()
        buffer.feed("login: welcome\r\n$ ")
        val withBanner = buffer.frame()
        assertThat(withBanner.revision).isGreaterThan(atAttach.revision)

        assertThat(newerTerminalFrame(published = withBanner, built = atAttach)).isSameInstanceAs(withBanner)
        assertThat(newerTerminalFrame(published = withBanner, built = atAttach).asDrawn()).contains("welcome")
    }

    @Test
    fun `a frame built after the published one replaces it`() {
        // The ordinary case, and the one that must not become collateral damage of the guard above:
        // output that arrived after the frame on screen is the whole point of publishing.
        val before = buffer.frame()
        buffer.feed("$ uname -a\r\n")
        val after = buffer.frame()
        assertThat(newerTerminalFrame(published = before, built = after)).isSameInstanceAs(after)
    }

    @Test
    fun `a left arrow alone is enough to publish a new frame`() {
        // A cursor that moved has to be a buffer that changed, and this is the one arrow where that
        // is the whole story. readline writes `^H` for cursor-left and `ESC [ C` for cursor-right, so
        // left is the only arrow whose entire effect is the cursor: every other one either arrives as
        // a CSI or redraws the line, and both of those already advanced the revision. Setting the
        // column without reporting it left the frame comparing equal to the one on screen, and the
        // renderer memoizes the layout it draws the cursor box from on this value - so the user
        // pressed left, the buffer moved, and the cursor sat still until the next character was typed.
        buffer.feed("$ uname")
        val before = buffer.frame()
        buffer.feed("\u0008")
        val after = buffer.frame()

        assertThat(after.cursorColumn).isEqualTo(before.cursorColumn - 1)
        assertThat(after.revision).isGreaterThan(before.revision)
        assertThat(newerTerminalFrame(published = before, built = after)).isSameInstanceAs(after)
    }

    @Test
    fun `a rebuild of an unchanged buffer replaces the published frame`() {
        // Same revision, different viewport: scrolling back through the scrollback, a resize, and the
        // republish that happens when the app comes back to the foreground all rebuild a buffer that
        // has not changed. Rejecting those would freeze the terminal at whatever was last printed -
        // the user could not scroll - so equal revisions have to keep the incoming frame.
        buffer.feed((1..40).joinToString("\r\n") { "line $it" })
        val onScreen = buffer.frame()
        val scrolledBack = buffer.frame(scrollOffset = 12)
        assertThat(scrolledBack.revision).isEqualTo(onScreen.revision)
        assertThat(scrolledBack.firstLine).isLessThan(onScreen.firstLine)

        assertThat(newerTerminalFrame(published = onScreen, built = scrolledBack)).isSameInstanceAs(scrolledBack)
    }

    private fun TerminalFrame.asDrawn(): String =
        lines.joinToString(separator = "\n") { row -> row.joinToString(separator = "") { it.value.toString() } }
}

/**
 * The rule that keeps a scroll from being undone by a writer that read the offset before it happened.
 *
 * Same shape of bug as the one above and the same reason it cannot be reproduced on demand: the two
 * writers run on different threads and the loser is decided by which one is preempted between reading
 * and writing. Both rules are pure functions, so both are tested as functions.
 *
 * [heldOffset] is also where the ordering of the two steps lives - the growth is applied to the offset
 * that is there when the write happens, not to the one the collector read before feeding the buffer.
 *
 * Every assertion here names the object it expects by identity, and passes two *different* frames
 * wherever the rule is a choice between them. A branch that cannot be told apart from its alternative
 * is not covered by a test that passes either way, so `isSameInstanceAs` is doing the work - an assertion
 * that would hold for `newerTerminalFrame(published, built)` and for `newerTerminalFrame(built, published)`
 * alike proves nothing about the rule it is named after.
 */
class TerminalScrollPinTest {

    private val buffer = AnsiTerminalBuffer(columns = 40, rows = 8)

    @Test
    fun `a frame built at a scroll position the user has left is not published`() {
        buffer.feed((1..40).joinToString("\r\n") { "line $it" })
        val live = buffer.frame(scrollOffset = 0)
        val scrolledBack = buffer.frame(scrollOffset = 12)
        // Same revision: a scroll moves the window without mutating anything, which is why the frame
        // on screen cannot be defended by revision alone.
        assertThat(scrolledBack.revision).isEqualTo(live.revision)

        // What the ordering rule alone does with the collector's frame - it wins, and the screen goes
        // back to the bottom the user has just scrolled away from.
        assertThat(newerTerminalFrame(published = scrolledBack, built = live)).isSameInstanceAs(live)
        // What the viewport check does with it instead.
        assertThat(publishableFrame(published = scrolledBack, built = live, builtOffset = 0, currentOffset = 12))
            .isNull()
    }

    @Test
    fun `a frame built at the offset the user is on is published`() {
        buffer.feed("$ uname -a\r\n")
        val frame = buffer.frame(scrollOffset = 0)
        assertThat(publishableFrame(published = null, built = frame, builtOffset = 0, currentOffset = 0))
            .isSameInstanceAs(frame)
    }

    @Test
    fun `a scroll back to where the frame was built is published again`() {
        // The drop is not permanent: what makes a frame publishable is the offset being the current one,
        // so scrolling back to it publishes exactly as it did the first time.
        //
        // The two frames are deliberately different objects. An earlier version of this test passed one
        // frame as both arguments, which cannot see which branch answered - it would have passed
        // unchanged if `publishableFrame` returned `published` every time, and the offset check it is
        // here to cover could have been deleted without a red test.
        buffer.feed((1..40).joinToString("\r\n") { "line $it" })
        val live = buffer.frame(scrollOffset = 0)
        val scrolledBack = buffer.frame(scrollOffset = 12)
        assertThat(scrolledBack).isNotSameInstanceAs(live)

        val onScreen = publishableFrame(published = live, built = scrolledBack, builtOffset = 12, currentOffset = 12)
        assertThat(onScreen).isSameInstanceAs(scrolledBack)
        assertThat(onScreen).isNotSameInstanceAs(live)
    }

    @Test
    fun `a frame built below a scrolled-back view is not published either`() {
        // The mirror of the first test, and the direction the suite did not cover: the collector builds
        // at the live end while the user is reading the scrollback, and the user is back at the live end
        // before the write happens. A frame built back in the scrollback would land on top of output the
        // user has already scrolled past, which is the same wrong screen seen from the other side.
        buffer.feed((1..40).joinToString("\r\n") { "line $it" })
        val live = buffer.frame(scrollOffset = 0)
        val scrolledBack = buffer.frame(scrollOffset = 12)

        assertThat(publishableFrame(published = live, built = scrolledBack, builtOffset = 12, currentOffset = 0))
            .isNull()
    }

    @Test
    fun `an out-of-order build is rejected even when the offset matches`() {
        // The ordering rule, reached through the viewport check rather than around it. Class one covers
        // `newerTerminalFrame` on its own; nothing exercised the two rules composed, so a rewrite that
        // dropped the delegate would have gone unnoticed here.
        buffer.feed("$ uname -a\r\n")
        val older = buffer.frame(scrollOffset = 0)
        buffer.feed("Linux eclipse 6.12.86\r\n")
        val newer = buffer.frame(scrollOffset = 0)
        assertThat(newer.revision).isGreaterThan(older.revision)

        assertThat(publishableFrame(published = newer, built = older, builtOffset = 0, currentOffset = 0))
            .isSameInstanceAs(newer)
    }

    @Test
    fun `a newer build at the offset the user is on replaces the published frame`() {
        buffer.feed("$ uname -a\r\n")
        val older = buffer.frame(scrollOffset = 0)
        buffer.feed("Linux eclipse 6.12.86\r\n")
        val newer = buffer.frame(scrollOffset = 0)

        assertThat(publishableFrame(published = older, built = newer, builtOffset = 0, currentOffset = 0))
            .isSameInstanceAs(newer)
    }

    @Test
    fun `a republished viewport at an equal revision wins over the one on screen`() {
        // Equal revisions keep the incoming frame, and the incoming frame here is a *different object*:
        // a rebuild of an unchanged buffer - a foreground republish, a resize, a scroll landing back on
        // the live end. Same assertion as class one's, reached through the viewport check.
        buffer.feed((1..40).joinToString("\r\n") { "line $it" })
        val onScreen = buffer.frame(scrollOffset = 0)
        val rebuilt = buffer.frame(scrollOffset = 0)
        assertThat(rebuilt).isNotSameInstanceAs(onScreen)
        assertThat(rebuilt.revision).isEqualTo(onScreen.revision)

        assertThat(publishableFrame(published = onScreen, built = rebuilt, builtOffset = 0, currentOffset = 0))
            .isSameInstanceAs(rebuilt)
    }

    @Test
    fun `pinning adds the growth to the offset the view is on now`() {
        // The user scrolled to 80 while the collector had 50 in hand. Adding the growth to the stale
        // reading is the write that snaps the view back; adding it to the current one holds the text
        // the user is reading exactly where it is.
        assertThat(heldOffset(current = 50, growth = 3, ceiling = 1000)).isEqualTo(53)
        assertThat(heldOffset(current = 80, growth = 3, ceiling = 1000)).isEqualTo(83)
    }

    @Test
    fun `a view that is live is left to move`() {
        // Both spellings of "following the output": the entry is absent, or it holds zero.
        assertThat(heldOffset(current = null, growth = 3, ceiling = 1000)).isNull()
        assertThat(heldOffset(current = 0, growth = 3, ceiling = 1000)).isNull()
    }

    @Test
    fun `a negative offset is left to move as well`() {
        // The guard reads `offset <= 0` and only the zero half was exercised. A negative offset is not
        // reachable from the call site - every writer clamps to `maxScrollOffset` - but the guard is what
        // says so, and a guard whose negative half is never run is a guard nobody has checked.
        assertThat(heldOffset(current = -7, growth = 3, ceiling = 1000)).isNull()
        assertThat(heldOffset(current = -7, growth = 0, ceiling = 1000)).isNull()
    }

    @Test
    fun `holding on past the top of the scrollback clamps to what exists`() {
        assertThat(heldOffset(current = 50, growth = 40, ceiling = 60)).isEqualTo(60)
        // Landing exactly on the ceiling is the same branch as overshooting it, and it is the case that
        // happens most: the offset grows by one line at a time and stops where the scrollback stops.
        assertThat(heldOffset(current = 50, growth = 10, ceiling = 60)).isEqualTo(60)
        assertThat(heldOffset(current = 50, growth = 10, ceiling = 1)).isEqualTo(1)
    }

    @Test
    fun `an offset already past the ceiling is pulled back to it`() {
        // The view is holding a position that no longer exists - the scrollback limit evicted lines from
        // the top, or a resize shortened the buffer, between the read and the write. Holding still then
        // means moving *down*, to the top of what is left, rather than sticking to a line that is gone.
        assertThat(heldOffset(current = 80, growth = 5, ceiling = 60)).isEqualTo(60)
    }

    @Test
    fun `a clamp to nothing reports no entry rather than a zero`() {
        // The scrollback shrank under the view - the limit evicting lines from the top, or a resize -
        // to the point where there is nothing above the live end to hold. A stored zero would read as
        // "live" at every place the map is read, so removing it keeps the entries meaning one thing.
        assertThat(heldOffset(current = 2, growth = 5, ceiling = 0)).isNull()
    }

    @Test
    fun `nothing appended below leaves the offset where the user put it`() {
        assertThat(heldOffset(current = 50, growth = 0, ceiling = 1000)).isEqualTo(50)
        assertThat(heldOffset(current = 50, growth = -4, ceiling = 1000)).isEqualTo(50)
    }

    @Test
    fun `an add that wraps degrades to live rather than to a stuck screen`() {
        // Stated rather than assumed. `offset + growth` is Int arithmetic and wraps, and no writer can
        // reach a wrap: every one of them clamps to `AnsiTerminalBuffer.maxScrollOffset`, which is bounded
        // by the scrollback limit. The interesting half is which way it fails if that ever stops being
        // true - the wrapped value is negative, the clamp sends it to zero, and zero reads as "live", so
        // the screen follows the output instead of sticking at an offset that does not exist.
        assertThat(heldOffset(current = Int.MAX_VALUE - 1, growth = 8, ceiling = Int.MAX_VALUE)).isNull()
    }
}
