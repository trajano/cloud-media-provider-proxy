package net.trajano.cloudmediaproviderproxy.provider

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafMediaCacheStoreTest {

    @Test
    fun clearAllRemovesPersistedSnapshotsAndPreviewFiles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cacheKey = "clear-all-${System.nanoTime()}"

        SqliteSafMediaSnapshotCache(
            context = context,
            cacheKey = cacheKey,
            snapshotLoader = {
                SafMediaSnapshot(
                    mediaItems = listOf(
                        SafMediaItem(
                            mediaId = "media-id",
                            documentUri = Uri.parse("content://example/media-id"),
                            displayName = "media-id",
                            mimeType = "image/jpeg",
                            dateTakenMillis = 1L,
                            syncGeneration = 1L,
                            sizeBytes = 1L,
                        ),
                    ),
                    lastSyncGeneration = 1L,
                )
            },
            refreshIntervalMillis = TimeUnit.DAYS.toMillis(1),
            executor = ImmediateExecutorService(),
        ).currentStateSnapshot()

        val previewFile = PreviewFileStore(SafMediaCacheStore.previewDirectory(context)).fileFor("media-id", 1L) {
            it.writeText("preview")
        }
        val originalFile = OriginalMediaFileStore(SafMediaCacheStore.originalsDirectory(context)).open(
            "test-original", 1L, 8L, writer = { file, _ -> file.writeText("original") }, reader = { it },
        )!!

        assertTrue(File(context.applicationInfo.dataDir, "databases/${SqliteSafMediaSnapshotCache.DATABASE_NAME}").exists())
        assertTrue(previewFile.exists())
        assertTrue(originalFile.exists())
        assertTrue(originalFile.canonicalPath.startsWith(context.cacheDir.canonicalPath))

        SafMediaCacheStore.clearAll(context)

        assertFalse(File(context.applicationInfo.dataDir, "databases/${SqliteSafMediaSnapshotCache.DATABASE_NAME}").exists())
        assertFalse(previewFile.exists())
        assertFalse(originalFile.exists())
    }

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
