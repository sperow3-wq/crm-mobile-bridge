package pl.usundlug.crmbridge.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.usundlug.crmbridge.device.DeviceStore
import pl.usundlug.crmbridge.sync.ClientCacheScheduler
import pl.usundlug.crmbridge.sync.SyncQueueStore
import pl.usundlug.crmbridge.sync.SyncScheduler
import java.io.IOException

class CrmRepository(
    context: Context,
    private val deviceStore: DeviceStore,
    val syncQueue: SyncQueueStore = SyncQueueStore(context),
    val clientCache: ClientCacheStore = ClientCacheStore(context)
) {
    private val appContext = context.applicationContext
    private val api = CrmApiClient()

    suspend fun registerDevice(
        phone: String,
        subscriptionId: Int? = null,
        simSlotIndex: Int? = null,
        carrierName: String? = null
    ): EmployeeMatch = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("phone", phone)
            .put("device_uuid", deviceStore.deviceUuid)
            .put("device_manufacturer", Build.MANUFACTURER)
            .put("device_model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("service_subscription_id", subscriptionId ?: JSONObject.NULL)
            .put("service_sim_slot_index", simSlotIndex ?: JSONObject.NULL)
            .put("service_carrier_name", carrierName ?: JSONObject.NULL)

        val json = api.post("/api/mobile/device/register", payload)
        val match = EmployeeMatch(
            matched = json.optBoolean("matched", false),
            employeeId = json.optLongOrNull("employee_id"),
            employeeName = json.optStringOrNull("employee_name"),
            normalizedPhone = json.optStringOrNull("phone") ?: phone,
            deviceToken = json.optStringOrNull("device_token"),
            message = json.optStringOrNull("message")
        )

        if (match.matched && match.employeeId != null) {
            deviceStore.employeeId = match.employeeId
            deviceStore.employeeName = match.employeeName
            deviceStore.servicePhone = match.normalizedPhone
            deviceStore.serviceSubscriptionId = subscriptionId
            deviceStore.serviceSimSlotIndex = simSlotIndex
            deviceStore.serviceCarrierName = carrierName
            deviceStore.deviceToken = match.deviceToken
            if (!match.deviceToken.isNullOrBlank()) {
                clientCache.clearAll()
                ClientCacheScheduler.schedulePeriodic(appContext)
                ClientCacheScheduler.requestNow(appContext)
            }
        }
        match
    }

    /**
     * Network is the source of truth. If it is temporarily unavailable, the encrypted
     * local cache is used as a fallback so caller identification still works offline.
     */
    suspend fun identifyClient(phone: String): ClientMatch = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("phone", phone)
                .put("device_uuid", deviceStore.deviceUuid)
                .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)

            val json = api.post("/api/mobile/client/identify", payload, deviceStore.deviceToken)
            val match = parseClientMatch(
                json = json,
                fallbackPhone = phone,
                dataSource = ClientDataSource.NETWORK,
                defaultUpdatedAt = System.currentTimeMillis()
            )

            if (match.matched) {
                clientCache.put(match, match.dataUpdatedAtEpochMs ?: System.currentTimeMillis())
            }
            // Do not delete a fresh local mapping merely because one live lookup did
            // not resolve. Duplicate/imported CRM records can be reconciled by the
            // next cache sync; keeping the cache prevents Caller ID from disappearing
            // between calls.
            match
        } catch (networkError: Exception) {
            clientCache.find(phone) ?: throw networkError
        }
    }

    /**
     * Downloads an incremental encrypted caller-ID cache. The server should return
     * only fields needed on the incoming-call screen; no documents or notes belong here.
     */
    suspend fun syncClientCache(maxPages: Int = 20): Int = withContext(Dispatchers.IO) {
        if (deviceStore.deviceToken.isNullOrBlank() || deviceStore.employeeId == null) return@withContext 0

        val since = clientCache.lastSuccessfulSyncEpochMs
        var cursor: String? = null
        var downloaded = 0
        var page = 0
        var watermark = System.currentTimeMillis()

        do {
            val payload = JSONObject()
                .put("device_uuid", deviceStore.deviceUuid)
                .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)
                .put("since_epoch_ms", since)
                .put("limit", 500)
                .put("scope", "caller_id")
                .apply {
                    if (!cursor.isNullOrBlank()) put("cursor", cursor)
                }

            val json = api.post("/api/mobile/client/cache/sync", payload, deviceStore.deviceToken)
            watermark = json.optLong("server_time_epoch_ms", watermark)

            val clients = json.optJSONArray("clients")
            if (clients != null) {
                for (i in 0 until clients.length()) {
                    val item = clients.optJSONObject(i) ?: continue
                    val match = parseClientMatch(
                        json = item,
                        fallbackPhone = item.optString("phone"),
                        dataSource = ClientDataSource.NETWORK,
                        defaultUpdatedAt = item.optLong("updated_at_epoch_ms", watermark)
                    )
                    if (match.matched && !match.normalizedPhone.isNullOrBlank()) {
                        clientCache.put(match, match.dataUpdatedAtEpochMs ?: watermark)
                        downloaded++
                    }
                }
            }

            val deleted = json.optJSONArray("deleted_client_ids")
            if (deleted != null) {
                val ids = buildList {
                    for (i in 0 until deleted.length()) {
                        deleted.optLong(i).takeIf { it > 0L }?.let { add(it) }
                    }
                }
                clientCache.removeByClientIds(ids)
            }

            cursor = json.optStringOrNull("next_cursor")
            page++
        } while (!cursor.isNullOrBlank() && page < maxPages)

        if (cursor.isNullOrBlank()) {
            clientCache.lastSuccessfulSyncEpochMs = watermark
            clientCache.pruneExpired()
        }

        downloaded
    }

    suspend fun sendCallStarted(
        phone: String,
        direction: CallDirection,
        clientId: Long?,
        eventUuid: String,
        startedAtEpochMs: Long
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("event_uuid", eventUuid)
            .put("phone", phone)
            .put("device_uuid", deviceStore.deviceUuid)
            .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)
            .put("client_id", clientId ?: JSONObject.NULL)
            .put("direction", direction.name.lowercase())
            .put("started_at_epoch_ms", startedAtEpochMs)
            .put("status", "started")

        reliablePost(
            dedupeKey = "call:$eventUuid",
            endpoint = "/api/mobile/call/event",
            payload = payload
        )
    }

    suspend fun sendCallFinished(session: CallSession, resolved: ResolvedCall) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("event_uuid", session.eventUuid)
            .put("phone", resolved.phone)
            .put("device_uuid", deviceStore.deviceUuid)
            .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)
            .put("client_id", session.clientId ?: JSONObject.NULL)
            .put("direction", resolved.direction.name.lowercase())
            .put("started_at_epoch_ms", resolved.startedAtEpochMs)
            .put("ended_at_epoch_ms", resolved.endedAtEpochMs)
            .put("duration_seconds", resolved.durationSeconds)
            .put("status", resolved.status)

        reliablePost(
            dedupeKey = "call:${session.eventUuid}",
            endpoint = "/api/mobile/call/event",
            payload = payload
        )
    }

    /** A presence ping is intentionally not queued: a delayed ping must never replay after the final event. */
    suspend fun sendCallTalking(session: CallSession, durationSeconds: Long) = withContext(Dispatchers.IO) {
        if (deviceStore.deviceToken.isNullOrBlank()) return@withContext
        val payload = JSONObject()
            .put("event_uuid", session.eventUuid)
            .put("phone", session.phone)
            .put("device_uuid", deviceStore.deviceUuid)
            .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)
            .put("client_id", session.clientId ?: JSONObject.NULL)
            .put("direction", session.direction.name.lowercase())
            .put("started_at_epoch_ms", session.startedAtEpochMs)
            .put("duration_seconds", durationSeconds)
            .put("status", "talking")
        api.post("/api/mobile/call/event", payload, deviceStore.deviceToken)
    }

    suspend fun sendSms(event: SmsEvent) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("event_uuid", event.eventUuid)
            .put("phone", event.phone)
            .put("device_uuid", deviceStore.deviceUuid)
            .put("employee_id", event.employeeId ?: JSONObject.NULL)
            .put("client_id", event.clientId ?: JSONObject.NULL)
            .put("direction", event.direction)
            .put("body", event.body)
            .put("occurred_at_epoch_ms", event.occurredAtEpochMs)
            .put("service_subscription_id", event.subscriptionId ?: JSONObject.NULL)
            .put("provider_message_id", event.providerMessageId ?: JSONObject.NULL)
            .put("status", event.status ?: JSONObject.NULL)
            .put("source", event.source)

        reliablePost(
            dedupeKey = "sms:${event.eventUuid}",
            endpoint = "/api/mobile/sms/event",
            payload = payload
        )
    }

    /**
     * Fresh mobile client card. Activity history is intentionally not persisted locally;
     * only the minimal caller-ID header can fall back to the encrypted cache.
     */
    suspend fun getClientOverview(clientId: Long, limit: Int = 50): ClientOverview = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put("client_id", clientId)
                .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)
                .put("device_uuid", deviceStore.deviceUuid)
                .put("limit", limit.coerceIn(1, 100))

            val json = api.post("/api/mobile/client/overview", payload, deviceStore.deviceToken)
            val clientJson = json.optJSONObject("client") ?: json
            val financial = clientJson.optJSONObject("financial")
                ?: json.optJSONObject("financial")

            val activities = buildList {
                val array = json.optJSONArray("activities")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val type = when (item.optString("type").lowercase()) {
                            "sms" -> ClientActivityType.SMS
                            else -> ClientActivityType.CALL
                        }
                        val occurredAt = item.optLong("occurred_at_epoch_ms", 0L)
                        if (occurredAt <= 0L) continue
                        add(
                            ClientActivityItem(
                                activityKey = item.optString("activity_key").ifBlank { "${type.name.lowercase()}:$i:$occurredAt" },
                                type = type,
                                direction = item.optString("direction").ifBlank { "unknown" },
                                status = item.optStringOrNull("status"),
                                phone = item.optStringOrNull("phone") ?: item.optStringOrNull("remote_phone"),
                                messageBody = item.optStringOrNull("message_body"),
                                occurredAtEpochMs = occurredAt,
                                durationSeconds = item.optLongOrNull("duration_seconds"),
                                employeeName = item.optStringOrNull("employee_name"),
                                resultLabel = item.optStringOrNull("result_label"),
                                wrapUpNote = item.optStringOrNull("wrap_up_note"),
                                followUpAtEpochMs = item.optLongOrNull("follow_up_at_epoch_ms")
                            )
                        )
                    }
                }
            }

            val overview = ClientOverview(
                clientId = clientJson.optLongOrNull("client_id") ?: clientId,
                clientName = clientJson.optStringOrNull("client_name") ?: "Klient #$clientId",
                phone = clientJson.optStringOrNull("phone"),
                product = clientJson.optStringOrNull("product"),
                stage = clientJson.optStringOrNull("stage"),
                guardianName = clientJson.optStringOrNull("guardian_name"),
                overdueInvoicesCount = financial?.optInt("overdue_invoices_count", 0)
                    ?: clientJson.optInt("overdue_invoices_count", 0),
                overdueAmount = financial?.optDouble("overdue_amount", 0.0)
                    ?: clientJson.optDouble("overdue_amount", 0.0),
                currency = financial?.optStringOrNull("currency")
                    ?: clientJson.optStringOrNull("currency")
                    ?: "PLN",
                crmUrl = json.optStringOrNull("crm_url") ?: clientJson.optStringOrNull("crm_url"),
                activities = activities.sortedByDescending { it.occurredAtEpochMs },
                nextCursor = json.optStringOrNull("next_cursor"),
                dataSource = ClientDataSource.NETWORK,
                dataUpdatedAtEpochMs = json.optLong("server_time_epoch_ms", System.currentTimeMillis())
            )

            clientCache.put(
                ClientMatch(
                    matched = true,
                    clientId = overview.clientId,
                    clientName = overview.clientName,
                    product = overview.product,
                    stage = overview.stage,
                    guardianName = overview.guardianName,
                    normalizedPhone = overview.phone,
                    overdueInvoicesCount = overview.overdueInvoicesCount,
                    overdueAmount = overview.overdueAmount,
                    currency = overview.currency,
                    dataSource = ClientDataSource.NETWORK,
                    dataUpdatedAtEpochMs = overview.dataUpdatedAtEpochMs
                ),
                overview.dataUpdatedAtEpochMs ?: System.currentTimeMillis()
            )
            overview
        } catch (networkError: Exception) {
            val cached = clientCache.findByClientId(clientId) ?: throw networkError
            ClientOverview(
                clientId = clientId,
                clientName = cached.clientName ?: "Klient #$clientId",
                phone = cached.normalizedPhone,
                product = cached.product,
                stage = cached.stage,
                guardianName = cached.guardianName,
                overdueInvoicesCount = cached.overdueInvoicesCount,
                overdueAmount = cached.overdueAmount,
                currency = cached.currency,
                crmUrl = null,
                activities = emptyList(),
                dataSource = ClientDataSource.CACHE,
                dataUpdatedAtEpochMs = cached.dataUpdatedAtEpochMs
            )
        }
    }

    suspend fun sendCallWrapUp(event: CallWrapUpEvent) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("event_uuid", event.eventUuid)
            .put("device_uuid", deviceStore.deviceUuid)
            .put("employee_id", deviceStore.employeeId ?: JSONObject.NULL)
            .put("client_id", event.clientId ?: JSONObject.NULL)
            .put("result_code", event.resultCode)
            .put("result_label", event.resultLabel)
            .put("note", event.note?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("follow_up_at_epoch_ms", event.followUpAtEpochMs ?: JSONObject.NULL)
            .put("updated_at_epoch_ms", System.currentTimeMillis())

        reliablePost(
            dedupeKey = "call_wrap_up:${event.eventUuid}",
            endpoint = "/api/mobile/call/wrap-up",
            payload = payload
        )
    }

    suspend fun flushPendingNow() = withContext(Dispatchers.IO) {
        syncQueue.retryBlocked()
        SyncScheduler.request(appContext)
    }

    private fun reliablePost(dedupeKey: String, endpoint: String, payload: JSONObject) {
        syncQueue.enqueueOrReplace(dedupeKey, endpoint, payload.toString())
        val snapshot = syncQueue.findByDedupeKey(dedupeKey) ?: return

        try {
            api.post(endpoint, JSONObject(snapshot.payloadJson), deviceStore.deviceToken)
            syncQueue.removeIfUnchanged(snapshot.id, snapshot.updatedAtEpochMs)
        } catch (e: CrmApiException) {
            if (e.statusCode == 408 || e.statusCode == 429 || e.statusCode >= 500) {
                syncQueue.markRetryIfUnchanged(
                    snapshot.id,
                    snapshot.updatedAtEpochMs,
                    "HTTP ${e.statusCode}: ${e.message}"
                )
            } else {
                syncQueue.markBlockedIfUnchanged(
                    snapshot.id,
                    snapshot.updatedAtEpochMs,
                    "HTTP ${e.statusCode}: ${e.message}"
                )
            }
        } catch (e: IOException) {
            syncQueue.markRetryIfUnchanged(snapshot.id, snapshot.updatedAtEpochMs, e.message)
        } catch (e: Exception) {
            syncQueue.markRetryIfUnchanged(
                snapshot.id,
                snapshot.updatedAtEpochMs,
                e.message ?: e.javaClass.simpleName
            )
        } finally {
            if (syncQueue.countPending() > 0) SyncScheduler.request(appContext)
        }
    }

    private fun parseClientMatch(
        json: JSONObject,
        fallbackPhone: String,
        dataSource: ClientDataSource,
        defaultUpdatedAt: Long
    ): ClientMatch {
        val financial = json.optJSONObject("financial")
        val overdueCount = financial?.optInt("overdue_invoices_count", 0)
            ?: json.optInt("overdue_invoices_count", 0)
        val overdueAmount = financial?.optDouble("overdue_amount", 0.0)
            ?: json.optDouble("overdue_amount", 0.0)
        val currency = financial?.optStringOrNull("currency")
            ?: json.optStringOrNull("currency")
            ?: "PLN"

        return ClientMatch(
            matched = json.optBoolean("matched", true),
            clientId = json.optLongOrNull("client_id"),
            clientName = json.optStringOrNull("client_name"),
            product = json.optStringOrNull("product"),
            stage = json.optStringOrNull("stage"),
            guardianName = json.optStringOrNull("guardian_name"),
            normalizedPhone = json.optStringOrNull("phone") ?: fallbackPhone,
            overdueInvoicesCount = overdueCount,
            overdueAmount = overdueAmount,
            currency = currency,
            dataSource = dataSource,
            dataUpdatedAtEpochMs = json.optLong("updated_at_epoch_ms", defaultUpdatedAt)
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key).takeIf { it > 0 }
