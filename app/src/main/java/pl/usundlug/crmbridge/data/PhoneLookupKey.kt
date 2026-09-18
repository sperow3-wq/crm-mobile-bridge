package pl.usundlug.crmbridge.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Creates a deterministic local lookup key without storing the phone number or an
 * unkeyed phone hash. The HMAC secret lives in Android Keystore.
 */
internal class PhoneLookupKey {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun forPhone(normalizedPhone: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(getOrCreateKey())
        return mac.doFinal(normalizedPhone.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALGORITHM = "HmacSHA256"
        private const val KEY_ALIAS = "crm_mobile_bridge_phone_lookup_v1"
    }
}
