package net.trajano.cloudmediaproviderproxy.provider

import coil3.disk.DiskCache
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import okio.Path.Companion.toOkioPath

/** One journaled disk cache per directory; snapshots protect files until a reader opens them. */
internal class PreviewFileStore(private val previewDirectory: File, maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val state = states.computeIfAbsent(previewDirectory.canonicalPath) {
        previewDirectory.listFiles { file -> file.extension in setOf("preview", "tmp") }?.forEach { it.delete() }
        DirectoryState(DiskCache.Builder().directory(File(previewDirectory, "coil-v1").toOkioPath())
            .maxSizeBytes(maxBytes).build())
    }

    val sizeBytes: Long get() = state.cache.size

    fun cachedFileFor(mediaId: String, syncGeneration: Long): File? =
        readCached(mediaId, syncGeneration) { it }

    fun latestCachedFileFor(mediaId: String): File? = readLatest(mediaId) { it }

    fun <T> readCached(mediaId: String, syncGeneration: Long, reader: (File) -> T): T? =
        read(mediaId, syncGeneration, reader)

    fun <T> readLatest(mediaId: String, reader: (File) -> T): T? = read(mediaId, null, reader)

    private fun <T> read(mediaId: String, generation: Long?, reader: (File) -> T): T? = synchronized(state) {
        checkActive()
        try {
            state.cache.openSnapshot(mediaId)?.use { snapshot ->
                if (generation != null && snapshot.metadata.toFile().readText().toLongOrNull() != generation) return null
                val file = snapshot.data.toFile()
                if (!file.isFile || file.length() == 0L) throw IOException("Cached thumbnail was reclaimed")
                reader(file)
            }
        } catch (_: IOException) {
            state.cache.remove(mediaId)
            null
        }
    }

    fun fileFor(mediaId: String, syncGeneration: Long, checkCanceled: () -> Unit = {}, writer: (File) -> Unit): File =
        withFile(mediaId, syncGeneration, checkCanceled, writer) { it }

    fun <T> withFile(mediaId: String, syncGeneration: Long, checkCanceled: () -> Unit = {},
        writer: (File) -> Unit, reader: (File) -> T): T = withMediaLock(mediaId, checkCanceled) {
        checkCanceled()
        readCached(mediaId, syncGeneration, reader)?.let { return@withMediaLock it }
        val editor = synchronized(state) {
            checkActive()
            state.cache.openEditor(mediaId) ?: throw IOException("Thumbnail cache entry is busy")
        }
        var committed = false
        try {
            val target = editor.data.toFile()
            writer(target)
            checkCanceled()
            checkActive()
            if (target.length() == 0L) throw IOException("Preview is empty")
            editor.metadata.toFile().writeText(syncGeneration.toString())
            if (target.length() + editor.metadata.toFile().length() > state.cache.maxSize) {
                throw IOException("Preview exceeds cache budget")
            }
            synchronized(state) {
                checkActive()
                val snapshot = editor.commitAndOpenSnapshot() ?: throw IOException("Cannot commit thumbnail")
                committed = true
                snapshot.use { reader(it.data.toFile()) }
            }
        } finally {
            if (!committed) runCatching { editor.abort() }
        }
    }

    private fun checkActive() {
        if (state.invalidated) throw CancellationException("Thumbnail cache was cleared")
    }

    private fun <T> withMediaLock(mediaId: String, checkCanceled: () -> Unit, action: () -> T): T {
        val key = "${previewDirectory.canonicalPath}:$mediaId"
        val entry = locks.compute(key) { _, existing -> (existing ?: MediaLock()).also { it.users++ } }!!
        var acquired = false
        try {
            while (!acquired) {
                checkCanceled()
                checkActive()
                acquired = entry.lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            return action()
        } finally {
            if (acquired) entry.lock.unlock()
            locks.computeIfPresent(key) { _, current -> current.users--; current.takeIf { it.users > 0 } }
        }
    }

    private class DirectoryState(val cache: DiskCache) { @Volatile var invalidated = false }
    private class MediaLock(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)

    companion object {
        const val DEFAULT_MAX_BYTES = 128L * 1024 * 1024
        private val states = ConcurrentHashMap<String, DirectoryState>()
        private val locks = ConcurrentHashMap<String, MediaLock>()

        fun invalidateDirectory(directory: File) {
            states.computeIfPresent(directory.canonicalPath) { _, state ->
                close(state)
                null
            }
        }

        fun clearDirectory(directory: File): Boolean {
            var deleted = false
            states.compute(directory.canonicalPath) { _, state ->
                // Keep a new cache from opening while its previous files are removed.
                if (state != null) close(state)
                deleted = directory.deleteRecursively()
                null
            }
            return deleted
        }

        private fun close(state: DirectoryState) {
            synchronized(state) {
                state.invalidated = true
                state.cache.shutdown()
            }
        }
    }
}
