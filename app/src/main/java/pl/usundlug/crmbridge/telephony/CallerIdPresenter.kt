package pl.usundlug.crmbridge.telephony

import android.content.Context
import android.content.Intent
import android.telecom.Call
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.ClientMatch
import pl.usundlug.crmbridge.notifications.CallerIdNotifier
import pl.usundlug.crmbridge.ui.CallerIdActivity

/**
 * One presentation path for both CallScreeningService and the PHONE_STATE fallback.
 * Using a high-priority full-screen notification plus an activity start gives us the
 * fallback path for normal caller screening and the presentation path used by the
 * custom default-dialer InCallService.
 */
object CallerIdPresenter {
    fun show(
        context: Context,
        app: CrmBridgeApp,
        number: String,
        client: ClientMatch,
        lookupError: String? = null
    ) {
        val intent = Intent(context, CallerIdActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CallerIdActivity.EXTRA_PHONE, number)
            putExtra(CallerIdActivity.EXTRA_CLIENT_ID, client.clientId ?: -1L)
            putExtra(CallerIdActivity.EXTRA_CLIENT_NAME, client.clientName ?: "Nieznany numer")
            putExtra(CallerIdActivity.EXTRA_PRODUCT, client.product ?: "")
            putExtra(CallerIdActivity.EXTRA_STAGE, client.stage ?: "")
            putExtra(
                CallerIdActivity.EXTRA_GUARDIAN,
                client.guardianName.orEmpty()
            )
            putExtra(CallerIdActivity.EXTRA_MATCHED, client.matched)
            putExtra(CallerIdActivity.EXTRA_OVERDUE_COUNT, client.overdueInvoicesCount)
            putExtra(CallerIdActivity.EXTRA_OVERDUE_AMOUNT, client.overdueAmount)
            putExtra(CallerIdActivity.EXTRA_CURRENCY, client.currency)
            putExtra(CallerIdActivity.EXTRA_DATA_SOURCE, client.dataSource.name)
            putExtra(CallerIdActivity.EXTRA_DATA_UPDATED_AT, client.dataUpdatedAtEpochMs ?: 0L)
            putExtra(CallerIdActivity.EXTRA_LOOKUP_ERROR, lookupError.orEmpty())
            val defaultDialer = DialerRole.isHeld(context)
            putExtra(
                CallerIdActivity.EXTRA_CALL_STATE,
                if (defaultDialer) ActiveCallRegistry.state else Call.STATE_RINGING
            )
            putExtra(CallerIdActivity.EXTRA_MUTED, ActiveCallRegistry.muted)
            putExtra(CallerIdActivity.EXTRA_SPEAKER, ActiveCallRegistry.speaker)
            putExtra(
                CallerIdActivity.EXTRA_INCOMING,
                if (defaultDialer) ActiveCallRegistry.incoming else true
            )
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

        // Never refresh or resurrect the caller UI after the call has already
        // been rejected or disconnected.
        if (!app.deviceStore.callerCardRinging && !ActiveCallRegistry.hasCall()) return

        // Always refresh an already visible caller card directly. This avoids
        // relying on Activity recreation/orientation changes for new CRM data.
        context.sendBroadcast(
            Intent(CallerIdActivity.ACTION_REFRESH_CALLER_ID)
                .setPackage(context.packageName)
                .putExtras(intent.extras ?: android.os.Bundle())
        )

        if (!DialerRole.isHeld(context)) {
            CallerIdNotifier.show(context, intent, title, text)
        }
        runCatching { context.startActivity(intent) }
    }
}
