package pl.usundlug.crmbridge.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import pl.usundlug.crmbridge.sms.SmsSyncScheduler

/**
 * The broadcast is only a wake-up signal. The message body is read later from the
 * system SMS provider and accepted only when its sub_id equals the paired service SIM.
 * This avoids accidental CRM logging from a private SIM on dual-SIM devices.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        SmsSyncScheduler.requestNow(context.applicationContext)
    }
}
