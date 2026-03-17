package net.trajano.cloudmediaproviderproxy.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SafMediaSnapshotStateTest {

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
