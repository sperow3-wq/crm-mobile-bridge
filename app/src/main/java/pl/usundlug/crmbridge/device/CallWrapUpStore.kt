package pl.usundlug.crmbridge.device

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import pl.usundlug.crmbridge.data.CallDirection
import pl.usundlug.crmbridge.data.CompletedCallContext
import pl.usundlug.crmbridge.sync.PayloadCipher

class CallWrapUpStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val cipher = PayloadCipher()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pending_call_wrapups (
                event_uuid TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                created_at_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_call_wrapups_created ON pending_call_wrapups(created_at_epoch_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun save(call: CompletedCallContext) {
        val json = JSONObject()
            .put("event_uuid", call.eventUuid)
            .put("client_id", call.clientId ?: JSONObject.NULL)
            .put("client_name", call.clientName ?: JSONObject.NULL)
            .put("phone", call.phone)
            .put("direction", call.direction.name)
            .put("status", call.status)
            .put("started_at_epoch_ms", call.startedAtEpochMs)
            .put("ended_at_epoch_ms", call.endedAtEpochMs)
            .put("duration_seconds", call.durationSeconds)

        val values = ContentValues().apply {
            put("event_uuid", call.eventUuid)
            put("payload", cipher.encrypt(json.toString()))
            put("created_at_epoch_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "pending_call_wrapups",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        prune()
    }

    @Synchronized
    fun get(eventUuid: String): CompletedCallContext? {
        return readableDatabase.query(
            "pending_call_wrapups",
            arrayOf("payload"),
            "event_uuid = ?",
            arrayOf(eventUuid),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val json = JSONObject(cipher.decrypt(cursor.getString(0)))
            CompletedCallContext(
                eventUuid = json.getString("event_uuid"),
                clientId = json.optLong("client_id", -1L).takeIf { it > 0L },
                clientName = json.optString("client_name").takeIf { it.isNotBlank() && it != "null" },
                phone = json.getString("phone"),
                direction = runCatching { CallDirection.valueOf(json.getString("direction")) }
                    .getOrDefault(CallDirection.INCOMING),
                status = json.optString("status", "completed"),
                startedAtEpochMs = json.optLong("started_at_epoch_ms", 0L),
                endedAtEpochMs = json.optLong("ended_at_epoch_ms", 0L),
                durationSeconds = json.optLong("duration_seconds", 0L)
            )
        }
    }

    @Synchronized
    fun latest(): CompletedCallContext? {
        val eventUuid = readableDatabase.query(
            "pending_call_wrapups",
            arrayOf("event_uuid"),
            null,
            null,
            null,
            null,
            "created_at_epoch_ms DESC",
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return eventUuid?.let(::get)
    }

    @Synchronized
    fun remove(eventUuid: String) {
        writableDatabase.delete("pending_call_wrapups", "event_uuid = ?", arrayOf(eventUuid))
    }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM pending_call_wrapups",
        emptyArray<String>()
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun clear() {
        writableDatabase.delete("pending_call_wrapups", null, null)
    }

    @Synchronized
    private fun prune() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        writableDatabase.delete(
            "pending_call_wrapups",
            "created_at_epoch_ms < ?",
            arrayOf(cutoff.toString())
        )
    }

    companion object {
        private const val DB_NAME = "crm_mobile_wrapups.db"
        private const val DB_VERSION = 1
        private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
