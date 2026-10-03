package dev.eclipse.ssh

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.ssh.SshKeyAlgorithm
import dev.eclipse.ssh.ui.settings.KeyGenViewModel
import dev.eclipse.ssh.ui.settings.KeyPickerKind
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The chain behind "Generate & save", and where its state lives.
 *
 * This is the regression cover for the audit's D2 finding. The chain used to be carried by
 * `KeyGenActivity`'s composition: the generated pair sat in a `remember`, and [VaultUnlockGate] was
 * held by a composition that released it only from its own picker callbacks. A rotation anywhere in
 * the chain therefore did two things at once — destroyed the only copy of a private key that will
 * ever exist, and left the gate held for the rest of the process's life, suspending the auto-lock
 * countdown with nothing on screen to say so.
 *
 * What is asserted here is the chain's own protocol, driven directly rather than through the window:
 * that a generation puts a pair in the view model's hands and asks for the first picker, that
 * cancelling that picker ends the chain and gives the gate back, and that clearing the view model —
 * which is what closing the window does for good — gives it back too. The chain is held here rather
 * than in the window's composition, which is the repair, and `KeyGenActivityRobolectricTest` pins the
 * half of that only a window can show: that a tap on a chip writes into this view model rather than
 * into a `remember` beside it. Neither test asserts survival across `scenario.recreate()` — this
 * suite records in two places that a re-created activity is handed a new view model, so it cannot
 * tell a configuration change from a teardown; that half is the framework's `by viewModels()`
 * contract.
 *
 * The generation is real, on a real `Dispatchers.Default` thread, and that is why the fixtures are
 * ECDSA: a P-256 generation is milliseconds where a 4096-bit RSA one is tens of seconds. The wait
 * below idles the paused main looper rather than assuming a duration, because the resumption it is
 * waiting for is posted there and nothing else will run it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class KeyGenViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * The real gate, not a stand-in, and owned by this test rather than by the Hilt graph.
     *
     * A stub would let the assertions below agree with the stub instead of with the flag the
     * auto-lock observer in `MainActivity` actually reads. Constructed directly because the gate is a
     * `@Singleton` with no collaborators: `VaultUnlockGate()` is the same object the graph would hand
     * out, minus the graph.
     */
    private val gate = VaultUnlockGate()

    private fun viewModel() = KeyGenViewModel(context, gate)

    /**
     * Generates a pair and waits for the chain to reach the first picker.
     *
     * `viewModelScope` runs on the main dispatcher, which Robolectric keeps paused, so the resumption
     * that follows the generation sits in the main looper's queue until something idles it. Idling
     * and sleeping in turn is what lets a real background thread finish and hand over.
     */
    private fun generateUntilTheFirstPicker(viewModel: KeyGenViewModel) {
        viewModel.select(SshKeyAlgorithm.ECDSA_P256)
        viewModel.generateAndSave()
        val deadline = System.currentTimeMillis() + 15_000
        while (viewModel.pickerRequest == null) {
            if (System.currentTimeMillis() > deadline) fail("the chain never reached the first picker")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun aGenerationHoldsTheGateAndAsksForThePrivateKeyPicker() {
        val viewModel = viewModel()

        generateUntilTheFirstPicker(viewModel)

        assertThat(viewModel.pendingKeyPair).isNotNull()
        assertThat(viewModel.busy).isTrue()
        assertThat(viewModel.pickerRequest?.kind).isEqualTo(KeyPickerKind.PRIVATE_KEY)
        // The name the picker is offered, off the algorithm's own default rather than spelled here.
        assertThat(viewModel.pickerRequest?.suggestedName)
            .isEqualTo(SshKeyAlgorithm.ECDSA_P256.defaultPrivateName)
        assertThat(gate.pickerActive).isTrue()
    }

    @Test
    fun cancellingThePrivateKeyPickerEndsTheChainAndGivesTheGateBack() {
        val viewModel = viewModel()
        generateUntilTheFirstPicker(viewModel)

        viewModel.onPrivateKeyPicked(null)

        assertThat(viewModel.pendingKeyPair).isNull()
        assertThat(viewModel.busy).isFalse()
        assertThat(viewModel.pickerRequest).isNull()
        assertThat(gate.pickerActive).isFalse()
    }

    /**
     * The window closing mid-chain is the leak the finding is about, and this is where it is closed.
     *
     * Driven through a `ViewModelStore` because that is what re-creation and finishing both go
     * through, and because `onCleared` is not reachable from here any other way. Reached through
     * [ViewModelProvider] rather than by hand because that is the path the framework itself takes:
     * a store is populated by a provider, and going round it would test something the app never does.
     */
    @Test
    fun clearingTheViewModelMidChainGivesTheGateBackAndDropsThePair() {
        val viewModel = viewModel()
        generateUntilTheFirstPicker(viewModel)
        assertThat(gate.pickerActive).isTrue()

        val store = ViewModelStore()
        val provider = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
            },
        )
        provider[KeyGenViewModel::class.java]
        store.clear()

        assertThat(gate.pickerActive).isFalse()
        assertThat(viewModel.pendingKeyPair).isNull()
    }
}
