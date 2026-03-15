package net.trajano.cloudmediaproviderproxy.config

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

class SafRootPreferences(context: Context) {

    private val sharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getRootUri(): Uri? = sharedPreferences.getString(KEY_ROOT_URI, null)?.let(Uri::parse)

    fun saveRootUri(rootUri: Uri) {
        sharedPreferences.edit().putString(KEY_ROOT_URI, rootUri.toString()).apply()
    }

    fun clearRootUri() {
        sharedPreferences.edit().remove(KEY_ROOT_URI).apply()
    }

    fun hasPersistedReadPermission(contentResolver: ContentResolver): Boolean {
        val rootUri = getRootUri() ?: return false
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == rootUri
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "saf-root"
        private const val KEY_ROOT_URI = "root-uri"
    }
}
