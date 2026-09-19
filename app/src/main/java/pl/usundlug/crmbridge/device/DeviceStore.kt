package pl.usundlug.crmbridge.device

import android.content.Context
import android.telephony.SubscriptionManager
import java.util.UUID

class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("crm_mobile_bridge", Context.MODE_PRIVATE)

    val deviceUuid: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_UUID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, created).apply()
            return created
        }

    var employeeId: Long?
        get() = prefs.getLong(KEY_EMPLOYEE_ID, -1L).takeIf { it > 0 }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_EMPLOYEE_ID) else putLong(KEY_EMPLOYEE_ID, value)
            }.apply()
        }

    var employeeName: String?
        get() = prefs.getString(KEY_EMPLOYEE_NAME, null)
        set(value) = prefs.edit().putString(KEY_EMPLOYEE_NAME, value).apply()

    var servicePhone: String?
        get() = prefs.getString(KEY_SERVICE_PHONE, null)
        set(value) = prefs.edit().putString(KEY_SERVICE_PHONE, value).apply()

    var serviceSubscriptionId: Int?
        get() = prefs.getInt(KEY_SERVICE_SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SERVICE_SUBSCRIPTION_ID) else putInt(KEY_SERVICE_SUBSCRIPTION_ID, value)
            }.apply()
        }

    var serviceSimSlotIndex: Int?
        get() = prefs.getInt(KEY_SERVICE_SIM_SLOT, SubscriptionManager.INVALID_SIM_SLOT_INDEX)
            .takeIf { it != SubscriptionManager.INVALID_SIM_SLOT_INDEX }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SERVICE_SIM_SLOT) else putInt(KEY_SERVICE_SIM_SLOT, value)
            }.apply()
        }

    var serviceCarrierName: String?
        get() = prefs.getString(KEY_SERVICE_CARRIER, null)
        set(value) = prefs.edit().putString(KEY_SERVICE_CARRIER, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var callerIdEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALLER_ID_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CALLER_ID_ENABLED, value).apply()


    var lastClientId: Long?
        get() = prefs.getLong(KEY_LAST_CLIENT_ID, -1L).takeIf { it > 0L }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_CLIENT_ID) else putLong(KEY_LAST_CLIENT_ID, value)
            }.apply()
        }

    var lastClientName: String?
        get() = prefs.getString(KEY_LAST_CLIENT_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_CLIENT_NAME, value).apply()

    var lastSyncedInboxSmsProviderId: Long
        get() = prefs.getLong(KEY_LAST_INBOX_SMS_PROVIDER_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_INBOX_SMS_PROVIDER_ID, value.coerceAtLeast(0L)).apply()

    var lastSyncedSentSmsProviderId: Long
        get() = prefs.getLong(KEY_LAST_SENT_SMS_PROVIDER_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SENT_SMS_PROVIDER_ID, value.coerceAtLeast(0L)).apply()

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_EMPLOYEE_ID)
            .remove(KEY_EMPLOYEE_NAME)
            .remove(KEY_SERVICE_PHONE)
            .remove(KEY_SERVICE_SUBSCRIPTION_ID)
            .remove(KEY_SERVICE_SIM_SLOT)
            .remove(KEY_SERVICE_CARRIER)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_LAST_INBOX_SMS_PROVIDER_ID)
            .remove(KEY_LAST_SENT_SMS_PROVIDER_ID)
            .remove(KEY_LAST_CLIENT_ID)
            .remove(KEY_LAST_CLIENT_NAME)
            .apply()
    }

    companion object {
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_EMPLOYEE_ID = "employee_id"
        private const val KEY_EMPLOYEE_NAME = "employee_name"
        private const val KEY_SERVICE_PHONE = "service_phone"
        private const val KEY_SERVICE_SUBSCRIPTION_ID = "service_subscription_id"
        private const val KEY_SERVICE_SIM_SLOT = "service_sim_slot"
        private const val KEY_SERVICE_CARRIER = "service_carrier"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_CALLER_ID_ENABLED = "caller_id_enabled"
        private const val KEY_LAST_INBOX_SMS_PROVIDER_ID = "last_inbox_sms_provider_id"
        private const val KEY_LAST_SENT_SMS_PROVIDER_ID = "last_sent_sms_provider_id"
        private const val KEY_LAST_CLIENT_ID = "last_client_id"
        private const val KEY_LAST_CLIENT_NAME = "last_client_name"
    }
}
