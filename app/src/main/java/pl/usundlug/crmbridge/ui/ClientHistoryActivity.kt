package pl.usundlug.crmbridge.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.usundlug.crmbridge.BuildConfig
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.ClientActivityItem
import pl.usundlug.crmbridge.data.ClientActivityType
import pl.usundlug.crmbridge.data.ClientDataSource
import pl.usundlug.crmbridge.data.ClientOverview
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

class ClientHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(false)

        val clientId = intent.getLongExtra(EXTRA_CLIENT_ID, -1L)
        if (clientId <= 0L) {
            finish()
            return
        }

        val app = application as CrmBridgeApp
        setContent {
            MaterialTheme {
                ClientHistoryScreen(
                    clientId = clientId,
                    app = app,
                    onBack = { finish() },
                    onOpenCrm = { url -> openCrm(url) }
                )
            }
        }
    }

    private fun openCrm(url: String) {
        runCatching {
            val target = Uri.parse(url)
            val trustedBase = Uri.parse(BuildConfig.CRM_BASE_URL)
            val allowedScheme = target.scheme == "https" || (target.scheme == "http" && trustedBase.scheme == "http")
            val trustedHost = !target.host.isNullOrBlank() && target.host.equals(trustedBase.host, ignoreCase = true)
            if (allowedScheme && trustedHost) {
                startActivity(Intent(Intent.ACTION_VIEW, target))
            }
        }
    }

    companion object {
        const val EXTRA_CLIENT_ID = "client_id"
    }
}

@Composable
private fun ClientHistoryScreen(
    clientId: Long,
    app: CrmBridgeApp,
    onBack: () -> Unit,
    onOpenCrm: (String) -> Unit
) {
    var overview by remember { mutableStateOf<ClientOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(clientId, refreshKey) {
        loading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { app.repository.getClientOverview(clientId) }
        }
        result.onSuccess { overview = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
        loading = false
    }

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
                    Text("Karta klienta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Historia rozmów i SMS", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Odśwież")
                }
            }

            when {
                loading && overview == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                error != null && overview == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nie udało się pobrać karty klienta.", fontWeight = FontWeight.Bold)
                        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { refreshKey++ }) { Text("Spróbuj ponownie") }
                    }
                }

                else -> {
                    val data = overview!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            ClientHeader(data)
                        }

                        if (data.dataSource == ClientDataSource.CACHE) {
                            item {
                                OfflineOverviewNotice(data.dataUpdatedAtEpochMs)
                            }
                        }

                        item {
                            FinancialCard(data)
                        }

                        if (!data.crmUrl.isNullOrBlank()) {
                            item {
                                FilledTonalButton(
                                    onClick = { onOpenCrm(data.crmUrl!!) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Otwórz pełną kartę w CRM")
                                }
                            }
                        }

                        item {
                            Text(
                                "Ostatnia aktywność",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        if (data.activities.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (data.dataSource == ClientDataSource.CACHE) {
                                            "Brak Internetu — nagłówek klienta pochodzi z pamięci offline. Historia rozmów i SMS nie jest zapisywana lokalnie."
                                        } else {
                                            "Brak rozmów lub SMS do wyświetlenia."
                                        },
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(data.activities, key = { it.activityKey }) { item ->
                                ActivityCard(item)
                            }
                        }

                        if (!data.nextCursor.isNullOrBlank()) {
                            item {
                                Text(
                                    "Wyświetlono najnowsze ${data.activities.size} zdarzeń. Pełna historia jest dostępna w CRM.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientHeader(data: ClientOverview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(data.clientName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            data.phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            data.product?.takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            data.stage?.takeIf { it.isNotBlank() }?.let { Text(it) }
            data.guardianName?.takeIf { it.isNotBlank() }?.let {
                Text("Opiekun: $it", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FinancialCard(data: ClientOverview) {
    val overdue = data.hasOverdueInvoices
    val borderColor = if (overdue) Color(0xFFD7474F) else Color(0xFF3B9B68)
    val background = if (overdue) Color(0xFFFFF0F0) else Color(0xFFF0FAF4)

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (overdue) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFB3261E))
                Column {
                    Text("Faktury po terminie", fontWeight = FontWeight.Bold, color = Color(0xFF7A161C))
                    Text("${data.overdueInvoicesCount} • ${formatHistoryMoney(data.overdueAmount, data.currency)}")
                }
            } else {
                Text("✓", fontWeight = FontWeight.Bold, color = Color(0xFF176B3A))
                Text("Faktury: brak zaległości", fontWeight = FontWeight.Bold, color = Color(0xFF176B3A))
            }
        }
    }
}

@Composable
private fun OfflineOverviewNotice(updatedAt: Long?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Color(0xFFB68A20)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7DD))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Color(0xFF805F00))
            Column {
                Text("Dane offline", fontWeight = FontWeight.Bold)
                Text(
                    if (updatedAt != null && updatedAt > 0L) {
                        "Dane klienta z ${formatHistoryDateTime(updatedAt)}. Historia wymaga połączenia z CRM."
                    } else {
                        "Historia wymaga połączenia z CRM."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(item: ClientActivityItem) {
    val isCall = item.type == ClientActivityType.CALL
    val title = if (isCall) {
        when (item.direction.lowercase()) {
            "incoming" -> "Połączenie przychodzące"
            "outgoing" -> "Połączenie wychodzące"
            else -> "Połączenie"
        }
    } else {
        when (item.direction.lowercase()) {
            "incoming" -> "SMS przychodzący"
            "outgoing" -> "SMS wychodzący"
            else -> "SMS"
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (isCall) Icons.Rounded.Call else Icons.Rounded.Sms,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(formatHistoryDateTime(item.occurredAtEpochMs), style = MaterialTheme.typography.labelSmall)
                }

                if (isCall) {
                    val duration = item.durationSeconds?.takeIf { it > 0L }?.let(::formatDuration)
                    val status = translateCallStatus(item.status)
                    Text(listOfNotNull(status, duration).joinToString(" • "), style = MaterialTheme.typography.bodyMedium)
                    item.resultLabel?.takeIf { it.isNotBlank() }?.let {
                        Text("Wynik: $it", fontWeight = FontWeight.SemiBold)
                    }
                    item.wrapUpNote?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    item.followUpAtEpochMs?.takeIf { it > 0L }?.let {
                        Text(
                            "Ponowny kontakt: ${formatHistoryDateTime(it)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    item.messageBody?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    translateSmsStatus(item.status)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                item.employeeName?.takeIf { it.isNotBlank() }?.let {
                    Text("Pracownik: $it", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun translateCallStatus(status: String?): String? = when (status?.lowercase()) {
    "answered", "completed" -> "Odebrane"
    "missed" -> "Nieodebrane"
    "rejected" -> "Odrzucone"
    "not_connected" -> "Niepołączone"
    "blocked" -> "Zablokowane"
    "answered_externally" -> "Odebrane na innym urządzeniu"
    "started" -> "Rozpoczęte"
    null, "" -> null
    else -> status
}

private fun translateSmsStatus(status: String?): String? = when (status?.lowercase()) {
    "received" -> "Odebrano"
    "sent" -> "Wysłano"
    "sent_pending_delivery" -> "Wysłano — oczekiwanie na doręczenie"
    "delivered" -> "Dostarczono"
    "delivery_failed" -> "Błąd doręczenia"
    null, "" -> null
    else -> status
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes > 0) "$minutes min ${rest}s" else "${rest}s"
}

private fun formatHistoryDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")).format(Date(epochMs))

private fun formatHistoryMoney(amount: Double, currencyCode: String): String = runCatching {
    NumberFormat.getCurrencyInstance(Locale("pl", "PL")).apply {
        currency = Currency.getInstance(currencyCode)
    }.format(amount)
}.getOrElse { String.format(Locale("pl", "PL"), "%.2f %s", amount, currencyCode) }
