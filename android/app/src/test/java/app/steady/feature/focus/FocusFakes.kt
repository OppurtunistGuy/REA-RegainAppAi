package app.steady.feature.focus

import app.steady.data.SessionRepository
import app.steady.data.UnlockRepository
import app.steady.learning.FocusSession
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Phase 7 test fakes (kept in this package so they never collide with other phases' fakes). */
class FakeSessionRepo(private val zone: ZoneId) : SessionRepository {
    val saved = MutableStateFlow<List<FocusSession>>(emptyList())
    val intercepts = MutableStateFlow<List<Pair<String, Instant>>>(emptyList())
    override val sessions: Flow<List<FocusSession>> = saved
    override suspend fun add(s: FocusSession) = saved.update { it + s }
    override suspend fun recordIntercept(surfaceId: String, at: Instant) = intercepts.update { it + (surfaceId to at) }
    override fun interceptsOn(date: LocalDate): Flow<Int> =
        intercepts.map { list -> list.count { it.second.atZone(zone).toLocalDate() == date } }
    override fun interceptsBySurface(from: LocalDate, toExclusive: LocalDate): Flow<Map<String, Int>> =
        intercepts.map { list ->
            list.filter { val d = it.second.atZone(zone).toLocalDate(); !d.isBefore(from) && d.isBefore(toExclusive) }
                .groupingBy { it.first }.eachCount()
        }
}

class FakeUnlockRepo(private val fail: Boolean = false) : UnlockRepository {
    val grantState = MutableStateFlow<List<UnlockGrant>>(emptyList())
    val requests = mutableListOf<Pair<String?, String>>()
    override val grants: Flow<List<UnlockGrant>> = grantState
    override suspend fun add(g: UnlockGrant) = grantState.update { it + g }
    override suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String {
        if (fail) error("offline")
        requests += surfaceId to reason
        return "req-${requests.size}"
    }
}

class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
}
