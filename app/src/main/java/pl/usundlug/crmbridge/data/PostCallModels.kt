package pl.usundlug.crmbridge.data

data class CompletedCallContext(
    val eventUuid: String,
    val clientId: Long?,
    val clientName: String?,
    val phone: String,
    val direction: CallDirection,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationSeconds: Long
)

data class CallWrapUpEvent(
    val eventUuid: String,
    val clientId: Long?,
    val resultCode: String,
    val resultLabel: String,
    val note: String?,
    val followUpAtEpochMs: Long?
)
