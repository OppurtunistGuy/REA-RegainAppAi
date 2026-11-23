package app.steady.remote

import app.steady.detect.DetectorCodec
import app.steady.detect.DetectorConfig
import app.steady.learning.FocusSession
import app.steady.rules.Plan
import app.steady.rules.PlanCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Typed failures of [ApiClient]. Every call throws one of these or returns normally. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * 401: missing, invalid, revoked or expired token ([code] = "token_expired" after 90 idle days),
     * or a wrong OTP code ([code] = "invalid_code").
     */
    class Unauthorized(val code: String?) : ApiException("Unauthorized ($code)") {
        val isExpired: Boolean get() = code == ApiClient.TOKEN_EXPIRED
    }

    /**
     * 409. For `PUT /v1/plan` [current] is the stored plan (null if none); [code] e.g. "version_conflict",
     * "no_keyholder", or "keyholder_limit" (a free owner can link one key holder).
     */
    class Conflict(val code: String?, val current: Plan?) : ApiException("Conflict ($code)") {
        val isKeyHolderLimit: Boolean get() = code == ApiClient.KEYHOLDER_LIMIT
    }

    class NotFound(val code: String?) : ApiException("Not found ($code)")

    /** Other 4xx the user can act on: 400 invalid body, 403, 410 invite used/expired, 413, 429 rate limited. */
    class Rejected(val status: Int, val code: String?, val serverMessage: String?) :
        ApiException("HTTP $status ($code)")

    /** No connection, timeout, bad base URL, or an unreadable response. */
    class Network(cause: Throwable?) : ApiException("Network error: ${cause?.message}", cause)

    /** 5xx. */
    class Server(val status: Int, val code: String?) : ApiException("Server error $status ($code)")
}

/**
 * Thin OkHttp + kotlinx-serialization client for `shared/api/openapi.yaml` (base `/v1`).
 * [baseUrl] and [token] are read per call, so changing them in Settings takes effect at once.
 */
/**
 * @param onUnauthorized called when an authenticated call is answered with 401 (token expired, revoked
 * or unknown), with the token that call sent. `RemoteModule` signs out locally and tells the user.
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val http: OkHttpClient = defaultHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onUnauthorized: (token: String, error: ApiException.Unauthorized) -> Unit = { _, _ -> },
) {
    // ---- Auth ----

    suspend fun startOtp(contact: String) {
        send<Unit>("POST", "auth/otp/start", body = encode(OtpStartBody.serializer(), OtpStartBody(contact)), auth = false)
    }

    suspend fun verifyOtp(contact: String, code: String, name: String?): AuthResult =
        send("POST", "auth/otp/verify", encode(OtpVerifyBody.serializer(), OtpVerifyBody(contact, code, name)),
            auth = false, parse = { json.decodeFromString(AuthResult.serializer(), it) })

    /** Revokes [token] on the server (204). Takes the token explicitly: sign-out forgets it locally first. */
    suspend fun logout(token: String) {
        send<Unit>("POST", "auth/logout", "{}", auth = false, headers = mapOf("Authorization" to "Bearer $token"))
    }

    /** Erases everything the server holds for this account (plan, sessions, requests, links, entitlements, tokens). */
    suspend fun deleteMe() {
        send<Unit>("DELETE", "me")
    }

    // ---- Plan / detectors ----

    /** null when the server has no plan yet (404). */
    suspend fun getPlan(): Plan? = try {
        send("GET", "plan", parse = PlanCodec::decode)
    } catch (e: ApiException.NotFound) {
        null
    }

    /** `plan.version` must be the stored version + 1, else [ApiException.Conflict] with the stored plan. */
    suspend fun putPlan(plan: Plan): Plan = send("PUT", "plan", PlanCodec.encode(plan), parse = PlanCodec::decode)

    suspend fun detectors(): DetectorConfig = send("GET", "detectors", auth = false, parse = DetectorCodec::decode)

    // ---- Key holder ----

    suspend fun createInvite(): Invite = send("POST", "keyholder/invites", "{}", parse = { json.decodeFromString(Invite.serializer(), it) })

    suspend fun acceptInvite(code: String): InviteAccepted {
        val clean = code.trim().uppercase()
        return send("POST", "keyholder/invites/$clean/accept", "{}",
            parse = { json.decodeFromString(InviteAccepted.serializer(), it) })
    }

    suspend fun keyHolderRequests(): List<KeyHolderRequest> =
        send("GET", "keyholder/requests", parse = { json.decodeFromString(ListSerializer(KeyHolderRequest.serializer()), it) })

    /** Owner view: who holds my key. */
    suspend fun keyHolderLinks(): List<KeyHolderLink> =
        send("GET", "keyholder/links", parse = { json.decodeFromString(ListSerializer(KeyHolderLink.serializer()), it) })

    /** Key-holder view: whose key I hold. */
    suspend fun keyHolderOwners(): List<OwnerLink> =
        send("GET", "keyholder/owners", parse = { json.decodeFromString(ListSerializer(OwnerLink.serializer()), it) })

    /** Either side may remove a link (404 if it is not theirs or already gone). */
    suspend fun removeKeyHolderLink(linkId: String) {
        send<Unit>("DELETE", "keyholder/links/$linkId")
    }

    /** The owner's weekly summary, readable only by a linked key holder (else 404). */
    suspend fun ownerSummary(ownerUserId: String, weekStart: LocalDate, zone: ZoneId): WeeklySummary =
        send("GET", "keyholder/summary",
            query = mapOf("ownerUserId" to ownerUserId, "weekStart" to weekStart.toString(), "tz" to zone.id),
            parse = { json.decodeFromString(WeeklySummary.serializer(), it) })

    // ---- Unlock requests ----

    suspend fun createUnlockRequest(surfaceId: String?, reason: String): UnlockRequestState =
        send("POST", "unlock-requests", encode(UnlockRequestBody.serializer(), UnlockRequestBody(surfaceId, reason)),
            parse = { json.decodeFromString(UnlockRequestState.serializer(), it) })

    suspend fun unlockRequest(id: String): UnlockRequestState =
        send("GET", "unlock-requests/$id", parse = { json.decodeFromString(UnlockRequestState.serializer(), it) })

    suspend fun decide(id: String, approve: Boolean, minutes: Int? = null): UnlockRequestState =
        send("POST", "unlock-requests/$id/decision", encode(DecisionBody.serializer(), DecisionBody(approve, minutes)),
            parse = { json.decodeFromString(UnlockRequestState.serializer(), it) })

    // ---- Sessions / summary / entitlements ----

    suspend fun postSession(session: FocusSession) {
        send<Unit>("POST", "sessions", encode(SessionBody.serializer(), SessionBody.of(session)))
    }

    suspend fun weeklySummary(weekStart: LocalDate, zone: ZoneId): WeeklySummary =
        send("GET", "summary/weekly", query = mapOf("weekStart" to weekStart.toString(), "tz" to zone.id),
            parse = { json.decodeFromString(WeeklySummary.serializer(), it) })

    suspend fun entitlements(): ServerEntitlement =
        send("GET", "entitlements", parse = { json.decodeFromString(ServerEntitlement.serializer(), it) })

    /** Sends a Play purchase for server-side verification; answers with the resulting entitlement. */
    suspend fun verifyPurchase(productId: String, purchaseToken: String): ServerEntitlement =
        send("POST", "billing/verify", encode(BillingVerifyBody.serializer(), BillingVerifyBody(productId, purchaseToken)),
            parse = { json.decodeFromString(ServerEntitlement.serializer(), it) })

    // ---- plumbing ----

    private fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private fun url(path: String, query: Map<String, String>): HttpUrl {
        val base = baseUrl().trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw ApiException.Network(IllegalArgumentException("Invalid API base URL"))
        return base.newBuilder().apply {
            addPathSegment("v1")
            path.split('/').forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> send(
        method: String,
        path: String,
        body: String? = null,
        auth: Boolean = true,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        parse: (String) -> T = { Unit as T },
    ): T = withContext(io) {
        val builder = Request.Builder().url(url(path, query)).header("Accept", "application/json")
        var sentToken: String? = null
        if (auth) {
            val t = token() ?: throw ApiException.Unauthorized("not_signed_in")
            builder.header("Authorization", "Bearer $t")
            sentToken = t
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, body?.toRequestBody(JSON_MEDIA))
        val (status, text) = try {
            http.newCall(builder.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        if (status in 200..299) {
            try {
                parse(text)
            } catch (e: Exception) {
                throw ApiException.Network(e)
            }
        } else {
            val error = errorFor(status, text)
            if (error is ApiException.Unauthorized && sentToken != null) onUnauthorized(sentToken, error)
            throw error
        }
    }

    private fun errorFor(status: Int, text: String): ApiException {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val error = obj?.get("error") as? JsonObject
        val code = error?.get("code")?.jsonPrimitive?.content
        val message = error?.get("message")?.jsonPrimitive?.content
        return when {
            status == 401 -> ApiException.Unauthorized(code)
            status == 404 -> ApiException.NotFound(code)
            status == 409 -> ApiException.Conflict(code, obj?.let { currentPlan(it) })
            status >= 500 -> ApiException.Server(status, code)
            else -> ApiException.Rejected(status, code, message)
        }
    }

    private fun currentPlan(obj: JsonObject): Plan? {
        val current = obj["current"] ?: return null
        if (current is JsonNull) return null
        return runCatching { PlanCodec.decode(current.toString()) }.getOrNull()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        const val TOKEN_EXPIRED = "token_expired"
        const val KEYHOLDER_LIMIT = "keyholder_limit"

        /** Lenient on responses (forward compatible); requests only ever hold schema fields. */
        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        /** True if [url] is a usable http(s) base URL. */
        fun isValidBaseUrl(url: String): Boolean = url.trim().toHttpUrlOrNull() != null
    }
}
