package app.steady.remote

import app.steady.learning.FocusSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire DTOs for shared/api/openapi.yaml. Request bodies hold only the schema's fields: the backend
 * rejects unknown fields, and nothing here can carry screen contents.
 */

@Serializable internal data class OtpStartBody(val contact: String)

@Serializable internal data class OtpVerifyBody(val contact: String, val code: String, val name: String? = null)

@Serializable data class AuthResult(val token: String, val userId: String)

@Serializable data class Invite(val code: String, val expiresAt: String)

@Serializable data class InviteAccepted(val ownerUserId: String, val ownerName: String)

@Serializable internal data class UnlockRequestBody(val surfaceId: String?, val reason: String)

@Serializable
enum class UnlockStatus {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("denied") DENIED,
}

/** `until` is an ISO-8601 instant, present once approved. */
@Serializable data class UnlockRequestState(val id: String, val status: UnlockStatus, val until: String? = null)

@Serializable
data class KeyHolderRequest(
    val id: String,
    val ownerName: String,
    val reason: String,
    val surfaceId: String? = null,
    val createdAt: String,
)

@Serializable internal data class DecisionBody(val approve: Boolean, val minutes: Int? = null)

/** POST /v1/sessions body. Aggregate fields only: times and counts. */
@Serializable
internal data class SessionBody(
    val id: String,
    val scheduleId: String?,
    val start: String,
    val end: String,
    val intercepts: Int,
    val unlocks: Int,
) {
    companion object {
        fun of(s: FocusSession) = SessionBody(
            id = s.id,
            scheduleId = s.scheduleId,
            start = s.start.toString(),
            end = s.end.toString(),
            intercepts = s.intercepts.coerceAtLeast(0),
            unlocks = s.unlocks.coerceAtLeast(0),
        )
    }
}

@Serializable
data class WeeklySummary(
    val focusMinutes: Int,
    val blocks: Int,
    val intercepts: Int,
    val unlocks: Int,
    val days: List<String> = emptyList(),
)

@Serializable data class ServerEntitlement(val tier: String, val features: List<String> = emptyList())
