package dev.eclipse.ssh.linux

import java.io.File
import java.io.IOException

/**
 * The apk-family [DistributionManager]: sets up an Alpine mini-rootfs into a usable userspace.
 *
 * The catalogue pins Alpine 3.21 / 3.20 / 3.19 (`family = ALPINE`, `packageManager = APK`), and the
 * apk install pipeline is the one this class is the first implementation of. It mirrors
 * [UbuntuDistributionManager]'s shape — register user, configure DNS, configure repositories,
 * update the index, install base packages, configure sudo, verify runtime — but every step speaks
 * apk, not apt, and the minirootfs's starting point is different in two ways the Ubuntu Base rootfs
 * is not:
 *
 *  - The Alpine minirootfs ships BusyBox and `apk` already, plus `alpine-baselayout`'s `/etc/passwd`,
 *    `/etc/group` (with `wheel` already present), `/etc/shadow`, `/etc/profile`, `/etc/profile.d/`,
 *    `/etc/hostname`, `/etc/hosts`, `/etc/nsswitch.conf` and `/etc/shells`. There is no bootstrap
 *    step: the rootfs is already a working BusyBox system, and this manager only has to add a
 *    non-root account, point apk at its repositories, and install the packages a terminal/SSH
 *    userspace wants on top.
 *  - `alpine-base` (the meta-package the minirootfs is built from) hard-depends on `openrc`, so the
 *    rc-service / rc-update tooling is present out of the box. Under proot no real init runs —
 *    PID 1 is the proot process or the login shell, not `/sbin/init` — so OpenRC is present as
 *    tooling only, and no service actually starts until something executes `rc-service <svc> start`
 *    manually inside the session. This manager does not try to start services.
 *
 * The contract [DistributionManager] keeps is the one this class keeps: [setup] either completes
 * every step it names or throws; [isConfigured] is cheap and file-based and never throws;
 * [healthProbe] is the expensive truth; [diagnostics] is the ring every event of an install is
 * filed into. [nameSupplementaryGroups] writes the Android group IDs into `/etc/group` the same way
 * the Ubuntu manager does (BusyBox's `/etc/group` format is identical to glibc's), and
 * [clearLastGoodMirror] is a no-op — apk keeps no apt-style last-known-good mirror memo, so there
 * is nothing to forget on uninstall, and the honest implementation is the one that does nothing.
 *
 * The shell is `/bin/sh` (BusyBox ash) until [installBasePackages] runs `apk add bash`, after which
 * `/bin/bash` exists too. The session argv is fixed at graph-build time and names `/bin/sh`, so a
 * session before setup has run still works — see [ProotRuntime.loginShell].
 *
 * @param distro the pinned Alpine release this manager is built around. `distro.release` is the
 *   Alpine branch (`v3.21`, `v3.20`, `v3.19`) and is what the repository URL is built from.
 * @param runtime the proot runtime, built with `family = DistroFamily.ALPINE` so its argv and env
 *   name `/bin/sh` and `/home/alpine` rather than `/bin/bash` and `/home/ubuntu`.
 * @param appUid the Android app's uid, written into `/etc/passwd` as the account's uid so proot's
 *   fake-root session maps to the account the workspace belongs to.
 * @param appGid the Android app's primary gid, written into `/etc/group` and `/etc/passwd`.
 * @param supplementaryGids the app process's supplementary Android group IDs, named into
 *   `/etc/group` by [nameSupplementaryGroups] on every start. A lambda for the same reason the
 *   Ubuntu manager's is one: the groups belong to the running process, not the graph.
 * @param dnsServers the device's current resolvers, written into `/etc/resolv.conf`. A lambda
 *   because the resolvers are read each time setup writes the file, so a network change between
 *   install and repair lands in the file instead of pinning the DNS of the moment the graph was
 *   built.
 * @param networkOnline the offline gate for the network-heavy phases (apk update, apk add). The
 *   install screen's repair ladder asks for this; the default is "online" so a test that builds
 *   this class alone does not have to wire connectivity.
 * @param installer the rootfs installer that owns the pinned tarball. Nullable and null by default
 *   for the same reason the Ubuntu manager's is: a test that builds this class alone still
 *   constructs.
 * @param diagnostics the shared install-log ring this manager and [RootfsInstaller] both write to.
 *   A constructor parameter rather than a property this class makes for itself, because the install
 *   is more than this class: [RootfsInstaller] downloads, verifies and extracts the rootfs before
 *   any of the work below starts, and one log has to hold both halves.
 */
class AlpineDistributionManager(
    private val distro: LinuxDistro,
    private val runtime: ProotRuntime,
    private val appUid: Int,
    private val appGid: Int,
    private val supplementaryGids: () -> IntArray = { IntArray(0) },
    private val dnsServers: () -> List<String> = { DEFAULT_DNS_SERVERS },
    private val networkOnline: () -> Boolean = { true },
    private val installer: RootfsInstaller? = null,
    override val diagnostics: UserspaceDiagnostics = UserspaceDiagnostics(),
) : DistributionManager {
    private val rootfs: File get() = runtime.rootfsDir

    /**
     * The setup pipeline, in order. Each step either completes or throws — there is no partial
     * success, because the state machine only marks the userspace Installed once this and
     * [healthProbe] have both passed. The steps mirror [UbuntuDistributionManager.setup]'s shape
     * (register user → configure DNS → configure repositories → update → install base → configure
     * sudo → verify runtime) but every step speaks apk, not apt.
     *
     * [SetupStep.INSTALL_EXTRA_PACKAGES] is the same exception the Ubuntu pipeline makes it: the
     * only step whose failure is always a warning, because it runs only because the user asked for
     * it and the userspace underneath it is already complete when it starts.
     *
     * @param onStep invoked as each step begins, for the install screen's progress display
     * @param onProgress invoked with the newest output line of the long-running commands, so a
     *   slow-but-alive `apk add` is visibly alive instead of looking wedged behind a step label
     * @param extras the optional packages the user ticked on the install dialog. Empty means the
     *   extras step does not run at all.
     * @return the warnings collected along the way (empty on a fully clean install)
     */
    override suspend fun setup(
        onStep: suspend (SetupStep) -> Unit,
        onProgress: (String) -> Unit,
        extras: List<OptionalPackage>,
    ): SetupReport {
        val warnings = mutableListOf<String>()
        requireOnline("starting setup")
        // One run, one blocked-syscall log: the fork appends to it for as long as it runs, so
        // without this the evidence for a failure at minute twelve is buried under the traps of
        // every previous run this device has done.
        runtime.resetSigsysLog()
        onStep(SetupStep.REGISTER_USER)
        registerAlpineUser()
        runCatching { nameSupplementaryGroups() }.onFailure { failure ->
            warnings += "could not name the Android group IDs in /etc/group: " +
                (failure.message ?: failure.javaClass.simpleName)
        }
        onStep(SetupStep.PREPARE_WORKSPACE)
        prepareWorkspace()
        onStep(SetupStep.CONFIGURE_DNS)
        configureDns()
        // Still inside the DNS step's label: resolution is verified from inside proot before a
        // single package byte is fetched, so a dead resolver is named as DNS — not discovered by
        // exhausting apk's repository fetch ten minutes later.
        verifyResolution()
        onStep(SetupStep.CONFIGURE_APT)
        configureApkRepositories()
        onStep(SetupStep.UPDATE_PACKAGES)
        // The first proot execution used to be the package-manager rung itself in the Ubuntu
        // pipeline, which is how a broken runtime read as "every mirror is down". The smoke command
        // answers before any repository is asked anything, and its verdict is a
        // [UserspaceFailure.ProotLaunchFailed].
        verifyRuntime()
        requireOnline("updating package index")
        requireApkDiskSpace()
        apkUpdate(onProgress)
        onStep(SetupStep.INSTALL_BASE_PACKAGES)
        installBasePackages(onProgress, warnings)
        configureSudo(warnings)
        configureGuestPath(warnings)
        if (OptionalPackages.resolve(extras).isNotEmpty()) {
            onStep(SetupStep.INSTALL_EXTRA_PACKAGES)
            installExtraPackages(extras, onProgress, warnings)
        }
        onStep(SetupStep.VERIFY)
        return SetupReport(warnings.toList())
    }

    /**
     * Whether setup has run to completion: the account exists, the workspace is in place and the
     * repositories file carries our marker. Cheap and file-based — [healthProbe] is the expensive
     * truth. Never throws, for the same reason the Ubuntu manager's [isConfigured] never does.
     */
    override fun isConfigured(): Boolean {
        val passwd = File(rootfs, "etc/passwd")
        val repos = File(rootfs, "etc/apk/repositories")
        if (!passwd.isFile || !repos.isFile) return false
        return passwd.readLines().any { it.startsWith(PASSWD_PREFIX) } &&
            File(rootfs, WORKSPACE_DIR).isDirectory &&
            repos.readText().contains(APK_MARKER)
    }

    /**
     * The post-install and on-demand health check: can the session path actually run commands, is
     * the session root (so `apk add` will work), is DNS up, is the package database consistent. The
     * host-list card is shown only while this passes. Mirrors [UbuntuDistributionManager.healthProbe]
     * field by field, with `apk info` standing in for `apt-get check` and the login probe checking
     * for `apk` rather than `dpkg`/`apt-get`.
     *
     * @param probeNetwork when false the name-resolution command is not run at all, and
     *   [HealthReport.networkUp] reads as passing by convention. The repair ladder asks for this.
     */
    override suspend fun healthProbe(probeNetwork: Boolean): HealthReport {
        val shell = runSessionCommand("echo $PROBE_MARKER", PROBE_TIMEOUT_MS)
        val whoami = runSessionCommand("whoami", PROBE_TIMEOUT_MS)
        val network =
            if (probeNetwork) {
                runSessionCommand("getent hosts $networkHost", NETWORK_TIMEOUT_MS)
            } else {
                null
            }
        // `apk info` (no arguments) lists every installed package and exits 0 when the package
        // database is consistent and usable — the apk equivalent of `apt-get check`.
        val apk = runSessionCommand("apk info", APT_TIMEOUT_MS)
        val login = runSessionCommand(LOGIN_PROBE_COMMAND, PROBE_TIMEOUT_MS)
        // The two faults no command from inside the guest would ever report as themselves, asked on
        // the host instead: the installer owns both proofs (a probe file for writability, an open
        // for the lock path) and neither question needs a proot child to answer.
        val unusableTemp = installer?.unusableGuestTemp().orEmpty()
        val unusableLocks = installer?.unusablePackageLocks().orEmpty()
        val loginLines =
            login
                ?.outputText()
                ?.lines()
                ?.map { stripEscapes(it).trim() }
                .orEmpty()
        val account = whoami?.outputText()?.answerLine()

        fun marked(prefix: String): String? =
            loginLines
                .firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        return HealthReport(
            shellWorks = shell != null && shell.exitCode == 0 && shell.outputText().contains(PROBE_MARKER),
            account = account,
            accountCorrect = account == ROOT_ACCOUNT,
            networkUp = if (probeNetwork) network?.exitCode == 0 else true,
            aptUsable = apk?.exitCode == 0,
            loginUid = marked(PROBE_UID_PREFIX)?.toIntOrNull(),
            loginPath = marked(PROBE_PATH_PREFIX),
            missingPrograms =
                loginLines
                    .filter { it.startsWith(PROBE_MISSING_PREFIX) }
                    .map { it.removePrefix(PROBE_MISSING_PREFIX).trim() }
                    .filter { it.isNotEmpty() },
            unusableTempDirs = unusableTemp,
            unusablePackageLocks = unusableLocks,
        )
    }

    /**
     * Names the Android group IDs the app process is in, by writing a line per unnamed ID into the
     * rootfs's `/etc/group`. Identical to the Ubuntu manager's [nameSupplementaryGroups] because
     * BusyBox's `/etc/group` format is identical to glibc's — the same [AndroidGroupNames.groupFile]
     * helper writes both. Idempotent; never fatal.
     */
    override fun nameSupplementaryGroups(): Boolean {
        val group = File(rootfs, "etc/group")
        if (!group.isFile) return false
        val existing = group.readLines()
        val named = AndroidGroupNames.groupFile(existing, supplementaryGids())
        if (named == existing) return false
        writeAtomically(group, named.joinToString("\n", postfix = "\n"))
        return true
    }

    /**
     * No-op. apk keeps no apt-style last-known-good mirror memo beside the rootfs, so there is
     * nothing to forget on uninstall — the honest implementation is the one that does nothing.
     * Recorded so the install log shows the uninstall path did reach this step.
     */
    override fun clearLastGoodMirror() {
        diagnostics.record(UserspaceDiagnosticCategory.APT, "apk keeps no mirror memo; nothing to clear")
    }

    // -------------------------------------------------------------------- setup steps

    /**
     * Writes the `alpine` account into `/etc/passwd`, `/etc/group` and `/etc/shadow`. The Alpine
     * minirootfs ships only `root` (and the `wheel` group, gid 10), so a non-root account must be
     * added before any session can stand in the user's home. The uid/gid written are the app's
     * own, for the same reason the Ubuntu manager writes its own: proot's fake-root session maps
     * to the account the workspace belongs to, and the workspace belongs to the app uid.
     *
     * The shell is `/bin/sh` (BusyBox ash), the shell the minirootfs ships; `/bin/bash` becomes
     * available after [installBasePackages] runs `apk add bash`, and a user who wants it can
     * `chsh` afterwards. Writing `/bin/bash` here would make every pre-setup session fail with 127.
     *
     * `/etc/shadow` uses `!` as the locked-password marker (Alpine's `alpine-baselayout` generates
     * every non-root account with `:!:`), so the account is locked until `passwd alpine` runs —
     * the same convention the Ubuntu manager uses with `*`. The session is fake root, so a login
     * never asks for a password, and the locked state is the safe one.
     */
    private fun registerAlpineUser() {
        val passwd = File(rootfs, "etc/passwd")
        val group = File(rootfs, "etc/group")
        val shadow = File(rootfs, "etc/shadow")
        val passwdLine = "$ACCOUNT_NAME:x:$appUid:$appGid:Alpine:$HOME_DIR:/bin/sh"
        val groupLine = "$ACCOUNT_NAME:x:$appGid:"
        val shadowLine = "$ACCOUNT_NAME:!:19850:0:99999:7:::"
        rewriteKeepingOthers(passwd, PASSWD_PREFIX, passwdLine)
        rewriteKeepingOthers(group, PASSWD_PREFIX, groupLine)
        rewriteKeepingOthers(shadow, PASSWD_PREFIX, shadowLine)
        // Add the account to wheel (already shipped by alpine-baselayout as wheel:x:10:root) so
        // [configureSudo]'s `%wheel ALL=(ALL) ALL` grants elevation. Idempotent: a second run does
        // not append the name a second time.
        addToGroup("wheel", ACCOUNT_NAME)
    }

    /**
     * Appends [member] to the named group's line in `/etc/group` if it is not already there. The
     * group line is `name:x:gid:comma,separated,members`; a line with no members ends in a colon
     * after the gid, so the first append writes `name:x:gid:member` and a second writes nothing.
     */
    private fun addToGroup(
        groupName: String,
        member: String,
    ) {
        val group = File(rootfs, "etc/group")
        if (!group.isFile) return
        val lines = group.readLines().toMutableList()
        var changed = false
        for (i in lines.indices) {
            val fields = lines[i].split(":").toMutableList()
            if (fields.size >= 4 && fields[0] == groupName) {
                val members = fields[3].split(",").filter { it.isNotBlank() }
                if (member !in members) {
                    val newMembers = (members + member).joinToString(",")
                    fields[3] = newMembers
                    lines[i] = fields.joinToString(":")
                    changed = true
                }
                break
            }
        }
        if (changed) writeAtomically(group, lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * Creates the user's home and the workspace directory under it. The minirootfs ships neither
     * `/home/alpine` nor its workspace, so both are mkdir'd here. A workspace that is a symlink is
     * deleted and recreated (the same [deleteTreeNoFollow] safety the Ubuntu manager uses), so a
     * planted link cannot send the workspace sweep out of the rootfs.
     */
    private suspend fun prepareWorkspace() {
        val home = File(rootfs, HOME_DIR.removePrefix("/"))
        if (!home.isDirectory) home.mkdirs()
        if (installer != null) installer.ensureGuestTemp() else File(rootfs, "tmp").mkdirs()
        val workspace = File(rootfs, WORKSPACE_DIR)
        val linked =
            java.nio.file.Files
                .isSymbolicLink(workspace.toPath())
        if (workspace.isDirectory && !linked) return
        deleteTreeNoFollow(workspace)
        workspace.mkdirs()
        if (!workspace.isDirectory) {
            throw IOException(
                "could not create the workspace at ${workspace.path} - Alpine's home directory " +
                    "is missing or not writable",
            )
        }
    }

    /**
     * Writes `/etc/resolv.conf` with the device's current resolvers. Alpine's musl resolver reads
     * the file the same way glibc does (one `nameserver` line per server), and the minirootfs ships
     * no systemd-resolved stub to clobber, so the write is a plain atomic replace. The
     * `options timeout:2 attempts:3` line mitigates musl's known behaviour of failing to resolve
     * when a server answers slowly.
     */
    private fun configureDns() {
        val servers = filterDnsServers(dnsServers())
        writeAtomically(
            File(rootfs, "etc/resolv.conf"),
            buildString {
                servers.forEach { append("nameserver $it\n") }
                append("options timeout:2 attempts:3\n")
            },
        )
        diagnostics.record(
            UserspaceDiagnosticCategory.DNS,
            "resolv.conf written",
            detail = servers.joinToString(" "),
        )
    }

    /**
     * Writes `/etc/apk/repositories` with the two lines a pinned release needs: `main` and
     * `community`, both under `https://dl-cdn.alpinelinux.org/alpine/<release>/`. The release comes
     * from [LinuxDistro.release] (e.g. `v3.21`), which the catalogue sets from the Alpine branch.
     * No `@edge` / `@testing` tag is used — those are for pulling edge alongside a stable release,
     * which a pinned install does not do.
     */
    private fun configureApkRepositories() {
        val base = "$ALPINE_RELEASES_URL/${distro.release}"
        writeAtomically(
            File(rootfs, "etc/apk/repositories"),
            "$base/main\n$base/community\n",
        )
        diagnostics.record(
            UserspaceDiagnosticCategory.APT,
            "apk repositories written",
            detail = "$base/{main,community}",
        )
    }

    /**
     * Proves resolution works from inside proot before anything network-heavy runs: `getent`
     * against the repository host apk's first fetch will ask for. A failure here is a typed
     * [UserspaceFailure.DnsUnresolved] naming the resolvers that were written — not an apk verdict
     * ten minutes later.
     */
    private suspend fun verifyResolution() {
        val result = runSetupCommand("getent hosts $networkHost", RESOLUTION_TIMEOUT_MS)
        if (result == null || result.exitCode != 0) {
            throw UserspaceFailure.DnsUnresolved(
                servers = filterDnsServers(dnsServers()),
                detail =
                    if (result == null) {
                        "the check did not answer within ${RESOLUTION_TIMEOUT_MS / 1000} seconds"
                    } else {
                        result.outputText().take(500).ifBlank { "getent hosts $networkHost failed without a word" }
                    },
            )
        }
    }

    /**
     * The smoke command: proves proot itself can launch and answer before any apk command runs. A
     * failure here is a [UserspaceFailure.ProotLaunchFailed] — the same verdict the Ubuntu pipeline
     * raises, because the failure is the runtime's, not the package manager's.
     */
    private suspend fun verifyRuntime() {
        val result = runSetupCommand("echo $RUNTIME_SMOKE_MARKER", RUNTIME_SMOKE_TIMEOUT_MS)
        val ok = result != null && result.exitCode == 0 && result.outputText().contains(RUNTIME_SMOKE_MARKER)
        diagnostics.record(
            UserspaceDiagnosticCategory.PROOT,
            "smoke",
            exitCode = result?.exitCode,
            detail = if (ok) null else "echo marker not answered",
        )
        if (!ok) {
            throw UserspaceFailure.ProotLaunchFailed(
                exitCode = result?.exitCode ?: -1,
                tail = result?.outputText()?.take(2000) ?: "",
                detail =
                    if (result == null) {
                        "the smoke command did not answer within ${RUNTIME_SMOKE_TIMEOUT_MS / 1000} seconds"
                    } else {
                        null
                    },
            )
        }
    }

    /** The offline gate for the network-heavy phases: one check, one typed verdict. */
    private fun requireOnline(phase: String) {
        if (!networkOnline()) {
            diagnostics.record(UserspaceDiagnosticCategory.APT, "offline gate", detail = phase)
            throw UserspaceFailure.Offline(detail = "checked before $phase")
        }
    }

    /**
     * The apk phase's own disk gate, parallel to the Ubuntu manager's apt gate. The installer's
     * gate budgeted the tarball plus the unpacked system, but it ran before any of that was
     * written, and the disk may have moved underneath the install since. The budget here is only
     * what the packages themselves need.
     */
    private fun requireApkDiskSpace() {
        val free = runtime.freeBytes()
        if (free <= 0L) return
        val needed = distro.rootfsSizeBytes * APK_TARBALL_MULTIPLE + APK_HEADROOM_BYTES
        diagnostics.record(
            UserspaceDiagnosticCategory.STORAGE,
            "apk disk gate",
            detail = "free=${free / MIB}MB, needed=${needed / MIB}MB",
        )
        if (free < needed) {
            throw UserspaceFailure.DiskFull(
                neededBytes = needed,
                freeBytes = free,
                detail = "Alpine needs ${needed / MIB}MB before the apk phase, free ${free / MIB}MB",
            )
        }
    }

    /**
     * `apk update`: fetches the repository index. `--no-cache` is used because the rootfs lives on
     * Android's emulated storage, where `/var/cache/apk` writes are slower than a clean fetch and
     * the cache adds nothing an install does not re-fetch anyway. A failure is an
     * [UserspaceFailure.ArchiveUnavailable] — the apk archive could not be reached, which is the
     * same shape of fault as the Ubuntu manager's apt-update failure.
     */
    private suspend fun apkUpdate(onProgress: (String) -> Unit) {
        val result = runSetupCommand("apk update", APK_UPDATE_TIMEOUT_MS, onOutput = { onOutputLine(it, onProgress) })
        if (result == null || result.exitCode != 0) {
            throw UserspaceFailure.ArchiveUnavailable(
                detail =
                    (if (result == null) "apk update did not answer in time" else "apk update failed") +
                        (result?.outputText()?.take(500)?.let { ": $it" } ?: ""),
            )
        }
    }

    /**
     * Installs the base package set a terminal/SSH-oriented Alpine userspace wants on top of the
     * minirootfs. `apk add` resolves and installs atomically; `--no-cache` keeps `/var/cache/apk`
     * clean. The set is smaller than Ubuntu's because BusyBox already provides most coreutils, but
     * the user-facing names (`bash`, `coreutils`, `util-linux`) are what a user expects to find.
     *
     * `openrc` is already in the minirootfs (a hard dependency of `alpine-base`), so it is not
     * listed here — listing it would be a no-op apk re-installs anyway.
     */
    private suspend fun installBasePackages(
        onProgress: (String) -> Unit,
        warnings: MutableList<String>,
    ) {
        val packages = BASE_PACKAGES.joinToString(" ")
        val result = runSetupCommand("apk add --no-cache $packages", APK_INSTALL_TIMEOUT_MS, onOutput = { onOutputLine(it, onProgress) })
        if (result == null || result.exitCode != 0) {
            throw UserspaceFailure.ArchiveUnavailable(
                detail =
                    (if (result == null) "apk add did not answer in time" else "apk add failed") +
                        (result?.outputText()?.take(500)?.let { ": $it" } ?: ""),
            )
        }
    }

    /**
     * Configures sudo for the `alpine` account via `/etc/sudoers.d`. Alpine's `wheel` group is
     * already shipped by `alpine-baselayout`, and [registerAlpineUser] added the account to it, so
     * a `%wheel` rule would also work — but the per-account rule is the same shape the Ubuntu
     * manager uses, and it is the one that does not depend on the group file's exact contents.
     * Best-effort: an unwritable `/etc/sudoers.d` is a warning, not a failed install.
     */
    private fun configureSudo(warnings: MutableList<String>) {
        try {
            val dir = File(rootfs, "etc/sudoers.d")
            dir.mkdirs()
            val file = File(dir, "90-eclipse-alpine")
            writeAtomically(
                file,
                "$ACCOUNT_NAME ALL=(ALL) NOPASSWD: ALL\n" +
                    "Defaults\tenv_reset\n" +
                    "Defaults\tsecure_path=\"$LINUX_PATH\"\n",
            )
            file.setExecutable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, false)
        } catch (t: Throwable) {
            warnings += "sudo configuration skipped: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Writes the guest's PATH into `/etc/environment` so a login shell, `su -` or PAM session
     * inherits the sbin directories. Alpine's `/etc/profile` already sets the same PATH on login,
     * but the file is read by tools that bypass the shell (sudo's secure_path, `su -`), so this
     * closes the same gap the Ubuntu manager's [configureGuestPath] closes. Best-effort.
     */
    private fun configureGuestPath(warnings: MutableList<String>) {
        try {
            writeAtomically(File(rootfs, "etc/environment"), "PATH=\"$LINUX_PATH\"\n")
        } catch (t: Throwable) {
            warnings += "guest PATH configuration skipped: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * Installs the optional packages the user ticked on the install dialog. Each
     * [OptionalPackage]'s apt-package names are translated to their apk equivalents via
     * [apkPackageNames]; the whole selection is `apk add`'d in one command (apk resolves
     * dependencies atomically, unlike apt's per-package install). The whole step is a warning, not
     * a failure, because the base system is already whole when it runs.
     */
    private suspend fun installExtraPackages(
        extras: List<OptionalPackage>,
        onProgress: (String) -> Unit,
        warnings: MutableList<String>,
    ) {
        val apkNames = OptionalPackages.resolve(extras).flatMap { apkPackageNames(it) }.distinct()
        if (apkNames.isEmpty()) return
        val packages = apkNames.joinToString(" ")
        val result = runSetupCommand("apk add --no-cache $packages", APK_INSTALL_TIMEOUT_MS, onOutput = { onOutputLine(it, onProgress) })
        if (result == null || result.exitCode != 0) {
            warnings += "could not install optional packages ($packages): " +
                (
                    result
                        ?.outputText()
                        ?.take(200)
                        ?.trim()
                        ?.ifBlank { "exit ${result.exitCode}" } ?: "no answer in time"
                )
        }
    }

    /**
     * Maps an [OptionalPackage]'s apt package names to their apk equivalents. Most are identical
     * (git, curl, vim); the few that differ are translated (python3-pip → py3-pip, build-essential
     * → build-base). npm globals and vendor installers are skipped here — the Ubuntu pipeline runs
     * them through a separate Node.js / npm path, and an Alpine extra-package install does not try
     * to reproduce that; a user who needs a global npm package can `npm install -g` it themselves
     * once Node.js is in place.
     */
    private fun apkPackageNames(pkg: OptionalPackage): List<String> =
        pkg.aptPackages.map { aptName ->
            when (aptName) {
                "python3-pip" -> "py3-pip"
                "build-essential" -> "build-base"
                "nodejs" -> "nodejs"
                "npm" -> "npm"
                else -> aptName
            }
        }

    // -------------------------------------------------------------------- command runners

    /**
     * Runs one scripted pipeline command through the runtime's argv with [setupEnv]. Parallel to
     * the Ubuntu manager's [runSetupCommand]; the only difference is [setupEnv]'s knobs.
     */
    private suspend fun runSetupCommand(
        command: String,
        timeoutMs: Long,
        onOutput: ((ByteArray) -> Unit)? = null,
    ): ProotCommandResult? =
        try {
            runtime.runCommand(runtime.commandArgv(command), env = setupEnv(), timeoutMs = timeoutMs, onOutput = onOutput)
        } catch (e: IOException) {
            throw UserspaceFailure.fromMessage(e.message ?: "", e) ?: e
        }

    /**
     * Runs a command through the *session* argv and environment — the exact pair a terminal tab is
     * spawned with — because [healthProbe] must prove the path the user meets works.
     */
    private suspend fun runSessionCommand(
        command: String,
        timeoutMs: Long,
    ): ProotCommandResult? = runtime.runCommand(runtime.sessionArgv(command), env = runtime.baseEnv(), timeoutMs = timeoutMs)

    /**
     * The environment every setup command runs with: the runtime's [ProotRuntime.baseEnv] (which
     * carries HOME, PATH, the proot loader vars and the locale) plus apk's own non-interactive
     * knob. Alpine has no `DEBIAN_FRONTEND` analogue, but `LC_ALL=C` keeps apk's output stable
     * and matches what the Ubuntu pipeline pins.
     */
    private fun setupEnv(): List<String> = runtime.baseEnv() + listOf("LC_ALL=C")

    /** Strips ANSI escape sequences from a line, so [healthProbe]'s `whoami`/`id` parsing is clean. */
    private fun stripEscapes(line: String): String = line.replace(ANSI_ESCAPE, "")

    /** The first non-blank line of `whoami`'s output — the account name, without proot's teardown noise. */
    private fun String.answerLine(): String? = lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.ifBlank { null }

    /** Forwards one output chunk to the install screen's progress line, as text. */
    private fun onOutputLine(
        bytes: ByteArray,
        onProgress: (String) -> Unit,
    ) {
        val text = String(bytes, Charsets.UTF_8)
        val lastLine = text.lineSequence().lastOrNull { it.isNotBlank() }
        if (lastLine != null) onProgress(lastLine.take(200))
    }

    /** Replaces any existing line starting with [prefix] with [line], preserving every other line. */
    private fun rewriteKeepingOthers(
        file: File,
        prefix: String,
        line: String,
    ) {
        check(file.isFile) { "the rootfs has no ${file.path}" }
        val kept = file.readLines().filterNot { it.startsWith(prefix) }
        writeAtomically(file, (kept + line).joinToString("\n", postfix = "\n"))
    }

    /**
     * Replaces [file]'s content in one atomic rename, so whatever reads these files next — the
     * shell a repair spawns, apk mid-run, or the app itself after a crash killed setup mid-write —
     * sees either the old complete file or the new complete one, never a truncated `/etc/passwd`.
     */
    private fun writeAtomically(
        file: File,
        content: String,
    ) {
        val tmp = File(file.parentFile, ".${file.name}.new-${System.nanoTime()}")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not replace ${file.path}")
        }
    }

    /**
     * The repository host [verifyResolution] resolves: the same CDN apk's first fetch will ask
     * for, so a dead resolver is named before a single package byte is fetched.
     */
    private val networkHost: String get() = "dl-cdn.alpinelinux.org"

    companion object {
        /** What `whoami` has to answer inside a session: proot's `-0` gives it root. */
        private const val ROOT_ACCOUNT = "root"

        /** The unprivileged account this manager creates. */
        private const val ACCOUNT_NAME = "alpine"

        private const val HOME_DIR = "/home/alpine"
        private const val WORKSPACE_DIR = "home/alpine/workspace"
        private const val PASSWD_PREFIX = "alpine:"

        /** The marker `isConfigured` looks for in `/etc/apk/repositories`. */
        private const val APK_MARKER = "dl-cdn.alpinelinux.org/alpine"

        private const val ALPINE_RELEASES_URL = "https://dl-cdn.alpinelinux.org/alpine"

        /**
         * The PATH a login shell should see. Identical to the Ubuntu manager's [LINUX_PATH];
         * Alpine's own `/etc/profile` sets the same list.
         */
        internal const val LINUX_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        /** The base package set installed on top of the minirootfs. */
        private val BASE_PACKAGES =
            listOf(
                "busybox-extras",
                "bash",
                "coreutils",
                "util-linux",
                "openssh-client",
                "ca-certificates",
                "tzdata",
                "sudo",
                "nano",
            )

        /** Reuses the Ubuntu manager's anycast DNS defaults: 1.1.1.1 and 8.8.8.8. */
        private val DEFAULT_DNS_SERVERS = UbuntuDistributionManager.DEFAULT_DNS_SERVERS

        private const val PROBE_MARKER = "eclipse-probe-ok"
        private const val RUNTIME_SMOKE_MARKER = "eclipse-runtime-ok"
        private const val PROBE_TIMEOUT_MS = 2 * 60_000L
        private const val NETWORK_TIMEOUT_MS = 15_000L
        private const val APT_TIMEOUT_MS = 60_000L
        private const val RESOLUTION_TIMEOUT_MS = 15_000L
        private const val RUNTIME_SMOKE_TIMEOUT_MS = 15_000L
        private const val APK_UPDATE_TIMEOUT_MS = 5 * 60_000L
        private const val APK_INSTALL_TIMEOUT_MS = 10 * 60_000L

        private const val APK_TARBALL_MULTIPLE = 4L
        private const val APK_HEADROOM_BYTES = 100L * 1024 * 1024
        private const val MIB = 1024L * 1024

        /** The markers [LOGIN_PROBE_COMMAND] prints, one line each, so the probe's answer is read field by field. */
        private const val PROBE_UID_PREFIX = "eclipse-uid="
        private const val PROBE_PATH_PREFIX = "eclipse-path="
        private const val PROBE_MISSING_PREFIX = "eclipse-missing="

        /**
         * The programs [healthProbe] asks a login shell to find, by name: `command -v`. Alpine's
         * equivalents of the Ubuntu set — `apk` instead of `apt-get`, no `dpkg`, `ldconfig` is
         * absent on musl so it is dropped.
         */
        private val LOGIN_PROBE_PROGRAMS = listOf("sh", "rm", "tar", "apk", "bash")

        /**
         * What [healthProbe] runs to answer for the *login* path rather than for the app's own.
         * Same shape as the Ubuntu manager's: the session argv is already a login shell
         * (`/bin/sh -l -c <command>` under [ProotRuntime]), so this text executes inside a login
         * shell and `$PATH` is the one `/etc/profile` and `/etc/environment` produced.
         */
        private val LOGIN_PROBE_COMMAND =
            "echo $PROBE_UID_PREFIX\$(id -u); echo $PROBE_PATH_PREFIX\$PATH; " +
                "for p in ${LOGIN_PROBE_PROGRAMS.joinToString(" ")}; do " +
                "command -v \$p >/dev/null 2>&1 || echo $PROBE_MISSING_PREFIX\$p; done"

        private val ANSI_ESCAPE = Regex("\u001b\\[[0-9;]*[a-zA-Z]")
    }
}
