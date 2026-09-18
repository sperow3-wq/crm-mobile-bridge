package pl.usundlug.crmbridge.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pl.usundlug.crmbridge.CrmBridgeApp

class SmsProviderSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CrmBridgeApp
        return runCatching {
            app.smsProviderScanner.scanAll()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
