package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import net.trajano.cloudmediaproviderproxy.ui.SetupActivity
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

internal class SafMediaCatalog(
    private val context: Context,
    private val rootPreferences: SafRootPreferences = SafRootPreferences(context),
) {

    fun buildCollectionInfo(): Bundle {
        val rootUri = configuredRootUri()

        return Bundle().apply {
            putString(KEY_MEDIA_COLLECTION_ID, mediaCollectionId(rootUri))
            putLong(KEY_LAST_MEDIA_SYNC_GENERATION, 0L)
            putString(KEY_ACCOUNT_NAME, rootUri?.authority ?: "Not configured")
            putParcelable(
                KEY_ACCOUNT_CONFIGURATION_INTENT,
                Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun queryMedia(): List<SafMediaItem> {
        val rootUri = configuredRootUri() ?: return emptyList()
        val rootDocumentUri = resolveRootDocumentUri(rootUri) ?: return emptyList()

        val mediaItems = mutableListOf<SafMediaItem>()
        walkDocumentTree(rootUri = rootUri, documentUri = rootDocumentUri, mediaItems = mediaItems)
        return mediaItems.sortedByDescending { it.dateTakenMillis ?: 0L }
    }

    fun openMedia(mediaId: String): ParcelFileDescriptor {
        val documentUri = resolveMediaUri(mediaId)
        return context.contentResolver.openFileDescriptor(documentUri, "r")
            ?: throw FileNotFoundException("Unable to open $documentUri")
    }

    fun openPreview(
        mediaId: String,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val documentUri = resolveMediaUri(mediaId)
        val options = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, android.graphics.Point(512, 512))
        }

        context.contentResolver.openTypedAssetFileDescriptor(
            documentUri,
            "image/*",
            options,
            signal,
        )?.let { return it }

        return context.contentResolver.openAssetFileDescriptor(documentUri, "r")
            ?: throw FileNotFoundException("Unable to open preview for $documentUri")
    }

    fun configuredRootUri(): Uri? {
        val rootUri = rootPreferences.getRootUri() ?: return null
        if (!rootPreferences.hasPersistedReadPermission(context.contentResolver)) {
            return null
        }
        return rootUri
    }

    companion object {
        private const val TAG = "SafMediaCatalog"
        private const val DIRECTORY_MIME_TYPE = DocumentsContract.Document.MIME_TYPE_DIR

        private const val KEY_MEDIA_COLLECTION_ID =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID
        private const val KEY_LAST_MEDIA_SYNC_GENERATION =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION
        private const val KEY_ACCOUNT_NAME =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME
        private const val KEY_ACCOUNT_CONFIGURATION_INTENT =
            android.provider.CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT

        fun mediaCollectionId(rootUri: Uri?): String =
            rootUri?.toString()?.let { "tree:${it.hashCode()}" } ?: "unconfigured"

        fun mediaId(documentUri: Uri): String =
            Base64.encodeToString(
                documentUri.toString().toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE,
            )

        fun decodeMediaId(mediaId: String): Uri =
            Uri.parse(
                String(
                    Base64.decode(mediaId, Base64.NO_WRAP or Base64.URL_SAFE),
                    StandardCharsets.UTF_8,
                ),
            )
    }

    private fun resolveMediaUri(mediaId: String): Uri {
        val documentUri = decodeMediaId(mediaId)
        val configuredRoot = configuredRootUri()
            ?: throw FileNotFoundException("SAF root is not configured")
        val configuredTreeId = DocumentsContract.getTreeDocumentId(configuredRoot)
        val documentTreeId = DocumentsContract.getTreeDocumentId(documentUri)

        if (configuredTreeId != documentTreeId) {
            throw FileNotFoundException("Media id does not belong to the configured SAF tree")
        }

        return documentUri
    }

    private fun walkDocumentTree(
        rootUri: Uri,
        documentUri: Uri,
        mediaItems: MutableList<SafMediaItem>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri,
            DocumentsContract.getDocumentId(documentUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val displayName = cursor.getString(1)
                val lastModified = cursor.getLongOrNull(2)
                val mimeType = cursor.getString(3) ?: continue
                val sizeBytes = cursor.getLongOrNull(4)
                val childDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(rootUri, documentId)

                when {
                    mimeType == DIRECTORY_MIME_TYPE -> walkDocumentTree(rootUri, childDocumentUri, mediaItems)
                    mimeType.startsWith("image/") || mimeType.startsWith("video/") -> {
                        mediaItems += SafMediaItem(
                            mediaId = mediaId(childDocumentUri),
                            documentUri = childDocumentUri,
                            displayName = displayName,
                            mimeType = mimeType,
                            dateTakenMillis = lastModified,
                            sizeBytes = sizeBytes,
                        )
                    }
                }
            }
        } ?: Log.w(TAG, "Unable to query SAF children for $documentUri")
    }

    private fun resolveRootDocumentUri(rootUri: Uri): Uri? =
        runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                rootUri,
                DocumentsContract.getTreeDocumentId(rootUri),
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to resolve SAF root document URI for $rootUri", error)
        }.getOrNull()

    private fun Cursor.getLongOrNull(columnIndex: Int): Long? =
        if (isNull(columnIndex)) {
            null
        } else {
            getLong(columnIndex)
        }
}

internal data class SafMediaItem(
    val mediaId: String,
    val documentUri: Uri,
    val displayName: String?,
    val mimeType: String,
    val dateTakenMillis: Long?,
    val sizeBytes: Long?,
)
