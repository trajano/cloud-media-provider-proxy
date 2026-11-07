package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.Base64
import java.io.IOException
import java.util.concurrent.CancellationException
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class SafMediaPreviewFallbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun generatedFallbackThumbnailIsReusedWithoutReopeningTheSource() = withSource(cachePreview = true) { catalog, resolver, document, _ ->
        mockStatic(ParcelFileDescriptor::class.java).use { descriptors ->
            descriptors.`when`<ParcelFileDescriptor> { ParcelFileDescriptor.open(any(java.io.File::class.java), anyInt()) }
                .thenReturn(mock(ParcelFileDescriptor::class.java))
            mockConstruction(AssetFileDescriptor::class.java).use { opened ->
                repeat(2) { catalog.openPreview("photo", 1L, null, mock(Point::class.java), mimeType = "image/jpeg").close() }
                assertEquals("Both reads must use the cached thumbnail", 2, opened.constructed().size)
                verify(resolver, times(1)).openTypedAssetFileDescriptor(eq(document), eq("image/*"), any(Bundle::class.java), isNull<CancellationSignal>())
                verify(resolver, never()).openAssetFileDescriptor(document, "r", null)
            }
        }
    }

    @Test fun emptySourceThumbnailFallsBackToTheReadableOriginal() = withSource { catalog, resolver, document, original ->
        assertSame(original, catalog.openPreview("photo", 1L, null, mock(Point::class.java), mimeType = "image/jpeg"))
        verify(resolver).openAssetFileDescriptor(document, "r", null)
    }

    @Test fun failedThumbnailTransferFallsBackToTheReadableOriginal() = withSource(IOException("Remote thumbnail stream failed")) { catalog, _, _, original ->
        assertSame(original, catalog.openPreview("photo", 1L, null, mock(Point::class.java), mimeType = "image/jpeg"))
    }

    @Test fun backgroundWarmingDoesNotDownloadAnOriginalForAnEmptyThumbnail() = withSource { catalog, resolver, _, _ ->
        assertThrows(IOException::class.java) {
            catalog.openPreview("photo", 1L, null, mock(Point::class.java), mimeType = "image/jpeg", allowOriginalFallback = false)
        }
        verify(resolver, never()).openAssetFileDescriptor(any(Uri::class.java), anyString(), isNull<CancellationSignal>())
    }

    @Test fun cancellationDoesNotStartAnOriginalTransfer() = withSource { catalog, resolver, document, _ ->
        `when`(resolver.openTypedAssetFileDescriptor(eq(document), eq("image/*"), any(Bundle::class.java), isNull<CancellationSignal>()))
            .thenThrow(CancellationException("Picker closed"))
        assertThrows(CancellationException::class.java) {
            catalog.openPreview("photo", 1L, null, mock(Point::class.java), mimeType = "image/jpeg")
        }
        verify(resolver, never()).openAssetFileDescriptor(any(Uri::class.java), anyString(), isNull<CancellationSignal>())
    }

    private fun withSource(transferFailure: IOException? = null, cachePreview: Boolean = false,
        check: (SafMediaCatalog, ContentResolver, Uri, AssetFileDescriptor) -> Unit) {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val preferences = mock(SafRootPreferences::class.java)
        val root = mock(Uri::class.java)
        val document = mock(Uri::class.java)
        val thumbnail = mock(AssetFileDescriptor::class.java)
        val input = mock(AssetFileDescriptor.AutoCloseInputStream::class.java)
        val original = mock(AssetFileDescriptor::class.java)
        val encoder = mock(PhotoPreviewEncoder::class.java) { invocation ->
            if (cachePreview && invocation.method.name == "write") {
                invocation.getArgument<java.io.File>(1).writeText("encoded-thumbnail")
            }
            null
        }
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(context.cacheDir).thenReturn(temporary.root)
        `when`(preferences.getRootUri()).thenReturn(root)
        `when`(preferences.hasPersistedReadPermission(resolver)).thenReturn(true)
        `when`(thumbnail.createInputStream()).thenReturn(input)
        `when`(input.read(any(ByteArray::class.java))).thenReturn(-1)
        if (transferFailure != null) `when`(input.read(any(ByteArray::class.java))).thenThrow(transferFailure)
        `when`(resolver.openTypedAssetFileDescriptor(eq(document), eq("image/*"), any(Bundle::class.java), isNull<CancellationSignal>()))
            .thenReturn(thumbnail)
        `when`(resolver.openAssetFileDescriptor(document, "r", null)).thenReturn(original)
        mockStatic(DocumentsContract::class.java).use { documents ->
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(root) }.thenReturn("root")
            documents.`when`<String> { DocumentsContract.getTreeDocumentId(document) }.thenReturn("root")
            mockStatic(Uri::class.java).use { uris ->
                uris.`when`<Uri> { Uri.parse("content://example/photo") }.thenReturn(document)
                mockStatic(Base64::class.java).use { base64 ->
                    base64.`when`<ByteArray> { Base64.decode(eq("photo"), anyInt()) }
                        .thenReturn("content://example/photo".toByteArray())
                    mockStatic(ImageDecoder::class.java).use { decoders ->
                    decoders.`when`<ImageDecoder.Source> { ImageDecoder.createSource(resolver, document) }
                        .thenReturn(mock(ImageDecoder.Source::class.java))
                    mockConstruction(Bundle::class.java).use {
                        val catalog = SafMediaCatalog(context, preferences, PreviewFileStore(temporary.newFolder()), thumbnailEncoder = encoder)
                        check(catalog, resolver, document, original)
                    }
                    }
                }
            }
        }
    }
}
