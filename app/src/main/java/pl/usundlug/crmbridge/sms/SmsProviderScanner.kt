package pl.usundlug.crmbridge.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat
import pl.usundlug.crmbridge.data.CrmRepository
import pl.usundlug.crmbridge.data.SmsEvent
import pl.usundlug.crmbridge.device.DeviceStore
import pl.usundlug.crmbridge.device.ServiceSimValidator
import pl.usundlug.crmbridge.util.PhoneNumberNormalizer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Synchronizes only SMS rows that belong to the SIM subscription explicitly paired
 * with the employee in CRM. Rows with an unknown/different sub_id are ignored.
 */
class SmsProviderScanner(
    private val context: Context,
    private val deviceStore: DeviceStore,
    private val repository: CrmRepository
) {

    fun initializeCheckpointToCurrent(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val serviceSubId = deviceStore.serviceSubscriptionId ?: return false
        deviceStore.lastSyncedInboxSmsProviderId = currentMaxId(Telephony.Sms.Inbox.CONTENT_URI, serviceSubId)
        deviceStore.lastSyncedSentSmsProviderId = currentMaxId(Telephony.Sms.Sent.CONTENT_URI, serviceSubId)
        return true
    }

    private fun currentMaxId(uri: Uri, serviceSubId: Int): Long {
        val cursor = try {
            context.contentResolver.query(
                uri,
                arrayOf(BaseColumns._ID),
                "${Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID} = ?",
                arrayOf(serviceSubId.toString()),
                "${BaseColumns._ID} DESC"
            )
        } catch (_: SecurityException) {
            null
        } ?: return 0L

        cursor.use { c ->
            return if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(BaseColumns._ID)) else 0L
        }
    }
    suspend fun scanAll(): SmsScanResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsScanResult(permissionMissing = true)
        }
        val serviceSubId = deviceStore.serviceSubscriptionId
            ?: return SmsScanResult(serviceSubscriptionMissing = true)
        val validation = ServiceSimValidator(context, deviceStore).validate()
        if (!validation.valid) return SmsScanResult(serviceSubscriptionMismatch = true, message = validation.reason)
        val employeeId = deviceStore.employeeId
            ?: return SmsScanResult(deviceNotPaired = true)

        val incoming = scanFolder(
            uri = Telephony.Sms.Inbox.CONTENT_URI,
            direction = "incoming",
            serviceSubId = serviceSubId,
            employeeId = employeeId,
            lastSeenId = deviceStore.lastSyncedInboxSmsProviderId,
            onCheckpoint = { deviceStore.lastSyncedInboxSmsProviderId = it }
        )
        val outgoing = scanFolder(
            uri = Telephony.Sms.Sent.CONTENT_URI,
            direction = "outgoing",
            serviceSubId = serviceSubId,
            employeeId = employeeId,
            lastSeenId = deviceStore.lastSyncedSentSmsProviderId,
            onCheckpoint = { deviceStore.lastSyncedSentSmsProviderId = it }
        )
        return SmsScanResult(
            incomingSynced = incoming,
            outgoingSynced = outgoing
        )
    }

    private suspend fun scanFolder(
        uri: Uri,
        direction: String,
        serviceSubId: Int,
        employeeId: Long,
        lastSeenId: Long,
        onCheckpoint: (Long) -> Unit
    ): Int {
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.TextBasedSmsColumns.ADDRESS,
            Telephony.TextBasedSmsColumns.BODY,
            Telephony.TextBasedSmsColumns.DATE,
            Telephony.TextBasedSmsColumns.DATE_SENT,
            Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID,
            Telephony.TextBasedSmsColumns.STATUS
        )
        val selection = "${BaseColumns._ID} > ? AND ${Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID} = ?"
        val args = arrayOf(lastSeenId.toString(), serviceSubId.toString())
        var synced = 0
        var highestId = lastSeenId

        val cursor = try {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                args,
                "${BaseColumns._ID} ASC"
            )
        } catch (_: SecurityException) {
            null
        } ?: return 0

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(BaseColumns._ID)
            val addressIdx = c.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE)
            val dateSentIdx = c.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.DATE_SENT)
            val subIdx = c.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.SUBSCRIPTION_ID)
            val statusIdx = c.getColumnIndexOrThrow(Telephony.TextBasedSmsColumns.STATUS)

            while (c.moveToNext()) {
                val providerId = c.getLong(idIdx)
                highestId = maxOf(highestId, providerId)

                // Defense in depth: never trust only the SQL selection for privacy filtering.
                val rowSubId = c.getInt(subIdx)
                if (rowSubId != serviceSubId) continue

                val phone = PhoneNumberNormalizer.normalizePolish(c.getString(addressIdx)) ?: continue
                val body = c.getString(bodyIdx).orEmpty()
                val date = c.getLong(dateIdx)
                val dateSent = c.getLong(dateSentIdx)
                val occurredAt = if (direction == "outgoing" && dateSent > 0L) dateSent else date
                val client = runCatching { repository.identifyClient(phone) }.getOrNull()
                if (client?.matched == true && client.clientId != null) {
                    deviceStore.lastClientId = client.clientId
                    deviceStore.lastClientName = client.clientName
                }

                repository.sendSms(
                    SmsEvent(
                        eventUuid = stableEventUuid(direction, providerId),
                        clientId = client?.clientId,
                        employeeId = employeeId,
                        phone = phone,
                        direction = direction,
                        body = body,
                        occurredAtEpochMs = occurredAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        subscriptionId = rowSubId,
                        providerMessageId = providerId,
                        status = if (direction == "incoming") "received" else mapProviderStatus(c.getInt(statusIdx)),
                        source = "android_sms_provider"
                    )
                )
                synced++
            }
        }

        // Checkpoint only after every discovered row has been safely handed to reliablePost(),
        // which persists it to the encrypted offline queue before network I/O.
        if (highestId > lastSeenId) onCheckpoint(highestId)
        return synced
    }

    private fun stableEventUuid(direction: String, providerId: Long): String {
        val key = "${deviceStore.deviceUuid}:sms:$direction:$providerId"
        return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun mapProviderStatus(status: Int): String = when (status) {
        Telephony.TextBasedSmsColumns.STATUS_COMPLETE -> "delivered"
        Telephony.TextBasedSmsColumns.STATUS_PENDING -> "sent_pending_delivery"
        Telephony.TextBasedSmsColumns.STATUS_FAILED -> "delivery_failed"
        else -> "sent"
    }
}

data class SmsScanResult(
    val incomingSynced: Int = 0,
    val outgoingSynced: Int = 0,
    val permissionMissing: Boolean = false,
    val serviceSubscriptionMissing: Boolean = false,
    val serviceSubscriptionMismatch: Boolean = false,
    val deviceNotPaired: Boolean = false,
    val message: String? = null
)
