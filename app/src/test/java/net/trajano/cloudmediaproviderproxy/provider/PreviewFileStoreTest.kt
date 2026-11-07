package net.trajano.cloudmediaproviderproxy.provider

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewFileStoreTest {
    @Test fun failedLegacyCacheCleanupIsReportedWithoutDisablingTheCache() {
        val directory = Files.createTempDirectory(java.io.File("build").toPath(), "legacy-preview-").toFile()
        val legacy = java.io.File(directory, "old.preview").apply { mkdir() }
        java.io.File(legacy, "occupied").writeText("legacy")
        val warnings = mutableListOf<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger(PreviewFileStore::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { warnings += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val store = PreviewFileStore(directory)
            assertTrue("Failed legacy cleanup must be reported", warnings.isNotEmpty())
            assertEquals("fresh", store.fileFor("photo", 1L) { it.writeText("fresh") }.readText())
        } finally {
            logger.removeHandler(handler)
            PreviewFileStore.invalidateDirectory(directory)
            directory.deleteRecursively()
        }
    }

    @Test fun reclaimedCacheFileCanBeGeneratedAgain() {
        val directory = Files.createTempDirectory(java.io.File("build").toPath(), "preview-reclaimed-").toFile()
        val store = PreviewFileStore(directory)
        try {
            val file = store.fileFor("photo", 1L) { it.writeText("old") }
            assertTrue(file.delete())
            assertNull(store.cachedFileFor("photo", 1L))
            assertEquals("reloaded", store.fileFor("photo", 1L) { it.writeText("reloaded") }.readText())
        } finally { PreviewFileStore.invalidateDirectory(directory); directory.deleteRecursively() }
    }

    @Test fun clearingTheCacheCancelsAnInFlightWriteAndAllowsANewStore() {
        val directory = Files.createTempDirectory(java.io.File("build").toPath(), "preview-cleared-").toFile()
        val store = PreviewFileStore(directory)
        try {
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                store.fileFor("photo", 1L) {
                    it.writeText("partial")
                    PreviewFileStore.invalidateDirectory(directory)
                }
            }
            val fresh = PreviewFileStore(directory)
            assertNull(fresh.cachedFileFor("photo", 1L))
            assertEquals("fresh", fresh.fileFor("photo", 1L) { it.writeText("fresh") }.readText())
        } finally { PreviewFileStore.invalidateDirectory(directory); directory.deleteRecursively() }
    }

    @Test fun cacheEvictsTheLeastRecentlyReadThumbnail() {
        val directory = Files.createTempDirectory(java.io.File("build").toPath(), "preview-budget-").toFile()
        val store = PreviewFileStore(directory, maxBytes = 28L)
        try {
            store.fileFor("old", 1L) { it.writeBytes(ByteArray(10)) }
            store.fileFor("recent", 1L) { it.writeBytes(ByteArray(10)) }
            assertTrue(store.cachedFileFor("recent", 1L) != null)
            store.fileFor("new", 1L) { it.writeBytes(ByteArray(10)) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (store.sizeBytes > 28L && System.nanoTime() < deadline) Thread.sleep(10)
            assertNull("Oldest unread thumbnail must be evicted", store.cachedFileFor("old", 1L))
            assertTrue(store.cachedFileFor("recent", 1L) != null)
            assertTrue(store.cachedFileFor("new", 1L) != null)
        } finally { directory.deleteRecursively() }
    }


    /** Remove partial downloads and allow a subsequent request to retry. */
    @Test
    fun failedDownloadCanBeRetried() {
        val previewDirectory = Files.createTempDirectory(java.io.File("build").toPath(), "preview-retry-").toFile()
        val store = PreviewFileStore(previewDirectory)
        try {
            assertThrows(java.io.IOException::class.java) {
                store.fileFor("retry", 10L) { target ->
                    target.writeText("partial")
                    throw java.io.IOException("Remote transfer failed")
                }
            }
            assertNull(store.cachedFileFor("retry", 10L))
            val completed = store.fileFor("retry", 10L) { it.writeText("complete") }
            assertEquals("complete", completed.readText())
            assertNull(store.cachedFileFor("retry", 9L))
        } finally {
            previewDirectory.deleteRecursively()
        }
    }

    @Test
    fun cacheHitDoesNotRewritePreviewFile() {
        val previewDirectory = Files.createTempDirectory("preview-store-test").toFile()
        val store = PreviewFileStore(previewDirectory)
        val writes = AtomicInteger()

        val firstFile = store.fileFor("media-id", 10L) { target ->
            writes.incrementAndGet()
            target.writeText("first")
        }

        val secondFile = store.fileFor("media-id", 10L) { target ->
            writes.incrementAndGet()
            target.writeText("second")
        }

        assertEquals(1, writes.get())
        assertEquals(firstFile.absolutePath, secondFile.absolutePath)
        assertEquals("first", secondFile.readText())
    }

    @Test
    fun newGenerationReplacesOlderPreviewFile() {
        val previewDirectory = Files.createTempDirectory("preview-store-test").toFile()
        val store = PreviewFileStore(previewDirectory)

        val oldFile = store.fileFor("media-id", 10L) { target ->
            target.writeText("old")
        }

        val newFile = store.fileFor("media-id", 11L) { target ->
            target.writeText("new")
        }

        assertNull(store.cachedFileFor("media-id", 10L))
        assertTrue(newFile.exists())
        assertEquals("new", newFile.readText())
    }

    @Test
    fun latestCachedFileReturnsNewestGeneration() {
        val previewDirectory = Files.createTempDirectory("preview-store-test").toFile()
        val store = PreviewFileStore(previewDirectory)

        store.fileFor("media-id", 10L) { target ->
            target.writeText("old")
        }
        val newestFile = store.fileFor("media-id", 11L) { target ->
            target.writeText("new")
        }

        assertEquals(newestFile.absolutePath, store.latestCachedFileFor("media-id")?.absolutePath)
    }
}
