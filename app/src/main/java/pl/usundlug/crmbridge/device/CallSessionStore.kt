package pl.usundlug.crmbridge.device

import android.content.Context
import pl.usundlug.crmbridge.data.CallDirection
import pl.usundlug.crmbridge.data.CallSession

class CallSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("crm_mobile_call_session", Context.MODE_PRIVATE)

    fun save(session: CallSession) {
        val previousUuid = prefs.getString(KEY_EVENT_UUID, null)
        val edit = prefs.edit()
            .putString(KEY_EVENT_UUID, session.eventUuid)
            .putLong(KEY_CLIENT_ID, session.clientId ?: -1L)
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_DIRECTION, session.direction.name)
            .putLong(KEY_STARTED_AT, session.startedAtEpochMs)

        if (previousUuid != session.eventUuid) {
            edit
                .putBoolean(KEY_WAS_ANSWERED, false)
                .remove(KEY_ANSWERED_AT)
                .putBoolean(KEY_REJECTED_BY_APP, false)
        }
        edit.apply()
    }

    fun current(): CallSession? {
        val eventUuid = prefs.getString(KEY_EVENT_UUID, null) ?: return null
        val phone = prefs.getString(KEY_PHONE, null) ?: return null
        val direction = runCatching {
            CallDirection.valueOf(prefs.getString(KEY_DIRECTION, CallDirection.INCOMING.name)!!)
        }.getOrDefault(CallDirection.INCOMING)
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (startedAt <= 0L) return null

        return CallSession(
            eventUuid = eventUuid,
            clientId = prefs.getLong(KEY_CLIENT_ID, -1L).takeIf { it > 0 },
            phone = phone,
            direction = direction,
            startedAtEpochMs = startedAt
        )
    }

    fun markAnswered(atEpochMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_ANSWERED_AT, atEpochMs.coerceAtLeast(0L))
            .putBoolean(KEY_WAS_ANSWERED, true)
            .apply()
    }

    fun wasAnswered(): Boolean = prefs.getBoolean(KEY_WAS_ANSWERED, false)

    fun answeredAtEpochMs(): Long? =
        prefs.getLong(KEY_ANSWERED_AT, 0L).takeIf { it > 0L }

    fun markRejectedByApp() {
        prefs.edit().putBoolean(KEY_REJECTED_BY_APP, true).apply()
    }

    fun wasRejectedByApp(): Boolean = prefs.getBoolean(KEY_REJECTED_BY_APP, false)

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun clearIf(eventUuid: String) {
        if (prefs.getString(KEY_EVENT_UUID, null) == eventUuid) {
            prefs.edit().clear().apply()
        }
    }

    companion object {
        private const val KEY_EVENT_UUID = "event_uuid"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_WAS_ANSWERED = "was_answered"
        private const val KEY_ANSWERED_AT = "answered_at"
        private const val KEY_REJECTED_BY_APP = "rejected_by_app"
    }
}
