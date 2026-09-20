package pl.usundlug.crmbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.schemeSpecificPart.orEmpty()
        setContent {
            MaterialTheme {
                var number by remember { mutableStateOf(initial) }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Telefon CRM", style = MaterialTheme.typography.headlineMedium)
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Numer telefonu") },
                            singleLine = true
                        )
                        Button(
                            onClick = { placeCall(number) },
                            enabled = number.isNotBlank()
                        ) {
                            Text("Zadzwoń")
                        }
                    }
                }
            }
        }
    }

    private fun placeCall(number: String) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 2001)
            return
        }
        val telecom = getSystemService(TelecomManager::class.java) ?: return
        val uri = Uri.fromParts("tel", number.filter { it.isDigit() || it == '+' }, null)
        runCatching { telecom.placeCall(uri, Bundle()) }
    }
}
