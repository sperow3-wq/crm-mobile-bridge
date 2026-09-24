package pl.usundlug.crmbridge.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.InCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.CallDirection
import pl.usundlug.crmbridge.data.CallSession
import pl.usundlug.crmbridge.data.CompletedCallContext
import pl.usundlug.crmbridge.data.ResolvedCall
import pl.usundlug.crmbridge.notifications.CallerIdNotifier
import pl.usundlug.crmbridge.ui.CallerIdActivity
import pl.usundlug.crmbridge.util.PhoneNumberNormalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CrmInCallService : InCallService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbacks = ConcurrentHashMap<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        ActiveCallRegistry.attach(this, call)

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                handleState(call, state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                publishState(call)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        handleState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { runCatching { call.unregisterCallback(it) } }
        if (ActiveCallRegistry.isCurrent(call)) {
            closeOwnCallUi()
            CallerIdNotifier.cancel(this)
            ActiveCallRegistry.clear(call)
        }
        super.onCallRemoved(call)
    }

    private fun handleState(call: Call, state: Int) {
        ActiveCallRegistry.update(call, state)
        val app = application as CrmBridgeApp
        val number = normalizedNumber(call)

        when (state) {
            Call.STATE_RINGING -> {
                app.deviceStore.callerCardRinging = true
                ensureSession(number, CallDirection.INCOMING)
                showClient(call, number)
            }

            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_ACTIVE,
            Call.STATE_HOLDING -> {
                app.deviceStore.callerCardRinging = true
                if (number != null) {
                    val direction = if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING) {
                        CallDirection.OUTGOING
                    } else {
                        CallDirection.INCOMING
                    }
                    val session = ensureSession(number, direction)
                    if (state == Call.STATE_ACTIVE && session != null) {
                        if (!app.callSessionStore.wasAnswered()) app.callSessionStore.markAnswered()
                        MobileCallPresence.start(app, session)
                    }
                    showClient(call, number)
                }
            }

            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING -> {
                if (state == Call.STATE_DISCONNECTED) {
                    if (ActiveCallRegistry.isCurrent(call)) {
                        app.deviceStore.callerCardRinging = false
                        MobileCallPresence.stop(app.callSessionStore.current()?.eventUuid)
                        finalizeSession(call, number)
                        closeOwnCallUi()
                        CallerIdNotifier.cancel(this)
                        ActiveCallRegistry.clear(call)
                    }
                }
            }
        }
        publishState(call)
    }

    private fun ensureSession(number: String?, direction: CallDirection): CallSession? {
        if (number.isNullOrBlank()) return null
        val app = application as CrmBridgeApp
        val now = System.currentTimeMillis()
        val current = app.callSessionStore.current()
        if (current != null &&
            current.phone == number &&
            current.direction == direction &&
            current.startedAtEpochMs <= now
        ) return current

        val created = CallSession(
            eventUuid = UUID.randomUUID().toString(),
            clientId = null,
            phone = number,
            direction = direction,
            startedAtEpochMs = now
        )
        app.callSessionStore.save(created)
        scope.launch {
            app.repository.sendCallStarted(
                phone = number,
                direction = direction,
                clientId = null,
                eventUuid = created.eventUuid,
                startedAtEpochMs = created.startedAtEpochMs
            )
        }
        return created
    }

    private fun showClient(call: Call, number: String?) {
        if (number.isNullOrBlank()) return
        val app = application as CrmBridgeApp
        val cached = app.clientCacheStore.find(number)
        if (cached != null) {
            attachClientToSession(cached.clientId)
            CallerIdPresenter.show(this, app, number, cached, null)
        }

        scope.launch {
            val fresh = runCatching { app.repository.identifyClient(number) }.getOrNull() ?: return@launch
            if (!ActiveCallRegistry.hasCall() || call.state == Call.STATE_DISCONNECTED) return@launch
            if (fresh.matched) {
                app.deviceStore.lastClientId = fresh.clientId
                app.deviceStore.lastClientName = fresh.clientName
                attachClientToSession(fresh.clientId)
                CallerIdPresenter.show(this@CrmInCallService, app, number, fresh, null)
            } else if (cached == null) {
                CallerIdPresenter.show(this@CrmInCallService, app, number, fresh, null)
            }
        }
    }

    private fun attachClientToSession(clientId: Long?) {
        if (clientId == null) return
        val app = application as CrmBridgeApp
        val current = app.callSessionStore.current() ?: return
        if (current.clientId == clientId) return
        val enriched = current.copy(clientId = clientId)
        app.callSessionStore.save(enriched)
        scope.launch {
            app.repository.sendCallStarted(
                phone = enriched.phone,
                direction = enriched.direction,
                clientId = clientId,
                eventUuid = enriched.eventUuid,
                startedAtEpochMs = enriched.startedAtEpochMs
            )
        }
    }

    private fun publishState(call: Call) {
        sendBroadcast(
            Intent(CallerIdActivity.ACTION_CALL_STATE)
                .setPackage(packageName)
                .putExtra(CallerIdActivity.EXTRA_CALL_STATE, call.state)
                .putExtra(CallerIdActivity.EXTRA_MUTED, ActiveCallRegistry.muted)
                .putExtra(CallerIdActivity.EXTRA_SPEAKER, ActiveCallRegistry.speaker)
        )
    }

    private fun closeOwnCallUi() {
        sendBroadcast(
            Intent(CallerIdActivity.ACTION_CLOSE_CALLER_ID)
                .setPackage(packageName)
        )
    }

    private fun finalizeSession(call: Call, number: String?) {
        val app = application as CrmBridgeApp
        val session = app.callSessionStore.current() ?: return
        val endedAt = System.currentTimeMillis()
        val answered = app.callSessionStore.wasAnswered()
        val answeredAt = app.callSessionStore.answeredAtEpochMs()
        val rejectedByApp = app.callSessionStore.wasRejectedByApp()
        val duration = if (answered && answeredAt != null) {
            ((endedAt - answeredAt).coerceAtLeast(0L) / 1000L)
        } else {
            0L
        }

        val cause = call.details.disconnectCause?.code
        val status = when {
            rejectedByApp || cause == DisconnectCause.REJECTED -> "rejected"
            cause == DisconnectCause.MISSED -> "missed"
            answered -> "answered"
            session.direction == CallDirection.OUTGOING -> "not_connected"
            else -> "missed"
        }

        val resolved = ResolvedCall(
            phone = number ?: session.phone,
            direction = session.direction,
            startedAtEpochMs = session.startedAtEpochMs,
            endedAtEpochMs = endedAt,
            durationSeconds = duration,
            status = status
        )

        scope.launch {
            app.repository.sendCallFinished(session, resolved)
            if (answered) {
                val cachedClient = session.clientId?.let { app.clientCacheStore.findByClientId(it) }
                val completed = CompletedCallContext(
                    eventUuid = session.eventUuid,
                    clientId = session.clientId,
                    clientName = cachedClient?.clientName,
                    phone = resolved.phone,
                    direction = resolved.direction,
                    status = resolved.status,
                    startedAtEpochMs = resolved.startedAtEpochMs,
                    endedAtEpochMs = resolved.endedAtEpochMs,
                    durationSeconds = resolved.durationSeconds
                )
                app.callWrapUpStore.save(completed)
                app.postCallNotifier.show(completed)
            }
            // A slow network response from the previous call must never erase
            // a new call which has already started.
            app.callSessionStore.clearIf(session.eventUuid)
        }
    }

    private fun normalizedNumber(call: Call): String? =
        PhoneNumberNormalizer.normalizePolish(call.details.handle?.schemeSpecificPart)
}
