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
import pl.usundlug.crmbridge.data.CompletedCallContext
import pl.usundlug.crmbridge.notifications.CallerIdNotifier
import pl.usundlug.crmbridge.ui.CallerIdActivity

class PhoneStateReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val app = context.applicationContext as CrmBridgeApp

        // Once the call is answered, close the CRM caller card so Android's regular
        // in-call UI is unobstructed. The activity also closes on IDLE.
        if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            context.sendBroadcast(Intent(CallerIdActivity.ACTION_CLOSE_CALLER_ID).setPackage(context.packageName))
        CallerIdNotifier.cancel(context)
            CallerIdNotifier.cancel(context)
            return
        }

        if (state != TelephonyManager.EXTRA_STATE_IDLE) return
        context.sendBroadcast(Intent(CallerIdActivity.ACTION_CLOSE_CALLER_ID).setPackage(context.packageName))

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

                if (resolved != null) {
                    val finalCall = resolved!!
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
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
