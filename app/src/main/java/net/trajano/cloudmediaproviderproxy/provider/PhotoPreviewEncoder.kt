package net.trajano.cloudmediaproviderproxy.provider

import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal class PhotoPreviewEncoder {
    fun write(source: ImageDecoder.Source, target: File, edge: Int, checkCanceled: () -> Unit) {
        checkCanceled()
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            checkCanceled()
            val scale = minOf(1.0, edge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        var flattened: Bitmap? = null
        try {
            checkCanceled()
            val output = if (bitmap.hasAlpha()) {
                createBitmap(bitmap.width, bitmap.height).also {
                    flattened = it
                    it.eraseColor(Color.WHITE)
                    Canvas(it).drawBitmap(bitmap, 0f, 0f, null)
                }
            } else bitmap
            target.outputStream().use {
                if (!output.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("Cannot encode thumbnail")
            }
            checkCanceled()
        } finally {
            flattened?.recycle()
            bitmap.recycle()
        }
    }
}
