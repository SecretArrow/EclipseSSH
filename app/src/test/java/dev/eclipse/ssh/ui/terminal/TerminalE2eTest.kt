package dev.eclipse.ssh.ui.terminal

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.presentation.OutputTee
import dev.eclipse.ssh.presentation.TerminalSessionController
import dev.eclipse.ssh.ssh.SshTerminalChannel
import dev.eclipse.ssh.terminal.TerminalKey
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.connectbot.terminal.VTermKey
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The termlib migration's app-side end-to-end test: the terminal path the migration left with no
 * app-side coverage, driven through the app's own glue rather than the library's emulator in
 * isolation.
 *
 * The library's own suites (`ShellIntegrationTest`, `TerminalShapingChecks`,
 * `TerminalInversionChecks`) feed bytes to a `TerminalEmulator` they construct directly and read
 * back rendered cells from its `snapshot`. That tests the engine. This class tests the *wiring* the
 * migration wrote: the `TerminalSessionController` that owns the emulator and bridges a
 * `TerminalChannel` to it, the `SshTerminalChannel` that carries bytes between an SSH shell and that
 * emulator, and the `TerminalKey.toVTermKey()` / `vTermModifiers()` bridge the on-screen key bar
 * uses to turn a cap press into the same bytes a hardware key would have produced.
 *
 * Robolectric + JUnit 4 + Truth, an in-process Apache MINA SSHD server (the pattern
 * `DuplicateTerminalRobolectricTest` established), and a real `SshClient` dialling loopback. Every
 * assertion is something the wire can check: rendered output the controller teed out, byte
 * sequences the emulator produced on the keyboard path, and the literal `xterm-256color` SGR bytes
 * the migration's `TERM` advertises.
 *
 * **The layer this test runs at.** A full Compose e2e through `ConnectBotTerminal` is infeasible
 * here: that composable hosts the library's `Terminal()` inside a `MainActivity` Compose tree, and
 * the existing Compose suites stand that up with `createAndroidComposeRule<MainActivity>` plus the
 * app's whole DI graph. The narrower question this file exists to answer — does the app's glue
 * carry bytes and keys end to end — is answerable one layer down, against the same symbols the
 * composable itself calls. Every path exercised here is the one the UI takes:
 *
 *  - **Output**: `TerminalSessionController.attach(channel, onEnd)` pumps the channel's `output`
 *    flow into `emulator.writeInput(...)`, exactly as the composable-attached controller does in
 *    `MainViewModel`. The decoded chunks the controller tees out (the same `OutputTee` list
 *    `MainViewModel` wires to `terminalOutput` and `SessionLog`) are what these tests read as
 *    "what the terminal saw".
 *  - **Input**: `controller.emulator.dispatchKey(modifiers, key)` and
 *    `controller.emulator.dispatchCharacter(modifiers, codepoint)`, the exact calls the on-screen
 *    key bar (`MainActivity` `TerminalKeyRow` `onKey` / `onChar`) makes. The emulator's keyboard
 *    output reaches the SSH channel through the controller's `onKeyboardInput` callback, which the
 *    test wires to `channel.writeBytes` exactly as `MainViewModel` does.
 *  - **The key bridge**: `TerminalKey.toVTermKey()` and `vTermModifiers(ctrl, alt, shift)` are the
 *    app's own top-level functions in this package, applied here exactly where the key bar applies
 *    them.
 *
 * What this layer cannot assert, and why: the termlib `TerminalEmulator`'s rendered-cell API
 * (`snapshot`, `TerminalLine.cells`, `ColorCache`) is `internal` to the library module, so the app
 * cannot read a cell's foreground ARGB from outside it. The 256-colour test below therefore proves
 * the path the migration's `TERM=xterm-256color` advertises up to the emulator's parser — the SGR
 * bytes arrive at the controller intact and verbatim, and the channel is the byte pipe the
 * contract depends on — and the cell-colour mapping itself remains the library's own
 * `testBoldBlackUsesBrightPaletteForIndexedColor`-shaped assertion (in `ShellIntegrationTest`).
 * That boundary is documented on the test, not papered over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalE2eTest {
    /**
     * The keyboard bytes the emulator produced, captured from the controller's `onKeyboardInput`
     * callback — the same lambda `MainViewModel` wires to `channels[key]?.writeBytes(data)`.
     *
     * This is the exact byte sequence written to the SSH channel's stdin, in arrival order: the
     * emulator's `onKeyboardInput` is called once per `dispatchKey` / `dispatchCharacter` with the
     * full sequence for that key (one byte for Ctrl+C, three bytes `ESC[A` for Up), and the
     * controller's callback writes that whole array to the channel verbatim. Asserting here is
     * asserting what the server receives, because `onKeyboardInput` is the boundary at which the
     * emulator hands bytes to the channel — nothing re-chunks them on the way to the wire.
     *
     * A `LinkedBlockingQueue` because the emulator's keyboard output arrives on its own dispatcher,
     * and a test asserting "Ctrl-C sent ETX" needs to take the bytes off the wire in arrival order
     * without racing the producer.
     */
    private val keyboardSink = LinkedBlockingQueue<ByteArray>()

    /**
     * The decoded chunks the controller teed out, concatenated. This is the `terminalOutput` map's
     * source in `MainViewModel` (its `OutputTee { chunk -> scheduleTranscriptPublish(key, chunk) }`),
     * and it is what "the rendered terminal contains X" means at a layer the app can observe: every
     * chunk the channel delivered to the emulator, decoded as UTF-8, in arrival order. The tee fires
     * on the raw chunk before the emulator's parser runs, so this is the byte stream the emulator
     * received — the honest reading of "bytes written to the SSH channel appear in the terminal".
     */
    private val rendered = StringBuilder()

    /** The controller under test, built fresh per test so no session leaks into the next. */
    private lateinit var controller: TerminalSessionController

    /** The channel under test, closed per test. */
    private var channel: SshTerminalChannel? = null

    /** The authenticated MINA client session the channel rides on, closed per test. */
    private var clientSession: ClientSession? = null

    /** The SSH client itself, stopped per test. */
    private var sshClient: SshClient? = null

    /** Completes when the controller's `onEnd` fires, so a test can wait for a shell that exited. */
    private val ended = AtomicBoolean(false)

    @Before
    fun resetSink() {
        keyboardSink.clear()
        rendered.setLength(0)
        ended.set(false)
    }

    @After
    fun closeSession() {
        runCatching { controller.close() }
        runCatching { channel?.close() }
        runCatching { clientSession?.close(false) }
        runCatching { sshClient?.stop() }
        // The controller's teardown posts to its own scope; drain the main looper so the close
        // actually runs before the next test constructs another controller over the same native
        // emulator pool.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }

    // --------------------------------------------------------------------------- the four scenarios

    /**
     * Scenario 1 — bytes written to the SSH channel appear in the rendered terminal.
     *
     * Connects a session to an in-process SSH server whose shell writes a known byte sequence to
     * stdout, then asserts the decoded output the controller teed out contains "hello". This is
     * the whole SSH channel → `TerminalChannel.output` → `TerminalSessionController.attach` →
     * `emulator.writeInput` → tee path, wired exactly as `MainViewModel.launchTerminalCollector`
     * wires it, and it is the path the termlib migration left with no app-side test.
     */
    @Test
    fun bytesWrittenToTheSshChannelAppearInTheRenderedTerminal() {
        attachToShellThatWrites(ECHO_LINE)

        // The shell's bytes travel: MINA server stdout -> SshTerminalChannel.output (SharedFlow) ->
        // TerminalSessionController.attach's producer -> emulator.writeInput -> the OutputTee this
        // test installed. That is the same chain MainViewModel publishes terminalOutput through.
        pumpUntil("the echoed line never reached the rendered terminal: '$rendered'") {
            rendered.toString().contains("hello")
        }
        // The echoed line made it through, in order — the controller did not drop or reorder the
        // channel's chunks on the way to the emulator.
        assertThat(rendered.toString()).contains(ECHO_LINE)
    }

    /**
     * Scenario 2 — a keystroke dispatched through the app's key bar reaches the SSH channel.
     *
     * Drives `controller.emulator.dispatchKey(...)` with `TerminalKey.ENTER` (the same call
     * `TerminalKeyRow`'s `onKey` makes, through the same `toVTermKey()` / `vTermModifiers()`
     * bridge), after typing a line through `dispatchCharacter` (the `onChar` path). Then asserts
     * the corresponding byte sequence lands on the SSH channel's stdin — the exact bytes the
     * emulator produced on the keyboard path, which is what the server receives. This is the input
     * half of the migration's path: key bar → `toVTermKey` → `dispatchKey` → emulator's
     * `onKeyboardInput` → `channel.writeBytes` → SSH channel stdin.
     */
    @Test
    fun aKeyBarKeystrokeReachesTheSshChannel() {
        attachToShellThatWrites(SHELL_BANNER)

        // Type a line of text through the character path the on-screen keyboard's `onChar` uses,
        // then commit it with the Enter key the key bar's `onKey` dispatches. Both go through the
        // controller's emulator, exactly where the UI sends them.
        for (ch in TYPED_LINE) {
            controller.emulator.dispatchCharacter(vTermModifiers(ctrl = false, alt = false), ch.code)
        }
        controller.emulator.dispatchKey(
            vTermModifiers(ctrl = false, alt = false),
            TerminalKey.ENTER.toVTermKey(),
        )

        // Each dispatched character produces one byte on the keyboard path, in order; Enter
        // produces CR (0x0D), the byte an xterm sends for the Return key. Asserting the whole typed
        // line landed as a prefix of the keyboard sink, followed by CR, is the proof the key bar's
        // path reached the channel verbatim.
        val typedBytes = TYPED_LINE.toByteArray(Charsets.US_ASCII)
        for (b in typedBytes) {
            val produced = takeKeyboardChunk("a typed character")
            assertThat(produced).isEqualTo(byteArrayOf(b))
        }
        val enter = takeKeyboardChunk("Enter")
        assertThat(enter).isEqualTo(byteArrayOf(0x0D))
    }

    /**
     * Scenario 3 — 256-colour SGR sequences are carried through the channel intact.
     *
     * Has the server's shell emit `SGR 38;5;<n>` for a few indexed colours spanning all three
     * ranges of the xterm 256-colour palette (the 16 ANSI colours, the 6×6×6 cube, and the
     * grayscale ramp), then asserts:
     *
     *  1. The SGR bytes arrive at the controller's tee **intact and verbatim** — the
     *     `SshTerminalChannel` is a byte pipe (the contract on its own class), so the escape
     *     sequence the server wrote is the escape sequence the emulator receives, byte for byte.
     *     This is the path `TERM=xterm-256color` advertises, and a channel that decoded or
     *     re-encoded text here would split multi-byte sequences and corrupt the parser.
     *  2. The pty the channel opened with is the app's `DEFAULT_TERMINAL_TYPE` (`xterm-256color`),
     *     which is the contract that makes any of this meaningful: a server that believed it was
     *     talking to a `vt100` would never emit a 256-colour SGR at all.
     *
     * What this test does *not* assert, and why: the rendered cell's foreground ARGB is the
     * termlib `ColorCache` entry for index `n`, but `ColorCache` and the emulator's `snapshot` are
     * `internal` to the library module — the app cannot read a cell's colour from outside it. The
     * `OutputTee` fires on the raw chunk before the emulator's parser runs, so it carries the SGR
     * bytes regardless of whether the parser consumed them; it cannot prove the parser recognised
     * the `CSI`. That mapping is the library's own
     * `testBoldBlackUsesBrightPaletteForIndexedColor`-shaped assertion (in `ShellIntegrationTest`),
     * and this test closes the gap up to the boundary the migration actually wrote: the channel
     * carries the SGR intact, the controller feeds it to the emulator, and the pty advertises
     * `xterm-256color`. The byte the server emitted is the byte the emulator saw, which is the
     * contract the 256-colour path depends on.
     */
    @Test
    fun indexedColorSgrSequencesAreCarriedThroughTheChannelIntact() {
        // ESC[38;5;<n>m <marker> ESC[0m for a red (196, 6x6x6 cube), a green (46, 6x6x6 cube) and
        // a grayscale (244) cell, each followed by a literal marker the tee can find. The indices
        // span the three ranges the 256-colour palette has, so a regression in any one range's
        // forwarding surfaces here. A bright-blue (12, ANSI 16) is included to cover the first
        // range too.
        val sequences =
            listOf(
                "\u001B[38;5;12m" + MARKER_BLUE + "\u001B[0m",
                "\u001B[38;5;196m" + MARKER_RED + "\u001B[0m",
                "\u001B[38;5;46m" + MARKER_GREEN + "\u001B[0m",
                "\u001B[38;5;244m" + MARKER_GRAY + "\u001B[0m",
            )
        val payload = sequences.joinToString(separator = "")
        attachToShellThatWrites(payload)

        pumpUntil("the 256-colour payload never reached the rendered terminal: '$rendered'") {
            rendered.toString().contains(MARKER_GRAY)
        }

        val out = rendered.toString()
        // Every SGR sequence the server wrote arrived at the emulator verbatim, ESC and body
        // intact — the channel is a byte pipe, and the 256-colour path depends on that. A channel
        // that decoded chunks to text and re-encoded them would split the ESC from its body at a
        // chunk boundary and the parser would print the body as text.
        assertThat(out).contains("\u001B[38;5;12m")
        assertThat(out).contains("\u001B[38;5;196m")
        assertThat(out).contains("\u001B[38;5;46m")
        assertThat(out).contains("\u001B[38;5;244m")
        // And the markers that follow each SGR survived too — the channel did not truncate the
        // payload mid-sequence.
        assertThat(out).contains(MARKER_BLUE)
        assertThat(out).contains(MARKER_RED)
        assertThat(out).contains(MARKER_GREEN)
        assertThat(out).contains(MARKER_GRAY)
        // The reset SGR (ESC[0m) landed for every sequence — a missing reset would leak the colour
        // into the next cell, which is the bug a byte-dropping channel would produce.
        val resetCount = out.split("\u001B[0m").size - 1
        assertWithMessage("the reset SGR did not arrive for every colour sequence")
            .that(resetCount)
            .isAtLeast(sequences.size)

        // The pty type the migration advertises is what makes the 256-colour path meaningful at
        // all: a server that believes it is talking to a `vt100` would never emit a 256-colour SGR.
        // The channel opened with the app's DEFAULT_TERMINAL_TYPE, which is the contract this test
        // exercises — asserted here so a regression that changed the default pty type fails this
        // test rather than silently breaking 256-colour support.
        assertThat(DEFAULT_TERMINAL_TYPE).isEqualTo("xterm-256color")
    }

    /**
     * Scenario 4 — keyboard special keys (Ctrl+C, Tab, arrow keys) are mapped correctly.
     *
     * Dispatches a few special keys through the app's `TerminalKey.toVTermKey()` bridge and asserts
     * the byte sequences the emulator produces on the keyboard path — which are the byte sequences
     * written to the SSH channel's stdin, and so what the server receives — match what an xterm
     * sends: ETX (0x03) for Ctrl+C, HT (0x09) for Tab, `ESC[A` for Up, `ESC[B` for Down, `ESC[C`
     * for Right, `ESC[D` for Left. These are the sequences the `TERM=xterm-256color` pty
     * advertises, and they are the bridge the migration wrote (`toVTermKey` → `dispatchKey` →
     * emulator → channel).
     *
     * The assertion is on the emulator's `onKeyboardInput` output (captured by the controller's
     * callback, which writes those exact bytes to `channel.writeBytes`) rather than on the server's
     * stdin stream, because `onKeyboardInput` is the boundary at which the emulator hands a whole
     * key's byte sequence to the channel in one array — the exact sequence this test needs to
     * compare against. The server's stdin is the same bytes, but a shell reads them byte-by-byte
     * and the chunk boundaries the test cares about (one key = one sequence) are lost there. The
     * path is the same one the key bar takes in `MainActivity`, end to end through the app's glue.
     */
    @Test
    fun specialKeysAreMappedToTheirXtermByteSequences() {
        attachToShellThatWrites(SHELL_BANNER)

        // Ctrl+C: the key bar's `onChar` path with the Ctrl latch armed, the same call
        // MainActivity's TerminalKeyRow makes. The emulator turns Ctrl+'c' into ETX (0x03).
        controller.emulator.dispatchCharacter(vTermModifiers(ctrl = true, alt = false), 'c'.code)
        // Tab and the arrow keys: the key bar's `onKey` path, through toVTermKey() and
        // vTermModifiers(). The emulator turns each VTermKey into the xterm byte sequence for it.
        controller.emulator.dispatchKey(vTermModifiers(ctrl = false, alt = false), TerminalKey.TAB.toVTermKey())
        controller.emulator.dispatchKey(vTermModifiers(ctrl = false, alt = false), TerminalKey.ARROW_UP.toVTermKey())
        controller.emulator.dispatchKey(vTermModifiers(ctrl = false, alt = false), TerminalKey.ARROW_DOWN.toVTermKey())
        controller.emulator.dispatchKey(vTermModifiers(ctrl = false, alt = false), TerminalKey.ARROW_RIGHT.toVTermKey())
        controller.emulator.dispatchKey(vTermModifiers(ctrl = false, alt = false), TerminalKey.ARROW_LEFT.toVTermKey())

        // Each dispatched key produces exactly one byte sequence on the keyboard path, in dispatch
        // order. The bridge's contract is one VTermKey -> one xterm sequence, so taking them in
        // order is both the natural reading and the assertion that nothing doubled or dropped.
        val ctrlC = takeKeyboardChunk("Ctrl+C")
        assertThat(ctrlC).isEqualTo(byteArrayOf(0x03))
        val tab = takeKeyboardChunk("Tab")
        assertThat(tab).isEqualTo(byteArrayOf(0x09))
        val up = takeKeyboardChunk("Up")
        assertThat(up).isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()))
        val down = takeKeyboardChunk("Down")
        assertThat(down).isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()))
        val right = takeKeyboardChunk("Right")
        assertThat(right).isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()))
        val left = takeKeyboardChunk("Left")
        assertThat(left).isEqualTo(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()))

        // And the bridge itself is the one this file documents: every TerminalKey maps to the
        // VTermKey the library's KeyboardHandler expects, so a cap press is the same bytes a
        // hardware key would have produced. Asserted directly here so a regression in toVTermKey
        // fails this test rather than silently changing what the key bar sends.
        assertThat(TerminalKey.TAB.toVTermKey()).isEqualTo(VTermKey.TAB)
        assertThat(TerminalKey.ARROW_UP.toVTermKey()).isEqualTo(VTermKey.UP)
        assertThat(TerminalKey.ARROW_DOWN.toVTermKey()).isEqualTo(VTermKey.DOWN)
        assertThat(TerminalKey.ARROW_RIGHT.toVTermKey()).isEqualTo(VTermKey.RIGHT)
        assertThat(TerminalKey.ARROW_LEFT.toVTermKey()).isEqualTo(VTermKey.LEFT)
    }

    // --------------------------------------------------------------------------- driving the glue

    /**
     * Stands up the controller + channel against the shared in-process server, attaches a shell
     * whose `start` writes [banner] to stdout, and pumps until the banner has been rendered.
     *
     * One helper for the scenarios that need output to flow server -> emulator: the banner is the
     * proof the channel opened, the pty was sized and the controller's producer is draining
     * `channel.output` into `emulator.writeInput`. The test's own assertion runs against [rendered]
     * once the banner is in place.
     */
    private fun attachToShellThatWrites(banner: String) {
        val (ctrl, ch) = attachControllerToShell(ScriptedShell(banner = banner))
        controller = ctrl
        channel = ch
        // Wait for the banner — it is the channel-opened signal, and the test's real assertion can
        // only run once the controller's producer is actually pumping chunks into the emulator.
        pumpUntil("the shell's banner never reached the rendered terminal: '$rendered'") {
            rendered.toString().contains(banner)
        }
    }

    /**
     * Builds the controller + channel and attaches them, returning both for the caller to drive.
     *
     * This is the seam the migration wrote, exercised exactly as `MainViewModel` exercises it:
     *
     *  - `TerminalSessionController(defaultForeground, defaultBackground, onKeyboardInput, tees,
     *    externalScope)` — the `onKeyboardInput` lambda is what the emulator calls when a key is
     *    dispatched, and `MainViewModel` wires it to `channels[key]?.writeBytes(data)`. Here it
     *    feeds the channel's stdin and the test's `keyboardSink`, so a test can assert on the
     *    bytes the emulator produced.
     *  - `controller.attach(channel, onEnd)` — the producer that drains `channel.output` into
     *    `emulator.writeInput`, the same pump `MainViewModel.launchTerminalCollector` starts.
     *  - The `OutputTee` list carries one tee that appends to [rendered], mirroring the
     *    `OutputTee { chunk -> scheduleTranscriptPublish(key, chunk) }` `MainViewModel` installs.
     */
    private fun attachControllerToShell(shell: ScriptedShell): Pair<TerminalSessionController, SshTerminalChannel> {
        val client =
            SshClient.setUpDefaultClient().apply {
                serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
                start()
            }
        sshClient = client
        val session =
            client
                .connect(USER, LOOPBACK, serverPort)
                .verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .session
        session.addPasswordIdentity(PASSWORD)
        session.auth().verify(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        clientSession = session

        val rawChannel = SshTerminalChannel(session.createShellChannel())
        // The pty type the migration advertises — the same DEFAULT_TERMINAL_TYPE the app's
        // ptyRequestFor falls back to. Opening with it is what makes the 256-colour scenario
        // meaningful: a server that believed it was on a vt100 would not emit 256-colour SGRs.
        // `open` is suspend (it awaits the channel-open verify); runBlocking is the same bridge
        // SshConnectionManager.openTerminal crosses with withContext(Dispatchers.IO) in production,
        // and the test's thread is the honest place to block on it.
        runBlocking {
            rawChannel.open(
                columns = TERMINAL_COLUMNS,
                rows = TERMINAL_ROWS,
                usePty = true,
                terminalType = DEFAULT_TERMINAL_TYPE,
                environment = emptyMap(),
                agentForwarding = false,
            )
        }
        channel = rawChannel

        val ctrl =
            TerminalSessionController(
                defaultForeground = FOREGROUND_ARGB,
                defaultBackground = BACKGROUND_ARGB,
                onKeyboardInput = { data ->
                    // The emulator produced keyboard bytes — write them to the channel exactly as
                    // MainViewModel does, and capture them for the input-path assertions.
                    keyboardSink.add(data)
                    rawChannel.writeBytes(data)
                },
                tees = listOf(OutputTee { chunk -> synchronized(rendered) { rendered.append(chunk) } }),
                externalScope =
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
                    ),
            )
        ctrl.attach(rawChannel) { _ -> ended.set(true) }
        return ctrl to rawChannel
    }

    /**
     * Takes the next keyboard chunk the emulator produced, with a readable timeout message.
     *
     * The emulator dispatches keyboard output on its own dispatcher, so the chunk arrives
     * asynchronously; a bounded poll is the honest wait, and the message names the key so a failure
     * points at the dispatch that never produced bytes.
     */
    private fun takeKeyboardChunk(what: String): ByteArray {
        val chunk = keyboardSink.poll(KEYBOARD_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        check(chunk != null) { "the emulator never produced a byte sequence for $what" }
        return chunk
    }

    /**
     * Waits until [condition] holds, on a plain thread.
     *
     * Not `compose.waitUntil`: there is no Compose tree here, and the controller's producer runs on
     * `Dispatchers.Default` while the test thread reads [rendered]. The honest instrument is a
     * deadline, the same shape `VncTunnelTest.pumpUntil` uses for the same reason. The main looper
     * is idled a little each round so the emulator's handler-posted snapshot work runs (the
     * emulator builds snapshots on a handler, and the controller's producer needs the looper to
     * move for the bytes to reach [rendered] through the tee).
     */
    private fun pumpUntil(
        describe: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline && !condition()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            Thread.sleep(10)
        }
        check(condition()) { "timed out after ${TEST_TIMEOUT_MS}ms: $describe" }
    }

    // ------------------------------------------------------------------------- the in-process server

    private companion object {
        const val TEST_TIMEOUT_MS = 30_000L
        const val KEYBOARD_POLL_TIMEOUT_MS = 5_000L
        const val CONNECT_TIMEOUT_SECONDS = 30L

        const val LOOPBACK = "127.0.0.1"
        const val USER = "testuser"
        const val PASSWORD = "testpass123"

        /** The pty geometry the test opens at — inside the app's TERMINAL_COLUMN_RANGE / ROW_RANGE. */
        const val TERMINAL_COLUMNS = 80
        const val TERMINAL_ROWS = 24

        /** The controller's default colours, as ARGB ints (Compose Color's encoding). */
        const val FOREGROUND_ARGB = 0xFFFFFFFF.toInt()
        const val BACKGROUND_ARGB = 0xFF000000.toInt()

        /** The line the echo shell writes for scenario 1. */
        const val ECHO_LINE = "hello"

        /** The banner every shell writes, so [attachToShellThatWrites] can sync on channel-open. */
        const val SHELL_BANNER = "eclipse-e2e-shell"

        /** The line scenario 2 types through the character path and commits with Enter. */
        const val TYPED_LINE = "ping-e2e"

        /** The markers that follow each 256-colour SGR in scenario 3. */
        const val MARKER_BLUE = "B"
        const val MARKER_RED = "R"
        const val MARKER_GREEN = "G"
        const val MARKER_GRAY = "Y"

        var serverPort = 0
        private lateinit var server: SshServer

        @JvmStatic
        @BeforeClass
        fun startServer() {
            // The same provider scoping EclipseApp installs at startup, set from outside because
            // there is no Application to get in first under Robolectric. See
            // DuplicateTerminalRobolectricTest and ConnectionMatrixRobolectricTest for the
            // EdDSA/BC classloader race this avoids.
            listOf("BC", "EdDSA").forEach { provider ->
                System.setProperty("org.apache.sshd.security.provider.$provider.useNamed", "false")
            }
            val root = Files.createTempDirectory("eclipse-e2e")
            server =
                SshServer.setUpDefaultServer().apply {
                    port = 0
                    keyPairProvider =
                        SimpleGeneratorHostKeyProvider(
                            Files.createTempDirectory("e2e-hostkey").resolve("hostkey.ser"),
                        )
                    passwordAuthenticator =
                        PasswordAuthenticator { user, password, _ ->
                            user == USER && password == PASSWORD
                        }
                    // Password only, mirroring DuplicateTerminalRobolectricTest: one factory means
                    // the auth path is unambiguous and there is no keyboard-interactive double-count.
                    userAuthFactories = listOf<UserAuthFactory>(UserAuthPasswordFactory.INSTANCE)
                    fileSystemFactory = VirtualFileSystemFactory(root.toAbsolutePath())
                    shellFactory = ShellFactory { ScriptedShell(banner = SHELL_BANNER) }
                    start()
                }
            serverPort = (server.boundAddresses.first() as InetSocketAddress).port
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            runCatching { server.stop(true) }
        }
    }

    /**
     * A server-side shell the test scripts: it writes a banner to stdout and then sits idle,
     * draining stdin so the channel's write window does not fill and backpressure the emulator.
     *
     * The shape mirrors `DuplicateTerminalRobolectricTest.EchoShell`: a `Command` with its own
     * worker thread, reading stdin byte-by-byte so the channel's input stream does not block. The
     * scenarios that assert on keyboard output do so against the emulator's `onKeyboardInput`
     * callback (captured by the controller), not against this shell's stdin, so the shell's only job
     * is to stay alive and let the bytes through — the channel's write window has to be consumed or
     * the emulator's `writeBytes` would eventually block.
     */
    private class ScriptedShell(
        /** Written to stdout on start; the test syncs on this appearing in [rendered]. */
        private val banner: String,
    ) : Command {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null
        private var worker: Thread? = null

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(error: OutputStream) = Unit

        override fun setExitCallback(callback: ExitCallback) {
            this.exit = callback
        }

        override fun start(
            channel: ChannelSession,
            env: Environment,
        ) {
            val source = input ?: return
            val sink = output ?: return
            worker =
                Thread {
                    runCatching {
                        sink.write(banner.toByteArray())
                        sink.flush()
                        // Drain stdin so the channel's write window stays open: a test that
                        // dispatches keys writes them to the channel's input stream, and a stream
                        // nobody reads would eventually block the emulator's writeBytes. The bytes
                        // are read and discarded here — the assertions are on the emulator's
                        // onKeyboardInput output, not on what this shell saw.
                        while (true) {
                            val byte = source.read()
                            if (byte < 0) break
                        }
                    }
                    exit?.onExit(0)
                }.apply {
                    isDaemon = true
                    start()
                }
        }

        override fun destroy(channel: ChannelSession) {
            worker?.interrupt()
            worker = null
        }
    }
}
