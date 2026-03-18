package net.trajano.cloudmediaproviderproxy.provider

internal interface SafMediaSnapshotCache {
    fun currentStateSnapshot(): SafMediaSnapshotState

    fun currentSnapshot(): SafMediaSnapshot = currentStateSnapshot().current

    fun deletedMedia(): List<DeletedMedia> = currentStateSnapshot().deletedMedia
}
