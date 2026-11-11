package dev.eclipse.ssh.background

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.TransferRepository
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.security.SecureVault
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The restore decisions of [RetryTransferWorker.doWork] — the real method, constructed the
 * way the Hilt factory would construct it, with only the repository faked over its real
 * interface.
 *
 * What is pinned:
 *  1. a scheduled transfer whose host is NOT in the registry returns retry — the queue must
 *     wait for the user to reconnect, not fail the row on a device that has not reconnected
 *     yet;
 *  2. a scheduled transfer whose host IS in the registry returns success — the row is live
 *     and the restore pass has nothing left to hold the queue for;
 *  3. the five-attempt ceiling: a repository that keeps throwing makes attempt 5 the last
 *     retry, after which the worker returns failure so the queue drops the job instead of
 *     retrying forever. Attempt 4 still retries.
 *
 * SessionRegistry is the real one on the stand-in keystore — register() writes a real
 * DataStore, so "active" means what it means on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetryTransferWorkerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var registry: SessionRegistry
    private lateinit var repository: FakeTransferRepository

    @Before
    fun setUp() {
        StandInAndroidKeyStore.install()
        registry = SessionRegistry(context, SecureVault())
        repository = FakeTransferRepository()
    }

    private fun scheduledTransfer(id: String, hostId: String): TransferItem =
        TransferItem(
            id = id,
            name = "backup.tar",
            direction = TransferDirection.UPLOAD,
            hostName = "host-a",
            progress = 0f,
            status = TransferStatus.QUEUED,
            sizeLabel = "1 MB",
            hostId = hostId,
            scheduledAt = 1234L,
        )

    private suspend fun withAttempt(
        attempt: Int,
        data: Data = Data.EMPTY,
        block: suspend (RetryTransferWorker) -> Unit,
    ) {
        val params = TestListenableWorkerBuilder<RetryTransferWorker>(context)
            .setInputData(data)
            .setRunAttemptCount(attempt)
            .build()
            .parameters
        block(RetryTransferWorker(context, params, registry, repository))
    }

    @Test
    fun `a scheduled transfer whose host is not active asks to retry`() = runTest {
        repository.emit(listOf(scheduledTransfer(id = "t1", hostId = "host-a")))

        withAttempt(0, workDataOf(TransferScheduler.KEY_TRANSFER_ID to "t1")) { w ->

            val result = w.doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        }
    }

    @Test
    fun `a scheduled transfer whose host is active succeeds`() = runTest {
        repository.emit(listOf(scheduledTransfer(id = "t1", hostId = "host-a")))
        registry.register("host-a")

        withAttempt(0, workDataOf(TransferScheduler.KEY_TRANSFER_ID to "t1")) { w ->

            val result = w.doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.success())
        }
    }

    @Test
    fun `the fifth failure gives up instead of retrying`() = runTest {
        repository.failAlways()

        withAttempt(5) { w ->

            val result = w.doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.failure())
        }
    }

    @Test
    fun `before the fifth failure the worker still retries`() = runTest {
        repository.failAlways()

        withAttempt(4) { w ->

            val result = w.doWork()

            assertThat(result).isEqualTo(ListenableWorker.Result.retry())
        }
    }
}

/** A fake over the real interface: emits a list on demand, or throws on every mutation. */
private class FakeTransferRepository : TransferRepository {
    private val rows = MutableStateFlow<List<TransferItem>>(emptyList())
    private var failing = false

    override val transfers: Flow<List<TransferItem>> = rows

    fun emit(items: List<TransferItem>) {
        rows.value = items
    }

    fun failAlways() {
        failing = true
    }

    override suspend fun save(item: TransferItem) {
        if (failing) throw IllegalStateException("repository unavailable")
    }

    override suspend fun seedIfEmpty() {
        if (failing) throw IllegalStateException("repository unavailable")
    }

    override suspend fun clearCompleted() {}

    override suspend fun delete(id: String) {}
}
