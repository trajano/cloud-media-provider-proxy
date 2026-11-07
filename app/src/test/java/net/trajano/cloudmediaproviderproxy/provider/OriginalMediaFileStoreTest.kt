package net.trajano.cloudmediaproviderproxy.provider

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OriginalMediaFileStoreTest {
    private fun directory() = Files.createTempDirectory(File("build").toPath(), "original-cache-").toFile()

    private fun read(store: OriginalMediaFileStore, id: String, value: String, generation: Long = 1L) =
        store.open(id, generation, value.length.toLong(), writer = { file, _ -> file.writeText(value) },
            reader = { it })!!

    @Test
    fun newGenerationReplacesTheOldOriginal() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory)
            val old = read(store, "photo", "original")
            val updated = read(store, "photo", "newbytes", 2L)
            assertFalse(old.exists())
            assertEquals("newbytes", updated.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun missingCacheFileIsFetchedAgainAfterOsReclamation() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory)
            val file = read(store, "photo", "original")
            assertTrue(file.delete())
            assertEquals("original", read(store, "photo", "original").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun evictsLeastRecentlyUsedCompletedOriginals() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory, maxBytes = 16)
            val first = read(store, "first", "12345678")
            val second = read(store, "second", "abcdefgh")
            first.setLastModified(1L)
            second.setLastModified(2L)
            read(store, "first", "ignored!")
            read(store, "third", "87654321")
            assertTrue(first.exists())
            assertFalse(second.exists())
            assertTrue(directory.listFiles()!!.sumOf(File::length) <= 16L)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun oversizedOriginalBypassesTheCacheWithoutDownloading() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory, maxBytes = 4)
            assertNull(store.open("large", 1, 8, writer = { _, _ -> error("Must bypass download") },
                reader = { error("Must bypass cache") }))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun unknownSizeIsBoundedDuringTransferAndDoesNotLeavePartialFiles() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory, maxBytes = 4)
            val result = store.open("unknown", 1, null, writer = { file, check ->
                file.outputStream().use { output ->
                    copyWithCancellation("12345678".byteInputStream(), output, 4L, check)
                }
            }, reader = { error("Oversized transfer must not be committed") })
            assertNull(result)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun incompleteTransferIsRejectedAndCanBeRetried() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory)
            assertThrows(java.io.IOException::class.java) {
                store.open("photo", 1, 8, writer = { file, _ -> file.writeText("short") }, reader = File::readText)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
            assertEquals("original", read(store, "photo", "original").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cancelledTransferDoesNotPublishPartialData() {
        val directory = directory()
        var cancelled = false
        try {
            val store = OriginalMediaFileStore(directory)
            assertThrows(CancellationException::class.java) {
                store.open("photo", 1, 8, checkCanceled = {
                    if (cancelled) throw CancellationException()
                }, writer = { file, check ->
                    file.writeText("partial")
                    cancelled = true
                    check()
                }, reader = File::readText)
            }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cacheClearPreventsAnInFlightDownloadFromRepopulatingIt() {
        val directory = directory()
        try {
            val store = OriginalMediaFileStore(directory)
            val old = read(store, "old", "original")
            assertThrows(CancellationException::class.java) {
                store.open("new", 1, 8, writer = { file, _ ->
                    file.writeText("original")
                    OriginalMediaFileStore(directory).clear()
                }, reader = File::readText)
            }
            assertFalse(old.exists())
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun concurrentSelectionsShareOneDownloadAcrossStoreInstances() {
        val directory = directory()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val downloads = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable {
                OriginalMediaFileStore(directory).open("photo", 1, 8, writer = { file, _ ->
                    downloads.incrementAndGet()
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    file.writeText("original")
                }, reader = File::readText)
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit(Callable {
                secondStarted.countDown()
                OriginalMediaFileStore(directory).open("photo", 1, 8, writer = { file, _ ->
                    downloads.incrementAndGet()
                    file.writeText("original")
                }, reader = File::readText)
            })
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("original", first.get(5, TimeUnit.SECONDS))
            assertEquals("original", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, downloads.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    @Test
    fun repeatedSelectionReusesTheCompletedOriginalAcrossStoreInstances() {
        val directory = Files.createTempDirectory(File("build").toPath(), "original-cache-").toFile()
        val downloads = AtomicInteger()
        try {
            repeat(2) {
                val store = OriginalMediaFileStore(directory)
                val content = store.open("photo", 1L, 8L, writer = { file, _ ->
                    downloads.incrementAndGet()
                    file.writeText("original")
                }, reader = File::readText)
                assertEquals("original", content)
            }
            assertEquals("Second selection must not reopen the remote original", 1, downloads.get())
        } finally {
            directory.deleteRecursively()
        }
    }
}
