package pl.usundlug.crmbridge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pl.usundlug.crmbridge.CrmBridgeApp

class ClientCacheSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext as CrmBridgeApp
        if (app.deviceStore.deviceToken.isNullOrBlank() || app.deviceStore.employeeId == null) {
            return Result.success()
        }

        return runCatching {
            app.repository.syncClientCache()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
