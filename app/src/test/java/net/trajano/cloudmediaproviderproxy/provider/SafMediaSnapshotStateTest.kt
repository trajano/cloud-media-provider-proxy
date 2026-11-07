package net.trajano.cloudmediaproviderproxy.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SafMediaSnapshotStateTest {
    @Test
    fun localCopyLinkIsPublishedAsAnIncrementalMediaUpdate() {
        val photo = mediaItem("photo", 20L)
        val initial = SafMediaSnapshotState.initial(snapshot(photo))
        val local = mock(android.net.Uri::class.java)
        val source = snapshot(photo.copy(mediaStoreUri = local))
        val linked = initial.refresh(source)

        assertEquals(21L, linked.lastSyncGeneration)
        assertEquals(local, linked.current.mediaById.getValue("photo").mediaStoreUri)
        assertEquals(21L, linked.current.mediaById.getValue("photo").syncGeneration)
        assertTrue(linked === linked.refresh(source))
    }


    @Test
    fun newlyDiscoveredOlderPhotoIsIncludedInIncrementalSync() {
        val existing = mediaItem("existing", syncGeneration = 20L)
        val state = SafMediaSnapshotState.initial(snapshot(existing))

        val refreshed = state.refresh(snapshot(existing, mediaItem("uploaded-old-photo", 5L)))

        val incrementalItems = refreshed.current.mediaItems.filter { it.syncGeneration > state.lastSyncGeneration }
        assertEquals(listOf("uploaded-old-photo"), incrementalItems.map(SafMediaItem::mediaId))
        assertEquals(21L, incrementalItems.single().syncGeneration)
    }

    @Test
    fun rescanningSourceTimestampsDoesNotCreateAnotherChange() {
        val existing = mediaItem("existing", 20L)
        val olderPhoto = mediaItem("older", 5L)
        val source = snapshot(existing, olderPhoto)
        val refreshed = SafMediaSnapshotState.initial(snapshot(existing)).refresh(source)

        assertTrue(refreshed === refreshed.refresh(source))
    }

    @Test
    fun deletionsRemainAvailableUntilAnItemReappears() {
        val kept = mediaItem("kept", 20L)
        val removed = mediaItem("removed", 5L)
        val initial = SafMediaSnapshotState.initial(snapshot(kept, removed))
        val deleted = initial.refresh(snapshot(kept))
        val added = deleted.refresh(snapshot(kept, mediaItem("another", 1L)))

        assertEquals(deleted.deletedMedia, added.deletedMedia)
        assertTrue(added.refresh(snapshot(kept, removed)).deletedMedia.none { it.mediaId == removed.mediaId })
    }

    @Test
    fun repeatedGenerationWithoutChangesReusesExistingState() {
        val initialSnapshot = snapshot(
            mediaItem("first", syncGeneration = 10L),
            mediaItem("second", syncGeneration = 20L),
        )
        val state = SafMediaSnapshotState.initial(initialSnapshot)

        val refreshedState = state.refresh(initialSnapshot)

        assertTrue(refreshedState === state)
        assertEquals(20L, refreshedState.lastSyncGeneration)
        assertTrue(refreshedState.deletedMedia.isEmpty())
    }

    @Test
    fun refreshTracksDeletedMediaIdsWhenItemsDisappear() {
        val initialState = SafMediaSnapshotState.initial(
            snapshot(
                mediaItem("kept", syncGeneration = 11L),
                mediaItem("removed", syncGeneration = 12L),
            ),
        )

        val refreshedState = initialState.refresh(
            snapshot(
                mediaItem("kept", syncGeneration = 11L),
                mediaItem("added", syncGeneration = 13L),
            ),
        )

        assertEquals(listOf("removed"), refreshedState.deletedMedia.map(DeletedMedia::mediaId))
        assertEquals(13L, refreshedState.lastSyncGeneration)
    }

    @Test
    fun deletionAdvancesGenerationWhenRemainingItemsDoNot() {
        val initialState = SafMediaSnapshotState.initial(
            snapshot(
                mediaItem("kept", syncGeneration = 11L),
                mediaItem("removed", syncGeneration = 12L),
            ),
        )

        val refreshedState = initialState.refresh(
            snapshot(
                mediaItem("kept", syncGeneration = 11L),
            ),
        )

        assertEquals(13L, refreshedState.lastSyncGeneration)
        assertEquals(13L, refreshedState.deletedMedia.single().syncGeneration)
    }

    private fun snapshot(vararg items: SafMediaItem): SafMediaSnapshot =
        SafMediaSnapshot(
            mediaItems = items.toList(),
            lastSyncGeneration = items.maxOfOrNull(SafMediaItem::syncGeneration) ?: 0L,
        )

    private fun mediaItem(
        mediaId: String,
        syncGeneration: Long,
    ): SafMediaItem = SafMediaItem(
        mediaId = mediaId,
        documentUri = mock(android.net.Uri::class.java),
        displayName = mediaId,
        mimeType = "image/jpeg",
        dateTakenMillis = syncGeneration,
        syncGeneration = syncGeneration,
        sizeBytes = 1L,
    )
}
