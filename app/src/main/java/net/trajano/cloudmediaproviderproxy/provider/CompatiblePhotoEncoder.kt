package net.trajano.cloudmediaproviderproxy.provider

import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.IOException

internal object CompatiblePhotoEncoder {
    fun writeJpeg(source: ParcelFileDescriptor, target: File, maxBytes: Long, checkCanceled: () -> Unit) {
        checkCanceled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(source.fileDescriptor, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot decode source photo")
        // Bound decode memory even for exceptionally large PNGs.
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 16_000_000) sample *= 2
        val decoded = BitmapFactory.decodeFileDescriptor(source.fileDescriptor, null,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode source photo")
        var bitmap = try {
            createBitmap(decoded.width, decoded.height).apply {
                Canvas(this).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            }
        } finally { decoded.recycle() }
        try {
            while (true) {
                if (encodeAtAvailableQualities(bitmap, target, maxBytes, checkCanceled)) return
                if (bitmap.width == 1 && bitmap.height == 1) throw IOException("JPEG cannot fit size limit")
                val smaller = bitmap.scale(maxOf(1, bitmap.width * 3 / 4),
                    maxOf(1, bitmap.height * 3 / 4), true)
                bitmap.recycle()
                bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }

    private fun encodeAtAvailableQualities(bitmap: Bitmap, target: File, maxBytes: Long, checkCanceled: () -> Unit): Boolean {
        for (quality in listOf(92, 85, 75, 60)) {
            checkCanceled()
            target.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) {
                    throw IOException("Cannot encode compatible JPEG")
                }
            }
            if (target.length() in 1..maxBytes) return true
        }
        return false
    }
}
