package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The apk-family setup pipeline, in the same shape [UbuntuDistributionManagerTest] pins the apt one.
 *
 * Alpine's minirootfs is a different starting point than Ubuntu Base in exactly two ways that make
 * the manager's setup pipeline a different program rather than a renamed one: it ships BusyBox
 * and `apk` already, so there is no bootstrap step and the shell the account is registered with is
 * `/bin/sh` (ash), not `/bin/bash`; and `alpine-baselayout` already ships a `wheel` group, so the
 * account lands in an existing group rather than creating one. These tests pin the
 * file-writing consequences of those facts — what `/etc/passwd`, `/etc/group`, `/etc/shadow`,
 * `/etc/apk/repositories`, `/etc/resolv.conf` and `/etc/sudoers.d` hold after `setup()` runs —
 * against a scripted proot that fakes every fork, so they run on a plain JVM with no real proot,
 * no real `Os.getgid()` and no network.
 *
 * The harness mirrors [UbuntuDistributionManagerTest]'s: a `ProotRuntime` built with
 * `family = DistroFamily.ALPINE` so the session argv is `/bin/sh -l` rather than `/bin/bash --login`,
 * a `ScriptedPtySpawner` that answers the smoke/DNS/apk commands `setup()` runs, and a temp rootfs
 * carrying the alpine-minirootfs-shaped files (only `root` in passwd, `wheel:x:10:root` in group,
 * `/bin/sh`, `bin/apk`). The fixture files are written directly into the temp dir rather than
 * extracted from the tarball [TestTarballs.writeAlpineRootfsFixture] builds — the tarball extraction
 * path is [TarSafetyTest]'s territory, and a setup test should not pay its cost.
 */
class AlpineDistributionManagerTest {
    // ------------------------------------------------------------------ the account files

    @Test
    fun `registerAlpineUser writes the alpine account into passwd, group and shadow`() =
        runTest {
            val harness = Harness()

            harness.distribution.setup()

            val rootfs = harness.runtime.rootfsDir
            // The account line in passwd: uid/gid are the app's own, the shell is /bin/sh (BusyBox
            // ash) rather than /bin/bash — the minirootfs ships no bash until `apk add bash` runs.
            val passwd = File(rootfs, "etc/passwd").readText()
            assertThat(passwd).contains("alpine:x:${APP_UID}:${APP_GID}:Alpine:/home/alpine:/bin/sh")
            assertThat(passwd).doesNotContain("alpine:x:${APP_UID}:${APP_GID}:Alpine:/home/alpine:/bin/bash")
            // The locked-password marker Alpine's baselayout uses (`!`, not Ubuntu's `*`), so the
            // account is locked until `passwd alpine` runs — the session is fake root and never
            // asks for a password, which is what makes the locked state the safe one.
            val shadow = File(rootfs, "etc/shadow").readText()
            assertThat(shadow).contains("alpine:!:")
            // The group line for the account, and the wheel line with `alpine` appended — the
            // minirootfs ships `wheel:x:10:root`, and registerAlpineUser's idempotent append puts
            // the account in it so configureSudo's per-account rule could also be a `%wheel` one.
            val group = File(rootfs, "etc/group").readText()
            assertThat(group).contains("alpine:x:${APP_GID}:")
            val wheelLine = group.lines().first { it.startsWith("wheel:") }
            assertThat(wheelLine).isEqualTo("wheel:x:10:root,alpine")
            // The root line the minirootfs shipped is untouched.
            assertThat(group.lines().first { it.startsWith("root:") }).isEqualTo("root:x:0:")
        }

    @Test
    fun `configureApkRepositories writes main and community for the pinned release`() =
        runTest {
            val harness = Harness()

            harness.distribution.setup()

            // The two lines a pinned Alpine release needs: main and community, both under the
            // branch URL the catalogue's release carries (`v3.21` for alpine-3.21). No `@edge`
            // tag — those are for pulling edge alongside a stable release, which a pinned install
            // does not do.
            val repos = File(harness.runtime.rootfsDir, "etc/apk/repositories").readText()
            assertThat(repos).contains("https://dl-cdn.alpinelinux.org/alpine/v3.21/main")
            assertThat(repos).contains("https://dl-cdn.alpinelinux.org/alpine/v3.21/community")
            // The file is exactly the two lines, no third branch and no trailing content.
            assertThat(repos.lines().filter { it.isNotBlank() })
                .containsExactly(
                    "https://dl-cdn.alpinelinux.org/alpine/v3.21/main",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.21/community",
                ).inOrder()
        }

    @Test
    fun `isConfigured is false before setup and true after`() =
        runTest {
            val harness = Harness()

            // A fresh minirootfs has the account files but no `alpine:` line in passwd and the
            // empty repositories file the fixture shipped: isConfigured must read this as not done,
            // not as an exception — the state machine asks it from its own constructor.
            assertThat(harness.distribution.isConfigured()).isFalse()

            harness.distribution.setup()

            // The marker isConfigured looks for in /etc/apk/repositories, the alpine account line in
            // passwd and the workspace directory under /home/alpine: all three are setup's work.
            assertThat(harness.distribution.isConfigured()).isTrue()
        }

    @Test
    fun `configureDns writes the provided resolvers into resolv conf`() =
        runTest {
            // The defaults the manager's constructor wires when no resolver list is supplied: the
            // same anycast pair Ubuntu's manager uses (1.1.1.1 and 8.8.8.8), because musl's
            // resolver reads resolv.conf the same way glibc's does.
            val harness = Harness()

            harness.distribution.setup()

            val resolv = File(harness.runtime.rootfsDir, "etc/resolv.conf").readText()
            assertThat(resolv).contains("nameserver 1.1.1.1")
            assertThat(resolv).contains("nameserver 8.8.8.8")
            // The options line mitigates musl's known slow-server failure; its presence is what
            // makes this a configureDns write rather than a bare nameserver dump.
            assertThat(resolv).contains("options timeout:2 attempts:3")
        }

    @Test
    fun `clearLastGoodMirror is a no-op that does not throw and writes nothing`() =
        runTest {
            val harness = Harness()

            // apk keeps no apt-style last-known-good mirror memo beside the rootfs, so the honest
            // implementation is the one that does nothing. The harness runs setup first so the
            // rootfs is in a configured state; clearLastGoodMirror must not undo any of it and must
            // not create a last-good-mirror sidecar the way the Ubuntu manager's would delete one.
            harness.distribution.setup()
            val groupBefore = File(harness.runtime.rootfsDir, "etc/group").readText()
            val reposBefore = File(harness.runtime.rootfsDir, "etc/apk/repositories").readText()

            // The contract: never throws, and the uninstall path that calls it can rely on that.
            harness.distribution.clearLastGoodMirror()

            // Nothing on disk changed, and no last-good-mirror file appeared beside the rootfs.
            assertThat(File(harness.runtime.rootfsDir, "etc/group").readText()).isEqualTo(groupBefore)
            assertThat(File(harness.runtime.rootfsDir, "etc/apk/repositories").readText()).isEqualTo(reposBefore)
            assertThat(File(harness.rootDir, "last-good-mirror").exists()).isFalse()
            // The diagnostic ring records that the step ran and named why it did nothing — silence
            // here would read as "the uninstall forgot a step", not as "there was nothing to forget".
            assertThat(harness.distribution.diagnostics.export()).contains("apk keeps no mirror memo")
        }

    @Test
    fun `nameSupplementaryGroups writes the Android group ids into etc group`() =
        runTest {
            // The group set the Ubuntu test reports on: 3003 inet, 9997 everybody. A shell in the
            // rootfs is in both — proot passes them through — and a stock minirootfs group file
            // names neither, which is what `groups` complains about, once per ID. setup runs
            // nameSupplementaryGroups as a step, so the lines must land without a separate call.
            val harness = Harness(supplementaryGids = { intArrayOf(3003, 9997) })

            harness.distribution.setup()

            val group = File(harness.runtime.rootfsDir, "etc/group")
            // The platform's own names for the two IDs the app is in, prefixed with `android_` so
            // the lines the app writes are distinguishable from the ones the rootfs shipped.
            assertThat(group.readLines()).containsAtLeast(
                "android_inet:x:3003:",
                "android_everybody:x:9997:",
            )
            // The lines the minirootfs shipped — root and wheel — are still what they were, modulo
            // wheel's appended `alpine` member from registerAlpineUser.
            assertThat(group.readLines().first { it.startsWith("root:") }).isEqualTo("root:x:0:")
            assertThat(group.readLines().first { it.startsWith("wheel:") }).isEqualTo("wheel:x:10:root,alpine")
        }

    @Test
    fun `setup throws DnsUnresolved when getent cannot resolve the archive host`() =
        runTest {
            val harness = Harness()
            // getent's non-zero exit is the shape of a dead resolver from inside proot: the command
            // answered, but with "name not found" rather than an address. setup runs this check
            // before any apk command, so a dead resolver is named as DNS — not discovered ten
            // minutes later by an exhausted apk fetch.
            harness.scripted.respond = { command ->
                if (command.startsWith("getent hosts ")) {
                    2 to ""
                } else {
                    baseline(command)
                }
            }

            val thrown = runCatching { harness.distribution.setup() }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(UserspaceFailure.DnsUnresolved::class.java)
            // The typed verdict carries the resolvers that were actually written — the defaults,
            // because the harness did not override them — so the failure message can name them.
            assertThat((thrown as UserspaceFailure.DnsUnresolved).servers)
                .containsExactly("1.1.1.1", "8.8.8.8")
                .inOrder()
            // No apk command ran: the DNS check is what stood between configureDns and apkUpdate,
            // and a failure here aborts before the index is fetched.
            assertThat(harness.scripted.commands.none { it.startsWith("apk") }).isTrue()
        }

    // ------------------------------------------------------------------ the harness

    /**
     * A scripted [AlpineDistributionManager] against a temp Alpine-minirootfs-shaped rootfs.
     *
     * Mirrors [UbuntuDistributionManagerTest]'s harness one field at a time: a temp [rootDir] whose
     * `rootfs/` carries the alpine-baselayout account files (only `root` in passwd, `wheel:x:10:root`
     * in group, `/bin/sh` and `bin/apk` present), a [ProotRuntime] built with `family = ALPINE` so
     * the session argv is `/bin/sh -l` rather than `/bin/bash --login`, and a [ScriptedPtySpawner]
     * whose default [baseline] answers the smoke and DNS commands every setup runs.
     *
     * Inner because its scripted proot's default responses come from the outer class's [baseline].
     */
    private inner class Harness(
        /**
         * The Alpine distro to install. Defaults to the catalogue's alpine-3.21/arm64 entry, the
         * same way the Ubuntu harness defaults to jammy/arm64 — the catalogue is the source of truth
         * for what the app can install, and a test that hand-builds a distro would be testing a
         * distro the app does not ship.
         */
        distro: LinuxDistro =
            LinuxDistroCatalog.all.first {
                it.id == "alpine-3.21" && it.ubuntuArch == "arm64"
            },
        scripted: ScriptedPtySpawner = ScriptedPtySpawner(),
        dnsServers: () -> List<String> = { UbuntuDistributionManager.DEFAULT_DNS_SERVERS },
        networkOnline: () -> Boolean = { true },
        freeBytes: () -> Long = { 0L },
        supplementaryGids: () -> IntArray = { IntArray(0) },
    ) {
        /** The scripted fake every spawn lands in; tests override [respond] to break things. */
        val scripted: ScriptedPtySpawner = scripted.apply { respond = { baseline(it) } }
        val rootDir = Files.createTempDirectory("alpine-distribution").toFile().apply { deleteOnExit() }
        val runtime =
            ProotRuntime(
                rootDir,
                fakeNativeLibraryDir(),
                scripted,
                storage = RuntimeStorageManager(rootDir, freeBytesProbe = { freeBytes() }),
                // The family the runtime's session argv and home directory are built around. ALPINE
                // names /bin/sh and /home/alpine; without it the runtime defaults to Ubuntu's
                // /bin/bash and /home/ubuntu, and the Alpine account the manager registers would
                // never match the session the manager then probes.
                family = DistroFamily.ALPINE,
            )
        val distribution =
            AlpineDistributionManager(
                distro,
                runtime,
                appUid = APP_UID,
                appGid = APP_GID,
                supplementaryGids = supplementaryGids,
                dnsServers = dnsServers,
                networkOnline = networkOnline,
                // No installer wired: the host-side fields healthProbe asks the installer for
                // (unusable temp, unusable locks) read as empty, the same honest null the Ubuntu
                // harness reports when no tarball is pinned. The apk repair prologue does not exist,
                // so there is no `ESSENTIAL_PROGRAMS` fixture to write either.
                installer = null,
            )

        init {
            val rootfs = File(rootDir, "rootfs")
            // The alpine-baselayout account files the minirootfs ships and setup rewrites. Only
            // root in passwd, root and wheel:x:10:root in group, root in shadow — the starting
            // point registerAlpineUser's rewriteKeepingOthers preserves while adding the alpine line.
            File(rootfs, "etc/apk").mkdirs()
            File(rootfs, "etc/profile.d").mkdirs()
            File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
            File(rootfs, "etc/group").writeText("root:x:0:\nwheel:x:10:root\n")
            File(rootfs, "etc/shadow").writeText("root:!:19850:0:99999:7:::\n")
            // The minirootfs ships /bin/sh (BusyBox ash) and bin/apk; setup never creates them but
            // isConfigured's later checks and the session probe assume they exist. Empty files are
            // enough — the scripted spawner fakes every fork, so the bytes are never exec'd.
            File(rootfs, "bin").mkdirs()
            File(rootfs, "bin/sh").writeText("")
            File(rootfs, "bin/apk").writeText("")
            // The empty repositories file the minirootfs ships and configureApkRepositories
            // overwrites. isConfigured's pre-setup reading depends on this file existing but
            // carrying no marker, which is the shape a fresh extract leaves.
            File(rootfs, "etc/apk/repositories").writeText("")
            // /etc/profile is what makes a `-l` shell set PATH; /etc/hostname and /etc/hosts are
            // the baselayout files a real minirootfs ships and the manager leaves alone.
            File(rootfs, "etc/profile").writeText("# alpine-baselayout profile\n")
            File(rootfs, "etc/hostname").writeText("localhost\n")
            File(rootfs, "etc/hosts").writeText("127.0.0.1\tlocalhost\n")
        }
    }

    /**
     * How the scripted proot answers the commands every setup runs before its apk steps: the
     * runtime smoke echo must answer its own marker (a silent echo is the failure under test
     * elsewhere), and the in-proot resolution check must name the host it was asked for. apk update
     * and apk add answer exit 0 with minimal output — the install's own verdict is the file-writing
     * the test asserts on, not the package manager's real words.
     */
    private fun baseline(command: String): Pair<Int, String> =
        when {
            command == "echo eclipse-runtime-ok" -> 0 to "eclipse-runtime-ok\n"
            command.startsWith("getent hosts ") -> 0 to "1.2.3.4 ${command.removePrefix("getent hosts ")}\n"
            command.startsWith("apk update") -> 0 to "OK: 0 MiB in 0 packages\n"
            command.startsWith("apk add ") -> 0 to "OK: 0 MiB in 0 packages\n"
            else -> 0 to ""
        }

    private companion object {
        /** The app uid/gid the harness registers the alpine account with. Arbitrary but realistic. */
        private const val APP_UID = 10150
        private const val APP_GID = 10150
    }
}
