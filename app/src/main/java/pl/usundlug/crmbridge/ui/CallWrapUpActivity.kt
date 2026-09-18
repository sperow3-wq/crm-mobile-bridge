package pl.usundlug.crmbridge.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.CallWrapUpEvent
import pl.usundlug.crmbridge.data.CompletedCallContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CallWrapUpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(false)

        val eventUuid = intent.getStringExtra(EXTRA_EVENT_UUID)
        if (eventUuid.isNullOrBlank()) {
            finish()
            return
        }

        val app = application as CrmBridgeApp
        val call = app.callWrapUpStore.get(eventUuid)
        if (call == null) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                CallWrapUpScreen(
                    call = call,
                    app = app,
                    onBack = { finish() },
                    onOpenClient = { clientId ->
                        startActivity(
                            Intent(this, ClientHistoryActivity::class.java)
                                .putExtra(ClientHistoryActivity.EXTRA_CLIENT_ID, clientId)
                        )
                    },
                    onSaved = { finish() },
                    onDiscard = {
                        app.callWrapUpStore.remove(eventUuid)
                        app.postCallNotifier.cancel(eventUuid)
                        finish()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_EVENT_UUID = "event_uuid"
    }
}

private data class ResultOption(val code: String, val label: String)

private val resultOptions = listOf(
    ResultOption("contact_successful", "Kontakt skuteczny"),
    ResultOption("call_back", "Oddzwonić"),
    ResultOption("client_requests_later", "Klient prosi o kontakt później"),
    ResultOption("no_new_arrangements", "Brak nowych ustaleń"),
    ResultOption("no_further_contact", "Nie wymaga dalszego kontaktu")
)

@Composable
private fun CallWrapUpScreen(
    call: CompletedCallContext,
    app: CrmBridgeApp,
    onBack: () -> Unit,
    onOpenClient: (Long) -> Unit,
    onSaved: () -> Unit,
    onDiscard: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<ResultOption?>(null) }
    var note by remember { mutableStateOf("") }
    var followUpAt by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Wróć")
                }
                Column(Modifier.weight(1f)) {
                    Text("Podsumowanie rozmowy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Zapisz wynik kontaktu w CRM", style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { CallSummaryCard(call) }

                if (call.clientId != null) {
                    item {
                        OutlinedButton(
                            onClick = { onOpenClient(call.clientId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = null)
                            Spacer(Modifier.padding(4.dp))
                            Text("Otwórz historię klienta")
                        }
                    }
                }

                item {
                    Text("Wynik rozmowy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                resultOptions.forEach { option ->
                    item {
                        FilterChip(
                            selected = selected?.code == option.code,
                            onClick = { selected = option },
                            label = { Text(option.label) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 2000) note = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("Notatka z rozmowy") },
                        supportingText = { Text("${note.length}/2000") },
                        leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) }
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AccessTime, contentDescription = null)
                                Spacer(Modifier.padding(4.dp))
                                Text("Ponowny kontakt", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                followUpAt?.let { "Zaplanowano: ${formatDateTime(it)}" }
                                    ?: "Brak zaplanowanego kontaktu",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        showFollowUpPicker(context) { followUpAt = it }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Wybierz termin") }
                                if (followUpAt != null) {
                                    TextButton(onClick = { followUpAt = null }) { Text("Usuń") }
                                }
                            }
                        }
                    }
                }

                error?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                item {
                    Button(
                        onClick = {
                            val chosen = selected
                            if (chosen == null) {
                                error = "Wybierz wynik rozmowy."
                            } else {
                                saving = true
                                error = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            app.repository.sendCallWrapUp(
                                                CallWrapUpEvent(
                                                    eventUuid = call.eventUuid,
                                                    clientId = call.clientId,
                                                    resultCode = chosen.code,
                                                    resultLabel = chosen.label,
                                                    note = note.trim().takeIf { it.isNotBlank() },
                                                    followUpAtEpochMs = followUpAt
                                                )
                                            )
                                        }
                                    }
                                    result.onSuccess {
                                        app.callWrapUpStore.remove(call.eventUuid)
                                        app.postCallNotifier.cancel(call.eventUuid)
                                        onSaved()
                                    }.onFailure {
                                        error = "Nie udało się zapisać: ${it.message ?: it.javaClass.simpleName}"
                                        saving = false
                                    }
                                }
                            }
                        },
                        enabled = !saving && selected != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (saving) "Zapisuję…" else "Zapisz podsumowanie")
                    }
                }

                item {
                    TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                        Text("Nie uzupełniaj tej rozmowy")
                    }
                }

                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun CallSummaryCard(call: CompletedCallContext) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Call, contentDescription = null)
                Spacer(Modifier.padding(4.dp))
                Text(
                    call.clientName?.takeIf { it.isNotBlank() } ?: call.phone,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            if (!call.clientName.isNullOrBlank()) Text(call.phone)
            Text("${directionLabel(call.direction.name)} • ${statusLabel(call.status)}")
            Text("${formatDuration(call.durationSeconds)} • ${formatDateTime(call.endedAtEpochMs)}")
        }
    }
}

private fun showFollowUpPicker(context: android.content.Context, onSelected: (Long) -> Unit) {
    val now = Calendar.getInstance()
    val initial = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (selected.timeInMillis > now.timeInMillis) onSelected(selected.timeInMillis)
                },
                initial.get(Calendar.HOUR_OF_DAY),
                initial.get(Calendar.MINUTE),
                true
            ).show()
        },
        initial.get(Calendar.YEAR),
        initial.get(Calendar.MONTH),
        initial.get(Calendar.DAY_OF_MONTH)
    ).apply { datePicker.minDate = now.timeInMillis - 1000L }.show()
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")).format(Date(epochMs))

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val min = safe / 60
    val sec = safe % 60
    return if (min > 0) "$min min ${sec.toString().padStart(2, '0')} s" else "$sec s"
}

private fun directionLabel(direction: String): String = when (direction.uppercase()) {
    "OUTGOING" -> "Połączenie wychodzące"
    else -> "Połączenie przychodzące"
}

private fun statusLabel(status: String): String = when (status.lowercase()) {
    "answered", "completed" -> "odebrane"
    "missed" -> "nieodebrane"
    "rejected" -> "odrzucone"
    "not_connected" -> "nie połączono"
    "blocked" -> "zablokowane"
    "answered_externally" -> "odebrano na innym urządzeniu"
    else -> status.replace('_', ' ')
}
