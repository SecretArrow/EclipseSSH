package dev.eclipse.ssh.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.linux.LocalLinuxHost
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a passphrase-encrypted export of one or more connection profiles, credentials included.
 *
 * A singleton for the reason [VaultExporter] is one: the Settings row that offers a multi-profile
 * export opens a window of its own, and a second window cannot reach a `@HiltViewModel` scoped to
 * `MainActivity`'s `ViewModelStore`. Every input this needs is a singleton already — the hosts and
 * the per-host secrets — so the operation belongs on one too, and both callers (the profile picker
 * window and the host card's own "Export profile") share this instead of each gathering the seven
 * secrets in its own way.
 *
 * Gathering is where the care is. [HostProfile.socksPassword] is a column on the host row, encrypted
 * by the repository on the way in and decrypted on the way out, so the profile handed in here already
 * carries it in the clear — but [VaultBackup.toJson] deliberately never writes it, which is why an
 * exported profile used to arrive at the other end with its proxy settings and no proxy password.
 * It travels in the credentials block instead, where the rest of the secrets are.
 *
 * A host whose secrets cannot be read is exported *without* them rather than failing the whole
 * export: the store answers from a DataStore read that can fail, and losing one password is a smaller
 * loss than losing the five other profiles the user selected. The rest of that profile travels
 * intact, and the count returned still includes it.
 *
 * Failing is a *throw*, not a returned status, the same contract as [VaultExporter]: the realistic
 * failures are a revoked URI permission, a provider that refuses the write, and a full disk, and each
 * caller already owns the wording it wants on top.
 */
@Singleton
class ProfileExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: HostCredentialStore,
) {

    /**
     * Encrypt [hosts] and write them to [uri]. Returns how many profiles went into the file.
     *
     * The count is returned rather than reported because "Exported 3 profile(s)" needs a number the
     * caller does not otherwise have, and it is the one fact worth confirming back: it is what tells
     * a user their export is not silently empty.
     *
     * Counted after the local environment is dropped, not before, because [VaultBackup.toProfilesJson]
     * drops it too: its id is synthesized at runtime, so it can never arrive at the other end as a
     * profile and must not be counted as one here.
     */
    suspend fun export(hosts: List<HostProfile>, passphrase: String, uri: Uri): Int {
        val writable = hosts.filterNot { LocalLinuxHost.isLocalHost(it.id) }
        val credentials = writable.associate { host -> host.id to credentialsOf(host) }
        write(uri, VaultBackup.encrypt(VaultBackup.toProfilesJson(writable, credentials), passphrase))
        return writable.size
    }

    /**
     * Every secret stored for one host, read through the store's own accessors.
     *
     * Guarded on [StoredCredentials] rather than on a null return, exactly as the credential store's
     * contract asks: `password()` and `passphrase()` are plain reads that would each answer `null`
     * both for "not stored" and for "the store failed", and those are different facts to a user
     * deciding whether the file they just wrote is complete.
     */
    private suspend fun credentialsOf(host: HostProfile): ProfileCredentials {
        val stored = runCatching { credentialStore.stored(host.id) }.getOrDefault(StoredCredentials())
        val key = if (stored.hasKey) runCatching { credentialStore.keyBytes(host.id) }.getOrNull() else null
        val readable = key?.isNotEmpty() == true
        val rdp = runCatching { credentialStore.rdpCredentials(host.id) }.getOrNull()
        return ProfileCredentials(
            password = if (stored.hasPassword) runCatching { credentialStore.password(host.id) }.getOrNull() else null,
            // The label is what makes the key usable, so it is written only beside bytes that are
            // actually there — the same pairing the reader enforces on the way back in.
            keyBase64 = key?.takeIf { readable }?.let { Base64.getEncoder().encodeToString(it) },
            keyLabel = stored.keyLabel?.takeIf { readable },
            keyType = stored.keyType?.takeIf { readable },
            passphrase = if (stored.hasPassphrase) runCatching { credentialStore.passphrase(host.id) }.getOrNull() else null,
            socksPassword = host.socksPassword,
            rdpUsername = rdp?.username,
            rdpDomain = rdp?.domain,
            rdpPassword = rdp?.password,
        )
    }

    /**
     * `"wt"` and not the default, for the reason [VaultExporter] gives: a document provider that
     * already holds a longer file would otherwise leave its tail behind, and a truncated export that
     * still parses is worse than one that does not.
     */
    private fun write(uri: Uri, payload: String) {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupFormatException("That location could not be written")
        stream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }
}
