package pl.usundlug.crmbridge.telephony

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.CallSession

/** Refreshes active GSM call state while the phone process receives call callbacks. */
object MobileCallPresence {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var eventUuid: String? = null

    @Synchronized
    fun start(app: CrmBridgeApp, session: CallSession) {
        if (eventUuid == session.eventUuid && job?.isActive == true) return
        job?.cancel()
        eventUuid = session.eventUuid
        job = scope.launch {
            while (true) {
                val current = app.callSessionStore.current()
                if (current?.eventUuid != session.eventUuid || !app.callSessionStore.wasAnswered()) break
                val answeredAt = app.callSessionStore.answeredAtEpochMs() ?: System.currentTimeMillis()
                runCatching {
                    app.repository.sendCallTalking(
                        current,
                        ((System.currentTimeMillis() - answeredAt).coerceAtLeast(0L) / 1000L)
                    )
                }
                delay(25_000L)
            }
        }
    }

    @Synchronized
    fun stop(uuid: String?) {
        if (uuid != null && eventUuid != uuid) return
        job?.cancel()
        job = null
        eventUuid = null
    }
}
