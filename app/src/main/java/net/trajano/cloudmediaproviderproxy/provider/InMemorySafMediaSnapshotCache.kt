package net.trajano.cloudmediaproviderproxy.provider

import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class InMemorySafMediaSnapshotCache(
    private val snapshotLoader: () -> SafMediaSnapshot,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MILLIS,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : SafMediaSnapshotCache {

    @Volatile
    private var cached: CachedState? = null

    private val refreshRunning = AtomicBoolean(false)

    override fun currentStateSnapshot(): SafMediaSnapshotState = currentState()

    private fun currentState(): SafMediaSnapshotState {
        val cachedState = cached ?: return refreshBlocking()

        if (clock() - cachedState.refreshedAtMillis >= refreshIntervalMillis) {
            refreshAsync()
        }

        return cachedState.state
    }

    private fun refreshBlocking(): SafMediaSnapshotState {
        val refreshed = updateState(snapshotLoader())
        return refreshed.state
    }

    private fun refreshAsync() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return
        }

        executor.execute {
            try {
                updateState(snapshotLoader())
            } finally {
                refreshRunning.set(false)
            }
        }
    }

    @Synchronized
    private fun updateState(snapshot: SafMediaSnapshot): CachedState {
        val currentCached = cached
        val nextState = currentCached?.state?.refresh(snapshot) ?: SafMediaSnapshotState.initial(snapshot)
        val nextCached = CachedState(
            state = nextState,
            refreshedAtMillis = clock(),
        )
        cached = nextCached
        return nextCached
    }

    private data class CachedState(
        val state: SafMediaSnapshotState,
        val refreshedAtMillis: Long,
    )

    companion object {
        private const val REFRESH_INTERVAL_MILLIS = 5_000L
    }
}
