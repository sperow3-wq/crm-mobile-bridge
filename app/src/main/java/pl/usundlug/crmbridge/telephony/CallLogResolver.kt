package pl.usundlug.crmbridge.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import pl.usundlug.crmbridge.data.CallDirection
import pl.usundlug.crmbridge.data.CallSession
import pl.usundlug.crmbridge.data.ResolvedCall
import pl.usundlug.crmbridge.util.PhoneNumberNormalizer

class CallLogResolver(private val context: Context) {

    fun resolve(session: CallSession, endedAtEpochMs: Long): ResolvedCall? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val windowStart = session.startedAtEpochMs - 30_000L
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} >= ?",
            arrayOf(windowStart.toString()),
            "${CallLog.Calls.DATE} DESC"
        ) ?: return null

        cursor.use {
            val numberIndex = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIndex = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)

            var checked = 0
            while (it.moveToNext() && checked < 20) {
                checked++
                val rawNumber = it.getString(numberIndex).orEmpty()
                val normalized = PhoneNumberNormalizer.normalizePolish(rawNumber) ?: continue
                if (normalized != session.phone) continue

                val type = it.getInt(typeIndex)
                val resolvedDirection = directionFor(type) ?: continue
                if (resolvedDirection != session.direction) continue

                val startedAt = it.getLong(dateIndex)
                val duration = it.getLong(durationIndex).coerceAtLeast(0L)

                return ResolvedCall(
                    phone = normalized,
                    direction = resolvedDirection,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = endedAtEpochMs,
                    durationSeconds = duration,
                    status = statusFor(type, duration)
                )
            }
        }
        return null
    }

    private fun directionFor(type: Int): CallDirection? = when (type) {
        CallLog.Calls.INCOMING_TYPE,
        CallLog.Calls.MISSED_TYPE,
        CallLog.Calls.REJECTED_TYPE,
        CallLog.Calls.BLOCKED_TYPE,
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallDirection.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
        else -> null
    }

    private fun statusFor(type: Int, duration: Long): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "answered"
        CallLog.Calls.OUTGOING_TYPE -> if (duration > 0) "answered" else "not_connected"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered_externally"
        else -> "completed"
    }
}
