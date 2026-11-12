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
        // famously small (~3 MB compressed) — both are honest numbers from the publisher, and
        // both must pass the gate so a user picking either knows the storage budget upfront.
        LinuxDistroCatalog.all.forEach { distro ->
            assertThat(distro.rootfsSizeBytes).isAtLeast(3_000_000L)
            assertThat(distro.rootfsSizeBytes).isAtMost(100_000_000L)
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
        // 4 Ubuntu LTS series × 3 arches + 3 Alpine series × 3 arches = 21 entries.
        assertThat(LinuxDistroCatalog.all).hasSize(21)
    }

    @Test
    fun `versionsFor offers every LTS newest first`() {
        // The chooser's contract: the list is the installable versions for THIS device's arch,
        // newest LTS first, so "the latest" is always element zero and nothing has to sort.
        // The Ubuntu family is the default the chooser offers; Alpine is a separate family the
        // install pipeline routes on, so it lives in `all` (so byId can resolve it) but does not
        // crowd the Ubuntu-first chooser.
        val versions = LinuxDistroCatalog.versionsFor(listOf("arm64-v8a", "armeabi-v7a"))
        assertThat(versions.map { it.id })
            .containsExactly(
                "ubuntu-26.04",
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
        assertThat(LinuxDistroCatalog.byId("ubuntu-25.10", "arm64")).isNull()
    }

    @Test
    fun `each series carries the codename its apt suites are named after`() {
        // The distribution manager derives every apt suite from `release`; a typo here would make
        // the archive 404 on the first apt update of a fresh install.
        val codenames =
            mapOf(
                "ubuntu-26.04" to "resolute",
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
        // alpineVersionsFor rather than as de-duplicated ids.
        val alpine = LinuxDistroCatalog.all.filter { it.family == DistroFamily.ALPINE }
        assertThat(alpine).hasSize(9)
        assertThat(alpine.map { it.id }.distinct())
            .containsExactly("alpine-3.21", "alpine-3.20", "alpine-3.19")
            .inOrder()
        alpine.forEach { distro ->
            assertThat(distro.packageManager).isEqualTo(PackageManager.APK)
            assertThat(distro.release).startsWith("v3.")
            // The tarball name is `alpine-minirootfs-<point>-<arch>.tar.gz` — the pin is the
            // exact point release this build was verified against.
            assertThat(distro.rootfsTarballUrl).contains("alpine-minirootfs-")
            assertThat(distro.rootfsTarballUrl).contains(distro.release)
        }
    }
}
