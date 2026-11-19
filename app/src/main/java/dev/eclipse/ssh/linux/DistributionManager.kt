package dev.eclipse.ssh.linux

/**
 * The setup, configured-state and health surface every rootfs-installable distribution exposes.
 *
 * This is the seam the install pipeline speaks to, regardless of which package manager the
 * rootfs on disk actually drives. The app was built around Ubuntu and `apt`, and
 * [UbuntuDistributionManager] is the first implementation; [LinuxDistroCatalog] already
 * declares Alpine entries (`family = ALPINE`, `packageManager = APK`), and a second
 * implementation for Alpine's `apk` ([AlpineDistributionManager]) can land behind this same
 * interface without the install flow, the state machine, the UI or the diagnostics ring knowing
 * anything about either one.
 *
 * The contract every implementation keeps:
 *
 *  - [setup] either completes every step it names (the userspace is whole when it returns) or
 *    throws; the state machine only marks the install Installed once [setup] and [healthProbe]
 *    both pass, so a step that silently no-ops is a step that ships a broken rootfs.
 *  - [isConfigured] is cheap and file-based and never throws — the state machine calls it from
 *    its own constructor to reconstruct state after a crash, where the missing rootfs that is
 *    the very thing being checked for must read as "not configured", not as an exception.
 *  - [healthProbe] is the expensive truth that [isConfigured] only approximates: it runs the
 *    session path the user's terminal tab will run, and reports each field it could measure.
 *  - [diagnostics] is the ring every event of an install is filed into; the install screen's
 *    "Install log" row reads it, and [RootfsInstaller] writes to the same instance so a
 *    failure in either half of the install — download/extract or setup — surfaces in one log.
 *
 * The two callers that hold a manager are [LinuxUserspaceGraph.distribution] (the graph the
 * device was built around) and [LinuxUserspaceManager.distribution] (the field the state
 * machine drives). Both are typed as this interface rather than as [UbuntuDistributionManager]
 * so a future family — Alpine first — slots in without a graph rebuild touching the call sites.
 *
 * The members an implementation keeps to itself are not on this interface: the mirror ladder,
 * the repair prologue, the apt-specific commands an Ubuntu rootfs runs are all private to
 * [UbuntuDistributionManager], and the apk-specific commands an Alpine rootfs will run will be
 * private to its manager. What is here is what the install flow calls, nothing more.
 */
interface DistributionManager {
    /**
     * The shared install-log ring this manager and [RootfsInstaller] both write to.
     *
     * A property rather than a constructor parameter the graph passes through, because the graph
     * builds the manager with a single [UserspaceDiagnostics] instance shared across the whole
     * install — and the install log the user reads after a failure is the one ring that holds
     * both halves of it, whichever half failed.
     */
    val diagnostics: UserspaceDiagnostics

    /**
     * Runs the setup pipeline, in order, until the rootfs on disk is a complete userspace.
     *
     * Each step either completes or throws — there is no partial success, because the state
     * machine only marks the userspace Installed once this and [healthProbe] have both passed.
     * The warnings an implementation returns in [SetupReport] are the softer things inside a
     * step (a base package that would not install, sudo not configured), never the step itself.
     *
     * @param onStep invoked as each step begins, for the install screen's progress display
     * @param onProgress invoked with the newest output line of the long-running commands, so a
     *   slow-but-alive run is visibly alive instead of looking wedged behind a step label
     * @param extras the optional packages the user ticked on the install dialog. Empty — which is
     *   every call but the install's own — means the extras step does not run at all.
     * @return the warnings collected along the way (empty on a fully clean install)
     */
    suspend fun setup(
        onStep: suspend (SetupStep) -> Unit = {},
        onProgress: (String) -> Unit = {},
        extras: List<OptionalPackage> = emptyList(),
    ): SetupReport

    /**
     * Whether setup has run to completion. Cheap and file-based — [healthProbe] is the expensive
     * truth, and this is the approximation the state machine reads at construction time.
     *
     * Never throws: it is called from the state machine's constructor to reconstruct state after
     * a crash, where the missing rootfs that is the very thing being checked for must read as
     * "not configured", not as an exception nobody can catch there.
     */
    fun isConfigured(): Boolean

    /**
     * The post-install and on-demand health check: can the user-facing session path actually
     * run commands, is the session root the one identity the package manager needs, is DNS up,
     * is the package database consistent. The host-list card is shown only while this passes.
     *
     * @param probeNetwork when false the name-resolution command is not run at all, and
     *   [HealthReport.networkUp] reads as passing by convention. The repair ladder asks for this:
     *   it can be running because the network is down, and a repair that reached for the network
     *   before it had looked at the rootfs is the mistake this whole layer is arranged to avoid.
     */
    suspend fun healthProbe(probeNetwork: Boolean = true): HealthReport

    /**
     * Writes the running app process's supplementary Android group IDs into the rootfs's
     * `/etc/group`, so a session's `groups`/`id` prints names instead of bare numbers.
     *
     * Called on every start rather than only at install time: the Android group IDs a session
     * shows are a property of the running app, so an install made before the rootfs named them
     * is corrected here — the first terminal it opens after this — instead of only by a
     * reinstall. Soft by contract: a rootfs that cannot be written here is a warning the caller
     * records, never a failed install — the userspace is usable either way, what changes is
     * whether `groups` output prints names or numbers.
     *
     * @return true when the group file was written, false when it was not (and the caller's
     *   warning explains why)
     */
    fun nameSupplementaryGroups(): Boolean

    /**
     * Clears whatever distribution-specific memo lives *beside* the rootfs (not inside it) and
     * so survives a `deleteRootfs` — Ubuntu's last-known-good mirror sidecar today, a future
     * Alpine repo pin later. The uninstall path calls this: the memo describes an install that
     * no longer exists, and leaving it behind would make "removed entirely" a lie a
     * storage-usage read can see. A no-op is the honest implementation for a distribution that
     * keeps no such memo, because there is nothing to forget.
     *
     * Never throws: the path is deleted best-effort, and a failure is recorded in [diagnostics]
     * rather than aborting an uninstall that has already torn the rootfs down.
     */
    fun clearLastGoodMirror()
}
