package pl.usundlug.crmbridge.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingSyncEvent(
    val id: Long,
    val dedupeKey: String,
    val endpoint: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val attempts: Int,
    val state: String,
    val lastError: String?
)

class SyncQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val payloadCipher = PayloadCipher()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe_key TEXT NOT NULL UNIQUE,
                endpoint TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at_epoch_ms INTEGER NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'pending',
                last_error TEXT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sync_queue_state_updated ON sync_queue(state, updated_at_epoch_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun enqueueOrReplace(dedupeKey: String, endpoint: String, payloadJson: String) {
        val now = System.currentTimeMillis()
        val existing = readableDatabase.query(
            "sync_queue",
            arrayOf("id", "created_at_epoch_ms"),
            "dedupe_key = ?",
            arrayOf(dedupeKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0) to cursor.getLong(1)
            } else null
        }

        val values = ContentValues().apply {
            put("dedupe_key", dedupeKey)
            put("endpoint", endpoint)
            put("payload_json", payloadCipher.encrypt(payloadJson))
            put("created_at_epoch_ms", existing?.second ?: now)
            put("updated_at_epoch_ms", now)
            put("attempts", 0)
            put("state", STATE_PENDING)
            putNull("last_error")
        }

        if (existing == null) {
            writableDatabase.insertOrThrow("sync_queue", null, values)
        } else {
            writableDatabase.update("sync_queue", values, "id = ?", arrayOf(existing.first.toString()))
        }
    }

    @Synchronized
    fun pending(limit: Int = 50): List<PendingSyncEvent> {
        return readableDatabase.query(
            "sync_queue",
            COLUMNS,
            "state = ?",
            arrayOf(STATE_PENDING),
            null,
            null,
            "updated_at_epoch_ms ASC",
            limit.coerceIn(1, 200).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingSyncEvent(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            dedupeKey = cursor.getString(cursor.getColumnIndexOrThrow("dedupe_key")),
                            endpoint = cursor.getString(cursor.getColumnIndexOrThrow("endpoint")),
                            payloadJson = payloadCipher.decrypt(cursor.getString(cursor.getColumnIndexOrThrow("payload_json"))),
                            createdAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_epoch_ms")),
                            updatedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_epoch_ms")),
                            attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),
                            state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                            lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error"))
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun findByDedupeKey(dedupeKey: String): PendingSyncEvent? {
        return readableDatabase.query(
            "sync_queue",
            COLUMNS,
            "dedupe_key = ?",
            arrayOf(dedupeKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            PendingSyncEvent(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                dedupeKey = cursor.getString(cursor.getColumnIndexOrThrow("dedupe_key")),
                endpoint = cursor.getString(cursor.getColumnIndexOrThrow("endpoint")),
                payloadJson = payloadCipher.decrypt(cursor.getString(cursor.getColumnIndexOrThrow("payload_json"))),
                createdAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_epoch_ms")),
                updatedAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_epoch_ms")),
                attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error"))
            )
        }
    }

    @Synchronized
    fun removeIfUnchanged(id: Long, updatedAtEpochMs: Long): Boolean {
        return writableDatabase.delete(
            "sync_queue",
            "id = ? AND updated_at_epoch_ms = ?",
            arrayOf(id.toString(), updatedAtEpochMs.toString())
        ) > 0
    }

    @Synchronized
    fun markRetryIfUnchanged(id: Long, updatedAtEpochMs: Long, message: String?): Boolean {
        val values = ContentValues().apply {
            put("attempts", currentAttempts(id) + 1)
            put("last_error", message?.take(1000))
            put("state", STATE_PENDING)
            // Deliberately keep updated_at unchanged: it is the optimistic-lock version
            // for the business payload, not a retry timestamp.
        }
        return writableDatabase.update(
            "sync_queue",
            values,
            "id = ? AND updated_at_epoch_ms = ?",
            arrayOf(id.toString(), updatedAtEpochMs.toString())
        ) > 0
    }

    @Synchronized
    fun markBlockedIfUnchanged(id: Long, updatedAtEpochMs: Long, message: String?): Boolean {
        val values = ContentValues().apply {
            put("state", STATE_BLOCKED)
            put("last_error", message?.take(1000))
        }
        return writableDatabase.update(
            "sync_queue",
            values,
            "id = ? AND updated_at_epoch_ms = ?",
            arrayOf(id.toString(), updatedAtEpochMs.toString())
        ) > 0
    }

    private fun currentAttempts(id: Long): Int = readableDatabase.rawQuery(
        "SELECT attempts FROM sync_queue WHERE id = ?",
        arrayOf(id.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun retryBlocked() {
        val values = ContentValues().apply {
            put("state", STATE_PENDING)
            put("attempts", 0)
            put("updated_at_epoch_ms", System.currentTimeMillis())
            putNull("last_error")
        }
        writableDatabase.update("sync_queue", values, "state = ?", arrayOf(STATE_BLOCKED))
    }

    fun countPending(): Int = countByState(STATE_PENDING)
    fun countBlocked(): Int = countByState(STATE_BLOCKED)

    private fun countByState(state: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sync_queue WHERE state = ?",
        arrayOf(state)
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    companion object {
        private const val DB_NAME = "crm_mobile_bridge.db"
        private const val DB_VERSION = 1
        private const val STATE_PENDING = "pending"
        private const val STATE_BLOCKED = "blocked"
        private val COLUMNS = arrayOf(
            "id", "dedupe_key", "endpoint", "payload_json", "created_at_epoch_ms",
            "updated_at_epoch_ms", "attempts", "state", "last_error"
        )
    }
}
