package pl.usundlug.crmbridge.sms

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SmsSyncScheduler {
    private const val PERIODIC_NAME = "crm-mobile-sms-provider-periodic"
    private const val NOW_NAME = "crm-mobile-sms-provider-now"

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SmsProviderSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun requestNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SmsProviderSyncWorker>().build()
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NOW_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }
}
