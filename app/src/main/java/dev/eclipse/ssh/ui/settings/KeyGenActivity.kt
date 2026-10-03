package dev.eclipse.ssh.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ssh.SshKeyAlgorithm

/**
 * Generate an SSH key pair, in its own window.
 *
 * The row this replaces opened a `KeyGenDialog` over Settings, and the dialog's content is what this
 * screen shows: the paragraph explaining what the two files are, one chip per algorithm, and the
 * "Generate & save" action. What the dialog could not be is the flow behind that action. The flow is
 * *pick an algorithm, generate a key pair, then create two documents through two other apps' pickers
 * in sequence*, and the generation in the middle of it is seconds to tens of seconds of prime
 * searching. A window owns that; a dialog owns a box that is measured and drawn by the screen behind
 * it, and the whole chain - two external pickers and the work between them - was being carried in
 * `MainActivity`'s composition on the dialog's behalf.
 *
 * **What this screen draws, and what it does not own.** The chain itself is [KeyGenViewModel]'s: the
 * chosen algorithm, the generated pair, the flag that says a generation is running, and the hold on
 * [dev.eclipse.ssh.feature.vault.VaultUnlockGate] all live there. This file is the window around it -
 * the layout, the two launchers, and the two effects that carry an instruction from the view model
 * into the window. That division is the repair for the audit's D2 finding: the pair exists nowhere
 * else in the world at the moment it is generated, and a `remember` holding it was destroyed by a
 * rotation, taking the key with it and leaving the gate held by a composition that no longer existed.
 * A view model survives exactly that, and is cleared exactly when the user leaves.
 *
 * The one thing that must not move back is where the generation runs. It is on `Dispatchers.Default`,
 * never in the click handler, for the reason recorded at [KeyGenViewModel.generateAndSave]: a 4096-bit
 * generation in a click handler is a main-thread block past the ANR threshold, and the app used to
 * freeze and offer to be killed for it.
 */
@AndroidEntryPoint
class KeyGenActivity : SettingsDestinationActivity() {

    /**
     * The chain, scoped to this window.
     *
     * `by viewModels()` rather than a `@Singleton` beside the gate: the gate is process-wide because a
     * lifecycle observer in *another* window reads it, and nothing outside this window has any
     * business reading a private key. See [KeyGenViewModel].
     */
    private val viewModel: KeyGenViewModel by viewModels()

    override val screenTitle = "Generate SSH key pair"

    // Neither the settings snapshot nor the repository is read: nothing on this screen is a
    // preference. The algorithm is chosen per key pair and the files go where the user points the
    // pickers, so there is nothing here for the store to hold.
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        val report = LocalSettingsReport.current

        // Both are declared before the effect below, which reaches both of them - a lambda cannot
        // capture a local declared after it. Which of the two comes first no longer means anything:
        // neither callback launches the other now, and the chain's order is the view model's.
        //
        // Both callbacks do nothing but hand the result to the view model. What happens next - the
        // write, the second picker, the end of the chain and the gate - is decided there, which is
        // what makes the chain survive this composition being torn down and rebuilt.
        val publicKeyPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? -> viewModel.onPublicKeyPicked(uri) }

        val privateKeyPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-pem-file"),
        ) { uri: Uri? -> viewModel.onPrivateKeyPicked(uri) }

        // The window catches up to the chain here, and this effect is the whole reason the two can be
        // separated. It runs on the first composition after a rotation as much as on the first one
        // after a tap, so a chain that reached "time to open the private-key picker" while the window
        // was being rebuilt opens that picker as soon as there is a window to open it from. Retiring
        // the request inside the same block is what stops the rebuild from opening it twice: the block
        // does not suspend, so a configuration change cannot be delivered between the launch and the
        // retirement.
        val request = viewModel.pickerRequest
        LaunchedEffect(request) {
            if (request != null) {
                when (request.kind) {
                    KeyPickerKind.PRIVATE_KEY -> privateKeyPicker.launch(request.suggestedName)
                    KeyPickerKind.PUBLIC_KEY -> publicKeyPicker.launch(request.suggestedName)
                }
                viewModel.onPickerOpened(request)
            }
        }

        // The chain reports its failures as state, because the reporter is a composition local and a
        // view model cannot reach one. Reported and then forgotten, so the same sentence can be shown
        // twice if the user manages to fail the same way twice.
        val message = viewModel.message
        LaunchedEffect(message) {
            if (message != null) {
                report(message)
                viewModel.consumeMessage()
            }
        }

        SettingsSection(screenTitle) {
            Text(
                "A private key (PEM) and a public key (OpenSSH format) will be saved as separate files. " +
                    "Keep the private key secret and add the public key to your server's authorized_keys.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp),
            )
            // `FlowRow`, not the dialog's horizontally scrolling row: a strip of chips hides the
            // algorithms that do not fit behind a gesture nobody knows is there, and the window has
            // the height to show all three at once. The names are the dialog's, straight off the enum,
            // so a fourth algorithm cannot be added without appearing here.
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SshKeyAlgorithm.entries.forEach { algorithm ->
                    FilterChip(
                        selected = viewModel.selected == algorithm,
                        onClick = { viewModel.select(algorithm) },
                        enabled = !viewModel.busy,
                        label = { Text(algorithm.label) },
                    )
                }
            }
        }

        // Outside the card, where the dialog's confirm button sat relative to its content. The
        // dialog's "Cancel" has no twin here: it dismissed a box that was covering Settings, and this
        // screen's back arrow leaves it just as well - there is no state here worth asking the user to
        // discard, only a choice of algorithm.
        Spacer(Modifier.height(14.dp))
        if (viewModel.busy) {
            // Not in the dialog, and it has to be here. The dialog had no such state because it froze:
            // the work was on the main thread, so the screen showed nothing at all until it finished.
            // With the work off the main thread the screen stays responsive, which leaves the tap with
            // no visible effect for as long as a slow phone takes to find primes - and a user who
            // believes a tap was dropped taps again. This says it was not, and the disabled action
            // below is what makes the second tap harmless.
            Text(
                "Generating the key pair…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Button(onClick = { viewModel.generateAndSave() }, enabled = !viewModel.busy) { Text("Generate & save") }
    }
}
