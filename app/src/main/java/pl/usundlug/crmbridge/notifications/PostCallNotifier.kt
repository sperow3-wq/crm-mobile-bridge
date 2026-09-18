package pl.usundlug.crmbridge.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.usundlug.crmbridge.R
import pl.usundlug.crmbridge.data.CompletedCallContext
import pl.usundlug.crmbridge.ui.CallWrapUpActivity
import java.util.Locale

class PostCallNotifier(private val context: Context) {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Podsumowanie rozmowy",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Przypomnienia o uzupełnieniu wyniku rozmowy w CRM"
        }
        manager.createNotificationChannel(channel)
    }

    fun show(call: CompletedCallContext) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(context, CallWrapUpActivity::class.java)
            .putExtra(CallWrapUpActivity.EXTRA_EVENT_UUID, call.eventUuid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(call.eventUuid),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val who = call.clientName?.takeIf { it.isNotBlank() } ?: call.phone
        val duration = formatDuration(call.durationSeconds)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Uzupełnij rozmowę: $who")
            .setContentText("${statusLabel(call.status)} • $duration")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${statusLabel(call.status)} • $duration. Dodaj wynik rozmowy, notatkę lub zaplanuj ponowny kontakt."
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_menu_edit, "Uzupełnij", pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(call.eventUuid), notification)
    }

    fun cancel(eventUuid: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(eventUuid))
    }

    private fun notificationId(eventUuid: String): Int = eventUuid.hashCode() and 0x7fffffff

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val min = safe / 60
        val sec = safe % 60
        return if (min > 0) String.format(Locale("pl", "PL"), "%d min %02d s", min, sec)
        else "$sec s"
    }

    private fun statusLabel(status: String): String = when (status.lowercase()) {
        "answered", "completed" -> "Rozmowa zakończona"
        "missed" -> "Połączenie nieodebrane"
        "rejected" -> "Połączenie odrzucone"
        "not_connected" -> "Nie połączono"
        "blocked" -> "Połączenie zablokowane"
        "answered_externally" -> "Odebrano na innym urządzeniu"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    companion object {
        const val CHANNEL_ID = "post_call_wrap_up"
    }
}
