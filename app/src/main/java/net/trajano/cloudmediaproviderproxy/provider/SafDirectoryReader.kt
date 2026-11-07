package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Keep a loading cursor open so closing it cannot cancel the provider's remote reload. */
internal class SafDirectoryReader(private val resolver: ContentResolver) {
    fun <T> read(
        childrenUri: Uri,
        projection: Array<String>,
        signal: CancellationSignal?,
        consume: (Cursor) -> T,
    ): T {
        val changes = Semaphore(0)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { changes.release() }
        }
        // Nextcloud notifies the canonical document URI, rather than the tree children URI.
        // Register before querying so even an immediate reload completion cannot be missed.
        val authorityUri = Uri.parse("content://${childrenUri.authority}")
        resolver.registerContentObserver(authorityUri, true, observer)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        try {
            while (true) {
                signal?.throwIfCanceled()
                changes.drainPermits()
                val cursor = resolver.query(childrenUri, projection, null, null, null, signal)
                    ?: throw IOException("Unable to query SAF folder")
                cursor.use {
                    if (!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                        if (cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                            throw IOException("Document provider could not load folder")
                        }
                        return consume(cursor)
                    }
                    while (!changes.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                        signal?.throwIfCanceled()
                        if (System.nanoTime() >= deadline) {
                            throw IOException("Timed out waiting for document provider reload")
                        }
                    }
                    if (System.nanoTime() >= deadline) {
                        throw IOException("Document provider is still loading folder")
                    }
                }
            }
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }
}
