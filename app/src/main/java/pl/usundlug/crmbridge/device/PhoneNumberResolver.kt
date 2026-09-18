package pl.usundlug.crmbridge.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import pl.usundlug.crmbridge.util.PhoneNumberNormalizer

data class ServiceSimCandidate(
    val phone: String,
    val subscriptionId: Int?,
    val simSlotIndex: Int?,
    val carrierName: String?,
    val displayName: String?
)

class PhoneNumberResolver(private val context: Context) {

    fun resolveServiceSimCandidates(): List<ServiceSimCandidate> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val candidates = linkedMapOf<String, ServiceSimCandidate>()
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

        try {
            val subscriptions = subscriptionManager?.activeSubscriptionInfoList.orEmpty()
            subscriptions.forEach { info ->
                val raw = if (Build.VERSION.SDK_INT >= 33) {
                    subscriptionManager?.getPhoneNumber(info.subscriptionId)
                } else {
                    @Suppress("DEPRECATION")
                    info.number
                }
                val normalized = PhoneNumberNormalizer.normalizePolish(raw) ?: return@forEach
                candidates[normalized] = ServiceSimCandidate(
                    phone = normalized,
                    subscriptionId = info.subscriptionId,
                    simSlotIndex = info.simSlotIndex.takeIf { it >= 0 },
                    carrierName = info.carrierName?.toString()?.takeIf { it.isNotBlank() },
                    displayName = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                )
            }
        } catch (_: SecurityException) {
            // Runtime permission or OEM policy can still block the API.
        } catch (_: UnsupportedOperationException) {
            // Device has no telephony subscription feature.
        }

        if (candidates.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val fallback = context.getSystemService(TelephonyManager::class.java)?.line1Number
                PhoneNumberNormalizer.normalizePolish(fallback)?.let { normalized ->
                    candidates[normalized] = ServiceSimCandidate(
                        phone = normalized,
                        subscriptionId = null,
                        simSlotIndex = null,
                        carrierName = null,
                        displayName = null
                    )
                }
            } catch (_: SecurityException) {
            }
        }

        return candidates.values.toList()
    }

    /** Kept for compatibility with the 0.5 code path. */
    fun resolveServiceNumbers(): List<String> = resolveServiceSimCandidates().map { it.phone }
}
