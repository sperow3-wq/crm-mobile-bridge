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
        val app = application as CrmBridgeApp
        if (incoming) app.deviceStore.callerCardRinging = true

        // Samsung may deliver CallScreeningService and PHONE_STATE in either order.
        // Reuse the same recent session so CRM gets one complete call row instead of
        // two partial rows (or no final row at all).
        val existing = app.callSessionStore.current()
        val reusable = existing?.takeIf {
            it.phone == number &&
                it.direction == direction &&
                kotlin.math.abs(it.startedAtEpochMs - startedAt) <= 20_000L
        }
        val sessionSeed = reusable ?: CallSession(
            eventUuid = UUID.randomUUID().toString(),
            clientId = null,
            phone = number,
            direction = direction,
            startedAtEpochMs = startedAt
        )
        val eventUuid = sessionSeed.eventUuid
        app.callSessionStore.save(sessionSeed)

        scope.launch {
            // Caller ID during ringing must not wait for the network. If the encrypted
            // cache already knows this number, show it immediately and refresh CRM in
            // the background. This avoids Android showing only the raw phone number.
            app.deviceStore.lastIncomingPhone = number
            app.deviceStore.lastCallerIdAtEpochMs = System.currentTimeMillis()

            val cached = app.clientCacheStore.find(number)
            if (incoming && app.deviceStore.callerIdEnabled && app.deviceStore.callerCardRinging && cached != null) {
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

            // Cache can be shown immediately, but the live CRM response always
            // replaces it on the same caller card. This keeps stage/payment data
            // current without waiting for the next call.
            if (incoming && app.deviceStore.callerIdEnabled && app.deviceStore.callerCardRinging) {
                CallerIdPresenter.show(this@CrmCallScreeningService, app, number, client, lookupError)
            }
        }
    }

}
