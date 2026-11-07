package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.content.ContentUris
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.Executors
import org.junit.Test
import org.junit.Assert.assertEquals
import org.mockito.Mockito.*

class LocalMediaLinkerTest {
    @Test fun linksAnExistingGalleryEntryUsingAnExactReadOnlyLookup() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        val store = mock(LocalMediaLinkStore::class.java)
        val item = SafMediaItem("existing", mock(Uri::class.java), "photo.png", "image/png", 1, 1, 15_000_000)
        `when`(store.key(item)).thenReturn("existing-link")
        val descriptor = mock(ParcelFileDescriptor::class.java)
        val signal = mock(CancellationSignal::class.java)
        val collection = mock(Uri::class.java)
        val local = mock(Uri::class.java)
        val cursor = mock(Cursor::class.java)
        val path = "/storage/emulated/0/Android/media/example/photo.png"
        `when`(resolver.query(eq(collection), any(Array<String>::class.java), eq("_data = ?"),
            eq(arrayOf(path)), isNull<String>(), eq(signal))).thenReturn(cursor)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getLong(0)).thenReturn(123L)
        `when`(store.record(item, local, 0L)).thenReturn(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            mockStatic(Os::class.java).use { os ->
                mockStatic(MediaStore.Files::class.java).use { files ->
                    mockStatic(ContentUris::class.java).use { uris ->
                        mockStatic(Log::class.java).use {
                            os.`when`<String> { Os.readlink("/proc/self/fd/0") }.thenReturn(path)
                            files.`when`<Uri> { MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) }.thenReturn(collection)
                            uris.`when`<Uri> { ContentUris.withAppendedId(collection, 123) }.thenReturn(local)
                            var notifications = 0
                            LocalMediaLinker(context, store, executor) { notifications++ }.fromDescriptor(item, descriptor, signal)
                            verify(store).record(item, local, 0L)
                            verify(cursor).close()
                            assertEquals(1, notifications)
                        }
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun openingASourceMustNotScanItIntoTheGallery() {
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(mock(ContentResolver::class.java))
        val store = mock(LocalMediaLinkStore::class.java)
        val item = SafMediaItem("photo", mock(Uri::class.java), "photo.png", "image/png", 1, 1, 15_000_000)
        `when`(store.key(item)).thenReturn("scan-regression")
        val descriptor = mock(ParcelFileDescriptor::class.java)
        val executor = Executors.newSingleThreadExecutor()
        try {
            mockStatic(Os::class.java).use { os ->
                mockStatic(MediaScannerConnection::class.java).use { scanner ->
                    mockStatic(Log::class.java).use {
                        os.`when`<String> { Os.readlink("/proc/self/fd/0") }
                            .thenReturn("/storage/emulated/0/Android/media/example/photo.png")
                        LocalMediaLinker(context, store, executor) {}.fromDescriptor(item, descriptor, mock(CancellationSignal::class.java))
                        scanner.verifyNoInteractions()
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }
}
