package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The device-architecture mapping, which is the gate on the whole feature: a device that maps to
 * no distro must be told "unsupported", not offered an install that downloads a tarball nothing on
 * the phone can execute.
 *
 * The three mappings and the one deliberate refusal are each asserted directly, because each is a
 * promise to a different device: an arm64 phone (every modern one), an armv7 phone (the ones
 * Ubuntu calls armhf), and an x86_64 emulator or tablet. The refusal is `x86`: Ubuntu publishes no
 * i386 Base image, so mapping it would be a lie with a download progress bar.
 */
class LinuxDistroCatalogTest {
    @Test
    fun `arm64 devices get the arm64 rootfs`() {
        val distro = LinuxDistroCatalog.forDevice(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        assertThat(distro).isNotNull()
        assertThat(distro!!.ubuntuArch).isEqualTo("arm64")
        assertThat(distro.id).isEqualTo(LinuxDistroCatalog.DEFAULT_DISTRO_ID)
    }

    @Test
    fun `armv7 devices get the armhf rootfs`() {
        val distro = LinuxDistroCatalog.forDevice(listOf("armeabi-v7a", "x86"))
        assertThat(distro).isNotNull()
        assertThat(distro!!.ubuntuArch).isEqualTo("armhf")
    }

    @Test
    fun `x86_64 devices get the amd64 rootfs`() {
        val distro = LinuxDistroCatalog.forDevice(listOf("x86_64", "x86"))
        assertThat(distro).isNotNull()
        assertThat(distro!!.ubuntuArch).isEqualTo("amd64")
    }

    @Test
    fun `x86-only devices are unsupported because no i386 base image exists`() {
        // The refusal, not a crash and not a fallback: `null` is what the wiring layer turns into
        // "this device cannot run the Linux userspace".
        assertThat(LinuxDistroCatalog.forDevice(listOf("x86"))).isNull()
        assertThat(LinuxDistroCatalog.forDevice(emptyList())).isNull()
        assertThat(LinuxDistroCatalog.forDevice(listOf("mips", "riscv64"))).isNull()
    }

    @Test
    fun `every catalog entry is pinned to a sha256 and an https url`() {
        // The pin is the feature's supply-chain boundary: an entry without a hash would extract
        // whatever cdimage served that day, and a non-https URL would let a network attacker
        // substitute the tarball mid-download.
        LinuxDistroCatalog.all.forEach { distro ->
            assertThat(distro.rootfsSha256).matches("[0-9a-f]{64}")
            assertThat(distro.rootfsTarballUrl).startsWith("https://")
            // The URL carries the tarball's own architecture name — Ubuntu Base uses the Ubuntu
            // arch (`arm64`), Alpine uses its own (`aarch64`) — and the entry's `ubuntuArch` is
            // the canonical internal one either way, so the assertion is "the URL names some
            // architecture" rather than "the URL names this exact string".
            assertThat(distro.rootfsTarballUrl).containsMatch("aarch64|arm64|x86_64|amd64|armhf")
        }
    }

    @Test
    fun `every entry carries a plausible tarball size for the storage gates`() {
        // The size estimate feeds the free-space gate and the content-length sanity check; an
        // entry with 0 would silently disable both, and a nonsense value would block honest
        // installs. Ubuntu Base tarballs live in the tens of megabytes; Alpine mini-rootfs are
        // famously small (~3 MB compressed); Chimera ships a *full* rootfs rather than a
        // minirootfs, so it lands near 195 MB compressed — all three are honest numbers from the
        // publisher, and all must pass the gate so a user picking either knows the storage budget
        // upfront. The upper bound accommodates a full rootfs without admitting an obvious typo;
        // Trisquel's cdimage rootfs is a full rootfs (~460 MB compressed, larger than Chimera's),
        // so the bound is widened past it.
        LinuxDistroCatalog.all.forEach { distro ->
            assertThat(distro.rootfsSizeBytes).isAtLeast(3_000_000L)
            assertThat(distro.rootfsSizeBytes).isAtMost(500_000_000L)
        }
    }

    @Test
    fun `the default distro id exists in the catalogue`() {
        // forDevice filters by DEFAULT_DISTRO_ID; an id nothing answers to would make every
        // device unsupported, silently.
        assertThat(LinuxDistroCatalog.all.map { it.id }).contains(LinuxDistroCatalog.DEFAULT_DISTRO_ID)
    }

    @Test
    fun `every entry is a unique id-arch pair`() {
        // Two entries answering to the same id and architecture would make byId's "the" entry a
        // silent first-match, and an installed-state distroId ambiguous.
        val pairs = LinuxDistroCatalog.all.map { it.id to it.ubuntuArch }
        assertThat(pairs.distinct()).isEqualTo(pairs)
        // 5 Ubuntu series (4 LTS + the 25.10 interim) × 3 arches + 3 Alpine series × 3 arches
        // + 1 Chimera series × 1 arch (arm64-only, the only arch verified) + 3 Debian-family
        // series × 1 arch (arm64-only, the only arch the proot-distro plugins and Trisquel's
        // cdimage publish) = 28 entries.
        assertThat(LinuxDistroCatalog.all).hasSize(28)
    }

    @Test
    fun `versionsFor offers every supported series newest first`() {
        // The chooser's contract: the list is the installable versions for THIS device's arch,
        // newest first, so "the latest" is always element zero and nothing has to sort.
        // The Ubuntu family is the default the chooser offers; Alpine is a separate family the
        // install pipeline routes on, so it lives in `all` (so byId can resolve it) but does not
        // crowd the Ubuntu-first chooser. The 25.10 interim sits between the LTS series it is
        // newer and older than — same family, same apt pipeline, shorter support window.
        val versions = LinuxDistroCatalog.versionsFor(listOf("arm64-v8a", "armeabi-v7a"))
        assertThat(versions.map { it.id })
            .containsExactly(
                "ubuntu-26.04",
                "ubuntu-25.10",
                "ubuntu-24.04",
                "ubuntu-22.04",
                "ubuntu-20.04",
            ).inOrder()
        assertThat(versions.all { it.ubuntuArch == "arm64" }).isTrue()
    }

    @Test
    fun `versionsFor is empty where the device maps to no arch`() {
        assertThat(LinuxDistroCatalog.versionsFor(listOf("x86"))).isEmpty()
        assertThat(LinuxDistroCatalog.versionsFor(emptyList())).isEmpty()
    }

    @Test
    fun `byId finds the exact entry and nothing vague`() {
        val distro = LinuxDistroCatalog.byId("ubuntu-24.04", "armhf")
        assertThat(distro).isNotNull()
        assertThat(distro!!.release).isEqualTo("noble")
        assertThat(distro.rootfsTarballUrl).contains("24.04.5")
        // A different arch under the same id is a different entry, not the same one again.
        assertThat(LinuxDistroCatalog.byId("ubuntu-24.04", "amd64")!!.rootfsSha256)
            .isNotEqualTo(distro.rootfsSha256)
        // The 25.10 interim publishes no point-suffixed tarball: the file name is built from the
        // bare version, and the pin is the cdimage SHA256SUMS line (the arm64 value was confirmed
        // against a full download at catalog time).
        val interim = LinuxDistroCatalog.byId("ubuntu-25.10", "arm64")
        assertThat(interim).isNotNull()
        assertThat(interim!!.release).isEqualTo("questing")
        assertThat(interim.rootfsTarballUrl).contains("ubuntu-base-25.10-base-arm64.tar.gz")
        assertThat(interim.rootfsTarballUrl).doesNotContain("25.10.1")
    }

    @Test
    fun `each series carries the codename its apt suites are named after`() {
        // The distribution manager derives every apt suite from `release`; a typo here would make
        // the archive 404 on the first apt update of a fresh install.
        val codenames =
            mapOf(
                "ubuntu-26.04" to "resolute",
                "ubuntu-25.10" to "questing",
                "ubuntu-24.04" to "noble",
                "ubuntu-22.04" to "jammy",
                "ubuntu-20.04" to "focal",
            )
        // Only the Ubuntu family carries an apt codename; Alpine's `release` is the branch
        // (`v3.20`) the apk repositories URL is built from, and is asserted by its own test below.
        LinuxDistroCatalog.all
            .filter { it.family == DistroFamily.UBUNTU }
            .forEach { distro ->
                assertThat(distro.release).isEqualTo(codenames.getValue(distro.id))
            }
    }

    @Test
    fun `alpine entries carry the apk branch and family`() {
        // The Alpine family routes through the apk pipeline, not apt; an entry stamped
        // `family = UBUNTU` would misroute the install. The branch (`v3.19`/`v3.20`/`v3.21`) is
        // what the apk repository URL is built from, the way an Ubuntu codename feeds apt.
        // Each Alpine series publishes three arches (arm64/amd64/armhf), so the catalogue carries
        // 9 Alpine entries — three per id — and the chooser sees them per-arch via
        // alpineVersionsFor rather than as de-duplicated ids. Chimera rides the same apk family
        // (it is apk-tools under the hood), so it shares the family-level assertions below and has
        // its own branch/URL shape pinned in its own block.
        val apkFamily = LinuxDistroCatalog.all.filter { it.family == DistroFamily.ALPINE }
        // 9 Alpine (3 series × 3 arches) + 1 Chimera (arm64-only) = 10 apk-family entries.
        assertThat(apkFamily).hasSize(10)
        assertThat(apkFamily.map { it.id }.distinct())
            .containsExactly("alpine-3.21", "alpine-3.20", "alpine-3.19", "chimera-20261103")
            .inOrder()
        // Every apk-family entry drives the apk pipeline, regardless of which apk distro it is.
        apkFamily.forEach { distro ->
            assertThat(distro.packageManager).isEqualTo(PackageManager.APK)
        }

        // The Alpine-proper subset — its tarball name, branch and URL shape are Alpine-specific,
        // so they are asserted on the Alpine entries alone rather than across the whole family.
        val alpine = apkFamily.filter { it.release.startsWith("v3.") }
        assertThat(alpine).hasSize(9)
        alpine.forEach { distro ->
            assertThat(distro.release).startsWith("v3.")
            // The tarball name is `alpine-minirootfs-<point>-<arch>.tar.gz` — the pin is the
            // exact point release this build was verified against.
            assertThat(distro.rootfsTarballUrl).contains("alpine-minirootfs-")
            assertThat(distro.rootfsTarballUrl).contains(distro.release)
            // Alpine's repos live on dl-cdn, so it does not override the manager's default base.
            assertThat(distro.repoBaseUrl).isNull()
        }

        // Chimera is the apk-family distro with its own repository base: its branches live under
        // repo.chimera-linux.org/current rather than dl-cdn.alpinelinux.org/alpine/<release>, and
        // the catalogue pins the dated full rootfs from the live/<date>/ tree. arm64-only because
        // that is the only architecture the survey verified.
        val chimera = apkFamily.filter { it.id == "chimera-20261103" }
        assertThat(chimera).hasSize(1)
        chimera.forEach { distro ->
            assertThat(distro.ubuntuArch).isEqualTo("arm64")
            assertThat(distro.release).isEqualTo("20261103")
            assertThat(distro.repoBaseUrl).isEqualTo("https://repo.chimera-linux.org/current")
            // The tarball name is `chimera-linux-aarch64-ROOTFS-<date>-full.tar.gz` — the pin is
            // the dated full rootfs snapshot this build was verified against.
            assertThat(distro.rootfsTarballUrl)
                .contains("chimera-linux-aarch64-ROOTFS-20261103-full.tar.gz")
            assertThat(distro.rootfsTarballUrl).startsWith("https://repo.chimera-linux.org/live/")
        }
    }

    @Test
    fun `debian family entries carry the apt codename, repo base url, wrapper dir and family`() {
        // The DEBIAN family routes through the Ubuntu apt pipeline (a DebianDistributionManager
        // subclasses UbuntuDistributionManager), so every entry must carry DistroFamily.DEBIAN +
        // PackageManager.APT, the distro's own archive base (repoBaseUrl, plain http because the
        // guest apt has no CA store yet), and the wrapper directory Termux's proot-distro rebuilds
        // wrap the rootfs in (null for Trisquel's plain rootfs). All three are arm64-only: that is
        // the only architecture the proot-distro plugins publish (Debian, Deepin) and the only one
        // Trisquel's cdimage carries, so a catalogue pin for any other arch would be a lie.
        val debianFamily = LinuxDistroCatalog.all.filter { it.family == DistroFamily.DEBIAN }
        // 3 Debian-family series (Debian 12, Deepin 20, Trisquel 12) × 1 arch (arm64-only) = 3.
        assertThat(debianFamily).hasSize(3)
        assertThat(debianFamily.map { it.id }.distinct())
            .containsExactly("debian-12-bookworm", "deepin-20", "trisquel-12")
            .inOrder()
        // Every Debian-family entry drives the apt pipeline.
        debianFamily.forEach { distro ->
            assertThat(distro.packageManager).isEqualTo(PackageManager.APT)
            assertThat(distro.ubuntuArch).isEqualTo("arm64")
            // http, not https: the guest apt has no CA store until ca-certificates installs from
            // this very first update — the same constraint Ubuntu's primaryArchiveUrl is http for.
            assertThat(distro.repoBaseUrl).startsWith("http://")
        }

        // Debian 12 (bookworm): the proot-distro v4.7.0 plugin pins the v4.6.0 aarch64 tarball
        // (the version mismatch between the plugin tag and the tarball release is normal for
        // proot-distro). The wrapper directory is `debian-aarch64/`; the archive base is
        // deb.debian.org/debian.
        val debian = debianFamily.first { it.id == "debian-12-bookworm" }
        assertThat(debian.displayName).isEqualTo("Debian 12 (bookworm)")
        assertThat(debian.release).isEqualTo("bookworm")
        assertThat(debian.repoBaseUrl).isEqualTo("http://deb.debian.org/debian")
        assertThat(debian.wrapperDir).isEqualTo("debian-aarch64/")
        assertThat(debian.rootfsSizeBytes).isEqualTo(42_942_216L)
        assertThat(debian.rootfsSha256)
            .isEqualTo("68dab31b46af61114014b54876c4f317be648ce8c76c0c6cbb5d6011d420886c")
        assertThat(debian.rootfsTarballUrl)
            .isEqualTo(
                "https://github.com/termux/proot-distro/releases/download/v4.6.0/" +
                    "debian-aarch64-pd-v4.6.0.tar.xz",
            )

        // Deepin 20: the proot-distro v4.6.0 plugin pins the v4.3.1-2 aarch64 tarball. The wrapper
        // directory is `deepin-aarch64/`; the archive base is packages.deepin.com/deepin; the
        // codename is `apricot` (Deepin 20's suite).
        val deepin = debianFamily.first { it.id == "deepin-20" }
        assertThat(deepin.displayName).isEqualTo("Deepin 20")
        assertThat(deepin.release).isEqualTo("apricot")
        assertThat(deepin.repoBaseUrl).isEqualTo("http://packages.deepin.com/deepin")
        assertThat(deepin.wrapperDir).isEqualTo("deepin-aarch64/")
        assertThat(deepin.rootfsSizeBytes).isEqualTo(49_052_588L)
        assertThat(deepin.rootfsSha256)
            .isEqualTo("00136de01343b61e2d8ab47b5439842ff2c32380a657a0892c2a2475d9d01e84")
        assertThat(deepin.rootfsTarballUrl)
            .isEqualTo(
                "https://github.com/termux/proot-distro/releases/download/v4.3.1/" +
                    "deepin-aarch64-pd-v4.3.1-2.tar.xz",
            )

        // Trisquel 12: the official cdimage tarball (no wrapper directory — a plain rootfs like
        // Ubuntu Base), with the codename `nabia` (derived from Ubuntu 20.04 focal). The archive
        // base is archive.trisquel.info/trisquel. bzip2-compressed, not xz, so openTarStream's
        // bzip2 magic detection is what serves it.
        val trisquel = debianFamily.first { it.id == "trisquel-12" }
        assertThat(trisquel.displayName).isEqualTo("Trisquel 12")
        assertThat(trisquel.release).isEqualTo("nabia")
        assertThat(trisquel.repoBaseUrl).isEqualTo("http://archive.trisquel.info/trisquel")
        assertThat(trisquel.wrapperDir).isNull()
        assertThat(trisquel.rootfsSizeBytes).isEqualTo(460_203_734L)
        assertThat(trisquel.rootfsSha256)
            .isEqualTo("a241899069ca300eb54fadb06a1107f9d218add00d5f76387a8121ba1f23bf46")
        assertThat(trisquel.rootfsTarballUrl)
            .isEqualTo(
                "https://cdimage.trisquel.info/trisquel-images/trisquel-base_12.0_arm64.tar.bz2",
            )

        // The wrapper-dir invariant the extractor depends on: Debian and Deepin carry a non-null
        // wrapper (the Termux rebuild wraps the rootfs in a top-level <distro>-<arch>/ directory),
        // and Trisquel carries null (a plain rootfs like Ubuntu Base). A swapped value here would
        // extract a wrapped rootfs into staging/<wrapper>/bin/ rather than staging/bin/, or strip a
        // prefix a plain rootfs does not have.
        assertThat(debian.wrapperDir).isNotNull()
        assertThat(deepin.wrapperDir).isNotNull()
        assertThat(trisquel.wrapperDir).isNull()

        // debianVersionsFor surfaces the family to the chooser for an arm64 device only — the
        // chooser concatenates it alongside the Ubuntu and Alpine lists.
        assertThat(LinuxDistroCatalog.debianVersionsFor(listOf("arm64-v8a")).map { it.id })
            .containsExactly("debian-12-bookworm", "deepin-20", "trisquel-12")
            .inOrder()
        // Non-arm64 devices get nothing: the Debian family is arm64-only.
        assertThat(LinuxDistroCatalog.debianVersionsFor(listOf("x86_64"))).isEmpty()
        assertThat(LinuxDistroCatalog.debianVersionsFor(listOf("x86"))).isEmpty()
    }
}
