package net.trajano.cloudmediaproviderproxy.provider

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewFileStoreConcurrencyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder(File("build"))

    /** Verify that separate request-scoped stores share one in-flight download. */
    @Test
    fun concurrentStoresDownloadTheSamePreviewOnce() {
        val directory = temporaryFolder.newFolder()
        val firstStore = PreviewFileStore(directory)
        val secondStore = PreviewFileStore(directory)
        val executor = Executors.newFixedThreadPool(2)
        val firstWriterStarted = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val secondRequestStarted = CountDownLatch(1)
        val secondWriterStarted = CountDownLatch(1)
        val downloads = AtomicInteger()

        try {
            val first = executor.submit(Callable {
                firstStore.fileFor("same-media", 10L) { target ->
                    downloads.incrementAndGet()
                    firstWriterStarted.countDown()
                    check(releaseFirstWriter.await(10, TimeUnit.SECONDS))
                    target.writeText("preview")
                }
            })
            assertTrue("The first download must start", firstWriterStarted.await(5, TimeUnit.SECONDS))

            val second = executor.submit(Callable {
                secondRequestStarted.countDown()
                secondStore.fileFor("same-media", 10L) { target ->
                    downloads.incrementAndGet()
                    target.writeText("preview")
                    secondWriterStarted.countDown()
                }
            })
            assertTrue("The second request must start", secondRequestStarted.await(5, TimeUnit.SECONDS))
            // A shared lock should keep the second writer out until the first finishes.
            secondWriterStarted.await(1, TimeUnit.SECONDS)
            releaseFirstWriter.countDown()

            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals("Concurrent requests must share one download", 1, downloads.get())
        } finally {
            releaseFirstWriter.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    /** Verify that a slow remote resource does not block an unrelated preview. */
    @Test
    fun differentMediaCanDownloadWhileAnotherPreviewIsBlocked() {
        val store = PreviewFileStore(temporaryFolder.newFolder())
        val executor = Executors.newFixedThreadPool(2)
        val firstWriterStarted = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val secondRequestStarted = CountDownLatch(1)
        val secondWriterFinished = CountDownLatch(1)

        try {
            val first = executor.submit(Callable {
                store.fileFor("slow-media", 10L) { target ->
                    firstWriterStarted.countDown()
                    check(releaseFirstWriter.await(10, TimeUnit.SECONDS))
                    target.writeText("slow preview")
                }
            })
            assertTrue("The first download must start", firstWriterStarted.await(5, TimeUnit.SECONDS))
            val second = executor.submit(Callable {
                secondRequestStarted.countDown()
                store.fileFor("other-media", 10L) { target ->
                    target.writeText("other preview")
                    secondWriterFinished.countDown()
                }
            })
            assertTrue("The second request must start", secondRequestStarted.await(5, TimeUnit.SECONDS))

            assertTrue(
                "An unrelated preview must finish while the first download is still blocked",
                secondWriterFinished.await(2, TimeUnit.SECONDS),
            )
            releaseFirstWriter.countDown()
            assertEquals("slow preview", first.get(5, TimeUnit.SECONDS).readText())
            assertEquals("other preview", second.get(5, TimeUnit.SECONDS).readText())
        } finally {
            releaseFirstWriter.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
