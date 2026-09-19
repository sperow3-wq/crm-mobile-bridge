package pl.usundlug.crmbridge.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.usundlug.crmbridge.BuildConfig
import pl.usundlug.crmbridge.CrmBridgeApp
import pl.usundlug.crmbridge.device.PhoneNumberResolver
import pl.usundlug.crmbridge.device.ServiceSimCandidate
import pl.usundlug.crmbridge.sms.SmsSyncScheduler
import pl.usundlug.crmbridge.sync.ClientCacheScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CrmBridgeApp

        setContent {
            MaterialTheme {
                SetupScreen(
                    app = app,
                    requestCallScreeningRole = { requestCallScreeningRole() }
                )
            }
        }
    }

    private fun requestCallScreeningRole() {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        ) {
            startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }
}

@Composable
private fun SetupScreen(
    app: CrmBridgeApp,
    requestCallScreeningRole: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    fun isCallScreeningRoleHeld(): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
    var status by remember {
        mutableStateOf(
            app.deviceStore.employeeName?.let { "Połączono z pracownikiem: $it" }
                ?: "Urządzenie nie jest jeszcze sparowane"
        )
    }
    var authStatus by remember {
        mutableStateOf(
            if (app.deviceStore.deviceToken.isNullOrBlank()) {
                "Autoryzacja urządzenia: brak tokenu CRM"
            } else {
                "Autoryzacja urządzenia: token zapisany lokalnie — wymaga ponownej weryfikacji"
            }
        )
    }
    var detectedPhone by remember { mutableStateOf(app.deviceStore.servicePhone.orEmpty()) }
    var serviceSimInfo by remember { mutableStateOf(formatServiceSimInfo(app.deviceStore.serviceSimSlotIndex, app.deviceStore.serviceCarrierName, app.deviceStore.serviceSubscriptionId)) }
    var pendingSync by remember { mutableStateOf(app.syncQueueStore.countPending()) }
    var blockedSync by remember { mutableStateOf(app.syncQueueStore.countBlocked()) }
    var cachedClients by remember { mutableStateOf(app.clientCacheStore.count()) }
    var cacheLastSync by remember { mutableStateOf(app.clientCacheStore.lastSuccessfulSyncEpochMs) }
    var lastClientId by remember { mutableStateOf(app.deviceStore.lastClientId) }
    var lastClientName by remember { mutableStateOf(app.deviceStore.lastClientName) }
    var pendingWrapUps by remember { mutableStateOf(app.callWrapUpStore.count()) }
    var callerIdEnabled by remember { mutableStateOf(app.deviceStore.callerIdEnabled) }
    var callScreeningRoleHeld by remember { mutableStateOf(isCallScreeningRoleHeld()) }
    var cacheSyncInProgress by remember { mutableStateOf(false) }
    var lastIncomingPhone by remember { mutableStateOf(app.deviceStore.lastIncomingPhone) }
    var lastCallerIdStatus by remember { mutableStateOf(app.deviceStore.lastCallerIdStatus) }
    var lastCallerIdAt by remember { mutableStateOf(app.deviceStore.lastCallerIdAtEpochMs) }

    fun refreshSyncCounters() {
        pendingSync = app.syncQueueStore.countPending()
        blockedSync = app.syncQueueStore.countBlocked()
    }

    fun refreshCacheStats() {
        cachedClients = app.clientCacheStore.count()
        cacheLastSync = app.clientCacheStore.lastSuccessfulSyncEpochMs
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshSyncCounters()
            refreshCacheStats()
            lastClientId = app.deviceStore.lastClientId
            lastClientName = app.deviceStore.lastClientName
            pendingWrapUps = app.callWrapUpStore.count()
            detectedPhone = app.deviceStore.servicePhone.orEmpty()
            serviceSimInfo = formatServiceSimInfo(
                app.deviceStore.serviceSimSlotIndex,
                app.deviceStore.serviceCarrierName,
                app.deviceStore.serviceSubscriptionId
            )
            callerIdEnabled = app.deviceStore.callerIdEnabled
            callScreeningRoleHeld = isCallScreeningRoleHeld()
            lastIncomingPhone = app.deviceStore.lastIncomingPhone
            lastCallerIdStatus = app.deviceStore.lastCallerIdStatus
            lastCallerIdAt = app.deviceStore.lastCallerIdAtEpochMs
            authStatus = if (app.deviceStore.deviceToken.isNullOrBlank()) {
                "Autoryzacja urządzenia: brak tokenu CRM"
            } else {
                "Autoryzacja urządzenia: aktywna"
            }
            delay(500)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val phoneGranted = result[Manifest.permission.READ_PHONE_NUMBERS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        val stateGranted = result[Manifest.permission.READ_PHONE_STATE] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        if (result[Manifest.permission.READ_SMS] == true || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            app.ensureSmsObserver()
        }
        status = if (phoneGranted && stateGranted) {
            "Uprawnienia telefonu przyznane. Możesz uruchomić automatyczne parowanie."
        } else {
            "Brak wymaganych uprawnień do odczytu numeru SIM."
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("CRM Mobile Bridge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Wersja ${BuildConfig.VERSION_NAME} — telefon służbowy + podsumowanie rozmów")

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Powiązanie pracownika", fontWeight = FontWeight.Bold)
                    Text(status)
                    Text(authStatus, style = MaterialTheme.typography.bodySmall)
                    if (detectedPhone.isNotBlank()) Text("Numer: $detectedPhone")
                    if (serviceSimInfo.isNotBlank()) Text(serviceSimInfo, style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Synchronizacja zdarzeń", fontWeight = FontWeight.Bold)
                    Text("Oczekujące zdarzenia: $pendingSync")
                    Text("Wymagające interwencji: $blockedSync")
                    if (pendingSync == 0 && blockedSync == 0) {
                        Text("Wszystkie połączenia i SMS-y są zsynchronizowane.")
                    }
                }
            }

            if (pendingWrapUps > 0) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Rozmowy do uzupełnienia", fontWeight = FontWeight.Bold)
                        Text("Oczekujące podsumowania: $pendingWrapUps")
                        OutlinedButton(onClick = {
                            app.callWrapUpStore.latest()?.let { call ->
                                context.startActivity(
                                    Intent(context, CallWrapUpActivity::class.java)
                                        .putExtra(CallWrapUpActivity.EXTRA_EVENT_UUID, call.eventUuid)
                                )
                            }
                        }) {
                            Text("Uzupełnij ostatnią rozmowę")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Identyfikacja offline", fontWeight = FontWeight.Bold)
                    Text("Klienci w zaszyfrowanej pamięci: $cachedClients")
                    if (cacheSyncInProgress) {
                        Text(
                            "Synchronizacja trwa… licznik aktualizuje się na żywo.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        if (cacheLastSync > 0L) {
                            "Ostatnia aktualizacja: ${formatUiDateTime(cacheLastSync)}"
                        } else {
                            "Baza offline nie była jeszcze synchronizowana."
                        }
                    )
                    Text(
                        "Cache zawiera tylko dane potrzebne do identyfikacji rozmówcy: klient, usługa, etap, opiekun i status faktur.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            lastClientId?.let { recentClientId ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ostatnio rozpoznany klient", fontWeight = FontWeight.Bold)
                        Text(lastClientName ?: "Klient #$recentClientId")
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(context, ClientHistoryActivity::class.java)
                                    .putExtra(ClientHistoryActivity.EXTRA_CLIENT_ID, recentClientId)
                            )
                        }) {
                            Text("Otwórz historię klienta")
                        }
                    }
                }
            }

            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_PHONE_NUMBERS,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            }) {
                Text("1. Nadaj uprawnienia")
            }

            Button(onClick = {
                scope.launch {
                    val candidates = PhoneNumberResolver(context).resolveServiceSimCandidates()
                    if (candidates.isEmpty()) {
                        status = "Android nie zwrócił numeru SIM. Wymagane będzie parowanie awaryjne z CRM."
                        return@launch
                    }

                    authStatus = "Autoryzacja urządzenia: weryfikacja z CRM…"
                    var matchedCandidate: ServiceSimCandidate? = null
                    var matchedName: String? = null
                    var matchedTokenPresent = false
                    var lastError: String? = null

                    for (candidate in candidates) {
                        status = "Sprawdzam numer ${candidate.phone} w CRM…"
                        val result = runCatching {
                            app.repository.registerDevice(
                                phone = candidate.phone,
                                subscriptionId = candidate.subscriptionId,
                                simSlotIndex = candidate.simSlotIndex,
                                carrierName = candidate.carrierName
                            )
                        }
                        result.onSuccess { match ->
                            if (match.matched) {
                                matchedCandidate = candidate
                                matchedName = match.employeeName ?: match.employeeId?.let { "#$it" }
                                matchedTokenPresent = !match.deviceToken.isNullOrBlank()
                            } else {
                                lastError = match.message
                            }
                        }.onFailure {
                            lastError = it.message ?: it.javaClass.simpleName
                        }
                        if (matchedCandidate != null) break
                    }

                    if (matchedCandidate != null) {
                        detectedPhone = matchedCandidate!!.phone
                        serviceSimInfo = formatServiceSimInfo(
                            matchedCandidate!!.simSlotIndex,
                            matchedCandidate!!.carrierName,
                            matchedCandidate!!.subscriptionId
                        )
                        app.smsProviderScanner.initializeCheckpointToCurrent()
                        SmsSyncScheduler.schedulePeriodic(context)
                        app.ensureSmsObserver()
                        status = if (!matchedTokenPresent) {
                            authStatus = "Autoryzacja urządzenia: brak tokenu z CRM"
                            "Rozpoznano pracownika: ${matchedName ?: "przypisany pracownik"}, ale CRM nie zwrócił tokenu urządzenia. Parowanie po stronie CRM jest niepełne — dane klientów i Caller ID nie będą dostępne."
                        } else {
                            authStatus = "Autoryzacja urządzenia: aktywna — potwierdzona przez CRM"
                            cacheSyncInProgress = true
                            val cacheResult = try {
                                runCatching { app.repository.syncClientCache() }
                            } finally {
                                cacheSyncInProgress = false
                            }
                            refreshCacheStats()
                            cacheResult.fold(
                                onSuccess = { downloaded ->
                                    "Połączono z pracownikiem: ${matchedName ?: "przypisany pracownik"}. Autoryzacja aktywna. Synchronizacja danych klientów: $downloaded rekordów."
                                },
                                onFailure = { error ->
                                    "Urządzenie sparowane, ale CRM nie udostępnił danych klientów: ${error.message ?: error.javaClass.simpleName}"
                                }
                            )
                        }
                    } else {
                        authStatus = "Autoryzacja urządzenia: niepotwierdzona"
                        status = lastError ?: "Żaden aktywny numer SIM nie jest przypisany do pracownika w CRM."
                    }
                }
            }) {
                Text("2. Sparuj telefon z CRM")
            }

            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(0.82f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Identyfikacja połączeń", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                !callerIdEnabled -> "Wyłączona"
                                callScreeningRoleHeld -> "Włączona • uprawnienie systemowe aktywne"
                                else -> "Włączona w aplikacji • Android wymaga jeszcze zgody"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = callerIdEnabled,
                        onCheckedChange = { enabled ->
                            app.deviceStore.callerIdEnabled = enabled
                            callerIdEnabled = enabled
                            if (enabled && !isCallScreeningRoleHeld()) {
                                requestCallScreeningRole()
                            }
                        }
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Diagnostyka Caller ID", fontWeight = FontWeight.Bold)
                    Text(
                        if (lastIncomingPhone.isNullOrBlank()) {
                            "Aplikacja nie zarejestrowała jeszcze połączenia przychodzącego."
                        } else {
                            "Ostatni numer: $lastIncomingPhone"
                        }
                    )
                    lastCallerIdStatus?.takeIf { it.isNotBlank() }?.let {
                        Text("Wynik: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (lastCallerIdAt > 0L) {
                        Text(
                            "Ostatnie zdarzenie: ${formatUiDateTime(lastCallerIdAt)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Ta sekcja pokazuje, czy Android przekazał numer do Mobile Bridge i czy klient został znaleziony w cache lub CRM.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            OutlinedButton(onClick = {
                scope.launch {
                    app.repository.flushPendingNow()
                    status = "Zlecono synchronizację zdarzeń z CRM."
                    delay(400)
                    refreshSyncCounters()
                }
            }) {
                Text("Synchronizuj zdarzenia teraz")
            }

            OutlinedButton(onClick = {
                scope.launch {
                    val result = runCatching { app.smsProviderScanner.scanAll() }
                    status = result.fold(
                        onSuccess = { scan ->
                            when {
                                scan.permissionMissing -> "Brak uprawnienia READ_SMS."
                                scan.serviceSubscriptionMissing -> "Nie ustalono służbowej karty SIM. Sparuj telefon ponownie."
                                scan.serviceSubscriptionMismatch -> "Wstrzymano SMS: ${scan.message ?: "nie można potwierdzić służbowej karty SIM"}"
                                scan.deviceNotPaired -> "Telefon nie jest sparowany z pracownikiem."
                                else -> "SMS zsynchronizowane: przychodzące ${scan.incomingSynced}, wychodzące ${scan.outgoingSynced}."
                            }
                        },
                        onFailure = { "Błąd synchronizacji SMS: ${it.message ?: it.javaClass.simpleName}" }
                    )
                    refreshSyncCounters()
                }
            }) {
                Text("Synchronizuj SMS teraz")
            }

            OutlinedButton(onClick = {
                scope.launch {
                    status = "Aktualizuję zaszyfrowaną bazę klientów…"
                    cacheSyncInProgress = true
                    val result = try {
                        runCatching { app.repository.syncClientCache() }
                    } finally {
                        cacheSyncInProgress = false
                    }
                    status = result.fold(
                        onSuccess = { "Baza offline zaktualizowana. Pobrano/zmieniono rekordów: $it" },
                        onFailure = { "Nie udało się odświeżyć bazy offline: ${it.message ?: it.javaClass.simpleName}" }
                    )
                    refreshCacheStats()
                }
            }) {
                Text("Odśwież bazę klientów")
            }

            OutlinedButton(onClick = {
                ClientCacheScheduler.cancel(context)
                SmsSyncScheduler.cancel(context)
                app.clientCacheStore.clearAll()
                app.callWrapUpStore.clear()
                app.deviceStore.clearPairing()
                detectedPhone = ""
                serviceSimInfo = ""
                lastClientId = null
                lastClientName = null
                refreshCacheStats()
                authStatus = "Autoryzacja urządzenia: brak tokenu CRM"
                status = "Urządzenie odłączone od pracownika, a lokalna baza klientów usunięta."
            }) {
                Text("Odłącz urządzenie")
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Po sparowaniu aplikacja rozpoznaje pracownika po numerze służbowym i zapamiętuje konkretną służbową kartę SIM. Połączenia korzystają z CRM/cache offline, a SMS-y są synchronizowane wyłącznie wtedy, gdy systemowy sub_id wiadomości zgadza się z przypisaną kartą służbową.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun formatUiDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("pl", "PL")).format(Date(epochMs))


private fun formatServiceSimInfo(slotIndex: Int?, carrier: String?, subscriptionId: Int?): String {
    if (slotIndex == null && carrier.isNullOrBlank() && subscriptionId == null) return ""
    val parts = mutableListOf<String>()
    slotIndex?.let { parts += "SIM ${it + 1}" }
    carrier?.takeIf { it.isNotBlank() }?.let(parts::add)
    subscriptionId?.let { parts += "subId $it" }
    return "Służbowa karta: ${parts.joinToString(" • ")}"
}
