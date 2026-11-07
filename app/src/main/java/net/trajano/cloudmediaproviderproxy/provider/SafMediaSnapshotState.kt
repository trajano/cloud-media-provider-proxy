package net.trajano.cloudmediaproviderproxy.provider

internal data class SafMediaSnapshotState(
    val current: SafMediaSnapshot,
    val lastSyncGeneration: Long,
    val deletedMedia: List<DeletedMedia>,
) {

    fun refresh(snapshot: SafMediaSnapshot): SafMediaSnapshotState {
        val changedItems = snapshot.mediaItems.filter { item ->
            val previous = current.mediaById[item.mediaId]
            previous == null || item.copy(syncGeneration = previous.syncGeneration) != previous
        }
        val refreshedIds = snapshot.mediaItems.mapTo(hashSetOf(), SafMediaItem::mediaId)
        val deletedIds = current.mediaById.keys.filterNot(refreshedIds::contains)
        if (changedItems.isEmpty() && deletedIds.isEmpty()) {
            return this
        }

        val nextGeneration = maxOf(snapshot.lastSyncGeneration, lastSyncGeneration + 1L)
        val changedIds = changedItems.mapTo(hashSetOf(), SafMediaItem::mediaId)
        val indexedItems = snapshot.mediaItems.map { item ->
            if (item.mediaId in changedIds) item.copy(syncGeneration = nextGeneration)
            else current.mediaById.getValue(item.mediaId)
        }
        val tombstones = deletedMedia.filterNot { it.mediaId in refreshedIds } + deletedIds.map { mediaId ->
            DeletedMedia(
                mediaId = mediaId,
                syncGeneration = nextGeneration,
            )
        }

        return SafMediaSnapshotState(
            current = SafMediaSnapshot(indexedItems, nextGeneration),
            lastSyncGeneration = nextGeneration,
            deletedMedia = tombstones,
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
