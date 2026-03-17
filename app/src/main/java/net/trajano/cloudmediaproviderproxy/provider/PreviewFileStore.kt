package net.trajano.cloudmediaproviderproxy.provider

import java.io.File
import java.io.IOException
import java.security.MessageDigest

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

    @Synchronized
    fun fileFor(
        mediaId: String,
        syncGeneration: Long,
        writer: (File) -> Unit,
    ): File {
        cachedFileFor(mediaId, syncGeneration)?.let {
            return it
        }

        val cacheKey = cacheKey(mediaId)
        val targetFile = File(previewDirectory, "$cacheKey-$syncGeneration.preview")
        if (targetFile.isFile && targetFile.length() > 0L) {
            return targetFile
        }

        if (!previewDirectory.exists() && !previewDirectory.mkdirs()) {
            throw IOException("Unable to create preview cache directory at $previewDirectory")
        }

        val tempFile = File(previewDirectory, "$cacheKey-$syncGeneration.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        runCatching {
            writer(tempFile)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            deleteOlderGenerations(cacheKey, targetFile)
        }.onFailure {
            tempFile.delete()
            targetFile.delete()
        }.getOrThrow()

        return targetFile
    }

    private fun deleteOlderGenerations(
        cacheKey: String,
        keepFile: File,
    ) {
        previewDirectory.listFiles { file ->
            file.name.startsWith("$cacheKey-") && file.absolutePath != keepFile.absolutePath
        }?.forEach(File::delete)
    }

    companion object {
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
