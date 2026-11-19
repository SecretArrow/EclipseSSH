package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Debian family's apt setup pipeline, in the same shape [AlpineDistributionManagerTest] pins the
 * apk one and [UbuntuDistributionManagerTest] pins the apt one.
 *
 * Debian, Deepin and Trisquel reuse the Ubuntu apt pipeline ([DebianDistributionManager] subclasses
 * [UbuntuDistributionManager]) but carry their own archive, codename and — for Debian/Deepin — a
 * different sources.list shape (Debian's `-security` suite lives at a separate base; Deepin ships a
 * single rolling suite). These tests pin the file-writing consequences of those facts — what
 * `/etc/apt/sources.list` holds after `setup()` runs, and that the Ubuntu mirror-list fetch is
 * skipped — against a scripted proot that fakes every fork, so they run on a plain JVM with no real
 * proot, no real `Os.getgid()` and no network.
 *
 * The harness mirrors [UbuntuDistributionManagerTest]'s: a `ProotRuntime` built with
 * `family = DistroFamily.DEBIAN` so the session argv is `/bin/bash --login` and the home is
 * `/home/ubuntu` (the internal user the Debian manager registers, the same as Ubuntu), a
 * [ScriptedPtySpawner] that answers the smoke/DNS/apt commands `setup()` runs, and a temp rootfs
 * carrying the Ubuntu-Base-shaped account files plus the essential programs the repair prologue
 * checks for. The mirror-list URL points at a localhost port nothing listens on, so if the skip
 * ever regressed the fetch would fail instantly rather than touching the network from a unit test.
 */
class DebianDistributionManagerTest {
    // ------------------------------------------------------------------ sources.list

    @Test
    fun `configureAptSources writes deb debian org and bookworm suites for debian`() =
        runTest {
            val harness = Harness(debianDistro())

            harness.distribution.setup()

            // The sources.list a Debian 12 install needs: the marker isConfigured looks for, the
            // bookworm / bookworm-updates suites at deb.debian.org/debian, and the bookworm-security
            // suite at deb.debian.org/debian-security (Debian's security suite lives at a separate
            // base — the one place Debian's sources.list shape differs from Ubuntu's same-base one).
            val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
            assertThat(sources).contains("# managed by Eclipse SSH")
            assertThat(sources).contains("deb.debian.org/debian")
            assertThat(sources).contains("deb http://deb.debian.org/debian bookworm main\n")
            assertThat(sources).contains("deb http://deb.debian.org/debian bookworm-updates main\n")
            assertThat(sources).contains("deb http://deb.debian.org/debian-security bookworm-security main\n")
            // No Ubuntu archive reference leaks in: the Debian family must not fall back to
            // archive.ubuntu.com or ports.ubuntu.com the way an Ubuntu entry would.
            assertThat(sources).doesNotContain("archive.ubuntu.com")
            assertThat(sources).doesNotContain("ports.ubuntu.com")
        }

    @Test
    fun `configureAptSources writes the single apricot suite for deepin`() =
        runTest {
            val harness =
                Harness(
                    LinuxDistroCatalog.all.first { it.id == "deepin-20" && it.ubuntuArch == "arm64" },
                )

            harness.distribution.setup()

            // Deepin 20 ships a single rolling suite (`apricot`), not the release/-updates/-security
            // triple Ubuntu and Debian carry. The component set is main/contrib/non-free — Deepin's
            // published components — and the base is packages.deepin.com/deepin.
            val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
            assertThat(sources).contains("deb http://packages.deepin.com/deepin apricot main contrib non-free\n")
            // No -updates or -security suites: Deepin does not publish them.
            assertThat(sources).doesNotContain("apricot-updates")
            assertThat(sources).doesNotContain("apricot-security")
            assertThat(sources).doesNotContain("archive.ubuntu.com")
        }

    @Test
    fun `configureAptSources writes the nabia suites for trisquel`() =
        runTest {
            val harness =
                Harness(
                    LinuxDistroCatalog.all.first { it.id == "trisquel-12" && it.ubuntuArch == "arm64" },
                )

            harness.distribution.setup()

            // Trisquel 12 mirrors Ubuntu's archive structure: nabia, nabia-updates, nabia-security,
            // all from the same archive.trisquel.info/trisquel base (unlike Debian, Trisquel serves
            // security from the same base). Components: `main` (Trisquel ships only free software).
            val sources = File(harness.runtime.rootfsDir, "etc/apt/sources.list").readText()
            assertThat(sources).contains("deb http://archive.trisquel.info/trisquel nabia main\n")
            assertThat(sources).contains("deb http://archive.trisquel.info/trisquel nabia-updates main\n")
            assertThat(sources).contains("deb http://archive.trisquel.info/trisquel nabia-security main\n")
            assertThat(sources).doesNotContain("archive.ubuntu.com")
        }

    @Test
    fun `the mirror list fetch is skipped so no mirrors ubuntu com reference reaches the ladder`() =
        runTest {
            // The Debian family skips the Ubuntu mirror-list feed (mirrors.ubuntu.com lists Ubuntu
            // mirrors only and is meaningless for a Debian-family archive). The harness points the
            // feed URL at a localhost port nothing listens on, so a regression that fetched it would
            // dial that port — and the diagnostic ring would carry the fetch. To exercise the skip on
            // the failure path (where the ladder would otherwise reach for the feed), the primary
            // archive rung is wedged to fail; the ladder then descends straight past the feed to the
            // empty built-in list and exhausts, never dialling the localhost feed.
            val harness = Harness(debianDistro())
            harness.scripted.respond = { command ->
                when {
                    command == "echo eclipse-runtime-ok" -> 0 to "eclipse-runtime-ok\n"
                    command.startsWith("getent hosts ") -> 0 to "1.2.3.4 ${command.removePrefix("getent hosts ")}\n"
                    // Every apt-get update rung fails: the ladder descends, and the skip is what
                    // stops it from fetching the Ubuntu mirror feed on the way down.
                    command.startsWith("apt-get update") -> 100 to "Err:1 http://deb.debian.org/debian bookworm Release\n"
                    else -> 0 to ""
                }
            }

            // The ladder exhausts and throws — the primary and its IPv4-forced twin both failed,
            // and the built-in fallback list is empty for the Debian family.
            val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()
            assertThat(thrown).isNotNull()

            val log = harness.distribution.diagnostics.export()
            // The skip held: no mirror-feed record, no Ubuntu feed host, no dial of the dead
            // localhost port the feed URL points at.
            assertThat(log).doesNotContain("mirror feed")
            assertThat(log).doesNotContain("mirrors.ubuntu.com")
            assertThat(log).doesNotContain("127.0.0.1")
        }

    @Test
    fun `isConfigured is false before setup and true after`() =
        runTest {
            val harness = Harness(debianDistro())

            // A fresh rootfs has the account files but no sources.list carrying our marker and no
            // workspace: isConfigured must read this as not done, not as an exception — the state
            // machine asks it from its own constructor.
            assertThat(harness.distribution.isConfigured()).isFalse()

            harness.distribution.setup()

            // The marker isConfigured looks for in /etc/apt/sources.list, the ubuntu account line
            // in passwd (the Debian family registers the same internal `ubuntu` user the parent
            // does) and the workspace directory under /home/ubuntu: all three are setup's work.
            assertThat(harness.distribution.isConfigured()).isTrue()
        }

    // ------------------------------------------------------------------ the harness

    /**
     * A scripted [DebianDistributionManager] against a temp Ubuntu-Base-shaped rootfs.
     *
     * Mirrors [UbuntuDistributionManagerTest]'s harness one field at a time: a temp [rootDir]
     * whose `rootfs/` carries the account files and essential programs the repair prologue checks
     * for, a [ProotRuntime] built with `family = DEBIAN` so the session argv is `/bin/bash --login`
     * and the home is `/home/ubuntu` (matching the `ubuntu` user the manager registers), and a
     * [ScriptedPtySpawner] whose default [baseline] answers the smoke and DNS commands every setup
     * runs. The mirror-list URL points at a dead localhost port so a skip regression would fail
     * loudly rather than touching the network.
     */
    private inner class Harness(
        distro: LinuxDistro,
        scripted: ScriptedPtySpawner = ScriptedPtySpawner(),
        dnsServers: () -> List<String> = { UbuntuDistributionManager.DEFAULT_DNS_SERVERS },
        networkOnline: () -> Boolean = { true },
        freeBytes: () -> Long = { 0L },
        supplementaryGids: () -> IntArray = { IntArray(0) },
    ) {
        /** The scripted fake every spawn lands in; tests override [respond] to break things. */
        val scripted: ScriptedPtySpawner = scripted.apply { respond = { baseline(it) } }
        val rootDir = Files.createTempDirectory("debian-distribution").toFile().apply { deleteOnExit() }
        val runtime =
            ProotRuntime(
                rootDir,
                fakeNativeLibraryDir(),
                scripted,
                storage = RuntimeStorageManager(rootDir, freeBytesProbe = { freeBytes() }),
                // The DEBIAN family routes to the same /bin/bash + /home/ubuntu as UBUNTU, so the
                // session argv and home match the ubuntu user the registerUbuntuUser step writes.
                family = DistroFamily.DEBIAN,
            )
        val distribution: DistributionManager =
            DebianDistributionManager(
                distro,
                runtime,
                appUid = APP_UID,
                appGid = APP_GID,
                supplementaryGids = supplementaryGids,
                dnsServers = dnsServers,
                networkOnline = networkOnline,
                // A dead localhost port: the skip means this is never fetched, but a regression
                // would fail the rung here rather than touching mirrors.ubuntu.com from a unit test.
                mirrorListUrl = "http://127.0.0.1:1/mirrors.txt",
                installer = null,
            )

        init {
            val rootfs = File(rootDir, "rootfs")
            // The account files setup rewrites and the apt directory sources.list is written to.
            File(rootfs, "etc/apt").mkdirs()
            File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
            File(rootfs, "etc/group").writeText("root:x:0:\n")
            File(rootfs, "etc/shadow").writeText("root:*:19850:0:99999:7:::\n")
            // The programs the repair prologue checks for before it asks dpkg anything. A real
            // rootfs has them because a real install unpacked them; this one has to say so, or the
            // check would raise its "missing" warning on every test in this file.
            UbuntuDistributionManager.ESSENTIAL_PROGRAMS.forEach { guest ->
                File(rootfs, guest.removePrefix("/")).apply { parentFile?.mkdirs() }.writeText("")
            }
        }
    }

    /**
     * How the scripted proot answers the commands every setup runs before its apt steps: the
     * runtime smoke echo must answer its own marker, and the in-proot resolution check must name
     * the host it was asked for — Debian's archive host (deb.debian.org) rather than Ubuntu's.
     */
    private fun baseline(command: String): Pair<Int, String> =
        when {
            command == "echo eclipse-runtime-ok" -> 0 to "eclipse-runtime-ok\n"
            command.startsWith("getent hosts ") -> 0 to "1.2.3.4 ${command.removePrefix("getent hosts ")}\n"
            else -> 0 to ""
        }

    /** A Debian 12 (bookworm) catalogue entry, the family's representative distro for these tests. */
    private fun debianDistro(): LinuxDistro = LinuxDistroCatalog.all.first { it.id == "debian-12-bookworm" && it.ubuntuArch == "arm64" }

    private companion object {
        /** The app uid/gid the harness registers the ubuntu account with. Arbitrary but realistic. */
        private const val APP_UID = 10150
        private const val APP_GID = 10150
    }
}
