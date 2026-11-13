package dev.eclipse.ssh.linux

/**
 * One Linux distribution the userspace can install, pinned to an exact rootfs tarball.
 *
 * The pin is the whole point: the install flow verifies the download against [rootfsSha256] before a
 * single byte of it is extracted, so the rootfs that lands on the device is exactly the rootfs this
 * build was tested with — not whatever cdimage happened to serve that day.
 *
 * Only Ubuntu Base images are offered, because those are the only root filesystems that are (a)
 * officially published by the distribution, (b) small enough for a phone (a 22.04 base is ~30 MB
 * compressed where the desktop image is ~4 GB), and (c) built without an installer, systemd or a
 * kernel expectation — everything a proot environment cannot provide.
 *
 * [release] is the Ubuntu codename (focal, jammy, …) the tarball series tracks. The distribution
 * manager derives every apt suite from it — `$release`, `$release-updates`, `$release-security` —
 * so a catalog entry is all a version needs to be installable: the rootfs pin and the archive
 * name travel together and can never disagree.
 *
 * [family] is the lineage the entry belongs to — Ubuntu, Debian, Alpine — and [packageManager]
 * is the tool the install pipeline drives inside that rootfs. The Ubuntu family reuses the apt
 * pipeline this class was built around; Alpine uses apk and runs through [ApkSetup] instead.
 * A new family carries its own setup path; the catalog only carries the pin and the names, never
 * the pipeline, so a wrong pair (an Alpine entry stamped `apt`) is caught at setup, not at install
 * time twenty minutes in.
 */
enum class DistroFamily {
    UBUNTU,
    ALPINE,
}

enum class PackageManager {
    APT,
    APK,
}

data class LinuxDistro(
    /** Stable machine id, used for the installed-state directory name. */
    val id: String,
    /** What the UI shows: "Ubuntu 22.04 LTS". */
    val displayName: String,
    /** The apt codename: jammy for 22.04, noble for 24.04, and so on. */
    val release: String,
    /** The Ubuntu architecture name, as it appears in the tarball file name. */
    val ubuntuArch: String,
    /** The lineage this entry belongs to — drives which setup pipeline runs. Defaults to UBUNTU. */
    val family: DistroFamily = DistroFamily.UBUNTU,
    /** The package tool the install pipeline drives inside this rootfs. */
    val packageManager: PackageManager = PackageManager.APT,
    /** Where the verified rootfs tarball comes from. */
    val rootfsTarballUrl: String,
    /** SHA256 of the tarball; a mismatch aborts the install. */
    val rootfsSha256: String,
    /**
     * The pinned tarball's approximate size in bytes, for the storage gates: the free-space
     * check before the download and the sanity check against what the server reports. It is an
     * estimate by design — the SHA is the integrity pin — so every check using it allows for
     * the estimate being wrong in either direction. 0 (the default for hand-built test distros)
     * means "unknown", and the gates skip rather than guess.
     */
    val rootfsSizeBytes: Long = 0L,
)

/**
 * The catalogue of installable distributions plus the device-architecture mapping.
 *
 * Every supported Ubuntu Base series — 20.04 through 26.04 — is listed, newest first, so a
 * caller reading [versionsFor] gets "the latest first" without sorting anything itself. LTS
 * series are the backbone of the list: they are what Ubuntu maintains for five years. The
 * current interim (25.10) is offered too — a conscious exception to the earlier LTS-only
 * rule, added on demand so the newest userspace is reachable — under the same pin-and-verify
 * contract as everything else: a cdimage `SHA256SUMS` line per architecture, verified before
 * a byte is extracted (the arm64 pin was additionally confirmed against a full download and
 * its own byte-for-byte hash at catalog time). Its nine-month support window is the price a
 * user picking it accepts knowingly.
 *
 * The default ([DEFAULT_DISTRO_ID]) is a starting point, not an override: the install screen's
 * version chooser offers every series above (for the device's architecture), newest first, and a
 * pick there — like an already-installed distro — wins over it. It names the newest LTS, not the
 * interim, so tapping through the install dialog without choosing never lands on a series that
 * stops receiving updates nine months after it ships. A user's existing install keeps its
 * installed distro regardless, because the userspace state file records the distro id it was
 * installed from. `LinuxUserspaceGraphProvider.resolveDistro` holds that resolution order.
 */
object LinuxDistroCatalog {
    /**
     * One LTS series: everything the per-architecture entries share. The per-arch map exists
     * because each architecture's tarball is a separate file with its own hash — the pin is per
     * file, not per release.
     */
    private data class ReleaseSeries(
        val id: String,
        val displayName: String,
        val release: String,
        /** The point release this build was verified against; a bump must re-verify the SHA256s. */
        val pointRelease: String,
        /**
         * The point release as it appears in the tarball's file name. Null when the series
         * publishes no point-suffixed tarball — interim releases name the file after the bare
         * version (`ubuntu-base-25.10-base-arm64.tar.gz`) — in which case the name is built
         * from [id].
         */
        val tarballPointRelease: String? = null,
        /** The tarball's approximate size, shared by the per-arch entries; see [LinuxDistro.rootfsSizeBytes]. */
        val tarballSizeBytes: Long,
        val sha256ByArch: Map<String, String>,
    )

    private const val CDIMAGE_RELEASES_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases"

    /** The architectures Ubuntu Base publishes, in catalogue order within a series. */
    private val ARCHES = listOf("amd64", "arm64", "armhf")

    /**
     * The supported series, newest first — the order [all] and [versionsFor] inherit. SHA256s
     * straight from each release's own `SHA256SUMS`, which is published beside the tarballs. If a
     * hash here ever disagrees with a fresh download, the tarball changed and the pin is stale —
     * the install refuses rather than extracting unknown bytes.
     */
    private val series =
        listOf(
            ReleaseSeries(
                id = "ubuntu-26.04",
                tarballSizeBytes = 34_000_000L,
                displayName = "Ubuntu 26.04 LTS",
                release = "resolute",
                pointRelease = "26.04.1",
                tarballPointRelease = "26.04.1",
                sha256ByArch =
                    mapOf(
                        "amd64" to "a496a960472ce474a59590b8987d3a1135d3cbef1991f3b1abe8cacfea8bf85a",
                        "arm64" to "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
                        "armhf" to "a40848e9c4ed72c22bdcb6a7bc9832e1ca8a30e5a3e2a0543dc56ed14e2e38b7",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-25.10",
                tarballSizeBytes = 35_000_000L,
                displayName = "Ubuntu 25.10",
                release = "questing",
                // Interims publish no point-suffixed tarball: the file is ubuntu-base-25.10-base-<arch>,
                // so tarballPointRelease stays null and the name is built from the id. SHA256s verbatim
                // from this release's SHA256SUMS; the arm64 value was confirmed against a full download.
                pointRelease = "25.10",
                sha256ByArch =
                    mapOf(
                        "amd64" to "febb74fc4e09b2d84ed8655a9e25b55777cdb970f8eb8e6dee302676b519bc95",
                        "arm64" to "11534a7d4d94db63e7c65d6012b8696abdd6cf14b4efde01c6b1da196b77bc3b",
                        "armhf" to "b830eca311f03e0514048bfb578bddb885d374be275299bf6391c53fbd6c5a27",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-24.04",
                tarballSizeBytes = 31_000_000L,
                displayName = "Ubuntu 24.04 LTS",
                release = "noble",
                pointRelease = "24.04.5",
                tarballPointRelease = "24.04.5",
                sha256ByArch =
                    mapOf(
                        "amd64" to "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86",
                        "arm64" to "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                        "armhf" to "4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-22.04",
                tarballSizeBytes = 30_000_000L,
                displayName = "Ubuntu 22.04 LTS",
                release = "jammy",
                pointRelease = "22.04.5",
                tarballPointRelease = "22.04.5",
                sha256ByArch =
                    mapOf(
                        "amd64" to "242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a",
                        "arm64" to "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f",
                        "armhf" to "fd77cb0659326b75c08ce06b6b8649d2e13ef9a704a8e9212fec32cb97d42add",
                    ),
            ),
            ReleaseSeries(
                id = "ubuntu-20.04",
                tarballSizeBytes = 29_000_000L,
                displayName = "Ubuntu 20.04 LTS",
                release = "focal",
                pointRelease = "20.04.5",
                tarballPointRelease = "20.04.5",
                sha256ByArch =
                    mapOf(
                        "amd64" to "60e216b60947653dc8989be3821380268315b99b2959c29882781541bfe5a426",
                        "arm64" to "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52",
                        "armhf" to "6bcbfa7f603d79d368d40e138dad98938907d2fb0d6416521417cf8702c2f5de",
                    ),
            ),
        )

    /**
     * One Alpine mini-rootfs release: the smallest root filesystem published by any mainstream
     * Linux distribution (~3 MB compressed). Officially published by dl-cdn.alpinelinux.org,
     * with a published `.sha256` beside every tarball — the same pin-and-verify contract Ubuntu
     * Base offers, only lighter.
     *
     * Alpine ships BusyBox and `apk` rather than `apt`/coreutils, so a catalog entry here carries
     * [DistroFamily.ALPINE] / [PackageManager.APK]; the install pipeline routes on `family` to
     * the apk-specific setup path rather than the apt one this class was first built around.
     * `release` is the Alpine branch (`v3.19`, `v3.20`, `v3.21`); `pointRelease` is the patch
     * version this build was verified against.
     */
    private data class AlpineRelease(
        val id: String,
        val displayName: String,
        val release: String,
        val pointRelease: String,
        val tarballSizeBytesByArch: Map<String, Long>,
        val sha256ByArch: Map<String, String>,
    )

    /** The Alpine architecture names, in catalogue order within a series. */
    private val ALPINE_ARCHES = listOf("arm64", "amd64", "armhf")

    /** The Alpine architecture as it appears in the tarball file name (aarch64 not arm64). */
    private fun alpineTarballArch(ubuntuArch: String): String =
        when (ubuntuArch) {
            "arm64" -> "aarch64"
            "amd64" -> "x86_64"
            "armhf" -> "armhf"
            else -> ubuntuArch
        }

    private const val ALPINE_RELEASES_URL = "https://dl-cdn.alpinelinux.org/alpine"

    /**
     * The Alpine series, newest first. Every SHA256 here was fetched from the `.sha256` file
     * `dl-cdn.alpinelinux.org` publishes beside each tarball (the same source the catalogue's
     * Ubuntu Base entries use cdimage's `SHA256SUMS` for); the install refuses a tarball whose
     * hash disagrees with this pin.
     */
    private val alpineSeries =
        listOf(
            AlpineRelease(
                id = "alpine-3.21",
                displayName = "Alpine 3.21",
                release = "v3.21",
                pointRelease = "3.21.0",
                tarballSizeBytesByArch =
                    mapOf(
                        "arm64" to 3_851_686L,
                        "amd64" to 3_509_360L,
                        "armhf" to 3_253_867L,
                    ),
                sha256ByArch =
                    mapOf(
                        "arm64" to "f31202c4070c4ef7de9e157e1bd01cb4da3a2150035d74ea5372c5e86f1efac1",
                        "amd64" to "55ea3e5a7c2c35e6268c5dcbb8e45a9cd5b0e372e7b4e798499a526834f7ed90",
                        "armhf" to "67ca07481d98ca85dfa52271ca22ed1cc01731ea5a0b37e7d9e4ac34bbfb211f",
                    ),
            ),
            AlpineRelease(
                id = "alpine-3.20",
                displayName = "Alpine 3.20",
                release = "v3.20",
                pointRelease = "3.20.3",
                tarballSizeBytesByArch =
                    mapOf(
                        "arm64" to 3_947_906L,
                        "amd64" to 3_490_290L,
                        "armhf" to 3_255_452L,
                    ),
                sha256ByArch =
                    mapOf(
                        "arm64" to "041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de",
                        "amd64" to "d4e6fd67dcf75e40c451560ac7265166c2b72a0f38ddc9aae756a7de3d1efa0c",
                        "armhf" to "39e396324dc765beb4eef6b1e4d52de3143101094acbe48b837623e4e6bae500",
                    ),
            ),
            AlpineRelease(
                id = "alpine-3.19",
                displayName = "Alpine 3.19",
                release = "v3.19",
                pointRelease = "3.19.7",
                tarballSizeBytesByArch =
                    mapOf(
                        "arm64" to 3_228_534L,
                        "amd64" to 3_300_388L,
                        "armhf" to 3_069_587L,
                    ),
                sha256ByArch =
                    mapOf(
                        "arm64" to "edff55343f93f873841045a017074077a3a0675463bd978abe73acec71417c28",
                        "amd64" to "49263264b5dd296568480ab4ba56af2aa48dac395b780e89a0d74fd7c077f9dc",
                        "armhf" to "4ec53e887a5e6bb396c140cbc8368b73d7556918231eeb225c9081ea5d2aa007",
                    ),
            ),
        )

    /** Everything the app can install, newest LTS series first, keyed by nothing — see [byId]. */
    val all: List<LinuxDistro> =
        series.flatMap { s ->
            ARCHES.map { arch ->
                LinuxDistro(
                    id = s.id,
                    displayName = s.displayName,
                    release = s.release,
                    ubuntuArch = arch,
                    rootfsTarballUrl =
                        "$CDIMAGE_RELEASES_URL/${s.id.removePrefix("ubuntu-")}/release/" +
                            "ubuntu-base-${s.tarballPointRelease ?: s.id.removePrefix("ubuntu-")}-base-$arch.tar.gz",
                    rootfsSha256 = s.sha256ByArch.getValue(arch),
                    rootfsSizeBytes = s.tarballSizeBytes,
                )
            }
        } +
            alpineSeries.flatMap { s ->
                ALPINE_ARCHES.map { arch ->
                    LinuxDistro(
                        id = s.id,
                        displayName = s.displayName,
                        release = s.release,
                        ubuntuArch = arch,
                        family = DistroFamily.ALPINE,
                        packageManager = PackageManager.APK,
                        rootfsTarballUrl =
                            "$ALPINE_RELEASES_URL/${s.release}/releases/${alpineTarballArch(arch)}/" +
                                "alpine-minirootfs-${s.pointRelease}-${alpineTarballArch(arch)}.tar.gz",
                        rootfsSha256 = s.sha256ByArch.getValue(arch),
                        rootfsSizeBytes = s.tarballSizeBytesByArch.getValue(arch),
                    )
                }
            }

    /**
     * Every LTS this device can run, newest first — the version chooser's list. Empty when the
     * device maps to no Ubuntu architecture, which the UI renders as "unsupported" rather than a
     * chooser with nothing in it.
     *
     * The chooser offers the Ubuntu family only: the apt install pipeline is the one this class
     * was first built around, and an entry a chooser surfaces is an entry the install screen
     * promises to set up. Alpine lives in [all] (so [byId] resolves an installed Alpine rootfs
     * already on disk), and surfaces through [alpineVersionsFor] once the apk pipeline is wired.
     *
     * [supportedAbis] is injectable because `Build.SUPPORTED_ABIS` is a device fact a JVM test
     * cannot produce; the first entry is the device's primary ABI, which is the one the proot
     * binaries in `nativeLibraryDir` are built for.
     */
    fun versionsFor(supportedAbis: List<String>): List<LinuxDistro> {
        val ubuntuArch = supportedAbis.firstOrNull()?.toUbuntuArch() ?: return emptyList()
        return all.filter { it.ubuntuArch == ubuntuArch && it.family == DistroFamily.UBUNTU }
    }

    /**
     * Every Alpine series this device can run, newest first. Parallel to [versionsFor] for the
     * apk family — the chooser surfaces Ubuntu first, and an Alpine-aware chooser (or a future
     * family switch) reads this for the apk branch.
     */
    fun alpineVersionsFor(supportedAbis: List<String>): List<LinuxDistro> {
        val ubuntuArch = supportedAbis.firstOrNull()?.toUbuntuArch() ?: return emptyList()
        return all.filter { it.ubuntuArch == ubuntuArch && it.family == DistroFamily.ALPINE }
    }

    /**
     * The default the catalogue would pick for a device's primary ABI: [DEFAULT_DISTRO_ID] on the
     * architecture that ABI maps to, or null where Ubuntu publishes no Base image for it.
     *
     * This is not the install path. `LinuxUserspaceGraphProvider.resolveDistro` prefers an
     * already-installed distro, then the user's pick in the version chooser, and only then this
     * default — so a caller that wants what the app installs wants that, not this.
     */
    fun forDevice(supportedAbis: List<String>): LinuxDistro? {
        val primary = supportedAbis.firstOrNull() ?: return null
        val ubuntuArch = primary.toUbuntuArch() ?: return null
        return all.firstOrNull { it.id == DEFAULT_DISTRO_ID && it.ubuntuArch == ubuntuArch }
    }

    /** One exact catalog entry, or null when the id/arch pair names nothing. */
    fun byId(
        id: String,
        ubuntuArch: String,
    ): LinuxDistro? = all.firstOrNull { it.id == id && it.ubuntuArch == ubuntuArch }

    /** The Android ABI name to Ubuntu's, or null where Ubuntu publishes no Base image for it. */
    private fun String.toUbuntuArch(): String? =
        when (this) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "amd64"
            // Deliberately unmapped: Ubuntu publishes no i386 Base image, so an x86 device gets no
            // userspace and the UI reports it as unsupported rather than offering an install that
            // cannot finish.
            else -> null
        }

    /**
     * The default `LinuxUserspaceGraphProvider.resolveDistro` falls back to when nothing is
     * installed and the user has not picked a version: the newest LTS the catalogue offers, so a
     * user who taps through the install dialog lands on the series Ubuntu maintains the longest.
     * It is only the starting point — the resolution order still lets an installed distro and a
     * chooser pick win, so this never names a version the device already has.
     */
    const val DEFAULT_DISTRO_ID = "ubuntu-26.04"
}
