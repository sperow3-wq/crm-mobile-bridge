package pl.usundlug.crmbridge.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

class CallerIdActivity : ComponentActivity() {
    private val uiState = androidx.compose.runtime.mutableStateOf(CallerUiState())

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_CALLER_ID) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        uiState.value = readUiState(intent)

        setContent {
            MaterialTheme {
                val state = uiState.value
                CallerIdScreen(
                    name = state.name,
                    product = state.product,
                    stage = state.stage,
                    guardian = state.guardian,
                    phone = state.phone,
                    matched = state.matched,
                    overdueCount = state.overdueCount,
                    overdueAmount = state.overdueAmount,
                    currency = state.currency,
                    offlineData = state.offlineData,
                    dataUpdatedAtEpochMs = state.dataUpdatedAtEpochMs,
                    lookupError = state.lookupError,
                    canOpenHistory = state.clientId != null,
                    onOpenHistory = { state.clientId?.let(::openClientHistory) },
                    onAnswer = { answerIncomingCall() },
                    onReject = { rejectIncomingCall() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        uiState.value = readUiState(intent)
    }

    private fun readUiState(intent: Intent): CallerUiState {
        val clientId = intent.getLongExtra(EXTRA_CLIENT_ID, -1L).takeIf { it > 0L }
        val source = intent.getStringExtra(EXTRA_DATA_SOURCE).orEmpty()
        return CallerUiState(
            clientId = clientId,
            name = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: "Nieznany numer",
            product = intent.getStringExtra(EXTRA_PRODUCT).orEmpty(),
            stage = intent.getStringExtra(EXTRA_STAGE).orEmpty(),
            guardian = intent.getStringExtra(EXTRA_GUARDIAN).orEmpty(),
            phone = intent.getStringExtra(EXTRA_PHONE).orEmpty(),
            matched = intent.getBooleanExtra(EXTRA_MATCHED, false),
            overdueCount = intent.getIntExtra(EXTRA_OVERDUE_COUNT, 0),
            overdueAmount = intent.getDoubleExtra(EXTRA_OVERDUE_AMOUNT, 0.0),
            currency = intent.getStringExtra(EXTRA_CURRENCY) ?: "PLN",
            offlineData = source == "CACHE",
            dataUpdatedAtEpochMs = intent.getLongExtra(EXTRA_DATA_UPDATED_AT, 0L),
            lookupError = intent.getStringExtra(EXTRA_LOOKUP_ERROR).orEmpty()
        )
    }

    @Suppress("DEPRECATION")
    private fun answerIncomingCall() {
        val telecom = getSystemService(TelecomManager::class.java)
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted || telecom == null) {
            showNativeCallScreen()
            return
        }

        val accepted = runCatching {
            telecom.acceptRingingCall()
            true
        }.getOrDefault(false)

        if (accepted) {
            finish()
        } else {
            showNativeCallScreen()
        }
    }

    @Suppress("DEPRECATION")
    private fun rejectIncomingCall() {
        val telecom = getSystemService(TelecomManager::class.java)
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted || telecom == null) {
            showNativeCallScreen()
            return
        }

        val ended = runCatching { telecom.endCall() }.getOrDefault(false)
        if (ended) {
            finish()
        } else {
            showNativeCallScreen()
        }
    }

    private fun showNativeCallScreen() {
        runCatching {
            getSystemService(TelecomManager::class.java)?.showInCallScreen(false)
        }
        finish()
    }

    private fun openClientHistory(clientId: Long) {
        val launch = {
            startActivity(
                Intent(this, ClientHistoryActivity::class.java)
                    .putExtra(ClientHistoryActivity.EXTRA_CLIENT_ID, clientId)
            )
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = launch()
                }
            )
        } else {
            launch()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_CLOSE_CALLER_ID)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(closeReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(closeReceiver) }
        super.onStop()
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_CLIENT_NAME = "client_name"
        const val EXTRA_PRODUCT = "product"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_GUARDIAN = "guardian"
        const val EXTRA_MATCHED = "matched"
        const val EXTRA_OVERDUE_COUNT = "overdue_count"
        const val EXTRA_OVERDUE_AMOUNT = "overdue_amount"
        const val EXTRA_CURRENCY = "currency"
        const val EXTRA_DATA_SOURCE = "data_source"
        const val EXTRA_DATA_UPDATED_AT = "data_updated_at"
        const val EXTRA_LOOKUP_ERROR = "lookup_error"
        const val ACTION_CLOSE_CALLER_ID = "pl.usundlug.crmbridge.CLOSE_CALLER_ID"
    }
}

private data class CallerUiState(
    val clientId: Long? = null,
    val name: String = "Nieznany numer",
    val product: String = "",
    val stage: String = "",
    val guardian: String = "",
    val phone: String = "",
    val matched: Boolean = false,
    val overdueCount: Int = 0,
    val overdueAmount: Double = 0.0,
    val currency: String = "PLN",
    val offlineData: Boolean = false,
    val dataUpdatedAtEpochMs: Long = 0L,
    val lookupError: String = ""
)

@Composable
private fun CallerIdScreen(
    name: String,
    product: String,
    stage: String,
    guardian: String,
    phone: String,
    matched: Boolean,
    overdueCount: Int,
    overdueAmount: Double,
    currency: String,
    offlineData: Boolean,
    dataUpdatedAtEpochMs: Long,
    lookupError: String,
    canOpenHistory: Boolean,
    onOpenHistory: () -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit
) {
    val top = Color(0xFF14283A)
    val bottom = Color(0xFF050B12)

    Surface(modifier = Modifier.fillMaxSize(), color = bottom) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .padding(horizontal = 24.dp, vertical = 40.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = "Połączenie przychodzące",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 18.sp
                )

                Spacer(Modifier.height(38.dp))

                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.height(58.dp)
                    )
                }

                Spacer(Modifier.height(34.dp))

                Text(
                    text = name.uppercase(),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 31.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(12.dp))

                if (matched) {
                    if (product.isNotBlank()) {
                        Text(product, color = Color.White.copy(alpha = 0.92f), fontSize = 21.sp)
                    }
                    val stageLabel = formatStageLabel(stage)
                    if (stageLabel.isNotBlank()) {
                        Text(stageLabel, color = Color.White.copy(alpha = 0.92f), fontSize = 20.sp)
                    }
                    if (guardian.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text("Opiekun: $guardian", color = Color.White.copy(alpha = 0.72f), fontSize = 17.sp)
                    }

                    Spacer(Modifier.height(24.dp))
                    FinancialStatus(overdueCount, overdueAmount, currency)

                    if (canOpenHistory) {
                        Spacer(Modifier.height(14.dp))
                        FilledTonalButton(onClick = onOpenHistory) {
                            Icon(Icons.Rounded.History, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Historia klienta")
                        }
                    }
                } else {
                    Text(phone, color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (lookupError.isNotBlank()) "Nie udało się pobrać danych klienta z CRM" else "Numer nie występuje w CRM",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 16.sp
                    )
                    if (lookupError.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            lookupError.take(180),
                            color = Color.White.copy(alpha = 0.50f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB3261E),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Odrzuć", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onAnswer,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E8E3E),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Odbierz", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "CRM Mobile Bridge",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun FinancialStatus(overdueCount: Int, overdueAmount: Double, currency: String) {
    val hasOverdue = overdueCount > 0 || overdueAmount > 0.0
    val background = if (hasOverdue) Color(0xFF681B20) else Color(0xFF123E2B)
    val border = if (hasOverdue) Color(0xFFFF5A5F) else Color(0xFF45C681)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = background.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasOverdue) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFD6D7)
                )
                Text(
                    text = "Faktury po terminie: $overdueCount • ${formatMoney(overdueAmount, currency)}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            } else {
                Text(
                    text = "✓  Faktury: brak zaległości",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun OfflineDataStatus(updatedAtEpochMs: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF4A3A17).copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD7A937))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = Color(0xFFFFE6A3)
            )
            Column {
                Text(
                    "Dane offline",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                if (updatedAtEpochMs > 0L) {
                    Text(
                        "Ostatnia aktualizacja: ${formatDateTime(updatedAtEpochMs)}",
                        color = Color.White.copy(alpha = 0.76f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

private fun formatStageLabel(raw: String): String {
    val stage = raw.trim()
    if (stage.isBlank() || stage == "—") return ""

    val normalized = stage.lowercase(Locale("pl", "PL"))
    if (
        normalized == "oczekuje na e1" ||
        normalized == "oczekuje na etap 1" ||
        normalized == "brak_wplat" ||
        normalized == "brak wpłat" ||
        normalized == "brak wplat" ||
        normalized == "przed etapem 1"
    ) {
        return "Przed Etapem 1"
    }

    Regex("""(?i)^e\s*(\d+)\s*(?:/\s*\d+)?$""").matchEntire(stage)?.let {
        return "Etap ${it.groupValues[1].toIntOrNull() ?: it.groupValues[1]}"
    }
    Regex("""(?i)^etap[_\s-]*(\d+)$""").matchEntire(stage)?.let {
        return "Etap ${it.groupValues[1].toIntOrNull() ?: it.groupValues[1]}"
    }

    return stage
}

private fun formatMoney(amount: Double, currencyCode: String): String {
    return runCatching {
        NumberFormat.getCurrencyInstance(Locale("pl", "PL")).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(amount)
    }.getOrElse { String.format(Locale("pl", "PL"), "%.2f %s", amount, currencyCode) }
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")).format(Date(epochMs))
