package dev.eclipse.ssh.ui.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.ssh.GeneratedKeyPair
import dev.eclipse.ssh.ssh.SshKeyAlgorithm
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the chain's two pickers the window has to open. */
enum class KeyPickerKind { PRIVATE_KEY, PUBLIC_KEY }

/**
 * A picker the window still has to open, and the file name to offer it.
 *
 * A value rather than a pair of callbacks into the composition, because the picker is started by the
 * window and the chain is owned here: on a rotation the window is rebuilt and the launcher it hands
 * out is a different one, so the instruction to launch has to outlive the composition that first
 * carried it. The composition observes this and launches; [KeyGenViewModel.onPickerOpened] is what
 * retires it, so a re-composition after the launch does not open a second picker.
 */
data class KeyPickerRequest(val kind: KeyPickerKind, val suggestedName: String)

/**
 * The chain behind "Generate & save", held where a rotation cannot reach it.
 *
 * This is the repair for a defect the audit recorded as D2. The chain is *pick an algorithm, generate
 * a key pair, then create two documents through two other apps' pickers in sequence*, and the
 * generation in the middle of it produces the only copy of a private key that will ever exist: it is
 * not in a file yet, it is not in an Intent, and no store has seen it. Every piece of that state used
 * to live in `KeyGenActivity`'s composition, in `remember`, which meant a rotation anywhere in the
 * chain destroyed the pair — and, worse, left [VaultUnlockGate] held by a composition that no longer
 * existed, so the auto-lock countdown stayed suspended for the rest of the process's life with
 * nothing on screen to say so.
 *
 * A view model rather than another `@Singleton` beside the gate, although the gate is the same shape
 * of answer to a related problem. The gate is process-wide because a *different* window's lifecycle
 * observer reads it; nothing outside this window needs the key pair, and a singleton holding a
 * private key would keep it alive past the window that generated it. A view model is scoped to
 * exactly the window, survives exactly the re-creation the pair has to survive, and is cleared
 * exactly when the user leaves — which is also the one moment the gate's hold must be given up.
 *
 * It is deliberately *not* the app's `MainViewModel`: that one is a `@HiltViewModel` with no scope of
 * its own, and [HostFormActivity] records why a second owner of it would be wrong. This view model
 * has one owner, and being owned by one window is the whole point of it.
 *
 * The chain runs on [viewModelScope], not on the composition's. A rotation mid-generation would
 * otherwise cancel a prime search that may be tens of seconds old and throw the work away; here the
 * work carries on and the rebuilt window catches up to whatever the chain has reached.
 */
@HiltViewModel
class KeyGenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultUnlockGate: VaultUnlockGate,
) : ViewModel() {

    /**
     * The algorithm the next generation will use.
     *
     * Held here rather than in the composition for the same reason as everything else, and it is the
     * one piece whose loss on a rotation was merely annoying rather than destructive: the chips would
     * silently fall back to RSA 2048 under the user's finger.
     */
    var selected: SshKeyAlgorithm by mutableStateOf(SshKeyAlgorithm.RSA_2048)
        private set

    /**
     * True from the tap until the chain ends — through the generation, the private-key picker, the
     * write, and the public-key picker.
     *
     * It disables the action so a second tap cannot start a second generation and a second pair of
     * pickers over the first pair, and it is what puts a status line on the screen while the first
     * one runs. Surviving re-creation is the point: a `remember` that reset to false on a rotation
     * would arm the action again while the first pair was still in flight.
     */
    var busy: Boolean by mutableStateOf(false)
        private set

    /**
     * The generated pair, from the generation until the last of the two writes resolves it.
     *
     * This is the value the whole view model exists for. It is dropped — not merely replaced — at
     * every end of the chain, so a pair whose private half never reached the file the user chose is
     * not left sitting in memory offering a retry this screen does not have.
     */
    var pendingKeyPair: GeneratedKeyPair? by mutableStateOf<GeneratedKeyPair?>(null)
        private set

    /** The picker the window has yet to open, or null when the chain is not waiting on one. */
    var pickerRequest: KeyPickerRequest? by mutableStateOf<KeyPickerRequest?>(null)
        private set

    /**
     * A sentence for the user, or null when there is nothing to say.
     *
     * A value the composition reports rather than a report made here: the reporter is a composition
     * local ([LocalSettingsReport]), which a view model cannot reach. Leaving it as state also means a
     * failure that happens while the window is mid-rotation still reaches the user once it is back,
     * instead of being reported into a snackbar host that no longer exists.
     */
    var message: String? by mutableStateOf<String?>(null)
        private set

    /** The user picked an algorithm. Ignored while the chain runs, matching the disabled chips. */
    fun select(algorithm: SshKeyAlgorithm) {
        if (busy) return
        selected = algorithm
    }

    /** The message has been shown; forget it so the next one is a different value. */
    fun consumeMessage() {
        message = null
    }

    /**
     * The chain is over: nothing is pending, nothing is running, and no picker is owed.
     *
     * Three states have to go together for that to be true, and [pickerRequest] is the one that is
     * easy to leave behind — it is retired when the window opens the picker it asks for, and every
     * path that *ends* the chain must retire it too. A request left standing is a request the next
     * composition will act on: `KeyGenActivity`'s effect launches whatever it finds there, so a stale
     * one would open a picker for a chain that is already finished, over a pair that no longer
     * exists. Nothing reaches that today — the only ways into these callbacks are pickers that the
     * effect itself launched, and it has retired the request by then — but "nothing reaches it today"
     * is exactly the kind of guarantee that stops being true when the code around it moves, and the
     * view model's own methods should leave it in a state the view model can explain.
     *
     * The gate is deliberately *not* part of this. Its hold is given up and taken again with an
     * ordering that is the subtle part of this class, and folding it in here would hide that.
     */
    private fun endChain() {
        pendingKeyPair = null
        pickerRequest = null
        busy = false
    }

    /**
     * Generate a pair, then start the chain that writes it.
     *
     * The generation is on [Dispatchers.Default], never in the click handler, for the reason the
     * screen it replaces recorded: RSA key generation searches for primes, so its cost is unbounded
     * rather than merely large — seconds on a fast phone and tens of seconds on a slow one, well past
     * the 5 s ANR threshold. `Dispatchers.Default` rather than IO because this is CPU-bound work with
     * no I/O in it, and it would be wrong to occupy the pool a disk write may be waiting on.
     */
    fun generateAndSave() {
        if (busy) return
        // Captured at the tap rather than read inside the coroutine: the chips are disabled while the
        // chain runs, so the two cannot disagree today, and reading the state here is what stops that
        // from becoming a silent race if the control ever changes.
        val algorithm = selected
        busy = true
        // Held before the coroutine starts, not when the private-key picker opens. The wait between
        // the two is the generation itself, and the shortest auto-lock delay the app offers is a
        // minute, so without the flag the vault could re-arm while a user watched the status line and
        // the picker that follows would open behind a PIN prompt they had not asked for.
        vaultUnlockGate.hold()
        viewModelScope.launch {
            val generated = withContext(Dispatchers.Default) { runCatching { algorithm.generate() } }
            generated
                .onSuccess { pair ->
                    pendingKeyPair = pair
                    pickerRequest = KeyPickerRequest(KeyPickerKind.PRIVATE_KEY, pair.defaultPrivateName)
                }
                .onFailure { error ->
                    // A cancellation is this view model being cleared, and it must keep travelling:
                    // swallowing it here would leave the coroutine running in a scope already torn
                    // down, and the gate's hold is given up by onCleared for that same path.
                    if (error is CancellationException) throw error
                    // Reachable: the generator rejects a coordinate it cannot encode, and a provider
                    // can refuse an algorithm outright. Thrown from a click handler this crashed the
                    // activity; here it is a message, and the chain is over before it started, so the
                    // flag comes down with it.
                    vaultUnlockGate.release()
                    endChain()
                    // "Could not generate the key pair" rather than the shell's "Could not save …"
                    // wording: nothing was being saved at this point, so reportWriteFailure would put
                    // the wrong verb on it. The detail is spelled exactly the way that helper spells
                    // it, so the two failures read as one family.
                    message = "Could not generate the key pair: ${detailOf(error)}"
                }
        }
    }

    /**
     * The private-key picker came back, with a location or with nothing.
     *
     * This is the half of the chain that can continue: a location starts the write, and the write is
     * what asks for the second picker. Cancelling ends the chain here, which is why the hold is given
     * up on entry and taken again only for the path that carries on.
     */
    fun onPrivateKeyPicked(uri: Uri?) {
        // Released and then taken again below, which is not a no-op: the release is the path the user
        // takes by cancelling this picker, where the chain ends here and the countdown may resume,
        // and the hold below is what covers the write and the second picker. Ordering matters more
        // than it looks - see the hold's own comment.
        vaultUnlockGate.release()
        val pair = pendingKeyPair
        if (uri == null || pair == null) {
            // Cancelled, or nothing was generated to write. Same end of the chain.
            endChain()
            return
        }
        // Held *across* the write rather than taken again when the public-key picker is about to
        // open. The write is a binder round trip into whichever app owns the URI, and a cloud
        // provider can sync the whole file before the stream closes, so the gap between this callback
        // and the second picker is however long that takes - not a moment. The app is in the
        // foreground for the whole gap, and the auto-lock observer has no way to tell that a chain is
        // still running: with the flag down, a slow write followed by a slow second picker looks
        // exactly like a user who left, and the vault re-locks with the fresh key pair still only in
        // this view model.
        vaultUnlockGate.hold()
        viewModelScope.launch {
            writeDocument(context, uri, pair.privatePem.toByteArray(Charsets.UTF_8))
                .onSuccess {
                    pickerRequest =
                        KeyPickerRequest(KeyPickerKind.PUBLIC_KEY, "${pair.defaultPrivateName}.pub")
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    // The chain ends here, so the flag and the screen both come back. The pair is
                    // dropped rather than kept: its private half never reached the file the user
                    // chose, and leaving it in state would offer a retry this screen does not have.
                    vaultUnlockGate.release()
                    endChain()
                    message = "Could not save private key: ${detailOf(error)}"
                }
        }
    }

    /**
     * The public-key picker came back. It is the second half of the chain and the end of it, so the
     * gate comes down and the chain's state clears here whether or not the user picked a location.
     */
    fun onPublicKeyPicked(uri: Uri?) {
        vaultUnlockGate.release()
        // Captured before [endChain] drops it: the write below still needs the pair.
        val pair = pendingKeyPair
        endChain()
        if (uri == null || pair == null) return
        viewModelScope.launch {
            writeDocument(context, uri, pair.publicLine.toByteArray(Charsets.UTF_8))
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    message = "Could not save public key: ${detailOf(error)}"
                }
        }
    }

    /**
     * The window has opened the picker [request] asked for.
     *
     * Retiring it is what keeps a re-composition — and, more to the point, the re-composition that a
     * rotation causes — from opening the same picker a second time. Compared rather than cleared
     * blindly so that a window catching up on an older request cannot retire a newer one.
     */
    fun onPickerOpened(request: KeyPickerRequest) {
        if (pickerRequest == request) pickerRequest = null
    }

    /**
     * The window is gone for good — this is a finish, not a rotation, which is why the view model is
     * being cleared at all.
     *
     * Giving up the gate here is the second half of the D2 repair. Every other release in this class
     * is reached from a picker callback, and a callback only runs if the composition that owns the
     * screen is still there to receive it: a user who backs out of the window mid-chain used to leave
     * the flag held forever, suspending the auto-lock countdown for the rest of the process's life
     * with nothing on screen to say so. The pair goes with it, so a private key that was never
     * written anywhere does not outlive the screen that generated it.
     */
    override fun onCleared() {
        vaultUnlockGate.release()
        pendingKeyPair = null
    }
}

/**
 * The detail half of a failure sentence, spelled the way [reportFailure] spells it.
 *
 * `Throwable.message` is frequently null and occasionally blank, and a message that ended in
 * "…: " with nothing after it would read as a truncation rather than as an explanation. The class
 * name is the fallback there, which is the same fallback the shell's own reporting uses.
 */
private fun detailOf(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

/**
 * Writes [bytes] to the SAF document at [uri], off the main thread.
 *
 * This was `MainActivity`'s helper, which is `private` there and therefore out of reach of a window
 * that is no longer part of that file; it moved here when the chain did, because the write is now
 * started by the view model rather than by an activity-result callback. The reasoning is that file's
 * and still applies: a document write is a binder round trip into whichever provider owns the URI —
 * which may be a cloud backend that syncs over the network before the stream closes. Inline it drops
 * frames at best and can block long enough to ANR at worst, and a revoked grant or a full volume
 * would throw straight out of the callback. The [Result] is what lets the caller report the failure
 * rather than crash on it.
 *
 * Both keys go through it, and neither write is a settings write: a failure here is a document that
 * did not land, so the caller reports it naming the document rather than in the store's vocabulary.
 */
private suspend fun writeDocument(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("The selected location could not be opened for writing")
            stream.use { it.write(bytes) }
        }
    }
