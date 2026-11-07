package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class CompatiblePhotoEncoderTest {
    @Test fun cancellationStopsEncoding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("compat-cancel", ".png", context.cacheDir)
        val output = File.createTempFile("compat-cancel", ".jpg", context.cacheDir)
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                try {
                    CompatiblePhotoEncoder.writeJpeg(it, output, 10_000_000) { throw android.os.OperationCanceledException() }
                    fail("Canceled conversion must stop")
                } catch (_: android.os.OperationCanceledException) { }
            }
            assertEquals(0, output.length())
        } finally { source.delete(); output.delete() }
    }

    @Test fun corruptInputDoesNotProduceACompatiblePhoto() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("compat-invalid", ".png", context.cacheDir)
        val output = File.createTempFile("compat-invalid", ".jpg", context.cacheDir)
        try {
            source.writeText("invalid PNG")
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                try { CompatiblePhotoEncoder.writeJpeg(it, output, 10_000_000) {}; fail("Must reject invalid input") }
                catch (_: java.io.IOException) { }
            }
        } finally { source.delete(); output.delete() }
    }

    @Test fun largePngBecomesJpegWithinTheLimitAndKeepsSourceIntact() {
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val random = Random(7)
        val pixels = IntArray(1024 * 1024) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        bitmap.setPixels(pixels, 0, 1024, 0, 0, 1024, 1024)
        withEncodedPhoto(bitmap, 80_000) { source, output ->
            assertTrue(source.length() > 80_000)
            assertTrue("JPEG must fit the configured maximum", output.length() in 1..80_000)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.path, options)
            assertEquals("image/jpeg", options.outMimeType)
            assertTrue(options.outWidth > 0)
        }
    }

    @Test fun transparentPixelsAreFlattenedOntoWhite() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        withEncodedPhoto(bitmap, 10_000_000) { _, output ->
            val decoded = BitmapFactory.decodeFile(output.path)
            try { assertEquals(Color.WHITE, decoded.getPixel(16, 16)) } finally { decoded.recycle() }
        }
    }

    private fun withEncodedPhoto(bitmap: Bitmap, limit: Long, verify: (File, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("compat-source", ".png", context.cacheDir)
        val output = File.createTempFile("compat-output", ".jpg", context.cacheDir)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val original = source.readBytes()
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                CompatiblePhotoEncoder.writeJpeg(it, output, limit) {}
            }
            assertArrayEquals(original, source.readBytes())
            verify(source, output)
        } finally { source.delete(); output.delete() }
    }
}
