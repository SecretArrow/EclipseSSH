package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.ui.settings.ExportProfilesActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Export profiles, in its own window: the multi-profile door beside the vault export.
 *
 * A separate class from the Settings suite because the subject is what the window shows rather than
 * the row that opens it.
 *
 * **Launching this activity is itself the assertion that it is declared in the manifest**, and that is
 * why the class exists at all rather than the screen being covered from the row that opens it. Nothing
 * in the build fails when an activity class has no `<activity>` entry: the module compiles, lint is
 * silent, the APK is built and signed, and the only symptom is `ActivityNotFoundException` the first
 * time a user taps the button. This one shipped without its manifest line and was caught by reading
 * the file, not by a build — a JVM test that resolves the class against the release manifest is the
 * only gate in this repository that would have failed on it, so a settings destination without one of
 * these classes is a destination nothing checks.
 *
 * The whole of what this screen does past its opening frame runs through a Storage Access Framework
 * create-document picker, which is another app's activity and is not reachable from a JVM test. So
 * nothing here touches the export: the picker is never launched, no passphrase is ever typed, no
 * profile is ever ticked and no document is ever chosen. What is asserted is the armed-but-not-fired
 * state the window opens in.
 *
 * The clipboard is not touched either, for the reason [ExportBackupActivityRobolectricTest] gives: the
 * paste affordance is asserted to *exist* and to be named for a screen reader, and tapping it would be
 * asserting the audited [dev.eclipse.ssh.security.SecureClipboard] boundary rather than this screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ExportProfilesActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<ExportProfilesActivity>()

    /**
     * The bar names the screen, with the string the Activity itself carries.
     *
     * One node: this screen has no row repeating its title - the first card's header is "PROFILES",
     * and the second's is the title uppercased, which is a different string from this one.
     */
    @Test
    fun theBarNamesTheScreen() {
        compose.waitForIdle()

        compose.onNodeWithText("Export profiles").assertIsDisplayed()
    }

    /** `SettingsSection` renders `title.uppercase()`, so the header is asserted in that form. */
    @Test
    fun theCardHeaderNamesTheSection() {
        compose.waitForIdle()

        compose.onNode(hasText("PROFILES")).assertIsDisplayed()
    }

    /**
     * A device with no profiles is told why the list is empty, in terms of what a profile is.
     *
     * This is the state the screen opens in on a fresh install - and, since the picker cannot be
     * reached from here, the only state any of these tests sees. An empty card with no sentence under
     * it would read as a list that failed to load rather than one with nothing in it yet.
     */
    @Test
    fun theScreenSaysWhyThereIsNothingToExport() {
        compose.waitForIdle()

        assertWithMessage("an empty profile list is not explained")
            .that(
                compose.onAllNodesWithText("There is nothing to export yet", substring = true)
                    .fetchSemanticsNodes(),
            ).isNotEmpty()
    }

    /**
     * The window states what travels in the file, which is the thing a user is being asked to consent
     * to by choosing a passphrase.
     */
    @Test
    fun theScreenSaysWhatTheFileCarries() {
        compose.waitForIdle()

        compose.onNodeWithText(
            "Passwords, private keys and passphrases travel with the profiles you pick",
        ).assertIsDisplayed()
    }

    /**
     * The passphrase field is on screen, and it is a field that takes text.
     *
     * Found by its input action rather than by its label, for the reason the vault export's test gives:
     * a `TextField`'s label is drawn inside its decoration box and is not guaranteed to be an
     * independent node in the merged semantics tree.
     */
    @Test
    fun thePassphraseFieldIsOnScreen() {
        compose.waitForIdle()

        assertWithMessage("the window offers nowhere to type a passphrase")
            .that(compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()).isNotEmpty()
    }

    /** The paste affordance is offered, and named for a screen reader. */
    @Test
    fun thePasteAffordanceIsOffered() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Paste passphrase from clipboard").assertIsDisplayed()
    }

    /**
     * The action refuses while there is nothing to export.
     *
     * This is the half of the rule that the window can be asked about without a profile on the device:
     * the button is gated on `chosen.isNotEmpty() && passphrase.isNotBlank()`, and with an empty list
     * the first clause is what holds it shut. It is the clause worth pinning here because the failure
     * it prevents is a file holding nothing, written to a location the user picked and then handed to
     * their other phone.
     *
     * "Export" is unique in this window - the bar's longer title is a different string, and the row
     * that would say "Export 2 profile(s)" is not here - so the label needs no disambiguation.
     */
    @Test
    fun theActionRefusesWhileThereIsNothingToExport() {
        compose.waitForIdle()

        compose.onNodeWithText("Export").assertIsDisplayed().assertIsNotEnabled()
    }

    /**
     * The body is laid out in a window that carries the destination's own chrome.
     *
     * What a regression to a dialog would take away: the shell's own bar with its navigation icon, and
     * body content laid out inside the window rather than clipped beneath a fold.
     */
    @Test
    fun theBodyIsLaidOutInAWindowWithItsOwnChrome() {
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNode(hasText("EXPORT PROFILES")).assertIsDisplayed()
    }
}
