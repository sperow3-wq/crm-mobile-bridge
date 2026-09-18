package pl.usundlug.crmbridge.data

data class EmployeeMatch(
    val matched: Boolean,
    val employeeId: Long? = null,
    val employeeName: String? = null,
    val normalizedPhone: String? = null,
    val deviceToken: String? = null,
    val message: String? = null
)

enum class ClientDataSource { NETWORK, CACHE, NONE }

data class ClientMatch(
    val matched: Boolean,
    val clientId: Long? = null,
    val clientName: String? = null,
    val product: String? = null,
    val stage: String? = null,
    val guardianName: String? = null,
    val normalizedPhone: String? = null,
    val overdueInvoicesCount: Int = 0,
    val overdueAmount: Double = 0.0,
    val currency: String = "PLN",
    val dataSource: ClientDataSource = ClientDataSource.NONE,
    val dataUpdatedAtEpochMs: Long? = null
) {
    val hasOverdueInvoices: Boolean
        get() = overdueInvoicesCount > 0 || overdueAmount > 0.0

    val isOfflineCache: Boolean
        get() = dataSource == ClientDataSource.CACHE
}

enum class CallDirection { INCOMING, OUTGOING }

data class CallEvent(
    val eventUuid: String,
    val clientId: Long?,
    val employeeId: Long?,
    val phone: String,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val durationSeconds: Long? = null,
    val status: String
)

data class CallSession(
    val eventUuid: String,
    val clientId: Long?,
    val phone: String,
    val direction: CallDirection,
    val startedAtEpochMs: Long
)

data class ResolvedCall(
    val phone: String,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationSeconds: Long,
    val status: String
)

data class SmsEvent(
    val eventUuid: String,
    val clientId: Long?,
    val employeeId: Long?,
    val phone: String,
    val direction: String,
    val body: String,
    val occurredAtEpochMs: Long,
    val subscriptionId: Int? = null,
    val providerMessageId: Long? = null,
    val status: String? = null,
    val source: String = "android"
)


enum class ClientActivityType { CALL, SMS }

data class ClientActivityItem(
    val activityKey: String,
    val type: ClientActivityType,
    val direction: String,
    val status: String?,
    val phone: String?,
    val messageBody: String?,
    val occurredAtEpochMs: Long,
    val durationSeconds: Long? = null,
    val employeeName: String? = null,
    val resultLabel: String? = null,
    val wrapUpNote: String? = null,
    val followUpAtEpochMs: Long? = null
)

data class ClientOverview(
    val clientId: Long,
    val clientName: String,
    val phone: String?,
    val product: String?,
    val stage: String?,
    val guardianName: String?,
    val overdueInvoicesCount: Int,
    val overdueAmount: Double,
    val currency: String,
    val crmUrl: String?,
    val activities: List<ClientActivityItem>,
    val nextCursor: String? = null,
    val dataSource: ClientDataSource = ClientDataSource.NETWORK,
    val dataUpdatedAtEpochMs: Long? = null
) {
    val hasOverdueInvoices: Boolean
        get() = overdueInvoicesCount > 0 || overdueAmount > 0.0
}
