package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import androidx.core.database.sqlite.transaction
import androidx.core.net.toUri
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class SqliteSafMediaSnapshotCache(
    context: Context,
    private val cacheKey: String,
    private val snapshotLoader: () -> SafMediaSnapshot,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MILLIS,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : SafMediaSnapshotCache {

    private val databaseHelper = SnapshotDatabaseHelper(context.applicationContext)
    private val refreshRunning = AtomicBoolean(false)

    override fun currentStateSnapshot(): SafMediaSnapshotState {
        val persistedState = readPersistedState() ?: return refreshBlocking()

        if (clock() - persistedState.refreshedAtMillis >= refreshIntervalMillis) {
            refreshAsync()
        }

        return persistedState.state
    }

    private fun refreshBlocking(): SafMediaSnapshotState = updateState(snapshotLoader()).state

    private fun refreshAsync() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return
        }

        executor.execute {
            try {
                updateState(snapshotLoader())
            } finally {
                refreshRunning.set(false)
            }
        }
    }

    private fun readPersistedState(): PersistedState? =
        databaseHelper.readableDatabase.useDatabase { database ->
            val metadata = database.query(
                SnapshotTables.TABLE_CACHE_STATE,
                arrayOf(
                    SnapshotTables.COLUMN_LAST_SYNC_GENERATION,
                    SnapshotTables.COLUMN_REFRESHED_AT_MILLIS,
                ),
                "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
                arrayOf(cacheKey),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                CacheMetadata(
                    lastSyncGeneration = cursor.getLong(0),
                    refreshedAtMillis = cursor.getLong(1),
                )
            } ?: return@useDatabase null

            PersistedState(
                state = SafMediaSnapshotState(
                    current = SafMediaSnapshot(
                        mediaItems = readMediaItems(database),
                        lastSyncGeneration = metadata.lastSyncGeneration,
                    ),
                    lastSyncGeneration = metadata.lastSyncGeneration,
                    deletedMedia = readDeletedMedia(database),
                ),
                refreshedAtMillis = metadata.refreshedAtMillis,
            )
        }

    @Synchronized
    private fun updateState(snapshot: SafMediaSnapshot): PersistedState {
        return databaseHelper.writableDatabase.useDatabase { database ->
            val persistedState = readPersistedState(database)?.state
            val nextState = persistedState?.refresh(snapshot) ?: SafMediaSnapshotState.initial(snapshot)
            val refreshedAtMillis = clock()

            database.transaction {
                delete(
                    SnapshotTables.TABLE_MEDIA_ITEMS,
                    "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
                    arrayOf(cacheKey),
                )
                delete(
                    SnapshotTables.TABLE_DELETED_MEDIA,
                    "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
                    arrayOf(cacheKey),
                )
                delete(
                    SnapshotTables.TABLE_CACHE_STATE,
                    "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
                    arrayOf(cacheKey),
                )

                insertOrThrow(
                    SnapshotTables.TABLE_CACHE_STATE,
                    null,
                    ContentValues().apply {
                        put(SnapshotTables.COLUMN_CACHE_KEY, cacheKey)
                        put(SnapshotTables.COLUMN_LAST_SYNC_GENERATION, nextState.lastSyncGeneration)
                        put(SnapshotTables.COLUMN_REFRESHED_AT_MILLIS, refreshedAtMillis)
                    },
                )

                nextState.current.mediaItems.forEach { item ->
                    insertOrThrow(
                        SnapshotTables.TABLE_MEDIA_ITEMS,
                        null,
                        ContentValues().apply {
                            put(SnapshotTables.COLUMN_CACHE_KEY, cacheKey)
                            put(SnapshotTables.COLUMN_MEDIA_ID, item.mediaId)
                            put(SnapshotTables.COLUMN_DOCUMENT_URI, item.documentUri.toString())
                            put(SnapshotTables.COLUMN_DISPLAY_NAME, item.displayName)
                            put(SnapshotTables.COLUMN_MIME_TYPE, item.mimeType)
                            putNullableLong(SnapshotTables.COLUMN_DATE_TAKEN_MILLIS, item.dateTakenMillis)
                            put(SnapshotTables.COLUMN_ITEM_SYNC_GENERATION, item.syncGeneration)
                            putNullableLong(SnapshotTables.COLUMN_SIZE_BYTES, item.sizeBytes)
                        },
                    )
                }

                nextState.deletedMedia.forEach { deletedMedia ->
                    insertOrThrow(
                        SnapshotTables.TABLE_DELETED_MEDIA,
                        null,
                        ContentValues().apply {
                            put(SnapshotTables.COLUMN_CACHE_KEY, cacheKey)
                            put(SnapshotTables.COLUMN_MEDIA_ID, deletedMedia.mediaId)
                            put(SnapshotTables.COLUMN_ITEM_SYNC_GENERATION, deletedMedia.syncGeneration)
                        },
                    )
                }
            }

            PersistedState(
                state = nextState,
                refreshedAtMillis = refreshedAtMillis,
            )
        }
    }

    private fun readPersistedState(database: SQLiteDatabase): PersistedState? {
        val metadata = database.query(
            SnapshotTables.TABLE_CACHE_STATE,
            arrayOf(
                SnapshotTables.COLUMN_LAST_SYNC_GENERATION,
                SnapshotTables.COLUMN_REFRESHED_AT_MILLIS,
            ),
            "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
            arrayOf(cacheKey),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            CacheMetadata(
                lastSyncGeneration = cursor.getLong(0),
                refreshedAtMillis = cursor.getLong(1),
            )
        }

        return PersistedState(
            state = SafMediaSnapshotState(
                current = SafMediaSnapshot(
                    mediaItems = readMediaItems(database),
                    lastSyncGeneration = metadata.lastSyncGeneration,
                ),
                lastSyncGeneration = metadata.lastSyncGeneration,
                deletedMedia = readDeletedMedia(database),
            ),
            refreshedAtMillis = metadata.refreshedAtMillis,
        )
    }

    private fun readMediaItems(database: SQLiteDatabase): List<SafMediaItem> =
        database.query(
            SnapshotTables.TABLE_MEDIA_ITEMS,
            arrayOf(
                SnapshotTables.COLUMN_MEDIA_ID,
                SnapshotTables.COLUMN_DOCUMENT_URI,
                SnapshotTables.COLUMN_DISPLAY_NAME,
                SnapshotTables.COLUMN_MIME_TYPE,
                SnapshotTables.COLUMN_DATE_TAKEN_MILLIS,
                SnapshotTables.COLUMN_ITEM_SYNC_GENERATION,
                SnapshotTables.COLUMN_SIZE_BYTES,
            ),
            "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
            arrayOf(cacheKey),
            null,
            null,
            "${SnapshotTables.COLUMN_ITEM_SYNC_GENERATION} DESC, ${SnapshotTables.COLUMN_MEDIA_ID} ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SafMediaItem(
                            mediaId = cursor.getString(0),
                            documentUri = cursor.getString(1).toUri(),
                            displayName = cursor.getString(2),
                            mimeType = cursor.getString(3),
                            dateTakenMillis = cursor.getNullableLong(4),
                            syncGeneration = cursor.getLong(5),
                            sizeBytes = cursor.getNullableLong(6),
                        ),
                    )
                }
            }
        }

    private fun readDeletedMedia(database: SQLiteDatabase): List<DeletedMedia> =
        database.query(
            SnapshotTables.TABLE_DELETED_MEDIA,
            arrayOf(
                SnapshotTables.COLUMN_MEDIA_ID,
                SnapshotTables.COLUMN_ITEM_SYNC_GENERATION,
            ),
            "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
            arrayOf(cacheKey),
            null,
            null,
            "${SnapshotTables.COLUMN_ITEM_SYNC_GENERATION} ASC, ${SnapshotTables.COLUMN_MEDIA_ID} ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DeletedMedia(
                            mediaId = cursor.getString(0),
                            syncGeneration = cursor.getLong(1),
                        ),
                    )
                }
            }
        }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun android.database.Cursor.getNullableLong(index: Int): Long? =
        if (isNull(index)) {
            null
        } else {
            getLong(index)
        }

    private inline fun <T> SQLiteDatabase.useDatabase(
        block: (SQLiteDatabase) -> T,
    ): T = block(this)

    private data class CacheMetadata(
        val lastSyncGeneration: Long,
        val refreshedAtMillis: Long,
    )

    private data class PersistedState(
        val state: SafMediaSnapshotState,
        val refreshedAtMillis: Long,
    )

    private class SnapshotDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE ${SnapshotTables.TABLE_CACHE_STATE} (
                    ${SnapshotTables.COLUMN_CACHE_KEY} TEXT PRIMARY KEY NOT NULL,
                    ${SnapshotTables.COLUMN_LAST_SYNC_GENERATION} INTEGER NOT NULL,
                    ${SnapshotTables.COLUMN_REFRESHED_AT_MILLIS} INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE ${SnapshotTables.TABLE_MEDIA_ITEMS} (
                    ${SnapshotTables.COLUMN_CACHE_KEY} TEXT NOT NULL,
                    ${SnapshotTables.COLUMN_MEDIA_ID} TEXT NOT NULL,
                    ${SnapshotTables.COLUMN_DOCUMENT_URI} TEXT NOT NULL,
                    ${SnapshotTables.COLUMN_DISPLAY_NAME} TEXT,
                    ${SnapshotTables.COLUMN_MIME_TYPE} TEXT NOT NULL,
                    ${SnapshotTables.COLUMN_DATE_TAKEN_MILLIS} INTEGER,
                    ${SnapshotTables.COLUMN_ITEM_SYNC_GENERATION} INTEGER NOT NULL,
                    ${SnapshotTables.COLUMN_SIZE_BYTES} INTEGER,
                    PRIMARY KEY (${SnapshotTables.COLUMN_CACHE_KEY}, ${SnapshotTables.COLUMN_MEDIA_ID})
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE ${SnapshotTables.TABLE_DELETED_MEDIA} (
                    ${SnapshotTables.COLUMN_CACHE_KEY} TEXT NOT NULL,
                    ${SnapshotTables.COLUMN_MEDIA_ID} TEXT NOT NULL,
                    ${SnapshotTables.COLUMN_ITEM_SYNC_GENERATION} INTEGER NOT NULL,
                    PRIMARY KEY (${SnapshotTables.COLUMN_CACHE_KEY}, ${SnapshotTables.COLUMN_MEDIA_ID})
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            db.execSQL("DROP TABLE IF EXISTS ${SnapshotTables.TABLE_DELETED_MEDIA}")
            db.execSQL("DROP TABLE IF EXISTS ${SnapshotTables.TABLE_MEDIA_ITEMS}")
            db.execSQL("DROP TABLE IF EXISTS ${SnapshotTables.TABLE_CACHE_STATE}")
            onCreate(db)
        }
    }

    private object SnapshotTables {
        const val TABLE_CACHE_STATE = "snapshot_cache_state"
        const val TABLE_MEDIA_ITEMS = "snapshot_media_items"
        const val TABLE_DELETED_MEDIA = "snapshot_deleted_media"

        const val COLUMN_CACHE_KEY = "cache_key"
        const val COLUMN_LAST_SYNC_GENERATION = "last_sync_generation"
        const val COLUMN_REFRESHED_AT_MILLIS = "refreshed_at_millis"

        const val COLUMN_MEDIA_ID = "media_id"
        const val COLUMN_DOCUMENT_URI = "document_uri"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_DATE_TAKEN_MILLIS = "date_taken_millis"
        const val COLUMN_ITEM_SYNC_GENERATION = "item_sync_generation"
        const val COLUMN_SIZE_BYTES = "size_bytes"
    }

    companion object {
        internal const val DATABASE_NAME = "saf-media-snapshots.db"
        private const val DATABASE_VERSION = 1
        private const val REFRESH_INTERVAL_MILLIS = 5_000L
    }
}
