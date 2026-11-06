package net.trajano.cloudmediaproviderproxy.provider

internal interface SafMediaSnapshotCache {
    /** Prevent work for a previous folder from repopulating cleared caches. */
    fun invalidate() {}

    fun currentStateSnapshot(): SafMediaSnapshotState

    fun currentSnapshot(): SafMediaSnapshot = currentStateSnapshot().current

    fun deletedMedia(): List<DeletedMedia> = currentStateSnapshot().deletedMedia
}
