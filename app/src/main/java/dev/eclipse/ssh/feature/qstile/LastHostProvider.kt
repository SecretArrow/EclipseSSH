package dev.eclipse.ssh.feature.qstile

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.model.HostProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Resolves the most-recently-used host for the Quick Settings tile and
 * the home-screen widget.
 *
 * Both surfaces show "Connect: <name>" on tap and need the answer in
 * the click handler — a tile that takes 200 ms to show its label is one
 * a user stops tapping. [lastConnected] blocks the calling thread for
 * the one-shot read; the host list is small (a few hundred rows) and the
 * query is `ORDER BY lastConnectedAt DESC LIMIT 1`, which is what makes
 * a synchronous read acceptable.
 */
interface LastHostProvider {
    suspend fun lastConnected(): HostProfile?
}

@Singleton
class LastHostProviderImpl @Inject constructor(
    private val repository: HostRepository,
) : LastHostProvider {
    override suspend fun lastConnected(): HostProfile? {
        // The repository exposes a Flow<List<HostProfile>>; `first()` takes the first emission. The
        // host list is loaded eagerly on app start, so the first emission arrives within a frame.
        //
        // Guarded because this runs in the tile's click handler and the widget's onUpdate, both of
        // which are the system's threads and neither of which is allowed to take the process down:
        // the previous unguarded `first()` let a failed read - the vault unlocked by a signed key
        // after a boot the store cannot decrypt, or a query error on the database - surface as an
        // uncaught exception out of a system callback. An empty list is the same answer the caller
        // already handles: it means "no last host", the tile shows its hint and the tap is a no-op,
        // which is the honest state when the host list could not be read. A crash is not.
        val all = runCatching { repository.hosts.first() }.getOrDefault(emptyList())
        return all
            .filter { it.lastConnectedAt != null }
            .maxByOrNull { it.lastConnectedAt ?: 0L }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LastHostProviderModule {
    @Binds
    abstract fun bindLastHostProvider(impl: LastHostProviderImpl): LastHostProvider
}
