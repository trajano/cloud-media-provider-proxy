package net.trajano.cloudmediaproviderproxy.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhotoPreviewEncoderTest {
    @Test fun thumbnailIsBoundedAndTransparencyIsFlattenedOntoWhite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "preview-encoder-${System.nanoTime()}").apply { mkdirs() }
        val source = File(folder, "source.png")
        val target = File(folder, "thumbnail.jpg")
        val bitmap = createBitmap(1280, 640)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val sourceSize = source.length()
            PhotoPreviewEncoder().write(ImageDecoder.createSource(source), target, 256) {}
            val decoded = requireNotNull(BitmapFactory.decodeFile(target.path))
            try {
                assertEquals(256, decoded.width)
                assertEquals(128, decoded.height)
                assertEquals(Color.WHITE, decoded.getPixel(0, 0))
                assertEquals(sourceSize, source.length())
            } finally { decoded.recycle() }
        } finally { bitmap.recycle(); folder.deleteRecursively() }
    }

    @Test fun canceledRequestDoesNotCreateAThumbnail() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "preview-canceled-${System.nanoTime()}").apply { mkdirs() }
        val target = File(folder, "thumbnail.jpg")
        try {
            assertThrows(CancellationException::class.java) {
                PhotoPreviewEncoder().write(ImageDecoder.createSource(File(folder, "unreadable.png")), target, 256) {
                    throw CancellationException("Picker closed")
                }
            }
            assertFalse(target.exists())
        } finally { folder.deleteRecursively() }
    }
}
