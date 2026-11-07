package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The host card's visibility rule and the synthesized profile's promises.
 *
 * The rule is small but it is the feature's contract with the user: a card is a promise that
 * tapping it opens a terminal, so every state that cannot keep that promise — not installed,
 * mid-install, broken — must hide it. And the profile is synthesized, never persisted, so its
 * reserved id and its never-reconnect flag are what keep the SSH machinery from ever trying to
 * dial it.
 */
class LocalLinuxHostTest {
    private val healthy =
        HealthReport(
            shellWorks = true,
            account = "root",
            accountCorrect = true,
            networkUp = true,
            aptUsable = true,
        )

    private val broken = healthy.copy(shellWorks = false)

    @Test
    fun `the card shows exactly when the userspace is installed and healthy`() {
        val installed: List<LinuxUserspaceState> =
            listOf(
                LinuxUserspaceState.Stopped,
                LinuxUserspaceState.Starting,
                LinuxUserspaceState.Running(sinceMs = 0),
            )
        val notInstalled: List<LinuxUserspaceState> =
            listOf(
                LinuxUserspaceState.NotInstalled,
                LinuxUserspaceState.Installing(LinuxInstallStep.Verifying),
                LinuxUserspaceState.Stopping,
                LinuxUserspaceState.NeedsRepair("anything"),
            )

        // The promise kept: every installed state with a passing probe shows the card.
        installed.forEach { state ->
            assertThat(LocalLinuxHost.shouldShowCard(state, healthy)).isTrue()
        }
        // The promise refused: no install in progress, no teardown, no broken environment —
        // a card that vanishes mid-tap or opens into an error is worse than one that arrives late.
        notInstalled.forEach { state ->
            assertThat(LocalLinuxHost.shouldShowCard(state, healthy)).isFalse()
        }
        // And health is required even for an installed state: the probe, not the state file,
        // is what the card trusts.
        installed.forEach { state ->
            assertThat(LocalLinuxHost.shouldShowCard(state, broken)).isFalse()
        }
        // No probe at all cannot be healthy - a null is never trusted as a pass.
        assertThat(LocalLinuxHost.shouldShowCard(LinuxUserspaceState.Stopped, null)).isFalse()
    }

    @Test
    fun `the synthesized profile is recognizable and never dials or reconnects`() {
        val distro = LinuxDistroCatalog.all.first()
        val profile = LocalLinuxHost.hostProfile(distro)

        assertThat(LocalLinuxHost.isLocalHost(profile.id)).isTrue()
        assertThat(LocalLinuxHost.isLocalHost("any-real-host")).isFalse()
        // The card is named after the release actually installed, not a constant: the catalogue
        // offers four LTS series, so a fixed "22.04" told most users they were running a version
        // they had not chosen.
        assertThat(profile.name).isEqualTo(distro.displayName)
        assertThat(LocalLinuxHost.cardSubtitle(profile.name)).startsWith(distro.displayName)
        // The connect path intercepts the reserved id and routes to the local terminal; if that
        // ever regresses, the placeholder must at least fail fast rather than authenticate
        // against localhost with someone's saved password.
        assertThat(profile.username).isEqualTo("ubuntu")
        // The local environment reconnects by construction - scheduling backoff attempts for it
        // would be five timers fighting over one pty that never needed redialing.
        assertThat(profile.autoReconnect).isFalse()
        assertThat(profile.group).isEqualTo(LocalLinuxHost.GROUP)
    }

    @Test
    fun `the notification title names the installed release, not a constant`() {
        // The notification is what the user reads while the app is backgrounded, and the title
        // was the one surface left naming a hard-coded release: "Local Ubuntu 22.04" over a 26.04
        // install, the same lie the card stopped telling. It derives from the distro the graph
        // resolved, so the two surfaces cannot disagree.
        val distro = LinuxDistroCatalog.byId("ubuntu-26.04", "arm64")!!
        assertThat(LocalLinuxHost.notificationTitle(distro)).isEqualTo("Local Ubuntu 26.04 LTS")

        val older = LinuxDistroCatalog.byId("ubuntu-22.04", "amd64")!!
        assertThat(LocalLinuxHost.notificationTitle(older)).isEqualTo("Local Ubuntu 22.04 LTS")
    }
}
