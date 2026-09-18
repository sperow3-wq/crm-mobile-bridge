package pl.usundlug.crmbridge.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import pl.usundlug.crmbridge.util.PhoneNumberNormalizer

class ServiceSimValidator(
    private val context: Context,
    private val deviceStore: DeviceStore
) {
    fun validate(): ServiceSimValidation {
        val expectedSubId = deviceStore.serviceSubscriptionId
            ?: return ServiceSimValidation(false, "Brak zapamiętanego subscriptionId służbowej karty SIM.")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return ServiceSimValidation(false, "Brak READ_PHONE_STATE — nie można bezpiecznie potwierdzić służbowej karty SIM.")
        }

        val manager = context.getSystemService(SubscriptionManager::class.java)
            ?: return ServiceSimValidation(false, "SubscriptionManager niedostępny.")

        val info = try {
            manager.activeSubscriptionInfoList.orEmpty().firstOrNull { it.subscriptionId == expectedSubId }
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        } ?: return ServiceSimValidation(false, "Służbowa karta SIM nie jest już aktywna.")

        val expectedPhone = deviceStore.servicePhone
        if (!expectedPhone.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        ) {
            val raw = try {
                if (Build.VERSION.SDK_INT >= 33) manager.getPhoneNumber(expectedSubId) else {
                    @Suppress("DEPRECATION")
                    info.number
                }
            } catch (_: Exception) {
                ""
            }
            val currentPhone = PhoneNumberNormalizer.normalizePolish(raw)
            if (currentPhone != null && currentPhone != expectedPhone) {
                return ServiceSimValidation(false, "subscriptionId wskazuje obecnie inny numer niż numer przypisany w CRM.")
            }
        }

        return ServiceSimValidation(true, null)
    }
}

data class ServiceSimValidation(
    val valid: Boolean,
    val reason: String?
)
