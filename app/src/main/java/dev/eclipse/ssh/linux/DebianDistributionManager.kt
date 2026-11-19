package dev.eclipse.ssh.linux

/**
 * The Debian family's apt setup manager: Debian, Deepin and Trisquel, all Debian derivatives that
 * reuse the Ubuntu apt pipeline this class subclasses but carry their own archive, codename and
 * (for the proot-distro rebuilds) tarball layout.
 *
 * The generalization is strictly additive — every override is guarded so an Ubuntu entry (family
 * UBUNTU, repoBaseUrl null) keeps byte-identical behaviour:
 *
 *  - [archiveBaseUrl] returns [LinuxDistro.repoBaseUrl] when the distro carries one, so the
 *    ladder and `configureAptSources` write the distro's own archive base (`deb.debian.org`,
 *    `packages.deepin.com`, `archive.trisquel.info`) instead of Ubuntu's
 *    `archive.ubuntu.com`/`ports.ubuntu.com`. Ubuntu entries keep the parent's behaviour.
 *  - [builtinMirrors] is empty: Debian/Deepin/Trisquel publish no machine-readable mirror feed and
 *    have no equivalent of Ubuntu's university-mirror fallbacks, so the ladder is the primary archive
 *    as-is + the primary with IPv4 forced. Ubuntu entries keep the parent's `builtinMirrorUrls`.
 *  - [skipMirrorFeed] is true: the feed at `mirrors.ubuntu.com/mirrors.txt` lists Ubuntu mirrors
 *    only and is meaningless for a Debian-family archive, so [aptUpdate] never fetches it.
 *  - [sourcesListFor] writes the per-distro suite set: Debian/Trisquel carry `<release>`,
 *    `<release>-updates`, `<release>-security` (security at a separate base for Debian/Trisquel),
 *    while Deepin ships a single rolling `apricot` suite. Ubuntu entries keep the parent's
 *    `sourcesListContent` (one base, three suites, the Ubuntu component set).
 *
 * The account model is unchanged: every apt-family distro registers the `ubuntu` account and
 * `/home/ubuntu`, the same internal user the parent registers, because (a) the session is fake root
 * and the account is what owns the workspace/SFTP surfaces, and (b) it is an internal user, not
 * user-visible — `whoami` answers `root`. The ProotRuntime routes the DEBIAN family to the same
 * `/bin/bash` + `/home/ubuntu` the UBUNTU arm uses, so the session argv and home match the user
 * this manager registers.
 *
 * @param distro a Debian-family catalog entry (family == [DistroFamily.DEBIAN]); its `repoBaseUrl`
 *   carries the archive base and its `release` carries the apt codename
 */
class DebianDistributionManager(
    distro: LinuxDistro,
    runtime: ProotRuntime,
    appUid: Int,
    appGid: Int,
    supplementaryGids: () -> IntArray = { IntArray(0) },
    dnsServers: () -> List<String> = { DEFAULT_DNS_SERVERS },
    mirrorListUrl: String = DEFAULT_MIRROR_LIST_URL,
    networkOnline: () -> Boolean = { true },
    aptUpdateAttemptTimeoutMs: Long = APT_UPDATE_ATTEMPT_TIMEOUT_MS,
    installer: RootfsInstaller? = null,
    diagnostics: UserspaceDiagnostics = UserspaceDiagnostics(),
) : UbuntuDistributionManager(
        distro = distro,
        runtime = runtime,
        appUid = appUid,
        appGid = appGid,
        supplementaryGids = supplementaryGids,
        dnsServers = dnsServers,
        mirrorListUrl = mirrorListUrl,
        networkOnline = networkOnline,
        aptUpdateAttemptTimeoutMs = aptUpdateAttemptTimeoutMs,
        installer = installer,
        diagnostics = diagnostics,
    ) {
    /**
     * The distro's own archive base, when the entry carries one — `deb.debian.org/debian`,
     * `packages.deepin.com/deepin`, `archive.trisquel.info/trisquel`. Falls back to the parent's
     * Ubuntu archive for an entry with no `repoBaseUrl`, which is the strictly-additive guard: an
     * Ubuntu entry (family UBUNTU, repoBaseUrl null) keeps `primaryArchiveUrl`'s behaviour.
     */
    override fun archiveBaseUrl(distro: LinuxDistro): String =
        distro.repoBaseUrl ?: super.archiveBaseUrl(distro)

    /**
     * Empty: Debian/Deepin/Trisquel have no Ubuntu-style built-in mirror family. The ladder's
     * fallback rungs are therefore just the primary archive retried (the IPv4-forced rung already ran
     * as rung two), and the exhaustion message names the primary alone.
     */
    override fun builtinMirrors(distro: LinuxDistro): List<String> = emptyList()

    /**
     * True: the live mirror feed at `mirrors.ubuntu.com/mirrors.txt` lists Ubuntu mirrors only, so
     * fetching it for a Debian-family archive would burn the rung's budget against a feed that names
     * nothing usable. The ladder skips the fetch and falls straight through to (empty) built-ins.
     */
    override val skipMirrorFeed: Boolean get() = true

    /**
     * The per-distro sources.list body. Three shapes:
     *
     *  - **Debian** (`bookworm`): `bookworm`, `bookworm-updates` at `deb.debian.org/debian`, and
     *    `bookworm-security` at `deb.debian.org/debian-security` (Debian's security suite lives at
     *    a separate base). Components: `main` (the only component a base install needs; the catalog
     *    pin is the publisher's `main`-only rootfs).
     *  - **Deepin** (`apricot`): a single rolling suite, `apricot`, with components `main contrib
     *    non-free` (Deepin 20's published component set). No `-updates`/`-security` suites.
     *  - **Trisquel** (`nabia`, derived from Ubuntu 20.04 focal): `nabia`, `nabia-updates`,
     *    `nabia-security`, mirroring Ubuntu's archive structure, all from the same
     *    `archive.trisquel.info/trisquel` base (Trisquel serves security from the same base, unlike
     *    Debian). Components: `main`.
     *
     * The marker is the same `# managed by Eclipse SSH` the parent writes, so [isConfigured]
     * recognizes the file as ours.
     */
    override fun sourcesListFor(baseUri: String): String {
        val release = distro.release
        val marker = "# $APT_MARKER\n"
        return when (release) {
            // Deepin 20 ships a single rolling suite; its three components are main/contrib/non-free.
            "apricot" ->
                buildString {
                    append(marker)
                    append("deb $baseUri $release $DEEPIN_COMPONENTS\n")
                }
            // Debian serves -security from a separate base (deb.debian.org/debian-security), the
            // only Debian-family distro whose security base differs from its main base.
            "bookworm" -> {
                val securityBase = debianSecurityBaseUrl(baseUri)
                buildString {
                    append(marker)
                    append("deb $baseUri $release $DEBIAN_COMPONENTS\n")
                    append("deb $baseUri $release-updates $DEBIAN_COMPONENTS\n")
                    append("deb $securityBase $release-security $DEBIAN_COMPONENTS\n")
                }
            }
            // Trisquel (nabia) mirrors Ubuntu's archive structure: security from the same base, like
            // Ubuntu, so the parent's three-line same-base shape applies.
            else ->
                buildString {
                    append(marker)
                    append("deb $baseUri $release $TRISQUEL_COMPONENTS\n")
                    append("deb $baseUri $release-updates $TRISQUEL_COMPONENTS\n")
                    append("deb $baseUri $release-security $TRISQUEL_COMPONENTS\n")
                }
        }
    }

    /**
     * The Debian security archive base, derived from the main base by swapping the
     * `/debian` path segment for `/debian-security` (Debian's published security archive path).
     * Falls back to the main base if the swap is not applicable, so a malformed base never produces
     * a worse source line than the main archive alone.
     */
    private fun debianSecurityBaseUrl(baseUri: String): String =
        if (baseUri.endsWith("/debian")) {
            baseUri.removeSuffix("/debian") + "/debian-security"
        } else if (baseUri.endsWith("/debian/")) {
            baseUri.removeSuffix("/debian/") + "/debian-security"
        } else {
            baseUri
        }

    private companion object {
        /** Debian's base component set: a base install pulls only `main`. */
        private const val DEBIAN_COMPONENTS = "main"

        /** Deepin 20's published component set: main, contrib and non-free. */
        private const val DEEPIN_COMPONENTS = "main contrib non-free"

        /** Trisquel's base component set: `main` (Trisquel ships only free software). */
        private const val TRISQUEL_COMPONENTS = "main"
    }
}
