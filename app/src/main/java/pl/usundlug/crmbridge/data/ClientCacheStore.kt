package pl.usundlug.crmbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import pl.usundlug.crmbridge.sync.PayloadCipher

/**
 * Encrypted, phone-indexed client cache used only as an offline fallback.
 * The normalized phone is never stored in plaintext. HMAC-SHA256 with a Keystore-held secret is used as the
 * deterministic lookup key while the full record is encrypted with Android Keystore.
 */
class ClientCacheStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val cipher = PayloadCipher()
    private val lookupKey = PhoneLookupKey()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE client_cache (
                phone_hash TEXT PRIMARY KEY,
                client_id INTEGER NULL,
                payload_encrypted TEXT NOT NULL,
                updated_at_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_client_cache_client_id ON client_cache(client_id)")
        db.execSQL(
            """
            CREATE TABLE cache_meta (
                meta_key TEXT PRIMARY KEY,
                meta_value TEXT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun put(client: ClientMatch, updatedAtEpochMs: Long = System.currentTimeMillis()) {
        if (!client.matched) return
        val phone = client.normalizedPhone?.takeIf { it.isNotBlank() } ?: return
        val payload = JSONObject()
            .put("matched", true)
            .put("client_id", client.clientId ?: JSONObject.NULL)
            .put("client_name", client.clientName ?: JSONObject.NULL)
            .put("phone", phone)
            .put("product", client.product ?: JSONObject.NULL)
            .put("stage", client.stage ?: JSONObject.NULL)
            .put("guardian_name", client.guardianName ?: JSONObject.NULL)
            .put("overdue_invoices_count", client.overdueInvoicesCount)
            .put("overdue_amount", client.overdueAmount)
            .put("currency", client.currency)
            .put("updated_at_epoch_ms", updatedAtEpochMs)

        val values = ContentValues().apply {
            put("phone_hash", lookupKey.forPhone(phone))
            if (client.clientId != null) put("client_id", client.clientId) else putNull("client_id")
            put("payload_encrypted", cipher.encrypt(payload.toString()))
            put("updated_at_epoch_ms", updatedAtEpochMs)
        }
        writableDatabase.insertWithOnConflict(
            "client_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun find(phone: String): ClientMatch? {
        val row = readableDatabase.query(
            "client_cache",
            arrayOf("payload_encrypted", "updated_at_epoch_ms"),
            "phone_hash = ?",
            arrayOf(lookupKey.forPhone(phone)),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0) to cursor.getLong(1)
        } ?: return null

        if (System.currentTimeMillis() - row.second > MAX_CACHE_AGE_MS) {
            remove(phone)
            return null
        }

        return runCatching {
            val json = JSONObject(cipher.decrypt(row.first))
            ClientMatch(
                matched = true,
                clientId = json.optLongOrNull("client_id"),
                clientName = json.optStringOrNull("client_name"),
                product = json.optStringOrNull("product"),
                stage = json.optStringOrNull("stage"),
                guardianName = json.optStringOrNull("guardian_name"),
                normalizedPhone = json.optStringOrNull("phone") ?: phone,
                overdueInvoicesCount = json.optInt("overdue_invoices_count", 0),
                overdueAmount = json.optDouble("overdue_amount", 0.0),
                currency = json.optStringOrNull("currency") ?: "PLN",
                dataSource = ClientDataSource.CACHE,
                dataUpdatedAtEpochMs = json.optLong("updated_at_epoch_ms", row.second)
            )
        }.getOrNull()
    }

    @Synchronized
    fun findByClientId(clientId: Long): ClientMatch? {
        val row = readableDatabase.query(
            "client_cache",
            arrayOf("payload_encrypted", "updated_at_epoch_ms"),
            "client_id = ?",
            arrayOf(clientId.toString()),
            null,
            null,
            "updated_at_epoch_ms DESC",
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0) to cursor.getLong(1)
        } ?: return null

        if (System.currentTimeMillis() - row.second > MAX_CACHE_AGE_MS) return null

        return runCatching {
            val json = JSONObject(cipher.decrypt(row.first))
            ClientMatch(
                matched = true,
                clientId = json.optLongOrNull("client_id"),
                clientName = json.optStringOrNull("client_name"),
                product = json.optStringOrNull("product"),
                stage = json.optStringOrNull("stage"),
                guardianName = json.optStringOrNull("guardian_name"),
                normalizedPhone = json.optStringOrNull("phone"),
                overdueInvoicesCount = json.optInt("overdue_invoices_count", 0),
                overdueAmount = json.optDouble("overdue_amount", 0.0),
                currency = json.optStringOrNull("currency") ?: "PLN",
                dataSource = ClientDataSource.CACHE,
                dataUpdatedAtEpochMs = json.optLong("updated_at_epoch_ms", row.second)
            )
        }.getOrNull()
    }

    @Synchronized
    fun remove(phone: String) {
        writableDatabase.delete("client_cache", "phone_hash = ?", arrayOf(lookupKey.forPhone(phone)))
    }

    @Synchronized
    fun removeByClientIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            writableDatabase.delete(
                "client_cache",
                "client_id IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray()
            )
        }
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("client_cache", null, null)
        writableDatabase.delete("cache_meta", null, null)
    }

    @Synchronized
    fun pruneExpired() {
        val threshold = System.currentTimeMillis() - MAX_CACHE_AGE_MS
        writableDatabase.delete(
            "client_cache",
            "updated_at_epoch_ms < ?",
            arrayOf(threshold.toString())
        )
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM client_cache", null)
        .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    var lastSuccessfulSyncEpochMs: Long
        get() = getMeta(META_LAST_SYNC)?.toLongOrNull() ?: 0L
        set(value) = putMeta(META_LAST_SYNC, value.toString())

    private fun getMeta(key: String): String? = readableDatabase.query(
        "cache_meta",
        arrayOf("meta_value"),
        "meta_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun putMeta(key: String, value: String?) {
        val values = ContentValues().apply {
            put("meta_key", key)
            put("meta_value", value)
        }
        writableDatabase.insertWithOnConflict(
            "cache_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }


    companion object {
        private const val DB_NAME = "crm_client_cache.db"
        private const val DB_VERSION = 1
        private const val META_LAST_SYNC = "last_successful_sync_epoch_ms"
        private const val MAX_CACHE_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key).takeIf { it > 0 }
