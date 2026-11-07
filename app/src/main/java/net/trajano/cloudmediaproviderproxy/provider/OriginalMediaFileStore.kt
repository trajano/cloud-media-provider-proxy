package net.trajano.cloudmediaproviderproxy.provider

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal class OriginalMediaFileStore(
    private val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val state = states.computeIfAbsent(directory.canonicalPath) {
        // No writer in this process owns leftovers from a previous process.
        directory.listFiles { file -> file.extension == "tmp" }?.forEach(File::delete)
        DirectoryState()
    }

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
        val key = MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val epoch = synchronized(state) { state.epoch }
        val checkActive = {
            checkCanceled()
            synchronized(state) {
                if (epoch != state.epoch) throw CancellationException("Original cache was cleared")
            }
        }
        val entry = synchronized(state) {
            state.locks.getOrPut(key) { EntryLock() }.also { it.users++ }
        }
        var locked = false
        var temporary: File? = null
        try {
            while (!locked) {
                checkActive()
                locked = entry.lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            val target = File(directory, "$key-$syncGeneration.media")
            synchronized(state) {
                checkActive()
                if (target.isFile && target.length() in 1..maxBytes &&
                    (expectedSize == null || expectedSize < 0 || target.length() == expectedSize)) {
                    val result = reader(target)
                    target.setLastModified(System.currentTimeMillis())
                    trim(target)
                    return result
                }
                if (target.exists() && !target.delete()) throw IOException("Cannot remove incomplete original")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create original cache")
                temporary = File.createTempFile("$key-", ".tmp", directory)
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
            if (length == 0L || (expectedSize != null && expectedSize >= 0 && length != expectedSize)) {
                throw IOException("Original download is empty or incomplete")
            }
            synchronized(state) {
                checkActive()
                if (!download.renameTo(target)) throw IOException("Cannot commit original cache file")
                temporary = null
                // Acquire the descriptor before eviction or a folder change can unlink this file.
                val result = reader(target)
                target.setLastModified(System.currentTimeMillis())
                directory.listFiles { file ->
                    file.name.startsWith("$key-") && file.extension == "media" &&
                        file.name.substringAfterLast('-').substringBefore('.').toLongOrNull()?.let {
                            it < syncGeneration
                        } == true
                }?.forEach(File::delete)
                trim(target)
                return result
            }
        } finally {
            temporary?.delete()
            if (locked) entry.lock.unlock()
            synchronized(state) {
                if (--entry.users == 0) state.locks.remove(key)
            }
        }
    }

    /** Invalidate in-flight transfers without waiting for remote reads to finish. */
    fun clear() = synchronized(state) {
        state.epoch++
        directory.listFiles { file -> file.extension == "media" }?.forEach(File::delete)
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
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024
    }
}
