package pl.usundlug.crmbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ClientCacheScheduler {
    private const val UNIQUE_PERIODIC = "crm_client_cache_periodic"
    private const val UNIQUE_NOW = "crm_client_cache_now"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ClientCacheSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun requestNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ClientCacheSyncWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(UNIQUE_NOW)
        manager.cancelUniqueWork(UNIQUE_PERIODIC)
    }
}
