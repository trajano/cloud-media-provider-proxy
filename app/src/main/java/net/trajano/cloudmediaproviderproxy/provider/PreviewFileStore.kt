package net.trajano.cloudmediaproviderproxy.provider

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal class PreviewFileStore(
    private val previewDirectory: File,
) {

    fun cachedFileFor(
        mediaId: String,
        syncGeneration: Long,
    ): File? {
        val targetFile = File(previewDirectory, "${cacheKey(mediaId)}-$syncGeneration.preview")
        return targetFile.takeIf { it.isFile && it.length() > 0L }
    }

    fun latestCachedFileFor(mediaId: String): File? {
        val cacheKey = cacheKey(mediaId)
        return previewDirectory.listFiles { file ->
            file.name.startsWith("$cacheKey-") && file.name.endsWith(".preview") && file.length() > 0L
        }?.maxByOrNull(::generationForFile)
    }

    /** Share writes for one resource across stores without blocking other media. */
    fun fileFor(
        mediaId: String,
        syncGeneration: Long,
        checkCanceled: () -> Unit = {},
        writer: (File) -> Unit,
    ): File = withMediaLock(mediaId, checkCanceled) {
        checkCanceled()
        cachedFileFor(mediaId, syncGeneration)?.let {
            return@withMediaLock it
        }

        val cacheKey = cacheKey(mediaId)
        val targetFile = File(previewDirectory, "$cacheKey-$syncGeneration.preview")
        if (targetFile.isFile && targetFile.length() > 0L) {
            return@withMediaLock targetFile
        }

        if (!previewDirectory.isDirectory && !previewDirectory.mkdirs() && !previewDirectory.isDirectory) {
            throw IOException("Unable to create preview cache directory at $previewDirectory")
        }

        val tempFile = File(previewDirectory, "$cacheKey-$syncGeneration.tmp")
        deleteIfExists(tempFile)

        runCatching {
            writer(tempFile)
            checkCanceled()
            if (tempFile.length() == 0L) {
                throw IOException("Preview is empty")
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                deleteIfExists(tempFile)
            }
            deleteOlderGenerations(cacheKey, targetFile)
        }.onFailure {
            deleteIfExists(tempFile)
            deleteIfExists(targetFile)
        }.getOrThrow()

        targetFile
    }

    /** Keep a lock alive until its last writer or waiter leaves. */
    private fun <T> withMediaLock(mediaId: String, checkCanceled: () -> Unit, action: () -> T): T {
        val key = "${previewDirectory.canonicalPath}:${cacheKey(mediaId)}"
        val entry = locks.compute(key) { _, existing ->
            (existing ?: MediaLock()).also { it.users++ }
        }!!
        var acquired = false
        try {
            while (!acquired) {
                checkCanceled()
                acquired = entry.lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            return action()
        } finally {
            if (acquired) entry.lock.unlock()
            locks.computeIfPresent(key) { _, current ->
                current.users--
                current.takeIf { it.users > 0 }
            }
        }
    }

    private class MediaLock(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)

    private fun deleteOlderGenerations(
        cacheKey: String,
        keepFile: File,
    ) {
        previewDirectory.listFiles { file ->
            file.name.startsWith("$cacheKey-") && file.absolutePath != keepFile.absolutePath
        }?.forEach(::deleteIfExists)
    }

    private fun deleteIfExists(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to delete preview cache file $file")
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, MediaLock>()
        internal fun cacheKey(mediaId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(mediaId.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

        private fun generationForFile(file: File): Long =
            file.name
                .substringAfterLast('-')
                .substringBefore(".preview")
                .toLongOrNull()
                ?: Long.MIN_VALUE
    }
}
