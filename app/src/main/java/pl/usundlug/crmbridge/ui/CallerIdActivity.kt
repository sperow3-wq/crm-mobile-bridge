package pl.usundlug.crmbridge.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.telecom.Call
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.data.ClientMatch
import pl.usundlug.crmbridge.telephony.ActiveCallRegistry
import pl.usundlug.crmbridge.telephony.DialerRole
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class CallerIdActivity : ComponentActivity() {
    private val uiState = androidx.compose.runtime.mutableStateOf(CallerUiState())
    private var refreshJob: Job? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_CALLER_ID) finish()
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_REFRESH_CALLER_ID) return
            val refreshed = readUiState(intent ?: return)
            uiState.value = refreshed.copy(
                callState = uiState.value.callState,
                muted = uiState.value.muted,
                speaker = uiState.value.speaker
            )
            startLiveRefresh()
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CALL_STATE) return
            val state = intent.getIntExtra(EXTRA_CALL_STATE, uiState.value.callState)
            uiState.value = uiState.value.copy(
                callState = state,
                muted = intent.getBooleanExtra(EXTRA_MUTED, ActiveCallRegistry.muted),
                speaker = intent.getBooleanExtra(EXTRA_SPEAKER, ActiveCallRegistry.speaker)
            )
            if (state == Call.STATE_DISCONNECTED) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        uiState.value = readUiState(intent)
        startLiveRefresh()

        setContent {
            MaterialTheme {
                val state = uiState.value
                CallerIdScreen(
                    state = state,
                    canOpenHistory = state.clientId != null,
                    onOpenHistory = { state.clientId?.let(::openClientHistory) },
                    onAnswer = { answerIncomingCall() },
                    onReject = { rejectIncomingCall() },
                    onDisconnect = { disconnectCall() },
                    onToggleMute = { toggleMute() },
                    onToggleSpeaker = { toggleSpeaker() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val updated = readUiState(intent)
        uiState.value = updated.copy(
            callState = if (updated.callState == Call.STATE_DISCONNECTED) uiState.value.callState else updated.callState,
            muted = ActiveCallRegistry.muted,
            speaker = ActiveCallRegistry.speaker
        )
        startLiveRefresh()
    }

    private fun startLiveRefresh() {
        refreshJob?.cancel()
        val phone = uiState.value.phone
        if (phone.isBlank()) return
        val app = application as CrmBridgeApp

        refreshJob = lifecycleScope.launch {
            repeat(4) { attempt ->
                if (!app.deviceStore.callerCardRinging && !ActiveCallRegistry.hasCall()) return@launch
                val fresh = runCatching { app.repository.identifyClient(phone) }.getOrNull()
                if (fresh?.matched == true) applyClient(fresh)
                if (attempt < 3) delay(if (attempt == 0) 350L else 900L)
            }
        }
    }

    private fun applyClient(client: ClientMatch) {
        uiState.value = uiState.value.copy(
            clientId = client.clientId,
            name = client.clientName ?: uiState.value.name,
            product = client.product.orEmpty(),
            stage = client.stage.orEmpty(),
            guardian = client.guardianName.orEmpty(),
            matched = client.matched,
            overdueCount = client.overdueInvoicesCount,
            overdueAmount = client.overdueAmount,
            currency = client.currency,
            lookupError = ""
        )
    }

    private fun readUiState(intent: Intent): CallerUiState {
        val clientId = intent.getLongExtra(EXTRA_CLIENT_ID, -1L).takeIf { it > 0L }
        val registryState = ActiveCallRegistry.state
        val defaultState = if (registryState != Call.STATE_DISCONNECTED) registryState else Call.STATE_RINGING
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
            lookupError = intent.getStringExtra(EXTRA_LOOKUP_ERROR).orEmpty(),
            callState = intent.getIntExtra(EXTRA_CALL_STATE, defaultState),
            muted = intent.getBooleanExtra(EXTRA_MUTED, ActiveCallRegistry.muted),
            speaker = intent.getBooleanExtra(EXTRA_SPEAKER, ActiveCallRegistry.speaker)
        )
    }

    @Suppress("DEPRECATION")
    private fun answerIncomingCall() {
        val app = application as CrmBridgeApp
        app.callSessionStore.markAnswered()

        if (DialerRole.isHeld(this) && ActiveCallRegistry.answer()) return

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
            app.deviceStore.callerCardRinging = false
            finish()
        } else {
            showNativeCallScreen()
        }
    }

    @Suppress("DEPRECATION")
    private fun rejectIncomingCall() {
        val app = application as CrmBridgeApp
        app.callSessionStore.markRejectedByApp()

        if (DialerRole.isHeld(this) && ActiveCallRegistry.reject()) return

        app.deviceStore.callerCardRinging = false
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
        if (ended) finish() else showNativeCallScreen()
    }

    private fun disconnectCall() {
        if (!ActiveCallRegistry.disconnect()) showNativeCallScreen()
    }

    private fun toggleMute() {
        uiState.value = uiState.value.copy(muted = ActiveCallRegistry.toggleMute())
    }

    private fun toggleSpeaker() {
        uiState.value = uiState.value.copy(speaker = ActiveCallRegistry.toggleSpeaker())
    }

    private fun showNativeCallScreen() {
        runCatching { getSystemService(TelecomManager::class.java)?.showInCallScreen(false) }
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
        val closeFilter = IntentFilter(ACTION_CLOSE_CALLER_ID)
        val refreshFilter = IntentFilter(ACTION_REFRESH_CALLER_ID)
        val stateFilter = IntentFilter(ACTION_CALL_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, closeFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(refreshReceiver, refreshFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(callStateReceiver, stateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(closeReceiver, closeFilter)
            @Suppress("DEPRECATION")
            registerReceiver(refreshReceiver, refreshFilter)
            @Suppress("DEPRECATION")
            registerReceiver(callStateReceiver, stateFilter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(closeReceiver) }
        runCatching { unregisterReceiver(refreshReceiver) }
        runCatching { unregisterReceiver(callStateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
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
        const val EXTRA_CALL_STATE = "call_state"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_SPEAKER = "speaker"
        const val ACTION_CLOSE_CALLER_ID = "pl.usundlug.crmbridge.CLOSE_CALLER_ID"
        const val ACTION_REFRESH_CALLER_ID = "pl.usundlug.crmbridge.REFRESH_CALLER_ID"
        const val ACTION_CALL_STATE = "pl.usundlug.crmbridge.CALL_STATE"
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
    val lookupError: String = "",
    val callState: Int = Call.STATE_RINGING,
    val muted: Boolean = false,
    val speaker: Boolean = false
)

@Composable
private fun CallerIdScreen(
    state: CallerUiState,
    canOpenHistory: Boolean,
    onOpenHistory: () -> Unit,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    val top = Color(0xFF14283A)
    val bottom = Color(0xFF050B12)
    val isRinging = state.callState == Call.STATE_RINGING
    val isActive = state.callState == Call.STATE_ACTIVE || state.callState == Call.STATE_HOLDING
    val title = when {
        isRinging -> "Połączenie przychodzące"
        isActive -> "Rozmowa trwa"
        state.callState == Call.STATE_DIALING || state.callState == Call.STATE_CONNECTING -> "Łączenie…"
        else -> "Połączenie"
    }

    Surface(modifier = Modifier.fillMaxSize(), color = bottom) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .padding(horizontal = 24.dp, vertical = 34.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(title, color = Color.White.copy(alpha = 0.78f), fontSize = 18.sp)
                Spacer(Modifier.height(26.dp))

                Box(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.height(52.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = state.name.uppercase(),
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 29.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(10.dp))
                if (state.matched) {
                    if (state.product.isNotBlank()) {
                        Text(state.product, color = Color.White.copy(alpha = 0.92f), fontSize = 20.sp)
                    }
                    val stageLabel = formatStageLabel(state.stage)
                    if (stageLabel.isNotBlank()) {
                        Text(stageLabel, color = Color.White.copy(alpha = 0.92f), fontSize = 19.sp)
                    }
                    if (state.guardian.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Opiekun: " + state.guardian, color = Color.White.copy(alpha = 0.74f), fontSize = 17.sp)
                    }

                    Spacer(Modifier.height(18.dp))
                    FinancialStatus(state.overdueCount, state.overdueAmount, state.currency)

                    if (canOpenHistory) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = onOpenHistory) {
                            Icon(Icons.Rounded.History, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Historia klienta")
                        }
                    }
                } else {
                    Text(state.phone, color = Color.White.copy(alpha = 0.78f), fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.lookupError.isNotBlank()) "Pobieram dane klienta z CRM…" else "Numer nie występuje w CRM",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 15.sp
                    )
                }

                Spacer(Modifier.weight(1f))

                if (isRinging) {
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
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onToggleMute, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.MicOff, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.muted) "Włącz mikrofon" else "Wycisz")
                        }
                        OutlinedButton(onClick = onToggleSpeaker, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.speaker) "Słuchawka" else "Głośnik")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB3261E),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Rozłącz", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("CRM Mobile Bridge", color = Color.White.copy(alpha = 0.38f), fontSize = 13.sp)
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
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasOverdue) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFFD6D7))
                Text(
                    "Faktury po terminie: " + overdueCount + " • " + formatMoney(overdueAmount, currency),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            } else {
                Text("✓  Faktury: brak zaległości", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    ) return "Przed Etapem 1"

    Regex("""(?i)^e\s*(\d+)\s*(?:/\s*\d+)?$""").matchEntire(stage)?.let {
        return "Etap " + (it.groupValues[1].toIntOrNull() ?: it.groupValues[1])
    }
    Regex("""(?i)^etap[_\s-]*(\d+)$""").matchEntire(stage)?.let {
        return "Etap " + (it.groupValues[1].toIntOrNull() ?: it.groupValues[1])
    }
    return stage
}

private fun formatMoney(amount: Double, currencyCode: String): String =
    runCatching {
        NumberFormat.getCurrencyInstance(Locale("pl", "PL")).apply {
            currency = Currency.getInstance(currencyCode)
        }.format(amount)
    }.getOrElse { String.format(Locale("pl", "PL"), "%.2f %s", amount, currencyCode) }
