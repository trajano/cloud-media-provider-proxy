package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import java.io.File
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SafMediaRefreshTest {
    @Test
    fun waitsForRemoteFolderReloadBeforePublishingLargePhoto() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val preferences = mock(SafRootPreferences::class.java)
        val root = mock(Uri::class.java)
        val document = mock(Uri::class.java)
        val children = mock(Uri::class.java)
        val photo = mock(Uri::class.java)
        val loading = mock(Cursor::class.java)
        val loaded = mock(Cursor::class.java)
        val loadingExtras = mock(Bundle::class.java)
        val loadedExtras = mock(Bundle::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(mock(android.content.SharedPreferences::class.java))
        `when`(preferences.getRootUri()).thenReturn(root)
        `when`(preferences.hasPersistedReadPermission(resolver)).thenReturn(true)
        `when`(root.authority).thenReturn("example")
        `when`(children.authority).thenReturn("example")
        `when`(photo.toString()).thenReturn("content://example/photo")
        `when`(loading.extras).thenReturn(loadingExtras)
        `when`(loaded.extras).thenReturn(loadedExtras)
        `when`(loadingExtras.getBoolean(DocumentsContract.EXTRA_LOADING, false)).thenReturn(true)
        `when`(loaded.moveToNext()).thenReturn(true, false)
        `when`(loaded.getString(0)).thenReturn("photo")
        `when`(loaded.getString(1)).thenReturn("photo.jpg")
        `when`(loaded.getString(3)).thenReturn("image/jpeg")
        `when`(loaded.getLong(2)).thenReturn(5L)
        `when`(loaded.getLong(4)).thenReturn(14_532_852L)
        `when`(resolver.query(eq(children), any(Array<String>::class.java), isNull<String>(),
            isNull<Array<String>>(), isNull<String>(), isNull<CancellationSignal>()))
            .thenAnswer { invocation ->
                if (queries++ == 0) loading else loaded
            }

        // A synchronous notification during the query models a fast remote reload.
        doAnswer { invocation ->
            observer = invocation.getArgument(2)
            null
        }.`when`(resolver).registerContentObserver(any(Uri::class.java), eq(true), any(ContentObserver::class.java))
        doAnswer {
            observer?.onChange(false)
            loadingExtras
        }.`when`(loading).getExtras()

        mockStatic(DocumentsContract::class.java).use { documents ->
            mockStatic(Uri::class.java).use { uris ->
                mockStatic(Base64::class.java).use { base64 ->
                    mockStatic(Log::class.java).use {
                        uris.`when`<Uri> { Uri.parse("content://example") }.thenReturn(document)
                        documents.`when`<String> { DocumentsContract.getTreeDocumentId(root) }.thenReturn("root")
                        documents.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(root, "root") }.thenReturn(document)
                        documents.`when`<String> { DocumentsContract.getDocumentId(document) }.thenReturn("root")
                        documents.`when`<Uri> { DocumentsContract.buildChildDocumentsUriUsingTree(root, "root") }.thenReturn(children)
                        documents.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(root, "photo") }.thenReturn(photo)
                        base64.`when`<String> { Base64.encodeToString(any(ByteArray::class.java), anyInt()) }.thenReturn("photo")
                        val catalog = SafMediaCatalog(context, preferences, PreviewFileStore(File("build/test-previews")))

                        assertEquals(listOf("photo"), catalog.queryMedia().mediaItems.map(SafMediaItem::mediaId))
                        verify(loading).close()
                        verify(loaded).close()
                        verify(resolver).unregisterContentObserver(any(ContentObserver::class.java))
                    }
                }
            }
        }
    }

    private var queries = 0
    private var observer: ContentObserver? = null
}
