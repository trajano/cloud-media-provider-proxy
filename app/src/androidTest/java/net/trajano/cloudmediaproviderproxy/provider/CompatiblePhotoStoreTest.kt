package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CompatiblePhotoStoreTest {
    @Test fun aJpegBetweenEightAndTenMegabytesKeepsItsInitialQuality() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("ten-mb-source", ".png", context.cacheDir)
        val reference = File.createTempFile("ten-mb-reference", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(4096, 2304, Bitmap.Config.ARGB_8888)
            try {
                val random = java.util.Random(17)
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    for (x in row.indices) row[x] = android.graphics.Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                reference.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } finally { bitmap.recycle() }
            assertTrue("Fixture must exercise the former 8 MB boundary: ${reference.length()}", reference.length() in 8_000_001..10_000_000)
            val item = SafMediaItem("ten-mb", Uri.fromFile(source), "photo.png", "image/png", 1L, 1L, source.length())
            CompatiblePhotoStore(context).open(item, null) {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }.use { assertEquals("Do not reduce quality when the JPEG already fits 10 MB", reference.length(), it.statSize) }
        } finally { source.delete(); reference.delete() }
    }

    @Test fun cachedJpegIsReusedAndChangedSourceIsRebuilt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("compatible-store", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val item = SafMediaItem("test", Uri.fromFile(source), "test.png", "image/png", 1L, 1L, source.length())
        val store = CompatiblePhotoStore(context)
        var opens = 0
        val openSource = { opens++; ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY) }
        try {
            val first = store.open(item, null, openSource).use { it.statSize }
            assertTrue(first in 1..CompatiblePhotoStore.MAX_BYTES)
            store.open(item, null, openSource).use { assertEquals(first, it.statSize) }
            assertEquals(1, opens)
            store.open(item.copy(dateTakenMillis = 2L), null, openSource).close()
            assertEquals(2, opens)
        } finally { source.delete() }
    }
}
