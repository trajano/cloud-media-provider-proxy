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
            assertEquals(1, previewDirectory.listFiles()?.size)
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

        assertFalse(oldFile.exists())
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
