package net.trajano.cloudmediaproviderproxy.provider

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger

internal class OriginalMediaFileStore(
    private val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val state = states.computeIfAbsent(directory.canonicalPath) {
        // No writer in this process owns leftovers from a previous process.
        directory.listFiles { file -> file.extension == "tmp" }?.forEach(::deleteIfPresent)
        DirectoryState()
    }

    /** Inspect a completed file without waiting for an in-flight download. */
    fun cachedSize(mediaId: String, syncGeneration: Long): Long? {
        val target = File(directory, "${keyFor(mediaId)}-$syncGeneration.media")
        return synchronized(state) {
            if (target.isFile) target.length().takeIf { it in 1..maxBytes } else null
        }
    }

    private fun keyFor(mediaId: String): String =
        MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun <T> open(
        mediaId: String,
        syncGeneration: Long,
        expectedSize: Long?,
        checkCanceled: () -> Unit = {},
        writer: (File, () -> Unit) -> Unit,
        reader: (File) -> T,
    ): T? {
        checkCanceled()
        if (maxBytes <= 0 || (expectedSize != null && expectedSize > maxBytes)) return null
        val key = keyFor(mediaId)
        val epoch = synchronized(state) { state.epoch }
        val checkActive = {
            checkCanceled()
            synchronized(state) {
                if (epoch != state.epoch) throw CancellationException("Original cache was cleared")
            }
        }
        return withEntryLock(key, checkActive) {
            openLocked(key, syncGeneration, expectedSize, checkActive, writer, reader)
        }
    }

    private fun <T> withEntryLock(key: String, checkActive: () -> Unit, action: () -> T): T {
        val entry = synchronized(state) {
            state.locks.getOrPut(key) { EntryLock() }.also { it.users++ }
        }
        var locked = false
        try {
            while (!locked) {
                checkActive()
                locked = entry.lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            return action()
        } finally {
            if (locked) entry.lock.unlock()
            synchronized(state) {
                if (--entry.users == 0) state.locks.remove(key)
            }
        }
    }

    private fun <T> openLocked(key: String, generation: Long, expectedSize: Long?, checkActive: () -> Unit,
        writer: (File, () -> Unit) -> Unit, reader: (File) -> T): T? {
        var temporary: File? = null
        val target = File(directory, "$key-$generation.media")
        try {
            synchronized(state) {
                checkActive()
                if (isValid(target, expectedSize)) {
                    val result = reader(target)
                    touch(target)
                    trim(target)
                    return result
                }
                temporary = createDownload(target, key)
            }
            val download = temporary!!
            try {
                writer(download, checkActive)
            } catch (_: TransferLimitExceededException) {
                return null
            }
            checkActive()
            val length = download.length()
            if (length > maxBytes) return null
            validateDownload(length, expectedSize)
            synchronized(state) {
                checkActive()
                if (!download.renameTo(target)) throw IOException("Cannot commit original cache file")
                temporary = null
                // Acquire the descriptor before eviction or a folder change can unlink this file.
                val result = reader(target)
                touch(target)
                deleteOlderGenerations(key, generation)
                trim(target)
                return result
            }
        } finally { temporary?.let(::deleteIfPresent) }
    }

    private fun isValid(target: File, expectedSize: Long?): Boolean {
        val length = target.length()
        return target.isFile && length in 1..maxBytes &&
            (expectedSize == null || expectedSize < 0 || length == expectedSize)
    }

    private fun createDownload(target: File, key: String): File {
        if (target.exists() && !target.delete()) throw IOException("Cannot remove incomplete original")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create original cache")
        return File.createTempFile("$key-", ".tmp", directory)
    }

    private fun validateDownload(length: Long, expectedSize: Long?) {
        if (length == 0L || (expectedSize != null && expectedSize >= 0 && length != expectedSize)) {
            throw IOException("Original download is empty or incomplete")
        }
    }

    private fun deleteOlderGenerations(key: String, generation: Long) {
        directory.listFiles { file ->
            file.name.startsWith("$key-") && file.extension == "media" &&
                file.name.substringAfterLast('-').substringBefore('.').toLongOrNull()?.let { it < generation } == true
        }?.forEach(::deleteIfPresent)
    }

    private fun touch(file: File) {
        if (!file.setLastModified(System.currentTimeMillis())) {
            LOGGER.warning("Cannot update cache access timestamp: ${file.path}")
        }
    }

    private fun deleteIfPresent(file: File) {
        if (file.exists() && !file.delete()) LOGGER.warning("Cannot remove cached file: ${file.path}")
    }

    /** Invalidate in-flight transfers without waiting for remote reads to finish. */
    fun clear() = synchronized(state) {
        state.epoch++
        directory.listFiles { file -> file.extension == "media" }?.forEach(::deleteIfPresent)
    }

    private fun trim(keep: File) {
        val files = directory.listFiles { file -> file.extension == "media" }.orEmpty()
        var bytes = files.sumOf(File::length)
        for (file in files.filter { it != keep }.sortedBy(File::lastModified)) {
            if (bytes <= maxBytes) break
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    private class DirectoryState {
        var epoch = 0L
        val locks = mutableMapOf<String, EntryLock>()
    }

    private class EntryLock {
        val lock = ReentrantLock()
        var users = 0
    }

    companion object {
        private val states = ConcurrentHashMap<String, DirectoryState>()
        private val LOGGER = Logger.getLogger(OriginalMediaFileStore::class.java.name)
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
    }
}
