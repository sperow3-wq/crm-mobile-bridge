package pl.usundlug.crmbridge.device

import android.content.Context
import pl.usundlug.crmbridge.data.CallDirection
import pl.usundlug.crmbridge.data.CallSession

class CallSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("crm_mobile_call_session", Context.MODE_PRIVATE)

    fun save(session: CallSession) {
        prefs.edit()
            .putString(KEY_EVENT_UUID, session.eventUuid)
            .putLong(KEY_CLIENT_ID, session.clientId ?: -1L)
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_DIRECTION, session.direction.name)
            .putLong(KEY_STARTED_AT, session.startedAtEpochMs)
            .apply()
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

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_EVENT_UUID = "event_uuid"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_PHONE = "phone"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_STARTED_AT = "started_at"
    }
}
