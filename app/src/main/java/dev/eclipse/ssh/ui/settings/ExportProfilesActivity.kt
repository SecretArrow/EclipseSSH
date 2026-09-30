package dev.eclipse.ssh.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.backup.ProfileExporter
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.linux.LocalLinuxHost
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.security.normalizePastedSecret
import dev.eclipse.ssh.ui.SecretFieldKeyboard
import dev.eclipse.ssh.ui.SecretPasteButton
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export one or more connection profiles, credentials included, in a window of its own.
 *
 * The host card's "Export profile" moves a single account and stays where it is; this is the door for
 * moving several at once, which is the thing that had no answer at all. It could not be a mode on the
 * host list — the list's gestures are all taken, and a long-press that sometimes means "select" and
 * sometimes means "menu" is a screen that has to be learned twice — so the selection lives on a
 * screen whose entire subject is the selection, reached from Settings → Backup & restore beside the
 * vault export it deliberately is not.
 *
 * **What it writes is not a backup.** A vault export is the whole workspace and carries no secret;
 * this carries the secrets and no settings. The two are separated on purpose and the file says which
 * it is (see [dev.eclipse.ssh.data.backup.VaultBackup.KIND]), because they are the two halves of a
 * device migration and a user who wants one is not usually asking for the other: a vault goes into
 * cloud storage and has to be paired with credentials that never left the device, while a profile
 * export is a file you hand to your other phone and expect to work.
 *
 * The window holds the passphrase for exactly as long as it takes to hand it to the export, the same
 * contract [ExportBackupActivity] keeps: never logged, never written to the settings store, and the
 * field cleared at launch rather than left on screen behind a finished file.
 */
@AndroidEntryPoint
class ExportProfilesActivity : SettingsDestinationActivity() {

    @Inject lateinit var hostRepository: HostRepository

    @Inject lateinit var profileExporter: ProfileExporter

    /** Bracket for the picker, for the reason [ExportBackupActivity] gives: an open picker reports
     * the whole app as backgrounded, and the auto-lock countdown is decided on process-wide state. */
    @Inject lateinit var vaultUnlockGate: VaultUnlockGate

    /** The audited clipboard boundary, injected so the field's Paste is not a second, unaudited way
     * in — the same singleton `MainViewModel.pasteSecret` reads through. */
    @Inject lateinit var secureClipboard: SecureClipboard

    override val screenTitle = "Export profiles"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        val hosts by hostRepository.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
        // Checked, not selected-and-forgotten: the ids survive an edit to a host's name or address
        // while this screen is open, so a rename behind the window cannot silently drop a profile
        // from the export the user is assembling. An id that stops existing simply stops matching.
        var selected by remember { mutableStateOf(emptySet<String>()) }
        var passphrase by remember { mutableStateOf("") }
        // The passphrase the picker was launched with; the field itself is cleared at launch, so this
        // is the only copy alive while another app is on screen.
        var pendingPassphrase by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current

        // The local Ubuntu environment is not a profile anyone can move: its id is synthesized at
        // runtime and its "host" is this device, so exporting it would write an entry that restores
        // as a server called Local Ubuntu pointing at localhost. The writer drops it too — this is
        // the same rule, shown rather than only enforced.
        val profiles = hosts.filterNot { LocalLinuxHost.isLocalHost(it.id) }
        val chosen = profiles.filter { it.id in selected }

        val exportPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            vaultUnlockGate.release()
            val pass = pendingPassphrase
            pendingPassphrase = null
            passphrase = ""
            if (uri == null || pass == null) {
                busy = false
            } else {
                scope.launch {
                    try {
                        // IO, not the main thread: the export reads six secrets per profile out of
                        // DataStore, encrypts, and writes through a content resolver that may be a
                        // binder round trip into a cloud provider.
                        val count = withContext(Dispatchers.IO) { profileExporter.export(chosen, pass, uri) }
                        report("Exported $count profile(s)")
                    } catch (failure: Throwable) {
                        reportWriteFailure(report, "the profile export", failure)
                    } finally {
                        busy = false
                    }
                }
            }
        }

        SettingsSection("Profiles") {
            when {
                profiles.isEmpty() -> Text(
                    "There is nothing to export yet. A profile is a saved server; add one from the hosts " +
                        "list and it will appear here, with whatever password, key or passphrase you have " +
                        "saved for it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
                else -> {
                    // Every profile or none, one tap. The alternative - a checkbox per row and nothing
                    // else - makes "move everything to my new phone", the reason this screen exists,
                    // a row-by-row chore.
                    val allChosen = profiles.all { it.id in selected }
                    SettingRow(
                        Icons.Default.DoneAll,
                        if (allChosen) "Clear selection" else "Select all",
                        if (allChosen) "Nothing will be exported" else "Every profile on this device",
                    ) {
                        TextButton(
                            onClick = { selected = if (allChosen) emptySet() else profiles.map { it.id }.toSet() },
                            enabled = !busy,
                            modifier = Modifier.semantics {
                                contentDescription = if (allChosen) "Clear selection" else "Select all"
                            },
                        ) { Text(if (allChosen) "Clear" else "All") }
                    }
                    profiles.forEach { host ->
                        SettingRow(Icons.Default.Person, host.name, host.describeEndpoint()) {
                            Checkbox(
                                checked = host.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + host.id else selected - host.id
                                },
                                enabled = !busy,
                                // Named by the host, so a screen reader and a UI test can both say
                                // *which* profile a checkbox belongs to - the row's own text is a
                                // sibling label, not part of the control.
                                modifier = Modifier.semantics { contentDescription = "Export ${host.name}" },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsSection(screenTitle) {
            Text(
                if (chosen.isEmpty()) {
                    "Passwords, private keys and passphrases travel with the profiles you pick"
                } else {
                    "Includes the saved password, private key, passphrase, SOCKS password and " +
                        "remote-desktop credential of ${chosen.size} profile(s)"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = SecretFieldKeyboard,
                trailingIcon = {
                    SecretPasteButton("passphrase", onPaste = {
                        val pasted = secureClipboard.paste()?.let(::normalizePastedSecret)
                        if (pasted.isNullOrEmpty()) {
                            report("There is nothing on the clipboard to paste")
                            null
                        } else {
                            pasted
                        }
                    }, into = { passphrase = it })
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                pendingPassphrase = passphrase
                passphrase = ""
                busy = true
                vaultUnlockGate.hold()
                exportPicker.launch("eclipse-profiles.enc")
            },
            // Three ways to have nothing to write, and all three disable the button: no profile
            // ticked, no passphrase (a file encrypted with an empty string is one anyone can read),
            // and a write already in flight.
            enabled = chosen.isNotEmpty() && passphrase.isNotBlank() && !busy,
        ) { Text(if (chosen.isEmpty()) "Export" else "Export ${chosen.size} profile(s)") }
    }
}

/**
 * `user@host:port`, the same one-line identity the host list shows.
 *
 * Built here rather than reused from the host card's own summary because that one is a composable
 * with a status badge and a live-session state attached; this is a subtitle in a settings row and
 * needs none of it.
 */
private fun HostProfile.describeEndpoint(): String = "$username@$host:$port"
