package net.trajano.cloudmediaproviderproxy.provider

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.transaction
import androidx.core.net.toUri
import androidx.core.database.getLongOrNull
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
    private val onSnapshotChanged: () -> Unit = {},
    private val initializeInBackground: Boolean = false,
    private val requestBackgroundRefresh: (() -> Unit)? = null,
    databaseName: String = DATABASE_NAME,
) : SafMediaSnapshotCache {

    private val databaseHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context.applicationContext)
            .name(databaseName).callback(SnapshotDatabaseCallback()).build(),
    )
    private val refreshRunning = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val initializationLock = Any()
    @Volatile
    private var cached: PersistedState? = null
    @Volatile
    private var invalidated = false

    /** Close storage after any current commit and reject subsequent refresh results. */
    @Synchronized
    override fun invalidate() {
        invalidated = true
        executor.shutdownNow()
        databaseHelper.close()
    }

    override fun currentStateSnapshot(): SafMediaSnapshotState {
        check(!invalidated) { CACHE_CLEARED_MESSAGE }
        val initializing = cached == null
        val persistedState = cached ?: synchronized(initializationLock) {
            cached ?: (readPersistedState() ?: updateState(
                if (initializeInBackground) SafMediaSnapshot(emptyList(), 0L) else snapshotLoader(),
            )).also {
                cached = it
                // A different collection can contain zero items. Announce its first committed
                // snapshot, including one restored from disk, so Android discards the old list.
                // Queue the callback so an OS query can return before the notification calls back.
                executor.execute { if (!invalidated) onSnapshotChanged() }
            }
        }

        val age = clock() - persistedState.refreshedAtMillis
        if ((initializeInBackground && initializing) || age < 0L || age >= refreshIntervalMillis) {
            refreshAsync()
        }

        return persistedState.state
    }

    override fun requestRefresh() {
        if (invalidated) return
        refreshRequested.set(true)
        refreshAsync()
    }

    private fun refreshAsync() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return
        }

        if (requestBackgroundRefresh != null) {
            refreshRequested.set(false)
            try { requestBackgroundRefresh.invoke() }
            catch (error: RuntimeException) { refreshRunning.set(false); throw error }
            return
        }

        try {
            executor.execute {
                try {
                    do {
                        refreshRequested.set(false)
                        val changed = synchronized(initializationLock) {
                            val previousState = cached?.state
                            updateState(snapshotLoader()).state !== previousState
                        }
                        if (changed && !invalidated) onSnapshotChanged()
                    } while (refreshRequested.get() && !invalidated)
                } catch (error: Exception) {
                    // A failed provider may notify on every attempt; wait for the next request to retry.
                    refreshRequested.set(false)
                    Log.w("SafSnapshotCache", "Unable to refresh media; retaining cached snapshot", error)
                } finally {
                    refreshRunning.set(false)
                    if (refreshRequested.get() && !invalidated) refreshAsync()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            refreshRunning.set(false)
        }
    }

    fun beginScheduledRefresh() {
        refreshRunning.set(true)
        refreshRequested.set(false)
    }

    fun finishScheduledRefresh() {
        refreshRunning.set(false)
        if (!invalidated && refreshRequested.getAndSet(false)) refreshAsync()
    }

    fun publishSnapshot(snapshot: SafMediaSnapshot) {
        val changed = synchronized(initializationLock) {
            val previous = cached?.state
            updateState(snapshot).state !== previous
        }
        if (changed && !invalidated) onSnapshotChanged()
    }

    @Synchronized
    fun savePreparationSource(token: String, snapshot: SafMediaSnapshot) {
        check(!invalidated) { CACHE_CLEARED_MESSAGE }
        val database = databaseHelper.writableDatabase
        val sourceKey = "source:$cacheKey"
        database.transaction {
            delete(SnapshotTables.TABLE_MEDIA_ITEMS, "cache_key = ?", arrayOf(sourceKey))
            snapshot.mediaItems.forEach { item ->
                insert(SnapshotTables.TABLE_MEDIA_ITEMS, SQLiteDatabase.CONFLICT_ABORT, mediaValues(item, sourceKey))
            }
            insert("preparation_checkpoints", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
                put("cache_key", cacheKey)
                put("token", token)
                put("last_sync_generation", snapshot.lastSyncGeneration)
            })
        }
    }

    @Synchronized
    fun preparationSource(token: String): SafMediaSnapshot? {
        check(!invalidated) { CACHE_CLEARED_MESSAGE }
        val database = databaseHelper.readableDatabase
        return database.transaction {
            val generation = query("SELECT last_sync_generation FROM preparation_checkpoints WHERE cache_key = ? AND token = ?",
                arrayOf(cacheKey, token)).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                ?: return@transaction null
            SafMediaSnapshot(readMediaItems(this, "source:$cacheKey"), generation)
        }
    }

    /** Read disk once per cache instance, serialized with invalidation and commits. */
    @Synchronized
    private fun readPersistedState(): PersistedState? {
        check(!invalidated) { CACHE_CLEARED_MESSAGE }
        return databaseHelper.readableDatabase.useDatabase(::readPersistedState)
    }

    @Synchronized
    private fun updateState(snapshot: SafMediaSnapshot): PersistedState {
        check(!invalidated) { CACHE_CLEARED_MESSAGE }
        return databaseHelper.writableDatabase.useDatabase { database ->
            val persistedState = cached?.state ?: readPersistedState(database)?.state
            val nextState = persistedState?.refresh(snapshot) ?: SafMediaSnapshotState.initial(snapshot)
            val refreshedAtMillis = clock()

            if (nextState === persistedState) {
                database.update(
                    SnapshotTables.TABLE_CACHE_STATE,
                    SQLiteDatabase.CONFLICT_ABORT,
                    ContentValues().apply { put(SnapshotTables.COLUMN_REFRESHED_AT_MILLIS, refreshedAtMillis) },
                    "${SnapshotTables.COLUMN_CACHE_KEY} = ?",
                    arrayOf(cacheKey),
                )
                return@useDatabase PersistedState(nextState, refreshedAtMillis).also { cached = it }
            }

            database.transaction {
                val previousMedia = persistedState?.current?.mediaById.orEmpty()
                previousMedia.keys.filterNot(nextState.current.mediaById::containsKey).forEach { id ->
                    delete(SnapshotTables.TABLE_MEDIA_ITEMS, "cache_key = ? AND media_id = ?", arrayOf(cacheKey, id))
                }
                val previousDeleted = persistedState?.deletedMedia.orEmpty().associateBy { it.mediaId }
                val nextDeleted = nextState.deletedMedia.associateBy { it.mediaId }
                previousDeleted.keys.filterNot(nextDeleted::containsKey).forEach { id ->
                    delete(SnapshotTables.TABLE_DELETED_MEDIA, "cache_key = ? AND media_id = ?", arrayOf(cacheKey, id))
                }

                insert(
                    SnapshotTables.TABLE_CACHE_STATE,
                    SQLiteDatabase.CONFLICT_REPLACE,
                    ContentValues().apply {
                        put(SnapshotTables.COLUMN_CACHE_KEY, cacheKey)
                        put(SnapshotTables.COLUMN_LAST_SYNC_GENERATION, nextState.lastSyncGeneration)
                        put(SnapshotTables.COLUMN_REFRESHED_AT_MILLIS, refreshedAtMillis)
                    },
                )

                nextState.current.mediaItems.filter { it != previousMedia[it.mediaId] }.forEach { item ->
                    insert(SnapshotTables.TABLE_MEDIA_ITEMS, SQLiteDatabase.CONFLICT_REPLACE, mediaValues(item))
                }

                nextState.deletedMedia.filter { it != previousDeleted[it.mediaId] }.forEach { deletedMedia ->
                    insert(
                        SnapshotTables.TABLE_DELETED_MEDIA,
                        SQLiteDatabase.CONFLICT_REPLACE,
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
            ).also { cached = it }
        }
    }

    private fun readPersistedState(database: SupportSQLiteDatabase): PersistedState? {
        val metadata = database.query(
            SupportSQLiteQueryBuilder.builder(SnapshotTables.TABLE_CACHE_STATE).columns(arrayOf(
                SnapshotTables.COLUMN_LAST_SYNC_GENERATION,
                SnapshotTables.COLUMN_REFRESHED_AT_MILLIS,
            )).selection("${SnapshotTables.COLUMN_CACHE_KEY} = ?", arrayOf(cacheKey)).create(),
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

    private fun readMediaItems(database: SupportSQLiteDatabase, key: String = cacheKey): List<SafMediaItem> =
        database.query(
            SupportSQLiteQueryBuilder.builder(SnapshotTables.TABLE_MEDIA_ITEMS).columns(arrayOf(
                SnapshotTables.COLUMN_MEDIA_ID,
                SnapshotTables.COLUMN_DOCUMENT_URI,
                SnapshotTables.COLUMN_DISPLAY_NAME,
                SnapshotTables.COLUMN_MIME_TYPE,
                SnapshotTables.COLUMN_DATE_TAKEN_MILLIS,
                SnapshotTables.COLUMN_ITEM_SYNC_GENERATION,
                SnapshotTables.COLUMN_SIZE_BYTES,
                SnapshotTables.COLUMN_MEDIA_STORE_URI,
            )).selection("${SnapshotTables.COLUMN_CACHE_KEY} = ?", arrayOf(key))
                .orderBy("${SnapshotTables.COLUMN_ITEM_SYNC_GENERATION} DESC, ${SnapshotTables.COLUMN_MEDIA_ID} ASC")
                .create(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SafMediaItem(
                            mediaId = cursor.getString(0),
                            documentUri = cursor.getString(1).toUri(),
                            displayName = cursor.getString(2),
                            mimeType = cursor.getString(3),
                            dateTakenMillis = cursor.getLongOrNull(4),
                            syncGeneration = cursor.getLong(5),
                            sizeBytes = cursor.getLongOrNull(6),
                            mediaStoreUri = cursor.getString(7)?.toUri(),
                        ),
                    )
                }
            }
        }

    private fun readDeletedMedia(database: SupportSQLiteDatabase): List<DeletedMedia> =
        database.query(
            SupportSQLiteQueryBuilder.builder(SnapshotTables.TABLE_DELETED_MEDIA).columns(arrayOf(
                SnapshotTables.COLUMN_MEDIA_ID,
                SnapshotTables.COLUMN_ITEM_SYNC_GENERATION,
            )).selection("${SnapshotTables.COLUMN_CACHE_KEY} = ?", arrayOf(cacheKey))
                .orderBy("${SnapshotTables.COLUMN_ITEM_SYNC_GENERATION} ASC, ${SnapshotTables.COLUMN_MEDIA_ID} ASC")
                .create(),
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

    private fun mediaValues(item: SafMediaItem, key: String = cacheKey) = ContentValues().apply {
        put(SnapshotTables.COLUMN_CACHE_KEY, key)
        put(SnapshotTables.COLUMN_MEDIA_ID, item.mediaId)
        put(SnapshotTables.COLUMN_DOCUMENT_URI, item.documentUri.toString())
        put(SnapshotTables.COLUMN_DISPLAY_NAME, item.displayName)
        put(SnapshotTables.COLUMN_MIME_TYPE, item.mimeType)
        putNullableLong(SnapshotTables.COLUMN_DATE_TAKEN_MILLIS, item.dateTakenMillis)
        put(SnapshotTables.COLUMN_ITEM_SYNC_GENERATION, item.syncGeneration)
        putNullableLong(SnapshotTables.COLUMN_SIZE_BYTES, item.sizeBytes)
        put(SnapshotTables.COLUMN_MEDIA_STORE_URI, item.mediaStoreUri?.toString())
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private inline fun <T> SupportSQLiteDatabase.useDatabase(
        block: (SupportSQLiteDatabase) -> T,
    ): T = block(this)

    private data class CacheMetadata(
        val lastSyncGeneration: Long,
        val refreshedAtMillis: Long,
    )

    private data class PersistedState(
        val state: SafMediaSnapshotState,
        val refreshedAtMillis: Long,
    )

    private class SnapshotDatabaseCallback : SupportSQLiteOpenHelper.Callback(DATABASE_VERSION) {

        private fun createPreparationTable(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE preparation_checkpoints (cache_key TEXT PRIMARY KEY NOT NULL, " +
                "token TEXT NOT NULL, last_sync_generation INTEGER NOT NULL)")
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            createPreparationTable(db)
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
                    ${SnapshotTables.COLUMN_MEDIA_STORE_URI} TEXT,
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
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            if (oldVersion < 3) createPreparationTable(db)
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE ${SnapshotTables.TABLE_MEDIA_ITEMS} " +
                    "ADD COLUMN ${SnapshotTables.COLUMN_MEDIA_STORE_URI} TEXT")
            }
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
        const val COLUMN_MEDIA_STORE_URI = "media_store_uri"
    }

    companion object {
        internal const val DATABASE_NAME = "saf-media-snapshots.db"
        private const val CACHE_CLEARED_MESSAGE = "Media cache was cleared"
        private const val DATABASE_VERSION = 3
        private const val REFRESH_INTERVAL_MILLIS = 5_000L
    }
}
