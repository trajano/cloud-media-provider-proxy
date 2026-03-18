package net.trajano.cloudmediaproviderproxy.provider

internal data class SafMediaSnapshotState(
    val current: SafMediaSnapshot,
    val lastSyncGeneration: Long,
    val deletedMedia: List<DeletedMedia>,
) {

    fun refresh(snapshot: SafMediaSnapshot): SafMediaSnapshotState {
        if (snapshot == current) {
            return this
        }

        val currentIds = current.mediaItems.mapTo(linkedSetOf(), SafMediaItem::mediaId)
        val refreshedIds = snapshot.mediaItems.mapTo(hashSetOf(), SafMediaItem::mediaId)
        val deletedIds = currentIds.filterNot(refreshedIds::contains)
        val hasChanges = snapshot != current
        val nextGeneration = if (hasChanges) {
            maxOf(snapshot.lastSyncGeneration, lastSyncGeneration + 1L)
        } else {
            lastSyncGeneration
        }
        val deletedMedia = deletedIds.map { mediaId ->
            DeletedMedia(
                mediaId = mediaId,
                syncGeneration = nextGeneration,
            )
        }

        return SafMediaSnapshotState(
            current = snapshot,
            lastSyncGeneration = nextGeneration,
            deletedMedia = deletedMedia,
        )
    }

    companion object {
        fun initial(snapshot: SafMediaSnapshot): SafMediaSnapshotState =
            SafMediaSnapshotState(
                current = snapshot,
                lastSyncGeneration = snapshot.lastSyncGeneration,
                deletedMedia = emptyList(),
            )
    }
}

internal data class DeletedMedia(
    val mediaId: String,
    val syncGeneration: Long,
)
