package pl.usundlug.crmbridge.data

import android.os.Build
import org.json.JSONObject
import pl.usundlug.crmbridge.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

internal class CrmApiClient {

    fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val base = BuildConfig.CRM_BASE_URL.trimEnd('/')
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2500
            readTimeout = 3500
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-CRM-Mobile-Platform", "android")
            setRequestProperty("X-CRM-Mobile-App-Version", BuildConfig.VERSION_NAME)
            setRequestProperty("X-CRM-Mobile-Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val status = connection.responseCode
        val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (status !in 200..299) {
            throw CrmApiException(status, responseText.ifBlank { "HTTP $status" })
        }

        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }
}

class CrmApiException(val statusCode: Int, message: String) : RuntimeException(message)
