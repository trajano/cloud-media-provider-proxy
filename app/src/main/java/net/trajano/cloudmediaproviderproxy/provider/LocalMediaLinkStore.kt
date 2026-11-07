package net.trajano.cloudmediaproviderproxy.provider

import android.content.SharedPreferences
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.security.MessageDigest

internal class LocalMediaLinkStore(private val preferences: SharedPreferences) {
    fun resolve(item: SafMediaItem): Uri? = preferences.getString(key(item), null)
        ?.takeIf(String::isNotEmpty)?.let(Uri::parse)

    fun hasResult(item: SafMediaItem): Boolean = preferences.contains(key(item))

    fun epoch(): Long = preferences.getLong("_epoch", 0L)

    fun record(item: SafMediaItem, uri: Uri, expectedEpoch: Long = epoch()): Boolean = synchronized(lock) {
        if (expectedEpoch != epoch()) return@synchronized false
        val key = key(item)
        if (preferences.getString(key, null) == uri.toString()) return@synchronized false
        preferences.edit { putString(key, uri.toString()) }
        true
    }

    fun recordUnavailable(item: SafMediaItem, expectedEpoch: Long = epoch()) = synchronized(lock) {
        if (expectedEpoch == epoch()) preferences.edit { putString(key(item), "") }
    }

    fun clear() = synchronized(lock) {
        val nextEpoch = epoch() + 1L
        preferences.edit { clear(); putLong("_epoch", nextEpoch) }
    }

    fun key(item: SafMediaItem): String {
        // Sync generations also change for local-link updates; key only the source content.
        val source = "${item.documentUri}\n${item.dateTakenMillis}\n${item.sizeBytes}\n${item.mimeType}"
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val lock = Any()
        fun forContext(context: Context) = LocalMediaLinkStore(
            context.getSharedPreferences("local-media-links", Context.MODE_PRIVATE),
        )
    }
}
