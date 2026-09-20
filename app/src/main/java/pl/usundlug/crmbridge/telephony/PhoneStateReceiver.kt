package pl.usundlug.crmbridge.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

class PhoneStateReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val app = context.applicationContext as CrmBridgeApp

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            app.deviceStore.callerCardRinging = true
            val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val number = PhoneNumberNormalizer.normalizePolish(rawNumber)
            if (number == null) {
                app.deviceStore.lastCallerIdStatus = "RINGING: Android nie przekazał numeru"
                app.deviceStore.lastCallerIdAtEpochMs = System.currentTimeMillis()
                return
            }

            app.deviceStore.lastIncomingPhone = number
            app.deviceStore.lastCallerIdAtEpochMs = System.currentTimeMillis()

            // PHONE_STATE is the fallback path on Samsung devices where the screening
            // callback can be late or absent. Ensure a durable call session exists
            // before any UI/network work so the call can always be finalized at IDLE.
            val now = System.currentTimeMillis()
            val existingSession = app.callSessionStore.current()
            val session = existingSession?.takeIf {
                it.phone == number &&
                    it.direction == CallDirection.INCOMING &&
                    kotlin.math.abs(it.startedAtEpochMs - now) <= 20_000L
            } ?: CallSession(
                eventUuid = UUID.randomUUID().toString(),
                clientId = null,
                phone = number,
                direction = CallDirection.INCOMING,
                startedAtEpochMs = now
            ).also(app.callSessionStore::save)

            val startPending = goAsync()
            scope.launch {
                try {
                    app.repository.sendCallStarted(
                        phone = number,
                        direction = CallDirection.INCOMING,
                        clientId = session.clientId,
                        eventUuid = session.eventUuid,
                        startedAtEpochMs = session.startedAtEpochMs
                    )
                } finally {
                    startPending.finish()
                }
            }

            if (!app.deviceStore.callerIdEnabled) {
                app.deviceStore.lastCallerIdStatus = "RINGING: identyfikacja wyłączona"
                return
            }

            val pendingResult = goAsync()
            scope.launch {
                try {
                    val cached = app.clientCacheStore.find(number)
                    if (cached != null) {
                        app.deviceStore.lastClientId = cached.clientId
                        app.deviceStore.lastClientName = cached.clientName
                        app.deviceStore.lastCallerIdStatus = "RINGING/CACHE: ${cached.clientName ?: "klient CRM"}"
                        if (app.deviceStore.callerCardRinging) {
                            CallerIdPresenter.show(context, app, number, cached, null)
                        }
                    }

                    val freshResult = runCatching { app.repository.identifyClient(number) }
                    freshResult.onSuccess { fresh ->
                        if (fresh.matched) {
                            app.deviceStore.lastClientId = fresh.clientId
                            app.deviceStore.lastClientName = fresh.clientName
                            app.deviceStore.lastCallerIdStatus = "RINGING/CRM: ${fresh.clientName ?: "klient CRM"}"
                            if (app.deviceStore.callerCardRinging) {
                                CallerIdPresenter.show(context, app, number, fresh, null)
                            }
                        } else if (cached == null) {
                            app.deviceStore.lastCallerIdStatus = "RINGING: brak dopasowania $number"
                        }
                    }.onFailure { error ->
                        if (cached == null) {
                            app.deviceStore.lastCallerIdStatus = "RINGING/BŁĄD: ${error.message ?: error.javaClass.simpleName}"
                        }
                    }
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }

        // Once the call is answered, close the CRM caller card so Android's regular
        // in-call UI is unobstructed. The activity also closes on IDLE.
        if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            app.deviceStore.callerCardRinging = false
            app.callSessionStore.markAnswered()
            context.sendBroadcast(Intent(CallerIdActivity.ACTION_CLOSE_CALLER_ID).setPackage(context.packageName))
            CallerIdNotifier.cancel(context)
            return
        }

        if (state != TelephonyManager.EXTRA_STATE_IDLE) return
        app.deviceStore.callerCardRinging = false
        context.sendBroadcast(Intent(CallerIdActivity.ACTION_CLOSE_CALLER_ID).setPackage(context.packageName))
        CallerIdNotifier.cancel(context)

        val pendingResult = goAsync()
        scope.launch {
            try {
                val session = app.callSessionStore.current() ?: return@launch

                // The call-log row can appear a fraction of a second after IDLE.
                val endedAt = System.currentTimeMillis()
                var resolved = CallLogResolver(context).resolve(session, endedAt)
                repeat(4) {
                    if (resolved != null) return@repeat
                    delay(500)
                    resolved = CallLogResolver(context).resolve(session, endedAt)
                }

                val finalCall = resolved ?: run {
                    val answered = app.callSessionStore.wasAnswered()
                    val answeredAt = app.callSessionStore.answeredAtEpochMs()
                    val rejectedByApp = app.callSessionStore.wasRejectedByApp()
                    val duration = if (answered && answeredAt != null) {
                        ((endedAt - answeredAt).coerceAtLeast(0L) / 1000L)
                    } else 0L
                    val fallbackStatus = when {
                        session.direction.name == "OUTGOING" && answered -> "answered"
                        session.direction.name == "OUTGOING" -> "not_connected"
                        rejectedByApp -> "rejected"
                        answered -> "answered"
                        else -> "missed"
                    }
                    ResolvedCall(
                        phone = session.phone,
                        direction = session.direction,
                        startedAtEpochMs = session.startedAtEpochMs,
                        endedAtEpochMs = endedAt,
                        durationSeconds = duration,
                        status = fallbackStatus
                    )
                }

                val sent = runCatching {
                    app.repository.sendCallFinished(session, finalCall)
                }.isSuccess

                if (sent) {
                    if (finalCall.durationSeconds > 0L || finalCall.status in setOf("answered", "completed")) {
                        val cachedClient = session.clientId?.let { app.clientCacheStore.findByClientId(it) }
                        val completed = CompletedCallContext(
                            eventUuid = session.eventUuid,
                            clientId = session.clientId,
                            clientName = cachedClient?.clientName,
                            phone = finalCall.phone,
                            direction = finalCall.direction,
                            status = finalCall.status,
                            startedAtEpochMs = finalCall.startedAtEpochMs,
                            endedAtEpochMs = finalCall.endedAtEpochMs,
                            durationSeconds = finalCall.durationSeconds
                        )
                        app.callWrapUpStore.save(completed)
                        app.postCallNotifier.show(completed)
                    }
                    app.callSessionStore.clear()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
