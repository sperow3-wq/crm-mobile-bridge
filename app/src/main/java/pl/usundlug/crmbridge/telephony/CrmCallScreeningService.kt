package pl.usundlug.crmbridge.telephony

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
            app.deviceStore.lastIncomingPhone = number
            app.deviceStore.lastCallerIdAtEpochMs = System.currentTimeMillis()

            val cached = app.clientCacheStore.find(number)
            if (incoming && app.deviceStore.callerIdEnabled && cached != null) {
                app.deviceStore.lastCallerIdStatus = "CACHE: ${cached.clientName ?: "klient CRM"}"
                CallerIdPresenter.show(this@CrmCallScreeningService, app, number, cached, null)
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
                app.deviceStore.lastCallerIdStatus = if (cached != null) {
                    "CACHE + CRM: ${client.clientName ?: "klient CRM"}"
                } else {
                    "CRM: ${client.clientName ?: "klient CRM"}"
                }
            } else if (cached == null) {
                app.deviceStore.lastCallerIdStatus = lookupError?.let { "BŁĄD CRM: $it" }
                    ?: "BRAK DOPASOWANIA: $number"
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
            if (incoming && app.deviceStore.callerIdEnabled && cached == null) {
                CallerIdPresenter.show(this@CrmCallScreeningService, app, number, client, lookupError)
            }
        }
    }

}
