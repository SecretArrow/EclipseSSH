package dev.eclipse.ssh.data.backup

import dev.eclipse.ssh.data.model.ALGORITHM_LIST_MAX_LENGTH
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_AUTH_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.DEFAULT_SERVER_ALIVE_COUNT_MAX
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.ENVIRONMENT_MAX_LENGTH
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.STARTUP_COMMAND_MAX_LENGTH
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_TYPE_CHOICES
import dev.eclipse.ssh.data.model.HOST_KEY_FINGERPRINT_PATTERN
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.data.model.encodeForwardRules
import dev.eclipse.ssh.data.model.encodeRemoteDesktop
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.wakeonlan.parseMac
import dev.eclipse.ssh.linux.LocalLinuxHost
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Passphrase-encrypted backup of the workspace configuration (host profiles and
 * settings). A full vault excludes credentials on purpose and they remain device-local inside the
 * Android Keystore vault: SSH passwords, private keys, the unlock PIN hash, and the SOCKS
 * proxy password are never written to a vault backup. Every other host and settings field
 * round-trips losslessly, so restoring a vault reproduces the workspace apart from those secrets.
 *
 * A profile export is the one deliberate exception, because its purpose is different: it moves
 * *an account* to another device, and an account that arrives without its password asks the user
 * for the one thing the export already knew. [toProfilesJson] therefore carries everything
 * [dev.eclipse.ssh.data.credentials.HostCredentialStore] holds for each exported profile — the SSH
 * password, the private key with its file name and detected type, the key passphrase, the SOCKS
 * proxy password, and the RDP username, domain and password — in an opt-in `credentials` block,
 * inside the same AES-GCM/PBKDF2 envelope as the rest of the payload: a block no vault backup ever
 * writes and no older build ever reads.
 *
 * Key material used to be excluded from both formats, and that changed deliberately. The reason
 * it was excluded was that a key has its own export path from the Keys screen, so a profile file
 * could be complete without duplicating it — true of a file that carries *one* account the user is
 * about to finish by hand, and false of the thing this format is now for. Selecting six profiles
 * and exporting them is a request to move six working accounts, and an account that needs its key
 * chosen and re-imported on the other side is a profile that arrived broken. The compromise the
 * key represents is not changed by which door it leaves through: the whole payload is encrypted
 * with the user's passphrase either way, and the file is written only at the user's explicit
 * request, to a document they picked. [toJson] remains credential-free, so a *vault* backup — the
 * whole-workspace file, which people mail to themselves and keep in cloud storage — still carries
 * nothing secret and has to be paired with saved credentials that never left the device.
 *
 * Older payloads still import, and this is the property the format is built around rather than a
 * concession: every key is read with an `opt…(key, default)` whose default is the field's own shipped
 * default, so a backup written before a field existed restores a host that behaves exactly as it did on
 * the build that wrote it. Version 1 omitted the extra settings and host fields; version 2 omitted the
 * twenty-one advanced per-host columns, which is what version 3 adds.
 *
 * **Every imported value is range- or shape-checked**, without exception, for a reason that is easy to
 * lose sight of: a backup is a file the user can hand-edit, mail to themselves, or restore from a
 * truncated copy, so it is untrusted input that goes straight into the engine. An unchecked import is a
 * way to install a 0-second timeout, a 0-column pty, a `TERM` no terminfo database has, or a forwarding
 * rule the form would have refused. Anything that fails its check falls back to the default rather than
 * failing the import - one bad field must not cost the user their hosts.
 */
object VaultBackup {
    const val VERSION = 3

    fun toJson(hosts: List<HostProfile>, settings: AppSettings, knownHosts: Map<String, String>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("settings", JSONObject().apply {
            put("biometricUnlock", settings.biometricUnlock)
            put("darkTheme", settings.darkTheme)
            put("clipboardSeconds", settings.clearClipboardAfterSeconds)
            put("keepAliveSeconds", settings.keepAliveSeconds)
            put("reconnectBaseSeconds", settings.reconnectBaseSeconds)
            put("terminalFontSize", settings.terminalFontSize)
            put("terminalMinColumns", settings.terminalMinColumns)
            put("terminalRows", settings.terminalRows)
            put("pinEnabled", settings.pinEnabled)
            put("vaultAutoLockMinutes", settings.vaultAutoLockMinutes)
            put("legacyAlgorithms", settings.legacyAlgorithms)
            put("terminalTheme", settings.terminalTheme)
            put("blockScreenshots", settings.blockScreenshots)
            put("reconnectAskFirst", settings.reconnectAskFirst)
            put("terminalKeepSystemBars", settings.terminalKeepSystemBars)
            put("terminalScrollbackCountVisible", settings.terminalScrollbackCountVisible)
            // Opt-keyed and length-capped like every untrusted string the vault round-trips: an
            // absent key is "old backup" (reader defaults the field), never a parse failure. The
            // blob's own codec decodes tolerantly, so a mangled value becomes defaults, not a
            // lockout — the same never-lockout posture as the rest of the settings block.
            settings.editorPrefsJson.take(MAX_EDITOR_PREFS_JSON)
                .takeIf { json -> json.isNotBlank() }?.let { put("editorPrefsJson", it) }
            // Opt-keyed and capped exactly like the editor blob above it: the shortcut bar's codec
            // decodes tolerantly, so a mangled or truncated value becomes the default bar rather
            // than a failed import.
            settings.terminalKeyBarJson.take(MAX_KEY_BAR_JSON)
                .takeIf { json -> json.isNotBlank() }?.let { put("terminalKeyBarJson", it) }
        })
        root.put("hosts", JSONArray().apply {
            hosts.forEach { host ->
                // The local Ubuntu environment's reserved id is synthesized at runtime from the
                // userspace's state, so it never legitimately reaches this writer — but a profile
                // with that id surviving into a vault would come back on another device as a
                // "Local Ubuntu" entry that dials localhost. Refused here rather than trusted to
                // every caller.
                if (LocalLinuxHost.isLocalHost(host.id)) return@forEach
                put(JSONObject().apply {
                put("id", host.id)
                put("name", host.name)
                put("host", host.host)
                put("username", host.username)
                put("port", host.port)
                put("authMethod", host.authMethod.name)
                put("group", host.group)
                put("tags", JSONArray(host.tags))
                put("isFavorite", host.isFavorite)
                host.lastConnectedAt?.let { put("lastConnectedAt", it) }
                host.fingerprint?.let { put("fingerprint", it) }
                put("proxyType", host.proxyType.name)
                host.proxyJump?.let { put("proxyJump", it) }
                host.socksHost?.let { put("socksHost", it) }
                put("socksPort", host.socksPort)
                host.socksUsername?.let { put("socksUsername", it) }
                host.accentColor?.let { put("accentColor", it) }
                put("connectTimeoutSeconds", host.connectTimeoutSeconds)
                host.keepAliveSeconds?.let { put("keepAliveSeconds", it) }
                put("autoLoginSftp", host.autoLoginSftp)
                // The advanced per-host columns. Absent from version 2 backups, which is why every one
                // of them is read back against its own default rather than against zero or false.
                put("compression", host.compression)
                put("keepAliveEnabled", host.keepAliveEnabled)
                put("serverAliveCountMax", host.serverAliveCountMax)
                put("authTimeoutSeconds", host.authTimeoutSeconds)
                put("autoReconnect", host.autoReconnect)
                put("maxReconnectAttempts", host.maxReconnectAttempts)
                put("reconnectBackoffSeconds", host.reconnectBackoffSeconds)
                put("usePty", host.usePty)
                put("terminalType", host.terminalType)
                put("terminalColumns", host.terminalColumns)
                put("terminalRows", host.terminalRows)
                put("keyboardInteractiveAuth", host.keyboardInteractiveAuth)
                // Tri-state: null means "follow the app-wide switch", and writing it as a boolean would
                // turn every host with no opinion into one that has pinned this device's setting.
                host.legacyAlgorithms?.let { put("legacyAlgorithms", it) }
                host.ciphers?.let { put("ciphers", it) }
                host.kexAlgorithms?.let { put("kexAlgorithms", it) }
                host.macs?.let { put("macs", it) }
                host.hostKeyAlgorithms?.let { put("hostKeyAlgorithms", it) }
                // Written unconditionally, blank included, so a host whose startup command was cleared
                // restores cleared. Neither is a credential, and both say so in the form that edits
                // them - but a backup is the one place they leave the device, so they are also the two
                // fields [HostProfile.toString] redacts, in case one is used as one anyway.
                put("startupCommand", host.startupCommand)
                put("environment", host.environment)
                // Re-encoded from the decoded rules rather than copied, so a column that arrived from a
                // hand-edited file is normalised on its way out instead of being passed on.
                put("savedForwards", encodeForwardRules(decodeForwardRules(host.savedForwards, host.id)))
                // Same treatment for the remote-desktop column, and it travels in the clear by
                // design: the line is a host, a port and flags - nothing to redact.
                put("remoteDesktop", encodeRemoteDesktop(decodeRemoteDesktop(host.remoteDesktop)))
                // The Wake-on-LAN MAC, written unconditionally like the other non-secret per-host
                // text: a MAC names a network card, not anything worth encrypting, and an empty
                // string has to survive the trip too or a cleared address would come back restored.
                put("wakeOnLanMac", host.wakeOnLanMac)
                // Written unconditionally even though it is false by default, so a host whose
                // forwarding was turned on and back off restores "off" rather than whatever the
                // importing build ships as its default. Absent from every older backup, which
                // optBoolean resolves to false - the grant stays opt-in on restore, the same way
                // the Room migration keeps it off for upgraded rows.
                put("agentForwarding", host.agentForwarding)
                put("hostKeyPolicy", host.hostKeyPolicy.name)
            }) }
        })
        root.put("knownHosts", JSONObject().apply { knownHosts.forEach { (key, value) -> put(key, value) } })
        return root.toString()
    }

    fun fromJson(json: String): Triple<List<HostProfile>, AppSettings, Map<String, String>> {
        val root = try {
            JSONObject(json)
        } catch (error: Throwable) {
            throw BackupFormatException("Backup contents are not valid Eclipse SSH JSON", error)
        }
        val defaults = AppSettings()
        val settingsObj = root.optJSONObject("settings") ?: JSONObject()
        val settings = AppSettings(
            biometricUnlock = settingsObj.optBoolean("biometricUnlock", defaults.biometricUnlock),
            darkTheme = settingsObj.optBoolean("darkTheme", defaults.darkTheme),
            clearClipboardAfterSeconds = settingsObj.optInt("clipboardSeconds", defaults.clearClipboardAfterSeconds),
            keepAliveSeconds = settingsObj.optInt("keepAliveSeconds", defaults.keepAliveSeconds),
            reconnectBaseSeconds = settingsObj.optInt("reconnectBaseSeconds", defaults.reconnectBaseSeconds),
            terminalFontSize = settingsObj.optInt("terminalFontSize", defaults.terminalFontSize),
            // Normalized on the way in as well as on the way out: a backup is a file a user can edit,
            // and the width it names has to be one the pty would actually accept.
            terminalMinColumns = SettingsRepository.normalizeMinColumns(
                settingsObj.optInt("terminalMinColumns", defaults.terminalMinColumns),
            ),
            // Normalized on the way in for the same reason, and defaulted the same way: absent from
            // backups written before the setting existed resolves to 0, which is "fit the screen" -
            // exactly the behaviour those backups were taken from.
            terminalRows = SettingsRepository.normalizeRows(
                settingsObj.optInt("terminalRows", defaults.terminalRows),
            ),
            pinEnabled = settingsObj.optBoolean("pinEnabled", defaults.pinEnabled),
            // Normalized on the way in like every other imported number: a backup is untrusted
            // input, and a hand-edited delay the dialog never offered would show as a value nobody
            // chose. Absent from backups written before the setting existed, which resolves to the
            // default rather than to zero (never re-lock) by accident.
            vaultAutoLockMinutes = SettingsRepository.normalizeVaultAutoLockMinutes(
                settingsObj.optInt("vaultAutoLockMinutes", defaults.vaultAutoLockMinutes),
            ),
            legacyAlgorithms = settingsObj.optBoolean("legacyAlgorithms", defaults.legacyAlgorithms),
            terminalTheme = settingsObj.optString("terminalTheme", defaults.terminalTheme)
                .takeIf { name -> TerminalTheme.entries.any { it.name == name } } ?: defaults.terminalTheme,
            // Absent from backups written before this setting existed, which optBoolean resolves to
            // the default rather than to false-by-accident.
            blockScreenshots = settingsObj.optBoolean("blockScreenshots", defaults.blockScreenshots),
            reconnectAskFirst = settingsObj.optBoolean("reconnectAskFirst", defaults.reconnectAskFirst),
            terminalKeepSystemBars = settingsObj.optBoolean("terminalKeepSystemBars", defaults.terminalKeepSystemBars),
            // Absent from backups written before this setting existed. It travels with the rest of
            // the display settings rather than staying on the device that set it, because a restore
            // is the only path a preference has to a new phone, and this is one a user turns off on
            // purpose: an import that quietly turned the count back on would undo that decision
            // while looking like it had restored their settings.
            terminalScrollbackCountVisible = settingsObj.optBoolean(
                "terminalScrollbackCountVisible",
                defaults.terminalScrollbackCountVisible,
            ),
            // The blob is decoded by EditorPrefsCodec, which never throws — a hand-edited or
            // truncated value becomes the editor's defaults rather than a failed import. Capped
            // on the way in for the same reason every vault string is: a backup is untrusted.
            editorPrefsJson = settingsObj.optString("editorPrefsJson", defaults.editorPrefsJson)
                .take(MAX_EDITOR_PREFS_JSON).takeIf { json -> json.isNotBlank() } ?: "{}",
            // Absent from backups written before the bar was configurable, which the default "{}"
            // resolves to "never configured" — the same bar those backups' installs shipped with.
            terminalKeyBarJson = settingsObj.optString("terminalKeyBarJson", defaults.terminalKeyBarJson)
                .take(MAX_KEY_BAR_JSON).takeIf { json -> json.isNotBlank() } ?: "{}",
        )
        val hostsArray = root.optJSONArray("hosts") ?: JSONArray()
        val hosts = ArrayList<HostProfile>(hostsArray.length())
        for (i in 0 until hostsArray.length()) {
            val h = hostsArray.getJSONObject(i)
            val id = h.optString("id").ifBlank { UUID.randomUUID().toString() }
            // The mirror of the writer's refusal: a hand-edited or foreign backup carrying the
            // local Ubuntu environment's reserved id is dropped as one bad entry, not trusted as
            // a host — the same posture as every other shape check in this loop.
            if (LocalLinuxHost.isLocalHost(id)) continue
            hosts += HostProfile(
                id = id,
                name = h.optString("name"),
                host = h.optString("host"),
                username = h.optString("username"),
                port = h.optInt("port", 22).takeIf { it in 1..65535 } ?: 22,
                authMethod = AuthMethod.entries.firstOrNull { it.name == h.optString("authMethod") } ?: AuthMethod.PASSWORD,
                group = h.optString("group", "Personal").ifBlank { "Personal" },
                tags = h.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                isFavorite = h.optBoolean("isFavorite", false),
                lastConnectedAt = if (h.isNull("lastConnectedAt")) null else h.optLong("lastConnectedAt").takeIf { it > 0L },
                // Shape-checked for the same reason as the known-hosts block below: this field is
                // not decoration, `MainViewModel.saveHost` seeds the trust store from it, so an
                // imported profile carrying an arbitrary string is a second route to pinning a key
                // the user never saw. Dropping an unparseable pin leaves the host on
                // trust-on-first-use, which is the app's normal behaviour and asks before trusting.
                fingerprint = if (h.isNull("fingerprint")) null else {
                    h.optString("fingerprint").trim().takeIf(HOST_KEY_FINGERPRINT_PATTERN::matches)
                },
                proxyType = ProxyType.entries.firstOrNull { it.name == h.optString("proxyType") } ?: ProxyType.NONE,
                proxyJump = if (h.isNull("proxyJump")) null else h.optString("proxyJump").takeIf(String::isNotBlank),
                socksHost = if (h.isNull("socksHost")) null else h.optString("socksHost").takeIf(String::isNotBlank),
                socksPort = h.optInt("socksPort", 1080).takeIf { it in 1..65535 } ?: 1080,
                socksUsername = if (h.isNull("socksUsername")) null else h.optString("socksUsername").takeIf(String::isNotBlank),
                accentColor = if (h.isNull("accentColor")) null else h.optLong("accentColor").takeIf { it != 0L },
                // Range-checked like every other imported number: a backup is untrusted input, and a
                // hand-edited or truncated file must not be able to install a 0-second timeout.
                connectTimeoutSeconds = h.optInt("connectTimeoutSeconds", DEFAULT_CONNECT_TIMEOUT_SECONDS)
                    .takeIf { it in CONNECT_TIMEOUT_RANGE } ?: DEFAULT_CONNECT_TIMEOUT_SECONDS,
                keepAliveSeconds = if (h.isNull("keepAliveSeconds")) null else {
                    h.optInt("keepAliveSeconds").takeIf { it in KEEP_ALIVE_RANGE }
                },
                // Absent from every backup written before this setting existed, and optBoolean's
                // default is what keeps those importing as they used to behave rather than as
                // false-by-accident.
                autoLoginSftp = h.optBoolean("autoLoginSftp", HostProfile.DEFAULT_AUTO_LOGIN_SFTP),
                compression = h.optBoolean("compression", false),
                keepAliveEnabled = h.optBoolean("keepAliveEnabled", true),
                serverAliveCountMax = h.optInt("serverAliveCountMax", DEFAULT_SERVER_ALIVE_COUNT_MAX)
                    .takeIf { it in SERVER_ALIVE_COUNT_RANGE } ?: DEFAULT_SERVER_ALIVE_COUNT_MAX,
                authTimeoutSeconds = h.optInt("authTimeoutSeconds", DEFAULT_AUTH_TIMEOUT_SECONDS)
                    .takeIf { it in AUTH_TIMEOUT_RANGE } ?: DEFAULT_AUTH_TIMEOUT_SECONDS,
                autoReconnect = h.optBoolean("autoReconnect", true),
                maxReconnectAttempts = h.optInt("maxReconnectAttempts", DEFAULT_MAX_RECONNECT_ATTEMPTS)
                    .takeIf { it in MAX_RECONNECT_ATTEMPTS_RANGE } ?: DEFAULT_MAX_RECONNECT_ATTEMPTS,
                // The sentinel is outside the range on purpose - 0 means "inherit the app-wide delay" -
                // so it has to be admitted here as well, or restoring a host that inherits would give it
                // a fixed delay it never had.
                reconnectBackoffSeconds = h.optInt("reconnectBackoffSeconds", INHERIT_RECONNECT_BACKOFF)
                    .takeIf { it == INHERIT_RECONNECT_BACKOFF || it in RECONNECT_BACKOFF_RANGE }
                    ?: INHERIT_RECONNECT_BACKOFF,
                usePty = h.optBoolean("usePty", true),
                // Checked against the offered list rather than merely non-blank: `TERM` is looked up in
                // the server's terminfo database, and a name that is not in it fails as a broken `vim`
                // rather than as a setting - the exact reason the form is a set of choices and not a
                // text field. See [TERMINAL_TYPE_CHOICES].
                terminalType = h.optString("terminalType", DEFAULT_TERMINAL_TYPE)
                    .takeIf { it in TERMINAL_TYPE_CHOICES } ?: DEFAULT_TERMINAL_TYPE,
                // 0 is the "match the screen" sentinel and is admitted alongside the range, like the
                // backoff above. A pty may not be 0 columns wide, so an out-of-range number becomes the
                // sentinel rather than being clamped to 20 - "automatic" is what the user had.
                terminalColumns = h.optInt("terminalColumns", 0)
                    .takeIf { it == 0 || it in TERMINAL_COLUMNS_RANGE } ?: 0,
                terminalRows = h.optInt("terminalRows", 0)
                    .takeIf { it == 0 || it in TERMINAL_ROWS_RANGE } ?: 0,
                keyboardInteractiveAuth = h.optBoolean("keyboardInteractiveAuth", true),
                // Tri-state, and `isNull` is what preserves it: an absent key and an explicit `null` both
                // have to come back as "follow the app-wide switch" rather than as false.
                legacyAlgorithms = if (h.isNull("legacyAlgorithms")) null else h.optBoolean("legacyAlgorithms"),
                // Length only. What the names mean is the engine's business, and it already refuses to
                // propose an empty list - a preference whose names this build cannot honour falls back to
                // the library's own list rather than failing key exchange - so dropping an unrecognised
                // name here would throw away a preference that is valid on the device it came from.
                ciphers = h.algorithmList("ciphers"),
                kexAlgorithms = h.algorithmList("kexAlgorithms"),
                macs = h.algorithmList("macs"),
                hostKeyAlgorithms = h.algorithmList("hostKeyAlgorithms"),
                startupCommand = h.optString("startupCommand").take(STARTUP_COMMAND_MAX_LENGTH),
                environment = h.optString("environment").take(ENVIRONMENT_MAX_LENGTH),
                // Re-encoded from what decoding accepted, so an imported column contains exactly the
                // rules the engine will act on: a line the app would not have let the user save does not
                // survive the round trip, and the [MAX_SAVED_FORWARDS] cap is applied by the decoder.
                savedForwards = encodeForwardRules(decodeForwardRules(h.optString("savedForwards"))),
                // The remote-desktop column reads the same way: what decoding accepts is what is kept,
                // and an absent key (a backup from before the column existed) is simply no endpoint.
                remoteDesktop = encodeRemoteDesktop(decodeRemoteDesktop(h.optString("remoteDesktop"))),
                // Shape-checked through the same parser the form uses, for the same reason the
                // fingerprint is: a hand-edited file must not install an address the wake would build
                // a packet no card answers to. An unparseable value is dropped rather than fatal, and
                // the spelling as typed is kept, so a host round-trips exactly as its owner wrote it.
                wakeOnLanMac = h.optString("wakeOnLanMac").trim().takeIf { parseMac(it) != null }.orEmpty(),
                // The one boolean in this file where "absent means false" is a security property and
                // not just a default: an older backup predating the flag must not restore it on, for
                // the same reason the migration keeps it off - it is a grant, and only the user can
                // make it on the host that carries it.
                agentForwarding = h.optBoolean("agentForwarding", false),
                hostKeyPolicy = HostKeyPolicy.entries.firstOrNull { it.name == h.optString("hostKeyPolicy") }
                    ?: HostKeyPolicy.ASK,
            )
        }
        // Known-host entries are validated before they are handed back, because importing a vault
        // writes them straight into the trust store: `MainViewModel.importVault` passes them to
        // `SshConnectionManager.importKnownHosts`, which merges them in. An unvalidated entry is
        // therefore a way to pre-trust a key the user has never seen — pair a hostile
        // `host:port -> fingerprint` line with a host profile in the same file and the fingerprint
        // prompt that is this app's entire host-key defence never appears for it. Only entries in
        // exactly the shape the app itself writes are kept, so a genuine backup round-trips
        // untouched while a hand-edited one loses whatever does not parse.
        //
        // Malformed entries are dropped rather than failing the whole import: one of them can only
        // ever fail to match a real host key, so refusing the import over a single bad line would
        // cost the user their hosts and settings for no gain.
        val knownHostsObj = root.optJSONObject("knownHosts") ?: JSONObject()
        val knownHosts = mutableMapOf<String, String>()
        knownHostsObj.keys().forEach { key ->
            val fingerprint = knownHostsObj.optString(key)
            if (isKnownHostKey(key) && HOST_KEY_FINGERPRINT_PATTERN.matches(fingerprint)) knownHosts[key] = fingerprint
        }
        return Triple(hosts, settings, knownHosts)
    }

    /**
     * One algorithm preference column as imported: bounded, trimmed, and null when there is nothing.
     *
     * Null rather than blank, because the column's two states are not the same thing to the engine -
     * null is "no opinion, negotiate normally" and an empty list would be an instruction to propose
     * nothing at all. See [HostProfile.ciphers].
     */
    private fun JSONObject.algorithmList(key: String): String? =
        if (isNull(key)) null else optString(key).trim().take(ALGORITHM_LIST_MAX_LENGTH).takeIf(String::isNotBlank)

    /**
     * Exports one or more connection profiles, credentials included, in the vault's own encrypted
     * format.
     *
     * Built on top of [toJson] rather than threaded through it, so the vault path cannot start
     * carrying credentials by accident: this function is the only writer of the block. The hosts
     * block is byte-for-byte what a vault would write for the same profiles, and the credentials
     * block is the only difference — which is what lets the two doors share a reader and what
     * [KIND] exists to tell apart.
     *
     * Keyed by host id rather than a list, because the block is a lookup and never an order: the
     * hosts array already carries the order the user picked, and a profile whose credentials failed
     * to be gathered is simply absent from the block rather than holding a placeholder beside its
     * host.
     */
    fun toProfilesJson(hosts: List<HostProfile>, credentials: Map<String, ProfileCredentials> = emptyMap()): String {
        val root = JSONObject(toJson(hosts, AppSettings(), emptyMap()))
        root.put("kind", KIND)
        val block = JSONObject()
        hosts.forEach { host ->
            // Skipped for the same reason toJson skips them: the local Ubuntu environment's id is
            // synthesized at runtime, so a block keyed by one would describe a profile no device
            // restores.
            if (LocalLinuxHost.isLocalHost(host.id)) return@forEach
            credentials[host.id]?.let { entry -> entry.toJsonObject().takeIf { it.length() > 0 }?.let { block.put(host.id, it) } }
        }
        // Absent when there is nothing to carry, so a profile with nothing saved and an export
        // written before the block existed read the same way on import - and no version bump is
        // needed either way.
        if (block.length() > 0) root.put("credentials", block)
        return root.toString()
    }

    /**
     * Reads a profile export of any size, and refuses a file written by a different door.
     *
     * The `kind` marker is what makes the refusal possible. A profile export's hosts block is a
     * valid vault hosts block - that is deliberate, it is how the two share a writer - so without
     * the marker a profile file dropped on "Import backup" would import its hosts and reset every
     * *setting* to the default an account export carries, silently. The marker is absent from every
     * file written before this function existed, so an absent one is read as "not a profile export"
     * rather than as an error: an old single-account file still imports here, because the shape it
     * has is the one the flat block below still handles.
     */
    fun fromProfilesJson(json: String): List<ImportedProfile> {
        val root = try {
            JSONObject(json)
        } catch (error: Throwable) {
            throw BackupFormatException("Profile export contents are not valid Eclipse SSH JSON", error)
        }
        val kind = root.optString("kind").trim().takeIf(String::isNotBlank)
        if (kind != null && kind != KIND) throw BackupFormatException("That file is not a profile export")
        val hosts = fromJson(json).first
        if (hosts.isEmpty()) throw BackupFormatException("That profile export contains no profiles")
        val block = root.optJSONObject("credentials")
        // The flat block is the shape the first account exports wrote: no host ids, and meaningful
        // only because the file held exactly one host. Kept because those files are out there and
        // were the only way to move an account until this function replaced that writer.
        val flat = block?.takeIf { it.has("password") || it.has("passphrase") }
        return hosts.map { host ->
            val entry = block?.optJSONObject(host.id) ?: flat?.takeIf { hosts.size == 1 }
            ImportedProfile(host = host, credentials = entry?.toProfileCredentials())
        }
    }

    /**
     * True when a payload is a profile export rather than a vault backup.
     *
     * Exists for one caller and one consequence: a profile export's hosts block *is* a valid vault
     * hosts block — that is what sharing a writer means — so "Import backup" would otherwise accept
     * one, import its hosts, and reset every setting to the default the profile writer carries in
     * its settings block, silently and with no way back. The reader cannot refuse it in [fromJson],
     * which is the vault path's own entry point and has to stay able to read every file it ever
     * wrote; so the check is a question the caller asks before it opens the door. False for anything
     * that does not parse, because "not a profile export" is the honest answer for a file that is
     * not a backup either — [fromJson] has a better sentence for that one.
     */
    fun isProfileExport(json: String): Boolean =
        runCatching { JSONObject(json).optString("kind").trim() }.getOrNull() == KIND

    /** One credential field as imported: bounded, trimmed, and null when there is nothing usable. */
    private fun JSONObject.readAccountSecret(key: String): String? = readBounded(key, MAX_ACCOUNT_CREDENTIAL_LENGTH)

    /** A short per-host text field as imported — a file name or a type label, not a secret. */
    private fun JSONObject.readBounded(key: String, max: Int): String? =
        if (isNull(key)) null else optString(key).trim().take(max).takeIf(String::isNotBlank)

    private fun ProfileCredentials.toJsonObject(): JSONObject = JSONObject().apply {
        password?.let { put("password", it) }
        keyBase64?.let { put("key", it) }
        keyLabel?.let { put("keyLabel", it) }
        keyType?.let { put("keyType", it) }
        passphrase?.let { put("passphrase", it) }
        socksPassword?.let { put("socksPassword", it) }
        rdpUsername?.let { put("rdpUsername", it) }
        rdpDomain?.let { put("rdpDomain", it) }
        rdpPassword?.let { put("rdpPassword", it) }
    }

    /**
     * One host's credentials block as imported, or null when nothing in it survived its check.
     *
     * Every field is bounded like the rest of the payload, and two relationships between fields are
     * enforced here rather than left to the store:
     *
     * - **A key needs its label.** `StoredCredentials.hasKey` is `keyLabel != null`, so bytes that
     *   arrived without a file name would be written into the vault and never read back — invisible
     *   storage the user can neither see nor delete from the Saved credentials screen. The type is
     *   dropped with them for the same reason: it describes a key that is not there.
     * - **An RDP credential is all three fields or none.** `HostCredentialStore.rdpCredentials`
     *   returns null unless a username *and* a password are stored, because an NLA answer is all
     *   three or it is nothing; a file carrying one of the pair would leave the host with a
     *   half-credential the store can never hand to a tunnel. A domain is the one optional field.
     */
    private fun JSONObject.toProfileCredentials(): ProfileCredentials? {
        val keyLabel = readBounded("keyLabel", MAX_KEY_LABEL_LENGTH)
        val keyBase64 = readBounded("key", MAX_KEY_BASE64_LENGTH)
            // Shape-checked as well as bounded: the value is decoded here so that a hand-edited key
            // is dropped as one bad field rather than throwing later, at the point a connection is
            // being made with it.
            ?.let { encoded -> encoded.takeIf { runCatching { Base64.getDecoder().decode(it) }.isSuccess } }
            ?.takeIf { keyLabel != null }
        val rdpUsername = readAccountSecret("rdpUsername")
        val rdpPassword = readAccountSecret("rdpPassword")
        val rdpPaired = rdpUsername != null && rdpPassword != null
        val credentials = ProfileCredentials(
            password = readAccountSecret("password"),
            keyBase64 = keyBase64,
            keyLabel = keyLabel.takeIf { keyBase64 != null },
            keyType = readBounded("keyType", MAX_KEY_TYPE_LENGTH).takeIf { keyBase64 != null },
            passphrase = readAccountSecret("passphrase"),
            socksPassword = readAccountSecret("socksPassword"),
            rdpUsername = rdpUsername.takeIf { rdpPaired },
            rdpDomain = readAccountSecret("rdpDomain").takeIf { rdpPaired },
            rdpPassword = rdpPassword.takeIf { rdpPaired },
        )
        return credentials.takeIf { !it.isEmpty }
    }

    fun encrypt(plaintext: String, passphrase: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(salt, cipher.iv, ciphertext).joinToString(DELIMITER) { Base64.getEncoder().encodeToString(it) }
    }

    /**
     * Reverses [encrypt].
     *
     * A wrong passphrase surfaces from the JCE as `AEADBadTagException`; it is translated
     * into [BackupFormatException] so callers can show "incorrect passphrase" instead of
     * leaking a crypto stack trace (or crashing).
     */
    fun decrypt(payload: String, passphrase: String): String {
        val parts = payload.trim().split(DELIMITER)
        if (parts.size != 3) throw BackupFormatException("This file is not an Eclipse SSH backup")
        return try {
            val salt = Base64.getDecoder().decode(parts[0])
            val iv = Base64.getDecoder().decode(parts[1])
            val ciphertext = Base64.getDecoder().decode(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (error: Throwable) {
            if (error is BackupFormatException) throw error
            throw BackupFormatException("Incorrect passphrase or corrupted backup", error)
        }
    }

    /**
     * True for a `host:port` key in the form `KnownHostsStore` writes.
     *
     * Split from the right, because a literal IPv6 address contains colons of its own and
     * `InetSocketAddress.getHostString` hands them over unbracketed — `::1:22` has to keep four.
     */
    private fun isKnownHostKey(key: String): Boolean {
        val host = key.substringBeforeLast(':', missingDelimiterValue = "")
        val port = key.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull()
        return host.isNotBlank() && host.none(Char::isWhitespace) && port != null && port in PORT_RANGE
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val ITERATIONS = 100_000
    private const val DELIMITER = "."

    /**
     * What a profile export calls itself, in the `kind` field of its root object.
     *
     * The one thing that separates the two kinds of file this object writes, and it has to be
     * something: both are `toJson` output, so both are structurally valid backups, and the
     * difference between them is which door may read them. See [fromProfilesJson].
     */
    const val KIND = "profiles"

    /**
     * Bound on an imported account credential's length. Generous against any real password - longer
     * than every passphrase policy - and bounded against a hand-edited file, because the value goes
     * from the file straight into the credential store.
     */
    private const val MAX_ACCOUNT_CREDENTIAL_LENGTH = 4096

    /**
     * Bound on an imported private key's base64 length - about 24 KB of key material, several times
     * the largest PEM this app writes (an RSA 4096 key is under 4 KB encoded). Bounded for the same
     * reason as every other field, and separately from them because a key is two orders of magnitude
     * larger than a password and would be truncated to uselessness by their bound.
     */
    private const val MAX_KEY_BASE64_LENGTH = 32_768

    /** A picked file's name. A file name is not a secret, and no filesystem has one this long. */
    private const val MAX_KEY_LABEL_LENGTH = 256

    /** `RSA 3072`, `ED25519` - a label from `sshKeyTypeLabel`, so a short one. */
    private const val MAX_KEY_TYPE_LENGTH = 64

    /**
     * Bound on the editor preferences blob's length inside a vault. The blob this app writes is
     * under 200 bytes; the bound exists only against a hand-edited file, so a padded blob cannot
     * ride into DataStore wholesale. Truncation past the codec's tolerance resolves to defaults
     * on decode, never to a failed import.
     */
    private const val MAX_EDITOR_PREFS_JSON = 4096

    /** Same order of magnitude as the editor blob: 64 caps with labels, comfortably. */
    private const val MAX_KEY_BAR_JSON = 8192
}

/** Raised when a backup payload cannot be read: wrong passphrase, truncation, or bad JSON. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything a profile export may carry for one host: the two secrets the credential store keeps
 * that name the account (its SSH password and its key passphrase), the private key itself with the
 * file name and detected type that make it usable, the SOCKS proxy password the host row holds, and
 * the RDP credential a remote-desktop session answers an NLA challenge with.
 *
 * The private key is base64 because that is how [dev.eclipse.ssh.data.credentials.HostCredentialStore]
 * holds it — preferences store strings, and a private key is not valid UTF-8 once it is DER — and
 * because it keeps this type comparable: a `ByteArray` field would make the generated `equals`
 * compare arrays by identity, so two identical credentials would read as different, which is exactly
 * the kind of wrong answer the tests that round-trip this type are asking for.
 *
 * `toString` is redacted because this type travels through ViewModels where anything can end up in
 * a log; the values themselves are the entire point of the type, so the redaction has to be spelled
 * out rather than inherited from a generic data-class rendering. [keyType] and [rdpDomain] are left
 * legible on purpose: neither is a secret — one is an algorithm name, the other is the domain half of
 * a login the file already carries in the clear where it is stored.
 */
data class ProfileCredentials(
    val password: String? = null,
    val keyBase64: String? = null,
    val keyLabel: String? = null,
    val keyType: String? = null,
    val passphrase: String? = null,
    val socksPassword: String? = null,
    val rdpUsername: String? = null,
    val rdpDomain: String? = null,
    val rdpPassword: String? = null,
) {
    /** The stored key as bytes, or null when there is none or it does not decode. */
    fun keyBytes(): ByteArray? = keyBase64?.let { encoded -> runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() }

    /** Nothing here would reach any store, so the block carrying it is not worth writing. */
    val isEmpty: Boolean
        get() = password == null && keyBase64 == null && passphrase == null && socksPassword == null &&
            rdpUsername == null && rdpPassword == null

    override fun toString(): String = "ProfileCredentials(password=${redact(password)}, key=${redact(keyBase64)}, " +
        "keyLabel=$keyLabel, keyType=$keyType, passphrase=${redact(passphrase)}, socksPassword=${redact(socksPassword)}, " +
        "rdpUsername=${redact(rdpUsername)}, rdpDomain=$rdpDomain, rdpPassword=${redact(rdpPassword)})"

    private fun redact(value: String?): String = if (value == null) "null" else "***"
}

/** One profile of an export as imported: the profile, plus the credentials the file carried. */
data class ImportedProfile(val host: HostProfile, val credentials: ProfileCredentials?)
