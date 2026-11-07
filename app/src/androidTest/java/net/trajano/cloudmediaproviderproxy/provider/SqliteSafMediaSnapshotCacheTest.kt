package net.trajano.cloudmediaproviderproxy.provider

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteSafMediaSnapshotCacheTest {
    @Test
    fun localCopyLinkSurvivesCacheRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "local-link-persistence-${System.nanoTime()}"
        val local = Uri.parse("content://media/external_primary/images/media/123")
        val initial = SqliteSafMediaSnapshotCache(context, key, {
            snapshot(mediaItem("photo", 20L).copy(mediaStoreUri = local))
        })
        initial.currentStateSnapshot()
        initial.invalidate()
        val restored = SqliteSafMediaSnapshotCache(context, key, { error("Must use persisted link") },
            refreshIntervalMillis = TimeUnit.DAYS.toMillis(1))
        try {
            assertEquals(local, restored.currentSnapshot().mediaById.getValue("photo").mediaStoreUri)
        } finally { restored.invalidate() }
    }


    @Test
    fun initialEmptyFolderNotifiesAfterIndexCommit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "empty-folder-${System.nanoTime()}"
        val notifications = AtomicInteger()
        val cache = SqliteSafMediaSnapshotCache(
            context, key, { snapshot() },
            executor = ImmediateExecutorService(),
            onSnapshotChanged = {
                val persisted = SqliteSafMediaSnapshotCache(
                    context, key, { error("Notification must follow the empty index commit") },
                    refreshIntervalMillis = TimeUnit.DAYS.toMillis(1),
                )
                try {
                    assertTrue(persisted.currentSnapshot().mediaItems.isEmpty())
                } finally {
                    persisted.invalidate()
                }
                notifications.incrementAndGet()
            },
        )
        try {
            assertTrue(cache.currentSnapshot().mediaItems.isEmpty())
            assertEquals(1, notifications.get())
        } finally {
            cache.invalidate()
        }
    }

    @Test
    fun restoredEmptyFolderIsAnnouncedOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = "restored-empty-${System.nanoTime()}"
        val initial = SqliteSafMediaSnapshotCache(context, key, { snapshot() })
        initial.currentSnapshot()
        initial.invalidate()
        val notifications = AtomicInteger()
        val restored = SqliteSafMediaSnapshotCache(
            context, key, { error("Should restore the committed empty index") },
            refreshIntervalMillis = TimeUnit.DAYS.toMillis(1),
            executor = ImmediateExecutorService(),
            onSnapshotChanged = { notifications.incrementAndGet() },
        )
        try {
            assertTrue(restored.currentSnapshot().mediaItems.isEmpty())
            restored.currentSnapshot()
            assertEquals(1, notifications.get())
        } finally {
            restored.invalidate()
        }
    }

    @Test
    fun sourceChangeDuringScanIsNotLost() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scans = AtomicInteger()
        lateinit var cache: SqliteSafMediaSnapshotCache
        cache = SqliteSafMediaSnapshotCache(
            context, "scan-notification-${System.nanoTime()}", {
                val scan = scans.incrementAndGet()
                if (scan == 2) cache.requestRefresh()
                if (scan < 3) snapshot(mediaItem("existing", 20L))
                else snapshot(mediaItem("existing", 20L), mediaItem("new", 5L))
            },
            executor = ImmediateExecutorService(),
        )
        try {
            cache.currentStateSnapshot()
            cache.requestRefresh()
            assertEquals(3, scans.get())
            assertEquals(21L, cache.currentSnapshot().mediaById.getValue("new").syncGeneration)
        } finally {
            cache.invalidate()
        }
    }

    @Test
    fun sourceChangeRefreshesBeforeExpiryAndNotifiesAfterCommit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val existing = mediaItem("existing", 20L)
        val source = AtomicReference(snapshot(existing))
        val notifications = AtomicInteger()
        val key = "source-change-${System.nanoTime()}"
        val cache = SqliteSafMediaSnapshotCache(
            context = context,
            cacheKey = key,
            snapshotLoader = source::get,
            refreshIntervalMillis = TimeUnit.DAYS.toMillis(1),
            executor = ImmediateExecutorService(),
            onSnapshotChanged = {
                val persisted = SqliteSafMediaSnapshotCache(
                    context, key, { error("Notification must follow commit") },
                    refreshIntervalMillis = TimeUnit.DAYS.toMillis(1),
                )
                try {
                    assertEquals(if (notifications.get() == 0) 20L else 21L,
                        persisted.currentStateSnapshot().lastSyncGeneration)
                } finally {
                    persisted.invalidate()
                }
                notifications.incrementAndGet()
            },
        )
        try {
            assertEquals(20L, cache.currentStateSnapshot().lastSyncGeneration)
            assertEquals(1, notifications.get())
            source.set(snapshot(existing, mediaItem("older-upload", 5L)))
            cache.requestRefresh()
            assertEquals(2, notifications.get())
            assertEquals(21L, cache.currentSnapshot().mediaById.getValue("older-upload").syncGeneration)
            cache.requestRefresh()
            assertEquals(2, notifications.get())
        } finally {
            cache.invalidate()
        }
    }

    @Test
    fun failedRemoteReloadRetainsTheIndexedPhotos() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var fail = false
        val notifications = AtomicInteger()
        val cache = SqliteSafMediaSnapshotCache(
            context, "failed-reload-${System.nanoTime()}", {
                if (fail) throw java.io.IOException("Remote folder is still loading")
                snapshot(mediaItem("kept", 20L))
            },
            executor = ImmediateExecutorService(),
            onSnapshotChanged = { notifications.incrementAndGet() },
        )
        try {
            val indexed = cache.currentStateSnapshot()
            fail = true
            cache.requestRefresh()
            assertSame(indexed, cache.currentStateSnapshot())
            assertEquals(1, notifications.get())
        } finally {
            cache.invalidate()
        }
    }

    /** Reuse the indexed snapshot for repeated thumbnail lookups. */
    @Test
    fun warmRequestsReuseIndexedSnapshot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = SqliteSafMediaSnapshotCache(
            context, "memory-test-${System.nanoTime()}",
            { snapshot(mediaItem("cached", 10L)) },
        )
        try {
            val first = cache.currentStateSnapshot()
            assertSame(first, cache.currentStateSnapshot())
            assertEquals("cached", cache.currentSnapshot().mediaById["cached"]?.mediaId)
        } finally {
            cache.invalidate()
        }
    }

    /** Share one initial remote scan between simultaneous picker requests. */
    @Test
    fun concurrentColdRequestsShareOneScan() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = Executors.newFixedThreadPool(2)
        val scans = AtomicInteger()
        val cache = SqliteSafMediaSnapshotCache(
            context, "cold-test-${System.nanoTime()}", {
                scans.incrementAndGet()
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                snapshot(mediaItem("cached", 10L))
            },
        )
        try {
            val first = requests.submit(Callable { cache.currentStateSnapshot() })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = requests.submit(Callable { cache.currentStateSnapshot() })
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, scans.get())
        } finally {
            release.countDown()
            requests.shutdownNow()
            check(requests.awaitTermination(5, TimeUnit.SECONDS))
            cache.invalidate()
        }
    }

    @Test
    fun persistedStateSurvivesAcrossCacheInstances() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clock = AtomicLong(0L)
        val snapshotRef = AtomicReference(
            snapshot(
                mediaItem("kept", 10L),
                mediaItem("removed", 11L),
            ),
        )
        val cacheKey = "sqlite-cache-test-${System.nanoTime()}"

        val cache = SqliteSafMediaSnapshotCache(
            context = context,
            cacheKey = cacheKey,
            snapshotLoader = snapshotRef::get,
            clock = clock::get,
            refreshIntervalMillis = 0L,
            executor = ImmediateExecutorService(),
        )

        assertEquals(11L, cache.currentStateSnapshot().lastSyncGeneration)

        snapshotRef.set(snapshot(mediaItem("kept", 10L)))
        clock.set(1L)

        val staleState = cache.currentStateSnapshot()

        assertEquals(11L, staleState.lastSyncGeneration)
        assertTrue(staleState.deletedMedia.isEmpty())

        val persistedCache = SqliteSafMediaSnapshotCache(
            context = context,
            cacheKey = cacheKey,
            snapshotLoader = { error("Expected persisted state to be returned before refresh") },
            clock = clock::get,
            refreshIntervalMillis = TimeUnit.DAYS.toMillis(1),
            executor = ImmediateExecutorService(),
        )

        val persistedState = persistedCache.currentStateSnapshot()

        assertEquals(12L, persistedState.lastSyncGeneration)
        assertEquals(listOf("kept"), persistedState.current.mediaItems.map(SafMediaItem::mediaId))
        assertEquals(listOf("removed"), persistedState.deletedMedia.map(DeletedMedia::mediaId))
    }

    private fun snapshot(vararg items: SafMediaItem): SafMediaSnapshot =
        SafMediaSnapshot(
            mediaItems = items.toList(),
            lastSyncGeneration = items.maxOfOrNull(SafMediaItem::syncGeneration) ?: 0L,
        )

    private fun mediaItem(
        mediaId: String,
        syncGeneration: Long,
    ): SafMediaItem = SafMediaItem(
        mediaId = mediaId,
        documentUri = Uri.parse("content://example/$mediaId"),
        displayName = mediaId,
        mimeType = "image/jpeg",
        dateTakenMillis = syncGeneration,
        syncGeneration = syncGeneration,
        sizeBytes = 1L,
    )

    private class ImmediateExecutorService : AbstractExecutorService() {
        private var shutdown = false

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return Collections.emptyList()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true

        override fun execute(command: Runnable) {
            check(!shutdown)
            command.run()
        }
    }
}
