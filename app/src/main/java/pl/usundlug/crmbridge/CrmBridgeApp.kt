package pl.usundlug.crmbridge

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import pl.usundlug.crmbridge.data.ClientCacheStore
import pl.usundlug.crmbridge.data.CrmRepository
import pl.usundlug.crmbridge.device.CallSessionStore
import pl.usundlug.crmbridge.device.CallWrapUpStore
import pl.usundlug.crmbridge.device.DeviceStore
import pl.usundlug.crmbridge.notifications.PostCallNotifier
import pl.usundlug.crmbridge.sms.SmsProviderObserver
import pl.usundlug.crmbridge.sms.SmsProviderScanner
import pl.usundlug.crmbridge.sms.SmsSyncScheduler
import pl.usundlug.crmbridge.sync.ClientCacheScheduler
import pl.usundlug.crmbridge.sync.SyncQueueStore
import pl.usundlug.crmbridge.sync.SyncScheduler

class CrmBridgeApp : Application() {
    lateinit var deviceStore: DeviceStore
        private set

    lateinit var callSessionStore: CallSessionStore
        private set

    lateinit var syncQueueStore: SyncQueueStore
        private set

    lateinit var callWrapUpStore: CallWrapUpStore
        private set

    lateinit var postCallNotifier: PostCallNotifier
        private set

    lateinit var clientCacheStore: ClientCacheStore
        private set

    lateinit var repository: CrmRepository
        private set

    lateinit var smsProviderScanner: SmsProviderScanner
        private set

    private var smsObserver: SmsProviderObserver? = null

    override fun onCreate() {
        super.onCreate()
        deviceStore = DeviceStore(this)
        callSessionStore = CallSessionStore(this)
        syncQueueStore = SyncQueueStore(this)
        callWrapUpStore = CallWrapUpStore(this)
        postCallNotifier = PostCallNotifier(this).also { it.ensureChannel() }
        clientCacheStore = ClientCacheStore(this)
        repository = CrmRepository(this, deviceStore, syncQueueStore, clientCacheStore)
        smsProviderScanner = SmsProviderScanner(this, deviceStore, repository)

        if (syncQueueStore.countPending() > 0) {
            SyncScheduler.request(this)
        }
        if (!deviceStore.deviceToken.isNullOrBlank() && deviceStore.employeeId != null) {
            ClientCacheScheduler.schedulePeriodic(this)
            ClientCacheScheduler.requestNow(this)
            SmsSyncScheduler.schedulePeriodic(this)
            SmsSyncScheduler.requestNow(this)
        }
        ensureSmsObserver()
    }

    fun ensureSmsObserver() {
        if (smsObserver != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        val observer = SmsProviderObserver {
            if (deviceStore.employeeId != null && deviceStore.serviceSubscriptionId != null) {
                SmsSyncScheduler.requestNow(this)
            }
        }
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            smsObserver = observer
        } catch (_: SecurityException) {
            // Some managed/OEM builds can still deny provider observation despite runtime permission.
        }
    }
}
