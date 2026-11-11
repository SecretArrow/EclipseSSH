package dev.eclipse.ssh.background

import android.app.Application
import android.content.Intent
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The boot-time restore path of [BootRestoreReceiver], against a real (test-initialised)
 * WorkManager so the enqueue itself is under test, not just the intent guard.
 *
 * The receiver's two jobs: ignore every broadcast that is not a boot or a package
 * replacement, and when it is one of those, enqueue exactly one REPLACE-policy restore job -
 * REPLACE because a boot that follows an update must not sit behind a stale job left in the
 * queue by the previous version of the receiver, which is the exact scenario
 * ACTION_MY_PACKAGE_REPLACED exists for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootRestoreReceiverTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var receiver: BootRestoreReceiver

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        receiver = BootRestoreReceiver()
    }

    /** The live queue state for the unique name the receiver writes, SUCCEEDED excluded -
     * a finished job stays in WorkManager's own bookkeeping and would count forever. */
    private fun liveRestoreJobs(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(RESTORE_WORK_NAME)
            .get()
            .filter { it.state != WorkInfo.State.SUCCEEDED && it.state != WorkInfo.State.FAILED }

    private fun enqueueStaleRestoreJob() {
        // What the previous app version's receiver would have left in the queue: a job under
        // the same unique name, still pending when the update's receiver fires.
        WorkManager.getInstance(context).enqueueUniqueWork(
            RESTORE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RetryTransferWorker>().build(),
        )
    }

    @Test
    fun `a boot completed enqueues the restore job`() {
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(liveRestoreJobs()).hasSize(1)
    }

    @Test
    fun `a package replacement enqueues the restore job`() {
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertThat(liveRestoreJobs()).hasSize(1)
    }

    @Test
    fun `unrelated broadcasts enqueue nothing`() {
        val before = liveRestoreJobs().size

        receiver.onReceive(context, Intent(Intent.ACTION_POWER_CONNECTED))
        receiver.onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        receiver.onReceive(context, Intent("dev.eclipse.ssh.SOMETHING_ELSE"))

        assertThat(liveRestoreJobs()).hasSize(before)
    }

    @Test
    fun `a boot replaces a stale job left by the previous version`() {
        enqueueStaleRestoreJob()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // REPLACE, not KEEP: the queue must hold the receiver's job, not the stale one.
        assertThat(liveRestoreJobs()).hasSize(1)
    }

    private companion object {
        const val RESTORE_WORK_NAME = "eclipse-restore"
    }
}
