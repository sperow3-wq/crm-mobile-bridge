package pl.usundlug.crmbridge.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.usundlug.crmbridge.data.CrmApiClient
import pl.usundlug.crmbridge.data.CrmApiException
import pl.usundlug.crmbridge.device.DeviceStore
import java.io.IOException

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val queue = SyncQueueStore(applicationContext)
        val deviceStore = DeviceStore(applicationContext)
        val api = CrmApiClient()

        var shouldRetry = false
        val events = queue.pending(limit = 100)

        for (event in events) {
            try {
                api.post(
                    path = event.endpoint,
                    body = JSONObject(event.payloadJson),
                    token = deviceStore.deviceToken
                )
                queue.removeIfUnchanged(event.id, event.updatedAtEpochMs)
            } catch (e: CrmApiException) {
                if (e.statusCode == 408 || e.statusCode == 429 || e.statusCode >= 500) {
                    queue.markRetryIfUnchanged(event.id, event.updatedAtEpochMs, "HTTP ${e.statusCode}: ${e.message}")
                    shouldRetry = true
                    break
                } else {
                    // Client/auth/validation errors need an admin or re-pairing fix.
                    // Keep the event instead of deleting business history.
                    queue.markBlockedIfUnchanged(event.id, event.updatedAtEpochMs, "HTTP ${e.statusCode}: ${e.message}")
                }
            } catch (e: IOException) {
                queue.markRetryIfUnchanged(event.id, event.updatedAtEpochMs, e.message)
                shouldRetry = true
                break
            } catch (e: Exception) {
                queue.markRetryIfUnchanged(event.id, event.updatedAtEpochMs, e.message ?: e.javaClass.simpleName)
                shouldRetry = true
                break
            }
        }

        if (shouldRetry || queue.countPending() > 0) Result.retry() else Result.success()
    }
}
