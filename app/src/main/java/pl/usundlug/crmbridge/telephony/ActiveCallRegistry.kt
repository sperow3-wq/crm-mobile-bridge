package pl.usundlug.crmbridge.telephony

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile

object ActiveCallRegistry {
    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var currentService: CrmInCallService? = null

    @Volatile
    var state: Int = Call.STATE_DISCONNECTED
        private set

    @Volatile
    var muted: Boolean = false
        private set

    @Volatile
    var speaker: Boolean = false
        private set

    @Volatile
    var incoming: Boolean = true
        private set

    fun attach(service: CrmInCallService, call: Call) {
        currentService = service
        currentCall = call
        state = call.state
        incoming = call.details.callDirection != Call.Details.DIRECTION_OUTGOING
    }

    fun update(call: Call, newState: Int) {
        if (currentCall == null || currentCall === call) {
            currentCall = call
            state = newState
        }
    }

    fun clear(call: Call) {
        if (currentCall === call) {
            currentCall = null
            currentService = null
            state = Call.STATE_DISCONNECTED
            muted = false
            speaker = false
            incoming = true
        }
    }

    fun hasCall(): Boolean = currentCall != null

    fun isCurrent(call: Call): Boolean = currentCall === call

    fun answer(): Boolean = runCatching {
        val call = currentCall ?: return false
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        true
    }.getOrDefault(false)

    fun reject(): Boolean = runCatching {
        val call = currentCall ?: return false
        call.reject(false, null)
        true
    }.getOrDefault(false)

    fun disconnect(): Boolean = runCatching {
        val call = currentCall ?: return false
        call.disconnect()
        true
    }.getOrDefault(false)

    fun toggleMute(): Boolean {
        val service = currentService ?: return muted
        val next = !muted
        runCatching { service.setMuted(next) }.onSuccess { muted = next }
        return muted
    }

    @Suppress("DEPRECATION")
    fun toggleSpeaker(): Boolean {
        val service = currentService ?: return speaker
        val next = !speaker
        val route = if (next) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        runCatching { service.setAudioRoute(route) }.onSuccess { speaker = next }
        return speaker
    }
}
