package pl.usundlug.crmbridge.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.CallDirection
import pl.usundlug.crmbridge.data.ClientMatch
import pl.usundlug.crmbridge.data.CallSession
import pl.usundlug.crmbridge.notifications.CallerIdNotifier
import pl.usundlug.crmbridge.ui.CallerIdActivity
import pl.usundlug.crmbridge.util.PhoneNumberNormalizer
import java.util.UUID

class CrmCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        // Incoming calls must be answered by the screening service quickly. We never
        // wait for CRM before allowing the phone to ring.
        if (incoming) {
            respondToCall(
                callDetails,
                CallResponse.Builder()
                    .setDisallowCall(false)
                    .setRejectCall(false)
                    .setSilenceCall(false)
                    .build()
            )
        }

        val rawNumber = callDetails.handle?.schemeSpecificPart ?: return
        val number = PhoneNumberNormalizer.normalizePolish(rawNumber) ?: return
        val direction = if (incoming) CallDirection.INCOMING else CallDirection.OUTGOING
        val startedAt = callDetails.creationTimeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val eventUuid = UUID.randomUUID().toString()
        val app = application as CrmBridgeApp

        // Persist the session before any network request so call completion can still
        // be resolved when CRM is slow or temporarily unavailable.
        app.callSessionStore.save(
            CallSession(
                eventUuid = eventUuid,
                clientId = null,
                phone = number,
                direction = direction,
                startedAtEpochMs = startedAt
            )
        )

        scope.launch {
            // Caller ID during ringing must not wait for the network. If the encrypted
            // cache already knows this number, show it immediately and refresh CRM in
            // the background. This avoids Android showing only the raw phone number.
            val cached = app.clientCacheStore.find(number)
            if (incoming && cached != null) {
                showCallerId(app, number, cached, null)
            }

            var lookupError: String? = null
            val fresh = runCatching { app.repository.identifyClient(number) }
                .getOrElse { error ->
                    lookupError = error.message ?: error.javaClass.simpleName
                    ClientMatch(
                        matched = false,
                        normalizedPhone = number
                    )
                }

            val client = when {
                fresh.matched -> fresh
                cached != null -> cached
                else -> fresh
            }

            if (client.matched && client.clientId != null) {
                app.deviceStore.lastClientId = client.clientId
                app.deviceStore.lastClientName = client.clientName
            }

            val session = CallSession(
                eventUuid = eventUuid,
                clientId = client.clientId,
                phone = number,
                direction = direction,
                startedAtEpochMs = startedAt
            )
            app.callSessionStore.save(session)

            runCatching {
                app.repository.sendCallStarted(
                    phone = number,
                    direction = direction,
                    clientId = client.clientId,
                    eventUuid = eventUuid,
                    startedAtEpochMs = startedAt
                )
            }

            // If there was no cached match, show the network result as soon as it is
            // available. When cache was already shown, the fresh result is stored by
            // identifyClient() and will be used immediately on the next call.
            if (incoming && cached == null) {
                showCallerId(app, number, client, lookupError)
            }
        }
    }

    private fun showCallerId(app: CrmBridgeApp, number: String, client: ClientMatch, lookupError: String?) {
        val intent = Intent(this, CallerIdActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CallerIdActivity.EXTRA_PHONE, number)
            putExtra(CallerIdActivity.EXTRA_CLIENT_ID, client.clientId ?: -1L)
            putExtra(CallerIdActivity.EXTRA_CLIENT_NAME, client.clientName ?: "Nieznany numer")
            putExtra(CallerIdActivity.EXTRA_PRODUCT, client.product ?: "")
            putExtra(CallerIdActivity.EXTRA_STAGE, client.stage ?: "")
            putExtra(
                CallerIdActivity.EXTRA_GUARDIAN,
                client.guardianName ?: app.deviceStore.employeeName.orEmpty()
            )
            putExtra(CallerIdActivity.EXTRA_MATCHED, client.matched)
            putExtra(CallerIdActivity.EXTRA_OVERDUE_COUNT, client.overdueInvoicesCount)
            putExtra(CallerIdActivity.EXTRA_OVERDUE_AMOUNT, client.overdueAmount)
            putExtra(CallerIdActivity.EXTRA_CURRENCY, client.currency)
            putExtra(CallerIdActivity.EXTRA_DATA_SOURCE, client.dataSource.name)
            putExtra(CallerIdActivity.EXTRA_DATA_UPDATED_AT, client.dataUpdatedAtEpochMs ?: 0L)
            putExtra(CallerIdActivity.EXTRA_LOOKUP_ERROR, lookupError.orEmpty())
        }
        val title = if (client.matched) {
            client.clientName ?: "Klient CRM"
        } else {
            "Połączenie przychodzące"
        }
        val text = if (client.matched) {
            listOfNotNull(
                client.product?.takeIf { it.isNotBlank() },
                client.stage?.takeIf { it.isNotBlank() }
            ).joinToString(" • ").ifBlank { number }
        } else {
            number
        }

        // Full-screen call notification is the supported path on modern Android.
        // It can open CallerIdActivity over the lock screen; if the OEM suppresses
        // the full-screen launch, the user still gets a high-priority heads-up card.
        CallerIdNotifier.show(this, intent, title, text)

        // On devices that still allow background activity starts from the call
        // screening role, this gives the fastest possible display.
        runCatching { startActivity(intent) }
    }
}
