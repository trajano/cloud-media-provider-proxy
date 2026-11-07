package net.trajano.cloudmediaproviderproxy.provider

internal interface SafMediaSnapshotCache {
    /** Prevent work for a previous folder from repopulating cleared caches. */
    fun invalidate() {
        // Default for caches that do not implement explicit invalidation.
    }

    fun requestRefresh() {
        // Default for caches that refresh only when their snapshot is read.
    }

    fun currentStateSnapshot(): SafMediaSnapshotState

    fun currentSnapshot(): SafMediaSnapshot = currentStateSnapshot().current

    fun deletedMedia(): List<DeletedMedia> = currentStateSnapshot().deletedMedia
}
