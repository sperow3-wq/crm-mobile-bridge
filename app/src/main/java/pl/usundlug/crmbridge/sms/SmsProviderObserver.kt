package pl.usundlug.crmbridge.sms

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper

class SmsProviderObserver(
    private val onChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        onChanged()
    }
}
