package dev.eclipse.ssh.background

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The restore decisions of [RetryTransferWorker.doWork] — the real method, built by
 * TestListenableWorkerBuilder through a factory that supplies the collaborators the Hilt
 * factory would, so the WorkerParameters (inputData, runAttemptCount) are the ones
 * WorkManager itself provides.
 *
 * What is pinned:
 *  1. a scheduled transfer whose host is NOT in the registry returns retry — the queue must
 *     wait for the user to reconnect, not fail the row on a device that has not reconnected
 *     yet;
 *  2. a scheduled transfer whose host IS in the registry returns success — the row is live
 *     and the restore pass has nothing left to hold the queue for;
 *  3. the five-attempt ceiling: a repository whose flow keeps throwing makes attempt 5 the
 *     last retry, after which the worker returns failure so the queue drops the job instead
 *     of retrying forever. Attempt 4 still retries.
 *
 * SessionRegistry is the real one on the stand-in keystore — register() writes a real
 * DataStore, so "active" means what it means on a device. runBlocking rather than runTest
 * for the same reason [SessionRegistryRobolectricTest] uses it: DataStore does its own
 * dispatching, and virtual time buys nothing there.
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

    private fun worker(attempt: Int, data: androidx.work.Data = androidx.work.Data.EMPTY): RetryTransferWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = RetryTransferWorker(appContext, workerParameters, registry, repository)
            }
        return TestListenableWorkerBuilder<RetryTransferWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(data)
            .setRunAttemptCount(attempt)
            .build() as RetryTransferWorker
    }

    @Test
    fun `a scheduled transfer whose host is not active asks to retry`() = runBlocking {
        repository.emit(listOf(scheduledTransfer(id = "t1", hostId = "host-a")))

        val result = worker(0, workDataOf(TransferScheduler.KEY_TRANSFER_ID to "t1")).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `a scheduled transfer whose host is active succeeds`() = runBlocking {
        repository.emit(listOf(scheduledTransfer(id = "t1", hostId = "host-a")))
        registry.register("host-a", null)

        val result = worker(0, workDataOf(TransferScheduler.KEY_TRANSFER_ID to "t1")).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun `the fifth failure gives up instead of retrying`() = runBlocking {
        repository.failAlways()

        val result = worker(5, workDataOf(TransferScheduler.KEY_TRANSFER_ID to "t1")).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `before the fifth failure the worker still retries`() = runBlocking {
        repository.failAlways()

        val result = worker(4, workDataOf(TransferScheduler.KEY_TRANSFER_ID to "t1")).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }
}

/**
 * A fake over the real interface: emits a list on demand, or throws on collection when asked
 * to. doWork reads `transfers.first()` and never calls save/seedIfEmpty, so the failure the
 * retry ladder exists for has to come from the flow itself.
 */
private class FakeTransferRepository : TransferRepository {
    private val rows = MutableStateFlow<List<TransferItem>>(emptyList())
    private var failing = false

    override val transfers: Flow<List<TransferItem>> =
        rows.onStart { if (failing) throw IllegalStateException("repository unavailable") }

    fun emit(items: List<TransferItem>) {
        rows.value = items
    }

    fun failAlways() {
        failing = true
    }

    override suspend fun save(item: TransferItem) {}

    override suspend fun seedIfEmpty() {}

    override suspend fun clearCompleted() {}

    override suspend fun delete(id: String) {}
}
